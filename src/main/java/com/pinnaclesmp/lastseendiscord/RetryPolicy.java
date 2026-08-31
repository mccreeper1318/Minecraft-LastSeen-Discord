package com.pinnaclesmp.lastseendiscord;

import java.util.concurrent.ThreadLocalRandom;

final class RetryPolicy {
    static final int MAX_ATTEMPTS = 5;
    static final long MAX_DELAY_MILLIS = 60_000L;
    private static final long BASE_DELAY_MILLIS = 1_000L;

    private RetryPolicy() {
    }

    static long delayMillis(int attempt, long serverSuggestedDelayMillis) {
        int boundedAttempt = Math.max(1, Math.min(attempt, MAX_ATTEMPTS));
        long exponential = Math.min(MAX_DELAY_MILLIS, BASE_DELAY_MILLIS << (boundedAttempt - 1));
        long base = serverSuggestedDelayMillis > 0L
                ? Math.min(MAX_DELAY_MILLIS, serverSuggestedDelayMillis)
                : exponential;
        long jitterLimit = Math.max(1L, Math.min(1_000L, base / 4L));
        long jitter = ThreadLocalRandom.current().nextLong(jitterLimit + 1L);
        return Math.min(MAX_DELAY_MILLIS, base + jitter);
    }
}
