package com.pinnaclesmp.lastseendiscord;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.scheduler.BukkitTask;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

public final class DiscordSyncService {
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
        webhookStateManager.shutdown();
        requestQueue.stop();
        synchronized (lifecycleLock) {
            if (retryTask != null) {
                retryTask.cancel();
                retryTask = null;
            }
        }
        webhookClient.shutdown();
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
        Future<CapturedSyncSnapshot> future = Bukkit.getScheduler().callSyncMethod(plugin, this::captureSyncSnapshot);
        final CapturedSyncSnapshot captured;
        try {
            captured = future.get();
        } catch (ExecutionException ex) {
            throw new IOException("Failed to collect the player activity snapshot on the server thread.");
        }

        if (!captured.configured()) {
            return SyncSnapshot.unconfigured(captured.configurationMessage());
        }

        List<String> chunks = DiscordActivityMessageBuilder.build(
                captured.settings(),
                captured.players(),
                captured.capturedAtMillis()
        );
        return new SyncSnapshot(
                true,
                null,
                captured.endpoint(),
                captured.generation(),
                captured.webhookIdentity(),
                captured.messageIds(),
                chunks
        );
    }

    private CapturedSyncSnapshot captureSyncSnapshot() {
        if (!runtimeStateUsable) {
            return CapturedSyncSnapshot.unconfigured("Skipping Discord sync: runtime state is unavailable. Repair state "
                    + "storage and reconcile the Discord messages if needed, then restart the server.");
        }

        WebhookStateManager.Snapshot stateSnapshot = webhookStateManager.snapshot();
        if (stateSnapshot.stopped()) {
            return CapturedSyncSnapshot.unconfigured("Skipping Discord sync: plugin shutdown is in progress.");
        }

        FileConfiguration config = plugin.config();
        String configuredUrl = config.getString("discord.webhook-url", "").trim();
        if (isWebhookUnconfigured(configuredUrl)) {
            return CapturedSyncSnapshot.unconfigured("Skipping Discord sync: discord.webhook-url is not configured.");
        }

        final WebhookEndpoint endpoint;
        try {
            endpoint = WebhookEndpoint.parse(configuredUrl);
        } catch (SyncException ex) {
            return CapturedSyncSnapshot.unconfigured("Skipping Discord sync: " + ex.getMessage());
        }

        if (!Objects.equals(stateSnapshot.webhookIdentity(), endpoint.stateIdentity())) {
            return CapturedSyncSnapshot.unconfigured("Skipping Discord sync: webhook runtime state is not bound to the "
                    + "configured destination. Run /lsd reload or restart the server after fixing state storage.");
        }

        int inactiveAfterDays = Math.max(1, config.getInt("activity.inactive-after-days", 30));
        boolean includeActivityDate = config.contains("discord.include-last-seen-date")
                ? config.getBoolean("discord.include-last-seen-date", false)
                : config.getBoolean("discord.include-last-login-date", false);
        TimestampSource timestampSource = TimestampSource.fromConfig(
                config.getString("activity.timestamp-source", "LAST_SEEN")
        );
        String header = config.getString("discord.header", "").trim();
        long capturedAtMillis = System.currentTimeMillis();

        List<DiscordActivityMessageBuilder.PlayerActivity> players = new ArrayList<>();
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

            players.add(new DiscordActivityMessageBuilder.PlayerActivity(name, activityTime));
        }

        return new CapturedSyncSnapshot(
                true,
                null,
                endpoint,
                stateSnapshot.generation(),
                stateSnapshot.webhookIdentity(),
                stateSnapshot.messageIds(),
                new DiscordActivityMessageBuilder.Settings(
                        inactiveAfterDays,
                        includeActivityDate,
                        timestampSource.displayName(),
                        header
                ),
                List.copyOf(players),
                capturedAtMillis
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

    private record CapturedSyncSnapshot(
            boolean configured,
            String configurationMessage,
            WebhookEndpoint endpoint,
            long generation,
            String webhookIdentity,
            List<String> messageIds,
            DiscordActivityMessageBuilder.Settings settings,
            List<DiscordActivityMessageBuilder.PlayerActivity> players,
            long capturedAtMillis
    ) {
        static CapturedSyncSnapshot unconfigured(String message) {
            return new CapturedSyncSnapshot(
                    false,
                    message,
                    null,
                    -1L,
                    null,
                    List.of(),
                    null,
                    List.of(),
                    0L
            );
        }
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
}
