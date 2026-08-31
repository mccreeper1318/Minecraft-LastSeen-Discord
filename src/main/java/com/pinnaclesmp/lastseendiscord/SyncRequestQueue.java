package com.pinnaclesmp.lastseendiscord;

final class SyncRequestQueue {
    private boolean workerOwned;
    private boolean pending;
    private boolean stopped;
    private String reason;

    synchronized boolean request(String requestedReason) {
        if (stopped) {
            return false;
        }

        pending = true;
        reason = requestedReason;
        if (workerOwned) {
            return false;
        }

        workerOwned = true;
        return true;
    }

    synchronized Work poll() {
        if (stopped || !pending) {
            workerOwned = false;
            return null;
        }

        pending = false;
        String currentReason = reason;
        reason = null;
        return new Work(currentReason == null ? "queued update" : currentReason);
    }

    synchronized void requeue(String retryReason) {
        if (!stopped) {
            pending = true;
            if (reason == null) {
                reason = retryReason;
            }
        }
    }

    synchronized void releaseWorker() {
        workerOwned = false;
    }

    synchronized boolean isStopped() {
        return stopped;
    }

    synchronized void stop() {
        stopped = true;
        pending = false;
        reason = null;
    }

    record Work(String reason) {
    }
}
