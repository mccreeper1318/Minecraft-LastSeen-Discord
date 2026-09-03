package com.pinnaclesmp.lastseendiscord;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.scheduler.BukkitTask;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

public final class DiscordSyncService {
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("uuuu-MM-dd")
            .withZone(ZoneId.systemDefault());
    private static final int DISCORD_CHUNK_MAX = 1900;

    private final LastSeenDiscordPlugin plugin;
    private final SyncRequestQueue requestQueue = new SyncRequestQueue();
    private final MessageStateStore messageStateStore;
    private final WebhookStateManager webhookStateManager;
    private final DiscordWebhookClient webhookClient;
    private final Object lifecycleLock = new Object();

    private volatile boolean runtimeStateUsable;
    private BukkitTask retryTask;
    private int retryAttempt;

    public DiscordSyncService(LastSeenDiscordPlugin plugin) {
        this.plugin = plugin;
        Path statePath = plugin.getDataFolder().toPath().resolve("message-state.json");
        this.messageStateStore = new MessageStateStore(statePath);

        ConfiguredWebhookIdentity configuredIdentity = configuredWebhookIdentity();
        InitialMessageState initialState = loadInitialMessageState(statePath, configuredIdentity);
        this.webhookStateManager = new WebhookStateManager(messageStateStore, initialState.state());
        this.runtimeStateUsable = initialState.usable();
        if (runtimeStateUsable) {
            try {
                boolean changed = webhookStateManager.advanceConfiguration(
                        configuredIdentity.identity(),
                        configuredIdentity.rebindState()
                );
                if (changed && Files.exists(statePath)) {
                    plugin.getLogger().info("Reset Discord message state for the configured webhook destination.");
                }
            } catch (IOException ex) {
                runtimeStateUsable = false;
                plugin.getLogger().severe("Could not bind message-state.json to the configured Discord webhook. "
                        + "Discord synchronization is disabled until state storage is repaired and the server is restarted.");
            }
        }

        String userAgent = plugin.getDescription().getName() + "/" + plugin.getDescription().getVersion();
        this.webhookClient = new DiscordWebhookClient(userAgent);
    }

    public void requestSync(String reason) {
        if (!requestQueue.request(reason)) {
            return;
        }

        try {
            Bukkit.getScheduler().runTaskAsynchronously(plugin, this::runSyncLoop);
        } catch (RuntimeException ex) {
            requestQueue.releaseWorker();
            plugin.getLogger().warning("Could not schedule the Discord synchronization worker.");
        }
    }

    public void reloadConfiguration() throws IOException {
        ConfiguredWebhookIdentity configuredIdentity = configuredWebhookIdentity();
        try {
            boolean changed = webhookStateManager.advanceConfiguration(
                    configuredIdentity.identity(),
                    configuredIdentity.rebindState()
            );
            if (changed) {
                plugin.getLogger().info("Discord webhook destination changed; cleared tracked message IDs for a clean lifecycle.");
            }
        } catch (IOException ex) {
            runtimeStateUsable = false;
            throw ex;
        }
    }

    public void shutdown() {
        requestQueue.stop();
        synchronized (lifecycleLock) {
            if (retryTask != null) {
                retryTask.cancel();
                retryTask = null;
            }
        }
    }

    public boolean recoverAmbiguousCreate() throws IOException {
        if (!runtimeStateUsable) {
            throw new SyncException("Discord runtime state is unavailable. Repair state storage and reconcile "
                    + "the Discord messages if needed, then restart the server.");
        }
        return webhookStateManager.recoverAmbiguousCreate();
    }

    private void runSyncLoop() {
        while (!requestQueue.isStopped()) {
            SyncRequestQueue.Work work = requestQueue.poll();
            if (work == null) {
                return;
            }

            try {
                syncOnce(work.reason());
                retryAttempt = 0;
            } catch (StaleConfigurationException ex) {
                retryAttempt = 0;
                plugin.getLogger().info("Discarded a Discord sync result because configuration changed while it was in flight.");
            } catch (RetryableSyncException ex) {
                retryAttempt++;
                if (retryAttempt <= RetryPolicy.MAX_ATTEMPTS && !requestQueue.isStopped()) {
                    long delayMillis = RetryPolicy.delayMillis(retryAttempt, ex.suggestedDelayMillis());
                    requestQueue.requeue("automatic retry");
                    plugin.getLogger().warning(ex.getMessage() + " Retrying in " + delayMillis
                            + "ms (attempt " + retryAttempt + "/" + RetryPolicy.MAX_ATTEMPTS + ").");
                    scheduleRetry(delayMillis);
                    return;
                }

                plugin.getLogger().severe("Discord sync stopped retrying after " + RetryPolicy.MAX_ATTEMPTS
                        + " attempts: " + ex.getMessage());
                retryAttempt = 0;
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                requestQueue.releaseWorker();
                return;
            } catch (Exception ex) {
                retryAttempt = 0;
                logSafeFailure(work.reason(), ex);
            }
        }
        requestQueue.releaseWorker();
    }

