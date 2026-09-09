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
        ValidatedConfiguration configuration = plugin.validatedConfiguration();
        if (configuration.updateOnJoin()) {
            requestActivitySync("player join: " + event.getPlayer().getName(), configuration);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        ValidatedConfiguration configuration = plugin.validatedConfiguration();
        if (configuration.updateOnQuit()) {
            requestActivitySync("player quit: " + event.getPlayer().getName(), configuration);
        }
    }

    private void requestActivitySync(String reason, ValidatedConfiguration configuration) {
        debouncer.request(reason, configuration.eventDebounceSeconds());
    }
}
