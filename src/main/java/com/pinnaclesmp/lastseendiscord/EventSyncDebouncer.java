package com.pinnaclesmp.lastseendiscord;

import java.util.function.Consumer;

final class EventSyncDebouncer {
    static final int DEFAULT_DEBOUNCE_SECONDS = 5;
    static final int MAX_DEBOUNCE_SECONDS = 60;

    private final Scheduler scheduler;
    private final Consumer<String> syncRequester;

    private Cancellable pendingTask;
    private long generation;

    EventSyncDebouncer(Scheduler scheduler, Consumer<String> syncRequester) {
        this.scheduler = scheduler;
        this.syncRequester = syncRequester;
    }

    void request(String reason, int configuredSeconds) {
        int debounceSeconds = normalizeSeconds(configuredSeconds);
        if (debounceSeconds == 0) {
            Cancellable taskToCancel;
            synchronized (this) {
                generation++;
                taskToCancel = pendingTask;
                pendingTask = null;
            }
            if (taskToCancel != null) {
                taskToCancel.cancel();
            }
            syncRequester.accept(reason);
            return;
        }

        final long requestGeneration;
        Cancellable taskToCancel;
        synchronized (this) {
            requestGeneration = ++generation;
            taskToCancel = pendingTask;
            pendingTask = null;
        }
        if (taskToCancel != null) {
            taskToCancel.cancel();
        }

        Cancellable scheduled = scheduler.schedule(debounceSeconds * 20L,
                () -> fire(requestGeneration, reason));
        synchronized (this) {
            if (requestGeneration != generation) {
                scheduled.cancel();
            } else {
                pendingTask = scheduled;
            }
        }
    }

    synchronized void cancel() {
        generation++;
        if (pendingTask != null) {
            pendingTask.cancel();
            pendingTask = null;
        }
    }

    private void fire(long requestGeneration, String reason) {
        synchronized (this) {
            if (requestGeneration != generation) {
                return;
            }
            generation++;
            pendingTask = null;
        }
        syncRequester.accept(reason);
    }

    static int normalizeSeconds(int configuredSeconds) {
        return Math.max(0, Math.min(MAX_DEBOUNCE_SECONDS, configuredSeconds));
    }

    interface Scheduler {
        Cancellable schedule(long delayTicks, Runnable task);
    }

    interface Cancellable {
        void cancel();
    }
}
