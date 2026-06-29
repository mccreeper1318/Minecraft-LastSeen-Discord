package com.pinnaclesmp.lastseendiscord;

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
import java.util.Locale;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

public final class DiscordSyncService {
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("uuuu-MM-dd")
            .withZone(ZoneId.systemDefault());
    private static final int DISCORD_CHUNK_MAX = 1900;
    private static final int MAX_RATE_LIMIT_RETRIES = 3;
    private static final long FALLBACK_RATE_LIMIT_DELAY_MILLIS = 2_000L;

    private final LastSeenDiscordPlugin plugin;
    private final HttpClient httpClient;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean pending = new AtomicBoolean(false);
    private final String userAgent;

    public DiscordSyncService(LastSeenDiscordPlugin plugin) {
        this.plugin = plugin;
        this.httpClient = HttpClient.newHttpClient();
        this.userAgent = plugin.getDescription().getName() + "/" + plugin.getDescription().getVersion();
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
        SyncSnapshot snapshot = collectSyncSnapshot();

        if (!snapshot.configured()) {
            plugin.getLogger().warning("Skipping Discord sync: discord.webhook-url is not configured.");
            return;
        }

        List<String> finalMessageIds = new ArrayList<>(snapshot.messageIds());
        for (int i = 0; i < snapshot.chunks().size(); i++) {
            String content = snapshot.chunks().get(i);
            if (i < finalMessageIds.size()) {
                editMessage(snapshot.webhookUrl(), finalMessageIds.get(i), content);
                continue;
            }

            String createdMessageId = createMessage(snapshot.webhookUrl(), content);
            if (createdMessageId != null && !createdMessageId.isBlank()) {
                finalMessageIds.add(createdMessageId);
            }
        }

        if (finalMessageIds.size() > snapshot.chunks().size()) {
            List<String> staleMessageIds = new ArrayList<>(finalMessageIds.subList(snapshot.chunks().size(), finalMessageIds.size()));
            for (String staleMessageId : staleMessageIds) {
                deleteMessage(snapshot.webhookUrl(), staleMessageId);
            }
            finalMessageIds = new ArrayList<>(finalMessageIds.subList(0, snapshot.chunks().size()));
        }

        saveMessageIds(finalMessageIds);
        plugin.getLogger().info("Updated Discord webhook messages (" + snapshot.chunks().size() + " chunk(s), reason: " + reason + ")");
    }

    private SyncSnapshot collectSyncSnapshot() throws IOException, InterruptedException {
        Future<SyncSnapshot> future = Bukkit.getScheduler().callSyncMethod(plugin, this::createSyncSnapshot);
        try {
            return future.get();
        } catch (ExecutionException ex) {
            throw new IOException("Failed to collect player activity snapshot on the server thread.", ex.getCause());
        }
    }

    private SyncSnapshot createSyncSnapshot() {
        FileConfiguration config = plugin.config();
        String webhookUrl = config.getString("discord.webhook-url", "").trim();
        List<String> messageIds = new ArrayList<>(config.getStringList("discord.message-ids"));
        messageIds = messageIds.stream()
                .map(String::trim)
                .filter(id -> !id.isBlank())
                .collect(Collectors.toCollection(ArrayList::new));

        if (messageIds.isEmpty()) {
            String legacyMessageId = config.getString("discord.message-id", "").trim();
            if (!legacyMessageId.isEmpty()) {
                messageIds.add(legacyMessageId);
                plugin.getLogger().info("Found legacy discord.message-id; it will be saved to discord.message-ids after a successful sync.");
            }
        }

        boolean configured = !webhookUrl.isEmpty() && !webhookUrl.equals("PUT_DISCORD_WEBHOOK_URL_HERE");
        List<String> chunks = configured ? buildDiscordMessages(config) : List.of();
        return new SyncSnapshot(configured, webhookUrl, messageIds, chunks);
    }

