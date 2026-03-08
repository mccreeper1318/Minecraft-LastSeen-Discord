package com.example.lastseendiscord;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

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
        getLogger().info("LastSeenDiscord disabled.");
    }

    public void restartScheduler() {
        stopScheduler();
        startScheduler();
    }

    private void startScheduler() {
        long intervalMinutes = Math.max(1L, getConfig().getLong("updates.interval-minutes", 60L));
        long intervalTicks = intervalMinutes * 60L * 20L;

        schedulerTaskId = Bukkit.getScheduler().runTaskTimerAsynchronously(
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
            sender.sendMessage("§eUsage: /" + label + " <reload|sync>");
            return true;
        }

        if (args[0].equalsIgnoreCase("reload")) {
            reloadConfig();
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

        sender.sendMessage("§eUsage: /" + label + " <reload|sync>");
        return true;
    }

    public FileConfiguration config() {
        return getConfig();
    }
}
