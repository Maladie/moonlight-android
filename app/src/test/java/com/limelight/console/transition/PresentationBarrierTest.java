package com.limelight.console.transition;

import com.limelight.console.transition.PresentationBarrier.Outcome;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class PresentationBarrierTest {
    /** A manual clock and scheduler: nothing runs until {@link #advance}. */
    public static final class ManualTime {
        private long now;
        private final List<long[]> due = new ArrayList<>();
        private final List<Runnable> tasks = new ArrayList<>();

        public PresentationBarrier barrier() {
            return new PresentationBarrier(() -> now, (task, delayMs) -> {
                long[] slot = {now + delayMs, 0L};
                due.add(slot);
                tasks.add(task);
                return () -> slot[1] = 1L;
            });
        }

        public void advance(long ms) {
            now += ms;
            for (int i = 0; i < tasks.size(); i++) {
                long[] slot = due.get(i);
                if (slot[1] == 0L && slot[0] <= now) {
                    slot[1] = 1L;
                    tasks.get(i).run();
                }
            }
        }

        public int scheduledPending() {
            int count = 0;
            for (long[] slot : due) if (slot[1] == 0L) count++;
            return count;
        }
    }

    private final ManualTime time = new ManualTime();
    private final PresentationBarrier barrier = time.barrier();
    private final List<String> settled = new ArrayList<>();

    private PresentationBarrier.Ticket await(String fence, long gen) {
        return barrier.await(fence, gen, PresentationBarrier.COVER_DEADLINE_MS,
                outcome -> settled.add(fence + "=" + outcome));
    }

    @Test public void committedFrameOfTheAwaitedSurfaceSubmits() {
        PresentationBarrier.Ticket ticket = await("t1", 7L);
        assertNull(ticket.outcome());
        barrier.onSignal("t1", 7L);
        assertEquals(List.of("t1=SUBMITTED"), settled);
        assertEquals(Outcome.SUBMITTED, ticket.outcome());
        assertEquals("the deadline is unscheduled", 0, time.scheduledPending());
    }

    @Test public void aThrowingEffectNeverSkipsTheOtherTicketsOfItsBatch() {
        barrier.await("t1", 7L, PresentationBarrier.COVER_DEADLINE_MS, outcome -> {
            throw new IllegalStateException("effect failed");
        });
        await("t1", 7L);
        barrier.onSignal("t1", 7L);
        assertEquals(List.of("t1=SUBMITTED"), settled);
    }

    @Test public void detachOrNewSurfaceSettlesUnavailable() {
        await("t1", 7L);
        barrier.surfaceChanged(8L);
        // Its frame arriving afterwards changes nothing.
        barrier.onSignal("t1", 7L);
        time.advance(10_000L);
        assertEquals(List.of("t1=UNAVAILABLE"), settled);
    }

    /**
     * A wait before a new presentation leaves its commit callback behind: firing on the same
     * commit as the newer wait's, it never settles the newer wait of the same fence.
     */
    @Test public void staleGenerationSignalLeavesTheNewerWaitPending() {
        await("t1", 7L);
        barrier.surfaceChanged(8L);
        PresentationBarrier.Ticket newer = await("t1", 8L);
        barrier.onSignal("t1", 7L);
        assertNull(newer.outcome());
        barrier.onSignal("t1", 8L);
        assertEquals(List.of("t1=UNAVAILABLE", "t1=SUBMITTED"), settled);
    }

    @Test public void noSurfaceSettlesUnavailableAtOnce() {
        PresentationBarrier.Ticket ticket = await("t1", 0L);
        assertEquals(List.of("t1=UNAVAILABLE"), settled);
        assertEquals(Outcome.UNAVAILABLE, ticket.outcome());
        assertEquals(0, time.scheduledPending());
    }

    @Test public void cancelSettlesCancelledAndALateSignalIsIgnored() {
        await("t1", 7L);
        barrier.cancel("t1");
        barrier.onSignal("t1", 7L);
        barrier.cancel("t1");
        time.advance(10_000L);
        assertEquals(List.of("t1=CANCELLED"), settled);
    }

    @Test public void deadlineSettlesUnavailableExactlyOnce() {
        await("t1", 7L);
        time.advance(PresentationBarrier.COVER_DEADLINE_MS - 1L);
        assertTrue(settled.isEmpty());
        time.advance(1L);
        barrier.onSignal("t1", 7L);
        barrier.cancel("t1");
        barrier.surfaceChanged(9L);
        time.advance(10_000L);
        assertEquals(List.of("t1=UNAVAILABLE"), settled);
    }

    @Test public void waitOfAnotherFenceReplacesAStaleOne() {
        await("t1", 7L);
        await("t2", 7L);
        assertEquals(List.of("t1=CANCELLED"), settled);
        // The stale fence's frame does not settle the new wait.
        barrier.onSignal("t1", 7L);
        assertEquals(1, settled.size());
        barrier.onSignal("t2", 7L);
        assertEquals(List.of("t1=CANCELLED", "t2=SUBMITTED"), settled);
    }

    @Test public void repeatedAwaitsAndCancelsOfOneFenceEachSettleOnce() {
        await("t1", 7L);
        await("t1", 7L);
        barrier.cancel("t1");
        barrier.cancel("t1");
        assertEquals(List.of("t1=CANCELLED", "t1=CANCELLED"), settled);
        await("t1", 7L);
        barrier.onSignal("t1", 7L);
        time.advance(10_000L);
        assertEquals(List.of("t1=CANCELLED", "t1=CANCELLED", "t1=SUBMITTED"), settled);
    }

    @Test public void anEffectMayWaitAgainFromItsOutcome() {
        barrier.await("t1", 7L, PresentationBarrier.COVER_DEADLINE_MS,
                first -> barrier.await("t1", 7L, PresentationBarrier.COVER_DEADLINE_MS,
                        second -> settled.add("second=" + second)));
        barrier.onSignal("t1", 7L);
        time.advance(PresentationBarrier.COVER_DEADLINE_MS);
        assertEquals(List.of("second=UNAVAILABLE"), settled);
    }
}
