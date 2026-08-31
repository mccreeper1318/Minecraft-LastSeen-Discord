package com.pinnaclesmp.lastseendiscord;

final class RetryableSyncException extends SyncException {
    static final long NO_SERVER_DELAY = -1L;

    private final long suggestedDelayMillis;

    RetryableSyncException(String safeMessage, long suggestedDelayMillis) {
        super(safeMessage);
        this.suggestedDelayMillis = suggestedDelayMillis;
    }

    RetryableSyncException(String safeMessage, Throwable cause) {
        super(safeMessage, cause);
        this.suggestedDelayMillis = NO_SERVER_DELAY;
    }

    long suggestedDelayMillis() {
        return suggestedDelayMillis;
    }
}
