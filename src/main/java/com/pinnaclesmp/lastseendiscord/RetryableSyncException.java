package com.pinnaclesmp.lastseendiscord;

final class RetryableSyncException extends SyncException {
    static final long NO_SERVER_DELAY = -1L;

    private final long suggestedDelayMillis;
    private final boolean deliveryMayBeAmbiguous;

    RetryableSyncException(String safeMessage, long suggestedDelayMillis) {
        super(safeMessage);
        this.suggestedDelayMillis = suggestedDelayMillis;
        this.deliveryMayBeAmbiguous = false;
    }

    RetryableSyncException(String safeMessage, Throwable cause) {
        super(safeMessage, cause);
        this.suggestedDelayMillis = NO_SERVER_DELAY;
        this.deliveryMayBeAmbiguous = true;
    }

    long suggestedDelayMillis() {
        return suggestedDelayMillis;
    }

    boolean deliveryMayBeAmbiguous() {
        return deliveryMayBeAmbiguous;
    }
}