    private void scheduleRetry(long delayMillis) {
        long delayTicks = Math.max(1L, (delayMillis + 49L) / 50L);
        try {
            BukkitTask scheduled = Bukkit.getScheduler().runTaskLaterAsynchronously(plugin, () -> {
                synchronized (lifecycleLock) {
                    retryTask = null;
                }
                runSyncLoop();
            }, delayTicks);

            synchronized (lifecycleLock) {
                if (requestQueue.isStopped()) {
                    scheduled.cancel();
                } else {
                    retryTask = scheduled;
                }
            }
        } catch (RuntimeException ex) {
            requestQueue.releaseWorker();
            plugin.getLogger().warning("Could not schedule a delayed Discord synchronization retry.");
        }
    }

    private void syncOnce(String reason) throws IOException, InterruptedException {
        SyncSnapshot snapshot = collectSyncSnapshot();
        if (!snapshot.configured()) {
            plugin.getLogger().warning(snapshot.configurationMessage());
            return;
        }

        DiscordMessageSynchronizer messageSynchronizer = new DiscordMessageSynchronizer(
                webhookClient,
                webhookStateManager.bind(snapshot.generation(), snapshot.webhookIdentity())
        );
        List<String> finalMessageIds = messageSynchronizer.synchronize(
                snapshot.endpoint(),
                snapshot.chunks(),
                snapshot.messageIds()
        );
        webhookStateManager.commitSyncResult(
                snapshot.generation(),
                snapshot.webhookIdentity(),
                finalMessageIds
        );
        plugin.getLogger().info("Updated Discord webhook messages (" + snapshot.chunks().size()
                + " chunk(s), reason: " + reason + ")");
    }

    private SyncSnapshot collectSyncSnapshot() throws IOException, InterruptedException {
        Future<SyncSnapshot> future = Bukkit.getScheduler().callSyncMethod(plugin, this::createSyncSnapshot);
        try {
            return future.get();
        } catch (ExecutionException ex) {
            throw new IOException("Failed to collect the player activity snapshot on the server thread.");
        }
    }

    private SyncSnapshot createSyncSnapshot() {
        if (!runtimeStateUsable) {
            return SyncSnapshot.unconfigured("Skipping Discord sync: runtime state is unavailable. Repair state "
                    + "storage and reconcile the Discord messages if needed, then restart the server.");
        }
        FileConfiguration config = plugin.config();
        String configuredUrl = config.getString("discord.webhook-url", "").trim();
        if (isWebhookUnconfigured(configuredUrl)) {
            return SyncSnapshot.unconfigured("Skipping Discord sync: discord.webhook-url is not configured.");
        }

        final WebhookEndpoint endpoint;
        try {
            endpoint = WebhookEndpoint.parse(configuredUrl);
        } catch (SyncException ex) {
            return SyncSnapshot.unconfigured("Skipping Discord sync: " + ex.getMessage());
        }

        WebhookStateManager.Snapshot stateSnapshot = webhookStateManager.snapshot();
        if (!Objects.equals(stateSnapshot.webhookIdentity(), endpoint.stateIdentity())) {
            return SyncSnapshot.unconfigured("Skipping Discord sync: webhook runtime state is not bound to the "
                    + "configured destination. Run /lsd reload or restart the server after fixing state storage.");
        }

        return new SyncSnapshot(
                true,
                null,
                endpoint,
                stateSnapshot.generation(),
                stateSnapshot.webhookIdentity(),
                stateSnapshot.messageIds(),
                buildDiscordMessages(config)
        );
    }

