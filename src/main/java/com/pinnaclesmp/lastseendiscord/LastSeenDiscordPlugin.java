package com.pinnaclesmp.lastseendiscord;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class LastSeenDiscordPlugin extends JavaPlugin {
    private static final String ADMIN_PERMISSION = "lastseendiscord.admin";
    private static final DateTimeFormatter STATUS_TIME_FORMAT = DateTimeFormatter
            .ofPattern("yyyy-MM-dd HH:mm:ss z")
            .withZone(ZoneId.systemDefault());

    private DiscordSyncService discordSyncService;
    private volatile ValidatedConfiguration validatedConfiguration;
    private long schedulerTaskId = -1L;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        reloadValidatedConfiguration();
        this.discordSyncService = new DiscordSyncService(this);
        Bukkit.getPluginManager().registerEvents(new PlayerActivityListener(this), this);
        startScheduler();

        if (validatedConfiguration.updateOnEnable()) {
            discordSyncService.requestSync("plugin enable");
        }

        getLogger().info("LastSeenDiscord enabled.");
    }

    @Override
    public void onDisable() {
        stopScheduler();
        if (discordSyncService != null) {
            discordSyncService.shutdown();
        }
        getLogger().info("LastSeenDiscord disabled.");
    }

    public void restartScheduler() {
        stopScheduler();
        startScheduler();
    }

    private void startScheduler() {
        long intervalMinutes = validatedConfiguration.intervalMinutes();
        long intervalTicks = intervalMinutes * 60L * 20L;

        schedulerTaskId = Bukkit.getScheduler().runTaskTimer(
                this,
                () -> discordSyncService.requestSync("scheduled sync"),
                intervalTicks,
                intervalTicks
        ).getTaskId();
    }

    private void stopScheduler() {
        if (schedulerTaskId != -1L) {
            Bukkit.getScheduler().cancelTask((int) schedulerTaskId);
            schedulerTaskId = -1L;
        }
    }

    public DiscordSyncService getDiscordSyncService() {
        return discordSyncService;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            if (!hasAnyCommandPermission(sender)) {
                sendNoPermission(sender);
                return true;
            }
            sendHelp(sender, label);
            return true;
        }

        String subcommand = args[0].toLowerCase(Locale.ROOT);
        switch (subcommand) {
            case "help" -> {
                if (!hasAnyCommandPermission(sender)) {
                    sendNoPermission(sender);
                    return true;
                }
                sendHelp(sender, label);
                return true;
            }
            case "reload" -> {
                if (!requirePermission(sender, "lastseendiscord.reload")) {
                    return true;
                }
                reloadConfig();
                reloadValidatedConfiguration();
                try {
                    discordSyncService.reloadConfiguration();
                } catch (IOException ex) {
                    restartScheduler();
                    sender.sendMessage("§cConfig reloaded, but Discord runtime state could not be rebound. "
                            + "Synchronization is disabled until state storage is fixed and the server is restarted.");
                    getLogger().severe("Could not rebind Discord message state after config reload.");
                    return true;
                }
                restartScheduler();
                sender.sendMessage("§aLastSeenDiscord config reloaded and validated.");
                discordSyncService.requestSync("manual reload");
                return true;
            }
            case "sync" -> {
                if (!requirePermission(sender, "lastseendiscord.sync")) {
                    return true;
                }
                sender.sendMessage("§aQueued a Discord sync.");
                discordSyncService.requestSync("manual sync");
                return true;
            }
            case "status" -> {
                if (!requirePermission(sender, "lastseendiscord.status")) {
                    return true;
                }
                sendStatus(sender);
                return true;
            }
            case "test" -> {
                if (!requirePermission(sender, "lastseendiscord.test")) {
                    return true;
                }
                sender.sendMessage("§eTesting the Discord webhook without creating or changing any messages...");
                discordSyncService.testWebhook(result -> {
                    if (result.success()) {
                        sender.sendMessage("§a" + result.message());
                    } else {
                        sender.sendMessage("§c" + result.message());
                    }
                });
                return true;
            }
            case "reset" -> {
                if (!requirePermission(sender, "lastseendiscord.reset")) {
                    return true;
                }
                return handleReset(sender, label, args);
            }
            case "recover-create" -> {
                if (!requirePermission(sender, "lastseendiscord.recover")) {
                    return true;
                }
                if (args.length < 2 || !args[1].equalsIgnoreCase("confirm")) {
                    sender.sendMessage("§cOnly use this after checking Discord and deleting any untracked duplicate page.");
                    sender.sendMessage("§eTo continue: /" + label + " recover-create confirm");
                    return true;
                }

                try {
                    if (!discordSyncService.recoverAmbiguousCreate()) {
                        sender.sendMessage("§eDiscord message creation is not currently paused.");
                        return true;
                    }
                } catch (IOException ex) {
                    sender.sendMessage("§cCould not save the recovered Discord message state. Check the server log.");
                    getLogger().severe("Could not clear the ambiguous Discord create state.");
                    return true;
                }

                sender.sendMessage("§aCleared the ambiguous create state and queued a Discord sync.");
                discordSyncService.requestSync("manual ambiguous-create recovery");
                return true;
            }
            default -> {
                if (!hasAnyCommandPermission(sender)) {
                    sendNoPermission(sender);
                    return true;
                }
                sendHelp(sender, label);
                return true;
            }
        }
    }

    private boolean handleReset(CommandSender sender, String label, String[] args) {
        if (args.length < 3
                || !args[1].equalsIgnoreCase("messages")
                || !args[2].equalsIgnoreCase("confirm")) {
            sender.sendMessage("§cThis forgets all tracked Discord page IDs but does not delete Discord messages.");
            sender.sendMessage("§cReconcile or delete the old managed pages first, or the next sync may create duplicates.");
            sender.sendMessage("§eTo continue: /" + label + " reset messages confirm");
            return true;
        }

        final DiscordSyncService.ResetResult result;
        try {
            result = discordSyncService.resetTrackedMessages();
        } catch (IOException ex) {
            sender.sendMessage("§cCould not save the reset Discord message state. Check the server log.");
            getLogger().severe("Could not reset tracked Discord message state.");
            return true;
        }

        switch (result.state()) {
            case SUCCESS -> {
                sender.sendMessage("§aCleared " + result.clearedPages()
                        + " tracked Discord page ID(s). No Discord messages were deleted.");
                sender.sendMessage("§eRun /" + label + " sync when you are ready to create a fresh managed page set.");
            }
            case BUSY -> sender.sendMessage("§cCannot reset message state while a sync, queue, or retry is active. "
                    + "Check /" + label + " status and try again when it is idle.");
            case STATE_UNAVAILABLE -> sender.sendMessage("§cDiscord runtime state is unavailable. Repair "
                    + "message-state.json or its storage and restart the server before resetting.");
            case UNCONFIGURED -> sender.sendMessage("§cA valid Discord webhook must be configured before resetting "
                    + "tracked message state.");
            case STALE_CONFIGURATION -> sender.sendMessage("§cDiscord webhook state changed. Run /" + label
                    + " reload, check status, and try again.");
        }
        return true;
    }

    private void sendStatus(CommandSender sender) {
        DiscordSyncService.SyncStatus status = discordSyncService.status();
        sender.sendMessage("§6LastSeenDiscord synchronization status");
        sender.sendMessage("§eState: §f" + status.state());
        sender.sendMessage("§eWebhook configured: §f" + yesNo(status.configured())
                + " §7| §eRuntime state: §f" + (status.runtimeStateUsable() ? "usable" : "unavailable"));
        sender.sendMessage("§eTracked pages: §f" + status.trackedPages()
                + " §7| §eCreate blocked: §f" + yesNo(status.createBlocked()));
        sender.sendMessage("§eQueue: §f" + (status.queued() ? "pending" : "clear")
                + " §7| §eWorker: §f" + (status.workerActive() ? "active" : "idle"));
        sender.sendMessage("§eRetry: §f" + (status.retryScheduled()
                ? "waiting (attempt " + status.retryAttempt() + "/" + RetryPolicy.MAX_ATTEMPTS + ")"
                : "none"));
        sender.sendMessage("§eLast attempt: §f" + formatStatusTime(status.lastAttemptMillis())
                + " §7| §eLast success: §f" + formatStatusTime(status.lastSuccessMillis()));
        sender.sendMessage("§eLast result: §f" + status.lastResult());
    }

    private void sendHelp(CommandSender sender, String label) {
        sender.sendMessage("§6LastSeenDiscord commands");
        addHelp(sender, label, "status", "Show synchronization and tracked-page status.", "lastseendiscord.status");
        addHelp(sender, label, "test", "Test the webhook without changing Discord messages.", "lastseendiscord.test");
        addHelp(sender, label, "sync", "Queue an immediate Discord update.", "lastseendiscord.sync");
        addHelp(sender, label, "reload", "Reload config, scheduling, and webhook state.", "lastseendiscord.reload");
        addHelp(sender, label, "recover-create confirm", "Resume after an ambiguous create response.",
                "lastseendiscord.recover");
        addHelp(sender, label, "reset messages confirm", "Forget tracked page IDs after reconciling Discord.",
                "lastseendiscord.reset");
    }

    private void addHelp(CommandSender sender, String label, String syntax, String description, String permission) {
        if (hasCommandPermission(sender, permission)) {
            sender.sendMessage("§e/" + label + " " + syntax + " §7- " + description);
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!hasAnyCommandPermission(sender)) {
            return List.of();
        }

        if (args.length == 1) {
            List<String> options = new ArrayList<>();
            options.add("help");
            addCompletion(sender, options, "reload", "lastseendiscord.reload");
            addCompletion(sender, options, "sync", "lastseendiscord.sync");
            addCompletion(sender, options, "status", "lastseendiscord.status");
            addCompletion(sender, options, "test", "lastseendiscord.test");
            addCompletion(sender, options, "recover-create", "lastseendiscord.recover");
            addCompletion(sender, options, "reset", "lastseendiscord.reset");
            return matching(options, args[0]);
        }

        if (args.length == 2
                && args[0].equalsIgnoreCase("recover-create")
                && hasCommandPermission(sender, "lastseendiscord.recover")) {
            return matching(List.of("confirm"), args[1]);
        }

        if (args.length == 2
                && args[0].equalsIgnoreCase("reset")
                && hasCommandPermission(sender, "lastseendiscord.reset")) {
            return matching(List.of("messages"), args[1]);
        }

        if (args.length == 3
                && args[0].equalsIgnoreCase("reset")
                && args[1].equalsIgnoreCase("messages")
                && hasCommandPermission(sender, "lastseendiscord.reset")) {
            return matching(List.of("confirm"), args[2]);
        }

        return List.of();
    }

    private void addCompletion(CommandSender sender, List<String> options, String option, String permission) {
        if (hasCommandPermission(sender, permission)) {
            options.add(option);
        }
    }

    private List<String> matching(List<String> options, String prefix) {
        String normalized = prefix == null ? "" : prefix.toLowerCase(Locale.ROOT);
        return options.stream()
                .filter(option -> option.startsWith(normalized))
                .sorted()
                .toList();
    }

    private boolean requirePermission(CommandSender sender, String permission) {
        if (hasCommandPermission(sender, permission)) {
            return true;
        }
        sendNoPermission(sender);
        return false;
    }

    private boolean hasCommandPermission(CommandSender sender, String permission) {
        return sender.hasPermission(ADMIN_PERMISSION) || sender.hasPermission(permission);
    }

    private boolean hasAnyCommandPermission(CommandSender sender) {
        return sender.hasPermission(ADMIN_PERMISSION)
                || sender.hasPermission("lastseendiscord.reload")
                || sender.hasPermission("lastseendiscord.sync")
                || sender.hasPermission("lastseendiscord.status")
                || sender.hasPermission("lastseendiscord.test")
                || sender.hasPermission("lastseendiscord.recover")
                || sender.hasPermission("lastseendiscord.reset");
    }

    private void sendNoPermission(CommandSender sender) {
        sender.sendMessage("§cYou do not have permission to use this command.");
    }

    private String formatStatusTime(long timestampMillis) {
        if (timestampMillis <= 0L) {
            return "never";
        }
        return STATUS_TIME_FORMAT.format(Instant.ofEpochMilli(timestampMillis));
    }

    private String yesNo(boolean value) {
        return value ? "yes" : "no";
    }

    private void reloadValidatedConfiguration() {
        FileConfiguration config = getConfig();
        validatedConfiguration = ValidatedConfiguration.parse(
                new ValidatedConfiguration.Values() {
                    @Override
                    public Object get(String path) {
                        return config.get(path);
                    }

                    @Override
                    public boolean contains(String path) {
                        return config.isSet(path);
                    }
                },
                message -> getLogger().warning("Configuration: " + message)
        );
    }

    ValidatedConfiguration validatedConfiguration() {
        return validatedConfiguration;
    }

    public FileConfiguration config() {
        return getConfig();
    }
}
