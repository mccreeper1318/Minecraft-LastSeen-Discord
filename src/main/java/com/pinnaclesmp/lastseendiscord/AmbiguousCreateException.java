package com.pinnaclesmp.lastseendiscord;

final class AmbiguousCreateException extends SyncException {
    AmbiguousCreateException(Throwable cause) {
        super(
                "Discord may have created a message, but its response was lost. Automatic message creation is "
                        + "paused to prevent duplicates; inspect the channel, then use /lsd recover-create confirm.",
                cause
        );
    }
}
