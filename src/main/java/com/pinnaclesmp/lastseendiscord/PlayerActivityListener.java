package com.pinnaclesmp.lastseendiscord;

import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.scheduler.BukkitTask;

public final class PlayerActivityListener implements Listener {
    private final LastSeenDiscordPlugin plugin;
    private final EventSyncDebouncer debouncer;

    public PlayerActivityListener(LastSeenDiscordPlugin plugin) {
        this.plugin = plugin;
        this.debouncer = new EventSyncDebouncer(
                (delayTicks, task) -> {
                    BukkitTask scheduled = Bukkit.getScheduler().runTaskLater(plugin, task, delayTicks);
                    return scheduled::cancel;
                },
                reason -> plugin.getDiscordSyncService().requestSync(reason)
        );
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        if (plugin.config().getBoolean("updates.update-on-join", true)) {
            requestActivitySync("player join: " + event.getPlayer().getName());
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        if (plugin.config().getBoolean("updates.update-on-quit", true)) {
            requestActivitySync("player quit: " + event.getPlayer().getName());
        }
    }

    private void requestActivitySync(String reason) {
        int debounceSeconds = plugin.config().getInt(
                "updates.event-debounce-seconds",
                EventSyncDebouncer.DEFAULT_DEBOUNCE_SECONDS
        );
        debouncer.request(reason, debounceSeconds);
    }
}
