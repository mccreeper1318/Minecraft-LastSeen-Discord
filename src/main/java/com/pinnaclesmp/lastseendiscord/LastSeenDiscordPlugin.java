package com.pinnaclesmp.lastseendiscord;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;

public final class LastSeenDiscordPlugin extends JavaPlugin {
    private DiscordSyncService discordSyncService;
    private long schedulerTaskId = -1L;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        this.discordSyncService = new DiscordSyncService(this);
        Bukkit.getPluginManager().registerEvents(new PlayerActivityListener(this), this);
        startScheduler();

        if (getConfig().getBoolean("updates.update-on-enable", true)) {
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
        long intervalMinutes = Math.max(1L, getConfig().getLong("updates.interval-minutes", 60L));
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
        if (!sender.hasPermission("lastseendiscord.admin")) {
            sender.sendMessage("§cYou do not have permission to use this command.");
            return true;
        }

        if (args.length == 0) {
            sender.sendMessage("§eUsage: /" + label + " <reload|sync|recover-create>");
            return true;
        }

        if (args[0].equalsIgnoreCase("reload")) {
            reloadConfig();
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
            sender.sendMessage("§aLastSeenDiscord config reloaded.");
            discordSyncService.requestSync("manual reload");
            return true;
        }

        if (args[0].equalsIgnoreCase("sync")) {
            sender.sendMessage("§aQueued a Discord sync.");
            discordSyncService.requestSync("manual sync");
            return true;
        }

        if (args[0].equalsIgnoreCase("recover-create")) {
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

        sender.sendMessage("§eUsage: /" + label + " <reload|sync|recover-create>");
        return true;
    }

    public FileConfiguration config() {
        return getConfig();
    }
}
