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
import java.util.stream.Collectors;

public final class DiscordSyncService {
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("uuuu-MM-dd")
            .withZone(ZoneId.systemDefault());
    private static final int DISCORD_CHUNK_MAX = 1900;

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
        List<String> messageIds = new ArrayList<>(config.getStringList("discord.message-ids"));
        messageIds = messageIds.stream().map(String::trim).filter(id -> !id.isBlank()).collect(Collectors.toCollection(ArrayList::new));

        if (messageIds.isEmpty()) {
            String legacyMessageId = config.getString("discord.message-id", "").trim();
            if (!legacyMessageId.isEmpty()) {
                messageIds.add(legacyMessageId);
                plugin.getConfig().set("discord.message-ids", messageIds);
                plugin.saveConfig();
                plugin.getLogger().info("Migrated legacy discord.message-id to discord.message-ids");
            }
        }

        if (webhookUrl.isEmpty() || webhookUrl.equals("PUT_DISCORD_WEBHOOK_URL_HERE")) {
            plugin.getLogger().warning("Skipping Discord sync: discord.webhook-url is not configured.");
            return;
        }

        List<String> chunks = buildDiscordMessages(config);

        List<String> finalMessageIds = new ArrayList<>(messageIds);
        for (int i = 0; i < chunks.size(); i++) {
            String content = chunks.get(i);
            if (i < finalMessageIds.size()) {
                editMessage(webhookUrl, finalMessageIds.get(i), content);
                continue;
            }

            String createdMessageId = createMessage(webhookUrl, content);
            if (createdMessageId != null && !createdMessageId.isBlank()) {
                finalMessageIds.add(createdMessageId);
            }
        }

        if (finalMessageIds.size() > chunks.size()) {
            List<String> staleMessageIds = new ArrayList<>(finalMessageIds.subList(chunks.size(), finalMessageIds.size()));
            for (String staleMessageId : staleMessageIds) {
                deleteMessage(webhookUrl, staleMessageId);
            }
            finalMessageIds = new ArrayList<>(finalMessageIds.subList(0, chunks.size()));
        }

        plugin.getConfig().set("discord.message-ids", finalMessageIds);
        plugin.saveConfig();
        plugin.getLogger().info("Updated Discord webhook messages (" + chunks.size() + " chunk(s), reason: " + reason + ")");
    }

    private List<String> buildDiscordMessages(FileConfiguration config) {
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

        List<String> lines = new ArrayList<>();
        if (!header.isEmpty()) {
            lines.add(header);
            lines.add("");
        }

        lines.add("Active threshold: past " + inactiveAfterDays + (inactiveAfterDays == 1 ? " day" : " days"));
        lines.add("");

        if (statuses.isEmpty()) {
            lines.add("No players with recorded login history were found.");
        } else {
            for (PlayerStatus status : statuses) {
                StringBuilder playerLine = new StringBuilder();
                playerLine.append("- ")
                        .append(escapeDiscord(status.name()))
                        .append(" (")
                        .append(status.active() ? "active" : "inactive")
                        .append(")");
                if (includeLastLoginDate && status.lastLogin() > 0L) {
                    playerLine.append(" — ").append(DATE_FORMATTER.format(Instant.ofEpochMilli(status.lastLogin())));
                }
                lines.add(playerLine.toString());
            }
        }

        lines.add("");
        lines.add("Updated: <t:" + Instant.now().getEpochSecond() + ":R>");

        return splitIntoChunks(lines);
    }

    private List<String> splitIntoChunks(List<String> lines) {
        List<String> chunks = new ArrayList<>();
        StringBuilder current = new StringBuilder();

        for (String line : lines) {
            String candidate = current.isEmpty() ? line : current + "\n" + line;
            if (candidate.length() <= DISCORD_CHUNK_MAX) {
                current.setLength(0);
                current.append(candidate);
                continue;
            }

            if (!current.isEmpty()) {
                chunks.add(current.toString());
                current.setLength(0);
            }

            if (line.length() > DISCORD_CHUNK_MAX) {
                throw new IllegalStateException("Single line exceeds Discord chunk limit: " + line.length());
            }

            current.append(line);
        }

        if (!current.isEmpty()) {
            chunks.add(current.toString());
        }

        if (chunks.isEmpty()) {
            chunks.add("");
        }

        return chunks;
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

    private void deleteMessage(String webhookUrl, String messageId) throws IOException, InterruptedException {
        String deleteUrl = webhookUrl + "/messages/" + messageId;

        HttpRequest request = baseRequest(deleteUrl)
                .DELETE()
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        ensureSuccess(response, "delete Discord webhook message");
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
        return value
                .replace("\\", "\\\\")
                .replace("*", "\\*")
                .replace("_", "\\_")
                .replace("~", "\\~")
                .replace("`", "\\`")
                .replace("|", "\\|")
                .replace(">", "\\>")
                .replace("@", "@");
    }

    private record PlayerStatus(String name, long lastLogin, boolean active) {
    }
}