    private ConfiguredWebhookIdentity configuredWebhookIdentity() {
        String configuredUrl = plugin.config().getString("discord.webhook-url", "").trim();
        if (isWebhookUnconfigured(configuredUrl)) {
            return new ConfiguredWebhookIdentity(null, true);
        }
        try {
            return new ConfiguredWebhookIdentity(WebhookEndpoint.parse(configuredUrl).stateIdentity(), true);
        } catch (SyncException ex) {
            return new ConfiguredWebhookIdentity(null, false);
        }
    }

    private boolean isWebhookUnconfigured(String configuredUrl) {
        return configuredUrl.isEmpty() || configuredUrl.equals("PUT_DISCORD_WEBHOOK_URL_HERE");
    }

    private InitialMessageState loadInitialMessageState(
            Path statePath,
            ConfiguredWebhookIdentity configuredIdentity
    ) {
        if (Files.exists(statePath)) {
            try {
                return new InitialMessageState(messageStateStore.load(), true);
            } catch (IOException ex) {
                plugin.getLogger().severe("Could not read message-state.json. Discord synchronization is disabled "
                        + "to prevent duplicate messages. Repair or remove the file after reconciling the Discord "
                        + "messages, then restart the server.");
                return new InitialMessageState(
                        new MessageStateStore.State(List.of(), true, configuredIdentity.identity()),
                        false
                );
            }
        }

        List<String> legacyIds = new ArrayList<>(plugin.config().getStringList("discord.message-ids"));
        String legacyId = plugin.config().getString("discord.message-id", "");
        List<String> sanitized = MessageStateStore.selectLegacyIds(legacyIds, legacyId);
        List<String> sanitizedList = MessageStateStore.sanitize(legacyIds);
        boolean ignoredConfiguredId = sanitizedList.size() != legacyIds.size()
                || (sanitizedList.isEmpty() && !legacyId.trim().isEmpty() && sanitized.isEmpty());
        if (ignoredConfiguredId) {
            plugin.getLogger().warning("Ignored invalid or duplicate Discord message IDs from config.yml.");
        }

        String migrationIdentity = configuredIdentity.rebindState() ? configuredIdentity.identity() : null;
        if (!sanitized.isEmpty() && migrationIdentity != null) {
            try {
                messageStateStore.save(sanitized, false, migrationIdentity);
                plugin.getLogger().info("Migrated Discord message IDs to message-state.json.");
            } catch (IOException ex) {
                plugin.getLogger().severe("Could not migrate Discord message IDs to message-state.json. Discord "
                        + "synchronization is disabled to prevent untracked messages. Fix state storage, then "
                        + "restart the server.");
                return new InitialMessageState(
                        new MessageStateStore.State(List.copyOf(sanitized), true, migrationIdentity),
                        false
                );
            }
        }
        return new InitialMessageState(
                new MessageStateStore.State(List.copyOf(sanitized), false, migrationIdentity),
                true
        );
    }

    private record InitialMessageState(MessageStateStore.State state, boolean usable) {
    }

    private record ConfiguredWebhookIdentity(String identity, boolean rebindState) {
    }

    private void logSafeFailure(String reason, Exception exception) {
        String safeMessage;
        if (exception instanceof SyncException) {
            safeMessage = exception.getMessage();
        } else if (exception instanceof IOException) {
            safeMessage = "A local I/O operation failed.";
        } else {
            safeMessage = "An unexpected " + exception.getClass().getSimpleName() + " occurred.";
        }
        plugin.getLogger().severe("Discord sync failed (" + reason + "): " + safeMessage);
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

            plugin.getLogger().warning("Splitting overlong Discord line (" + line.length()
                    + " chars, max " + DISCORD_CHUNK_MAX + ").");
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
                return lastSeen > 0L ? lastSeen : offlinePlayer.getLastLogin();
            }
        },
        LAST_LOGIN("last login") {
            @Override
            long resolve(OfflinePlayer offlinePlayer) {
                long lastLogin = offlinePlayer.getLastLogin();
                return lastLogin > 0L ? lastLogin : offlinePlayer.getLastSeen();
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

    private record SyncSnapshot(
            boolean configured,
            String configurationMessage,
            WebhookEndpoint endpoint,
            long generation,
            String webhookIdentity,
            List<String> messageIds,
            List<String> chunks
    ) {
        static SyncSnapshot unconfigured(String message) {
            return new SyncSnapshot(false, message, null, -1L, null, List.of(), List.of());
        }
    }

    private record PlayerStatus(String name, long activityTime, boolean active) {
    }
}