    private void saveMessageIds(List<String> messageIds) throws IOException, InterruptedException {
        Future<Void> future = Bukkit.getScheduler().callSyncMethod(plugin, () -> {
            plugin.getConfig().set("discord.message-ids", messageIds);
            plugin.saveConfig();
            return null;
        });

        try {
            future.get();
        } catch (ExecutionException ex) {
            throw new IOException("Failed to save Discord message IDs on the server thread.", ex.getCause());
        }
    }

    private List<String> buildDiscordMessages(FileConfiguration config) {
        int inactiveAfterDays = Math.max(1, config.getInt("activity.inactive-after-days", 30));
        boolean includeActivityDate = config.contains("discord.include-last-seen-date")
                ? config.getBoolean("discord.include-last-seen-date", false)
                : config.getBoolean("discord.include-last-login-date", false);
        TimestampSource timestampSource = TimestampSource.fromConfig(config.getString("activity.timestamp-source", "LAST_SEEN"));
        String header = config.getString("discord.header", "").trim();

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

            long activityTime = timestampSource.resolve(offlinePlayer);
            if (activityTime <= 0L) {
                continue;
            }

            boolean active = activityTime >= activeThreshold;
            statuses.add(new PlayerStatus(name, activityTime, active));
        }

        statuses.sort(Comparator.comparing(PlayerStatus::name, String.CASE_INSENSITIVE_ORDER));

        List<String> lines = new ArrayList<>();
        if (!header.isEmpty()) {
            lines.add(header);
            lines.add("");
        }

        lines.add("Active threshold: past " + inactiveAfterDays + (inactiveAfterDays == 1 ? " day" : " days"));
        lines.add("Timestamp source: " + timestampSource.displayName());
        lines.add("");

