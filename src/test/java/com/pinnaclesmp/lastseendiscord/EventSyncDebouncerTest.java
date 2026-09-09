package com.pinnaclesmp.lastseendiscord;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EventSyncDebouncerTest {
    @Test
    void burstOfActivityRequestsProducesOneSyncWithNewestReason() {
        FakeScheduler scheduler = new FakeScheduler();
        List<String> requested = new ArrayList<>();
        EventSyncDebouncer debouncer = new EventSyncDebouncer(scheduler, requested::add);

        debouncer.request("player join: Alpha", 5);
        debouncer.request("player quit: Bravo", 5);
        debouncer.request("player join: Charlie", 5);

        assertEquals(3, scheduler.tasks.size());
        assertEquals(100L, scheduler.tasks.get(2).delayTicks);
        assertTrue(scheduler.tasks.get(0).cancelled);
        assertTrue(scheduler.tasks.get(1).cancelled);

        scheduler.runAll();

        assertEquals(List.of("player join: Charlie"), requested);
    }

    @Test
    void eventFiringWhileSyncWorkerIsOwnedQueuesFinalPass() {
        FakeScheduler scheduler = new FakeScheduler();
        SyncRequestQueue queue = new SyncRequestQueue();
        EventSyncDebouncer debouncer = new EventSyncDebouncer(
                scheduler,
                reason -> queue.request(reason)
        );

        assertTrue(queue.request("current sync"));
        assertEquals("current sync", queue.poll().reason());

        debouncer.request("player quit: Delta", 2);
        scheduler.runAll();

        assertEquals("player quit: Delta", queue.poll().reason());
    }

    @Test
    void zeroDebounceCancelsPendingTimerAndRequestsImmediately() {
        FakeScheduler scheduler = new FakeScheduler();
        List<String> requested = new ArrayList<>();
        EventSyncDebouncer debouncer = new EventSyncDebouncer(scheduler, requested::add);

        debouncer.request("player join: Echo", 5);
        FakeTask pending = scheduler.tasks.get(0);

        debouncer.request("player quit: Foxtrot", 0);

        assertTrue(pending.cancelled);
        assertEquals(List.of("player quit: Foxtrot"), requested);
        scheduler.runAll();
        assertEquals(List.of("player quit: Foxtrot"), requested);
    }

    @Test
    void debounceDurationIsClampedToDocumentedRange() {
        assertEquals(0, EventSyncDebouncer.normalizeSeconds(-10));
        assertEquals(0, EventSyncDebouncer.normalizeSeconds(0));
        assertEquals(5, EventSyncDebouncer.normalizeSeconds(5));
        assertEquals(60, EventSyncDebouncer.normalizeSeconds(60));
        assertEquals(60, EventSyncDebouncer.normalizeSeconds(600));
    }

    @Test
    void cancellingDebouncerDropsPendingActivityRequest() {
        FakeScheduler scheduler = new FakeScheduler();
        List<String> requested = new ArrayList<>();
        EventSyncDebouncer debouncer = new EventSyncDebouncer(scheduler, requested::add);

        debouncer.request("player join: Golf", 5);
        debouncer.cancel();
        scheduler.runAll();

        assertTrue(requested.isEmpty());
        assertTrue(scheduler.tasks.get(0).cancelled);
    }

    private static final class FakeScheduler implements EventSyncDebouncer.Scheduler {
        private final List<FakeTask> tasks = new ArrayList<>();

        @Override
        public EventSyncDebouncer.Cancellable schedule(long delayTicks, Runnable task) {
            FakeTask scheduled = new FakeTask(delayTicks, task);
            tasks.add(scheduled);
            return scheduled;
        }

        void runAll() {
            for (FakeTask task : List.copyOf(tasks)) {
                if (!task.cancelled && !task.ran) {
                    task.ran = true;
                    task.runnable.run();
                }
            }
        }
    }

    private static final class FakeTask implements EventSyncDebouncer.Cancellable {
        private final long delayTicks;
        private final Runnable runnable;
        private boolean cancelled;
        private boolean ran;

        private FakeTask(long delayTicks, Runnable runnable) {
            this.delayTicks = delayTicks;
            this.runnable = runnable;
        }

        @Override
        public void cancel() {
            cancelled = true;
        }
    }
}
