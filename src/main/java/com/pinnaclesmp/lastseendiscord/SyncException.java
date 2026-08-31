package com.pinnaclesmp.lastseendiscord;

import java.io.IOException;

class SyncException extends IOException {
    SyncException(String safeMessage) {
        super(safeMessage);
    }

    SyncException(String safeMessage, Throwable cause) {
        super(safeMessage, cause);
    }
}
