package com.pinnaclesmp.lastseendiscord;

final class StaleConfigurationException extends SyncException {
    StaleConfigurationException() {
        super("Discarded a Discord synchronization result because the plugin configuration changed while it was in flight.");
    }
}
