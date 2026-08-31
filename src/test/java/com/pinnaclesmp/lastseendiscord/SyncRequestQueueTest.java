package com.pinnaclesmp.lastseendiscord;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SyncRequestQueueTest {
    @Test
    void requestArrivingAsWorkerFinishesCannotBeStranded() {
        SyncRequestQueue queue = new SyncRequestQueue();

        assertTrue(queue.request("first"));
        assertEquals("first", queue.poll().reason());
        assertFalse(queue.request("arrived while exiting"));
        assertEquals("arrived while exiting", queue.poll().reason());
        assertNull(queue.poll());

        assertTrue(queue.request("after release"));
        assertEquals("after release", queue.poll().reason());
    }

    @Test
    void retryPreservesANewerPendingReason() {
        SyncRequestQueue queue = new SyncRequestQueue();
        queue.request("first");
        queue.poll();
        queue.request("new player event");
        queue.requeue("automatic retry");

        assertEquals("new player event", queue.poll().reason());
    }

    @Test
    void stoppedQueueRejectsFutureWork() {
        SyncRequestQueue queue = new SyncRequestQueue();
        queue.request("first");
        queue.stop();

        assertNull(queue.poll());
        assertFalse(queue.request("late"));
    }
}
