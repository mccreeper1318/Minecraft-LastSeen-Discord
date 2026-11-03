package com.pinnaclesmp.lastseendiscord;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.scheduler.BukkitTask;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.function.Consumer;

public final class DiscordSyncService {
    private final LastSeenDiscordPlugin plugin;
    private final SyncRequestQueue requestQueue = new SyncRequestQueue();
    private final MessageStateStore messageStateStore;
    private final WebhookStateManager webhookStateManager;
    private final DiscordWebhookClient webhookClient;
    private final Object lifecycleLock = new Object();

    private volatile boolean runtimeStateUsable;
    private volatile boolean syncInProgress;
    private volatile int retryAttempt;
    private volatile long lastAttemptMillis;
    private volatile long lastSuccessMillis;
    private volatile String lastResult = "never";
    private BukkitTask retryTask;

    public DiscordSyncService(LastSeenDiscordPlugin plugin) {
        this.plugin = plugin;
        Path statePath = plugin.getDataFolder().toPath().resolve("message-state.json");
        this.messageStateStore = new MessageStateStore(statePath);

        ValidatedConfiguration configuration = plugin.validatedConfiguration();
        InitialMessageState initialState = loadInitialMessageState(statePath, configuration);
        this.webhookStateManager = new WebhookStateManager(messageStateStore, initialState.state());
        this.runtimeStateUsable = initialState.usable();
        if (runtimeStateUsable) {
            try {
                boolean changed = webhookStateManager.advanceConfiguration(
                        configuration.webhookIdentity(),
                        configuration.webhookRebindState()
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

        String userAgent = plugin.getPluginMeta().getName() + "/" + plugin.getPluginMeta().getVersion();
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
        ValidatedConfiguration configuration = plugin.validatedConfiguration();
        try {
            boolean changed = webhookStateManager.advanceConfiguration(
                    configuration.webhookIdentity(),
                    configuration.webhookRebindState()
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

    public SyncStatus status() {
        SyncRequestQueue.Snapshot queueSnapshot = requestQueue.snapshot();
        WebhookStateManager.Snapshot stateSnapshot = webhookStateManager.snapshot();
        boolean retryScheduled;
        synchronized (lifecycleLock) {
            retryScheduled = retryTask != null;
        }

        boolean configured = plugin.validatedConfiguration().webhookEndpoint() != null;
        String state;
        if (queueSnapshot.stopped() || stateSnapshot.stopped()) {
            state = "STOPPED";
        } else if (!runtimeStateUsable) {
            state = "STATE_UNAVAILABLE";
        } else if (!configured) {
            state = "UNCONFIGURED";
        } else if (syncInProgress || stateSnapshot.createInProgress()) {
            state = "SYNCING";
        } else if (stateSnapshot.createOutcomeUnknown()) {
            state = "CREATE_BLOCKED";
        } else if (retryScheduled) {
            state = "RETRY_WAIT";
        } else if (queueSnapshot.pending()) {
            state = "QUEUED";
        } else if (queueSnapshot.workerOwned()) {
            state = "WORKING";
        } else {
            state = "IDLE";
        }

        return new SyncStatus(
                state,
                configured,
                runtimeStateUsable,
                lastAttemptMillis,
                lastSuccessMillis,
                lastResult,
                queueSnapshot.pending(),
                queueSnapshot.workerOwned(),
                retryScheduled,
                retryAttempt,
                stateSnapshot.messageIds().size(),
                stateSnapshot.createOutcomeUnknown()
        );
    }

    public void testWebhook(Consumer<WebhookTestResult> resultConsumer) {
        WebhookEndpoint endpoint = plugin.validatedConfiguration().webhookEndpoint();
        if (endpoint == null) {
            resultConsumer.accept(new WebhookTestResult(
                    false,
                    "Discord webhook is not configured or is invalid."
            ));
            return;
        }

        try {
            Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                WebhookTestResult result;
                try {
                    webhookClient.test(endpoint);
                    result = new WebhookTestResult(true, "Discord webhook connection test passed.");
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    result = new WebhookTestResult(false, "Discord webhook connection test was interrupted.");
                } catch (Exception ex) {
                    result = new WebhookTestResult(false, safeFailureMessage(ex));
                }

                WebhookTestResult completedResult = result;
                try {
                    Bukkit.getScheduler().runTask(plugin, () -> resultConsumer.accept(completedResult));
                } catch (RuntimeException ex) {
                    plugin.getLogger().warning("Could not deliver the Discord webhook test result.");
                }
            });
        } catch (RuntimeException ex) {
            resultConsumer.accept(new WebhookTestResult(false, "Could not schedule the Discord webhook test."));
        }
    }

    public ResetResult resetTrackedMessages() throws IOException {
        synchronized (lifecycleLock) {
            if (!runtimeStateUsable) {
                return new ResetResult(ResetState.STATE_UNAVAILABLE, 0);
            }

            ValidatedConfiguration configuration = plugin.validatedConfiguration();
            if (configuration.webhookEndpoint() == null) {
                return new ResetResult(ResetState.UNCONFIGURED, 0);
            }

            SyncRequestQueue.Snapshot queueSnapshot = requestQueue.snapshot();
            WebhookStateManager.Snapshot stateSnapshot = webhookStateManager.snapshot();
            if (queueSnapshot.workerOwned()
                    || queueSnapshot.pending()
                    || retryTask != null
                    || stateSnapshot.createInProgress()) {
                return new ResetResult(ResetState.BUSY, 0);
            }

            try {
                int clearedPages = webhookStateManager.resetTrackedMessages(configuration.webhookIdentity());
                retryAttempt = 0;
                lastResult = "message state reset";
                return new ResetResult(ResetState.SUCCESS, clearedPages);
            } catch (StaleConfigurationException ex) {
                return new ResetResult(ResetState.STALE_CONFIGURATION, 0);
            }
        }
    }

    private void runSyncLoop() {
        while (!requestQueue.isStopped()) {
            SyncRequestQueue.Work work = requestQueue.poll();
            if (work == null) {
                return;
            }

            lastAttemptMillis = System.currentTimeMillis();
            lastResult = "running";
            syncInProgress = true;
            try {
                boolean updated = syncOnce(work.reason());
                retryAttempt = 0;
                if (updated) {
                    lastSuccessMillis = System.currentTimeMillis();
                    lastResult = "success";
                } else {
                    lastResult = "skipped";
                }
            } catch (StaleConfigurationException ex) {
                retryAttempt = 0;
                lastResult = "discarded after configuration change";
                plugin.getLogger().info("Discarded a Discord sync result because configuration changed while it was in flight.");
            } catch (RetryableSyncException ex) {
                retryAttempt++;
                if (retryAttempt <= RetryPolicy.MAX_ATTEMPTS && !requestQueue.isStopped()) {
                    long delayMillis = RetryPolicy.delayMillis(retryAttempt, ex.suggestedDelayMillis());
                    requestQueue.requeue("automatic retry");
                    lastResult = "waiting to retry";
                    plugin.getLogger().warning(ex.getMessage() + " Retrying in " + delayMillis
                            + "ms (attempt " + retryAttempt + "/" + RetryPolicy.MAX_ATTEMPTS + ").");
                    scheduleRetry(delayMillis);
                    return;
                }

                lastResult = "failed";
                plugin.getLogger().severe("Discord sync stopped retrying after " + RetryPolicy.MAX_ATTEMPTS
                        + " attempts: " + ex.getMessage());
                retryAttempt = 0;
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                lastResult = "interrupted";
                requestQueue.releaseWorker();
                return;
            } catch (Exception ex) {
                retryAttempt = 0;
                lastResult = "failed";
                logSafeFailure(work.reason(), ex);
            } finally {
                syncInProgress = false;
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

    private boolean syncOnce(String reason) throws IOException, InterruptedException {
        SyncSnapshot snapshot = collectSyncSnapshot();
        if (!snapshot.configured()) {
            plugin.getLogger().warning(snapshot.configurationMessage());
            return false;
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
        return true;
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

        ValidatedConfiguration configuration = plugin.validatedConfiguration();
        WebhookEndpoint endpoint = configuration.webhookEndpoint();
        if (endpoint == null) {
            return CapturedSyncSnapshot.unconfigured(configuration.webhookConfigurationMessage());
        }

        if (!configuration.webhookIdentity().equals(stateSnapshot.webhookIdentity())) {
            return CapturedSyncSnapshot.unconfigured("Skipping Discord sync: webhook runtime state is not bound to the "
                    + "configured destination. Run /lsd reload or restart the server after fixing state storage.");
        }

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
            if (!configuration.includesPlayer(
                    offlinePlayer.getUniqueId(),
                    name,
                    offlinePlayer.isWhitelisted()
            )) {
                continue;
            }

            long activityTime = resolveActivityTime(offlinePlayer, configuration.timestampSource());
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
                        configuration.inactiveAfterDays(),
                        configuration.includeActivityDate(),
                        configuration.timestampSource().displayName(),
                        configuration.header(),
                        configuration.activityFilter()
                ),
                List.copyOf(players),
                capturedAtMillis
        );
    }

    private long resolveActivityTime(
            OfflinePlayer offlinePlayer,
            ValidatedConfiguration.TimestampSource timestampSource
    ) {
        if (timestampSource == ValidatedConfiguration.TimestampSource.LAST_LOGIN) {
            long lastLogin = offlinePlayer.getLastLogin();
            return lastLogin > 0L ? lastLogin : offlinePlayer.getLastSeen();
        }
        long lastSeen = offlinePlayer.getLastSeen();
        return lastSeen > 0L ? lastSeen : offlinePlayer.getLastLogin();
    }

    private InitialMessageState loadInitialMessageState(Path statePath, ValidatedConfiguration configuration) {
        if (Files.exists(statePath)) {
            try {
                return new InitialMessageState(messageStateStore.load(), true);
            } catch (IOException ex) {
                plugin.getLogger().severe("Could not read message-state.json. Discord synchronization is disabled "
                        + "to prevent duplicate messages. Repair or remove the file after reconciling the Discord "
                        + "messages, then restart the server.");
                return new InitialMessageState(
                        new MessageStateStore.State(List.of(), true, configuration.webhookIdentity()),
                        false
                );
            }
        }

        List<String> legacyIds = configuration.legacyMessageIds();
        String migrationIdentity = configuration.webhookRebindState() ? configuration.webhookIdentity() : null;
        if (!legacyIds.isEmpty() && migrationIdentity != null) {
            try {
                messageStateStore.save(legacyIds, false, migrationIdentity);
                plugin.getLogger().info("Migrated Discord message IDs to message-state.json.");
            } catch (IOException ex) {
                plugin.getLogger().severe("Could not migrate Discord message IDs to message-state.json. Discord "
                        + "synchronization is disabled to prevent untracked messages. Fix state storage, then "
                        + "restart the server.");
                return new InitialMessageState(
                        new MessageStateStore.State(List.copyOf(legacyIds), true, migrationIdentity),
                        false
                );
            }
        }
        return new InitialMessageState(
                new MessageStateStore.State(List.copyOf(legacyIds), false, migrationIdentity),
                true
        );
    }

    private record InitialMessageState(MessageStateStore.State state, boolean usable) {
    }

    private void logSafeFailure(String reason, Exception exception) {
        plugin.getLogger().severe("Discord sync failed (" + reason + "): " + safeFailureMessage(exception));
    }

    private String safeFailureMessage(Exception exception) {
        if (exception instanceof SyncException) {
            return exception.getMessage();
        }
        if (exception instanceof IOException) {
            return "A local I/O operation failed.";
        }
        return "An unexpected " + exception.getClass().getSimpleName() + " occurred.";
    }

    public record SyncStatus(
            String state,
            boolean configured,
            boolean runtimeStateUsable,
            long lastAttemptMillis,
            long lastSuccessMillis,
            String lastResult,
            boolean queued,
            boolean workerActive,
            boolean retryScheduled,
            int retryAttempt,
            int trackedPages,
            boolean createBlocked
    ) {
    }

    public record WebhookTestResult(boolean success, String message) {
    }

    public enum ResetState {
        SUCCESS,
        BUSY,
        STATE_UNAVAILABLE,
        UNCONFIGURED,
        STALE_CONFIGURATION
    }

    public record ResetResult(ResetState state, int clearedPages) {
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
