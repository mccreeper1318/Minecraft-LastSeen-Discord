package com.example.lastseendiscord;

import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

public final class PlayerActivityListener implements Listener {
    private final LastSeenDiscordPlugin plugin;

    public PlayerActivityListener(LastSeenDiscordPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        if (plugin.config().getBoolean("updates.update-on-join", true)) {
            plugin.getDiscordSyncService().requestSync("player join: " + event.getPlayer().getName());
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        if (plugin.config().getBoolean("updates.update-on-quit", true)) {
            plugin.getDiscordSyncService().requestSync("player quit: " + event.getPlayer().getName());
        }
    }
}
