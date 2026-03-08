package com.example.lastseendiscord;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.configuration.file.FileConfiguration;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

public final class DiscordSyncService {
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("uuuu-MM-dd")
            .withZone(ZoneId.systemDefault());

    private final LastSeenDiscordPlugin plugin;
    private final HttpClient httpClient;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean pending = new AtomicBoolean(false);

    public DiscordSyncService(LastSeenDiscordPlugin plugin) {
        this.plugin = plugin;
        this.httpClient = HttpClient.newHttpClient();
    }

    public void requestSync(String reason) {
        if (pending.getAndSet(true)) {
            return;
        }

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> runSyncLoop(reason));
    }

    private void runSyncLoop(String initialReason) {
        if (!running.compareAndSet(false, true)) {
            return;
        }

        String reason = initialReason;
        try {
            while (pending.getAndSet(false)) {
                try {
                    syncOnce(reason);
                } catch (Exception ex) {
                    plugin.getLogger().severe("Discord sync failed (" + reason + "): " + ex.getMessage());
                    ex.printStackTrace();
                }
                reason = "coalesced update";
            }
        } finally {
            running.set(false);
            if (pending.get()) {
                requestSync("missed update");
            }
        }
    }

    private void syncOnce(String reason) throws IOException, InterruptedException {
        FileConfiguration config = plugin.config();
        String webhookUrl = config.getString("discord.webhook-url", "").trim();
        String messageId = config.getString("discord.message-id", "").trim();

        if (webhookUrl.isEmpty() || webhookUrl.equals("PUT_DISCORD_WEBHOOK_URL_HERE")) {
            plugin.getLogger().warning("Skipping Discord sync: discord.webhook-url is not configured.");
            return;
        }

        String content = buildDiscordMessage(config);

        if (messageId.isEmpty()) {
            String createdMessageId = createMessage(webhookUrl, content);
            if (createdMessageId != null && !createdMessageId.isBlank()) {
                plugin.getConfig().set("discord.message-id", createdMessageId);
                plugin.saveConfig();
                plugin.getLogger().info("Created Discord webhook message and saved message-id=" + createdMessageId + " (reason: " + reason + ")");
            }
            return;
        }

        editMessage(webhookUrl, messageId, content);
        plugin.getLogger().info("Updated Discord webhook message (reason: " + reason + ")");
    }

    private String buildDiscordMessage(FileConfiguration config) {
        int inactiveAfterDays = Math.max(1, config.getInt("activity.inactive-after-days", 30));
        boolean includeLastLoginDate = config.getBoolean("discord.include-last-login-date", false);
        String header = Objects.requireNonNullElse(config.getString("discord.header"), "").trim();

        long now = System.currentTimeMillis();
        long activeThreshold = now - (inactiveAfterDays * 24L * 60L * 60L * 1000L);

        List<PlayerStatus> statuses = new ArrayList<>();
        for (OfflinePlayer offlinePlayer : Bukkit.getOfflinePlayers()) {
            if (!offlinePlayer.hasPlayedBefore()) {
                continue;
            }

            String name = offlinePlayer.getName();
            if (name == null || name.isBlank()) {
                continue;
            }

            long lastLogin = offlinePlayer.getLastLogin();
            boolean active = lastLogin >= activeThreshold;
            statuses.add(new PlayerStatus(name, lastLogin, active));
        }

        statuses.sort(Comparator.comparing(PlayerStatus::name, String.CASE_INSENSITIVE_ORDER));

        StringBuilder sb = new StringBuilder();
        if (!header.isEmpty()) {
            sb.append(header).append("\n\n");
        }

        sb.append("Active threshold: past ")
                .append(inactiveAfterDays)
                .append(inactiveAfterDays == 1 ? " day" : " days")
                .append("\n\n");

        if (statuses.isEmpty()) {
            sb.append("No players with recorded login history were found.");
            return truncateDiscordContent(sb.toString());
        }

        for (PlayerStatus status : statuses) {
            sb.append("- ")
                    .append(escapeDiscord(status.name()))
                    .append(" (")
                    .append(status.active() ? "active" : "inactive")
                    .append(")");

            if (includeLastLoginDate && status.lastLogin() > 0L) {
                sb.append(" — ").append(DATE_FORMATTER.format(Instant.ofEpochMilli(status.lastLogin())));
            }
            sb.append("\n");
        }

        sb.append("\nUpdated: <t:")
                .append(Instant.now().getEpochSecond())
                .append(":R>");

        return truncateDiscordContent(sb.toString());
    }

    private String truncateDiscordContent(String content) {
        int max = 1900;
        if (content.length() <= max) {
            return content;
        }
        return content.substring(0, max - 24) + "\n... list truncated due to length";
    }

    private String createMessage(String webhookUrl, String content) throws IOException, InterruptedException {
        String executeUrl = webhookUrl.contains("?")
                ? webhookUrl + "&wait=true"
                : webhookUrl + "?wait=true";

        HttpRequest request = baseRequest(executeUrl)
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody(content), StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        ensureSuccess(response, "create Discord webhook message");

        return JsonUtil.extractTopLevelString(response.body(), "id");
    }

    private void editMessage(String webhookUrl, String messageId, String content) throws IOException, InterruptedException {
        String editUrl = webhookUrl + "/messages/" + messageId;

        HttpRequest request = baseRequest(editUrl)
                .method("PATCH", HttpRequest.BodyPublishers.ofString(jsonBody(content), StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        ensureSuccess(response, "edit Discord webhook message");
    }

    private HttpRequest.Builder baseRequest(String url) {
        return HttpRequest.newBuilder(URI.create(url))
                .header("Content-Type", "application/json")
                .header("User-Agent", "LastSeenDiscord/1.1.0");
    }

    private void ensureSuccess(HttpResponse<String> response, String action) throws IOException {
        int code = response.statusCode();
        if (code < 200 || code >= 300) {
            throw new IOException("Failed to " + action + ". HTTP " + code + " response: " + response.body());
        }
    }

    private String jsonBody(String content) {
        return "{\"content\":\"" + escapeJson(content) + "\"}";
    }

    private String escapeJson(String value) {
        StringBuilder out = new StringBuilder();
        for (char c : value.toCharArray()) {
            switch (c) {
                case '\\' -> out.append("\\\\");
                case '"' -> out.append("\\\"");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.toString();
    }

    private String escapeDiscord(String value) {
        return value.replace("*", "\\*")
                .replace("_", "\\_")
                .replace("~", "\\~")
                .replace("`", "\\`")
                .replace("|", "\\|");
    }

    private record PlayerStatus(String name, long lastLogin, boolean active) {
    }
}