        if (statuses.isEmpty()) {
            lines.add("No players with recorded activity history were found.");
        } else {
            for (PlayerStatus status : statuses) {
                StringBuilder playerLine = new StringBuilder();
                playerLine.append("- ")
                        .append(escapeDiscord(status.name()))
                        .append(" (")
                        .append(status.active() ? "active" : "inactive")
                        .append(")");
                if (includeActivityDate) {
                    playerLine.append(" — ").append(DATE_FORMATTER.format(Instant.ofEpochMilli(status.activityTime())));
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
            if (line.length() <= DISCORD_CHUNK_MAX) {
                appendLineToChunks(chunks, current, line);
                continue;
            }

            plugin.getLogger().warning("Splitting overlong Discord line (" + line.length() + " chars, max " + DISCORD_CHUNK_MAX + ").");
            for (int start = 0; start < line.length(); start += DISCORD_CHUNK_MAX) {
                int end = Math.min(start + DISCORD_CHUNK_MAX, line.length());
                appendLineToChunks(chunks, current, line.substring(start, end));
            }
        }

        if (!current.isEmpty()) {
            chunks.add(current.toString());
        }

        if (chunks.isEmpty()) {
            chunks.add("");
        }

        return chunks;
    }

    private void appendLineToChunks(List<String> chunks, StringBuilder current, String line) {
        String candidate = current.isEmpty() ? line : current + "\n" + line;
        if (candidate.length() <= DISCORD_CHUNK_MAX) {
            current.setLength(0);
            current.append(candidate);
            return;
        }

        if (!current.isEmpty()) {
            chunks.add(current.toString());
            current.setLength(0);
        }

        current.append(line);
    }

    private String createMessage(String webhookUrl, String content) throws IOException, InterruptedException {
        String executeUrl = webhookUrl.contains("?")
                ? webhookUrl + "&wait=true"
                : webhookUrl + "?wait=true";

        HttpRequest request = baseRequest(executeUrl)
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody(content), StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> response = sendWithRateLimitRetry(request, "create Discord webhook message");
        ensureSuccess(response, "create Discord webhook message");

        return JsonUtil.extractTopLevelString(response.body(), "id");
    }

    private void editMessage(String webhookUrl, String messageId, String content) throws IOException, InterruptedException {
        String editUrl = webhookUrl + "/messages/" + messageId;

        HttpRequest request = baseRequest(editUrl)
                .method("PATCH", HttpRequest.BodyPublishers.ofString(jsonBody(content), StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> response = sendWithRateLimitRetry(request, "edit Discord webhook message");
        ensureSuccess(response, "edit Discord webhook message");
    }

    private void deleteMessage(String webhookUrl, String messageId) throws IOException, InterruptedException {
        String deleteUrl = webhookUrl + "/messages/" + messageId;

        HttpRequest request = baseRequest(deleteUrl)
                .DELETE()
                .build();

        HttpResponse<String> response = sendWithRateLimitRetry(request, "delete Discord webhook message");
        if (response.statusCode() == 404) {
            plugin.getLogger().info("Discord message " + messageId + " was already deleted; pruning stale id from config.");
            return;
        }
        ensureSuccess(response, "delete Discord webhook message");
    }

    private HttpResponse<String> sendWithRateLimitRetry(HttpRequest request, String action) throws IOException, InterruptedException {
        for (int attempt = 0; attempt <= MAX_RATE_LIMIT_RETRIES; attempt++) {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() != 429 || attempt == MAX_RATE_LIMIT_RETRIES) {
                return response;
            }

            long delayMillis = extractRetryAfterMillis(response);
            plugin.getLogger().warning("Discord rate limited while trying to " + action + "; retrying in " + delayMillis + "ms.");
            Thread.sleep(delayMillis);
        }

        throw new IOException("Failed to " + action + " after Discord rate-limit retries.");
    }

    private long extractRetryAfterMillis(HttpResponse<String> response) {
        return response.headers().firstValue("Retry-After")
                .or(() -> response.headers().firstValue("X-RateLimit-Reset-After"))
                .map(this::parseRetryAfterMillis)
                .orElseGet(() -> {
                    Double retryAfter = JsonUtil.extractTopLevelNumber(response.body(), "retry_after");
                    if (retryAfter == null || retryAfter <= 0.0D) {
                        return FALLBACK_RATE_LIMIT_DELAY_MILLIS;
                    }
                    return Math.max(1L, Math.round(retryAfter * 1000.0D));
                });
    }

    private long parseRetryAfterMillis(String value) {
        try {
            double retryAfter = Double.parseDouble(value.trim());
            if (retryAfter <= 0.0D) {
                return FALLBACK_RATE_LIMIT_DELAY_MILLIS;
            }
            return Math.max(1L, Math.round(retryAfter * 1000.0D));
        } catch (NumberFormatException ex) {
            return FALLBACK_RATE_LIMIT_DELAY_MILLIS;
        }
    }

    private HttpRequest.Builder baseRequest(String url) {
        return HttpRequest.newBuilder(URI.create(url))
                .header("Content-Type", "application/json")
                .header("User-Agent", userAgent);
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
                .replace("@", "@\u200B");
    }

    private enum TimestampSource {
        LAST_SEEN("last seen") {
            @Override
            long resolve(OfflinePlayer offlinePlayer) {
                long lastSeen = offlinePlayer.getLastSeen();
                if (lastSeen > 0L) {
                    return lastSeen;
                }
                return offlinePlayer.getLastLogin();
            }
        },
        LAST_LOGIN("last login") {
            @Override
            long resolve(OfflinePlayer offlinePlayer) {
                long lastLogin = offlinePlayer.getLastLogin();
                if (lastLogin > 0L) {
                    return lastLogin;
                }
                return offlinePlayer.getLastSeen();
            }
        };

        private final String displayName;

        TimestampSource(String displayName) {
            this.displayName = displayName;
        }

        static TimestampSource fromConfig(String value) {
            if (value == null || value.isBlank()) {
                return LAST_SEEN;
            }

            try {
                return TimestampSource.valueOf(value.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ex) {
                return LAST_SEEN;
            }
        }

        String displayName() {
            return displayName;
        }

        abstract long resolve(OfflinePlayer offlinePlayer);
    }

    private record SyncSnapshot(boolean configured, String webhookUrl, List<String> messageIds, List<String> chunks) {
    }

    private record PlayerStatus(String name, long activityTime, boolean active) {
    }
}
