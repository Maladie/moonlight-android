package com.limelight.stream;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class TransportStopCoordinatorTest {
    @Test public void concurrentCallersJoinOneStopAfterItReturns() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch completed = new CountDownLatch(2);
        AtomicInteger stops = new AtomicInteger();
        List<TransportStopCoordinator.Result> results =
                Collections.synchronizedList(new ArrayList<>());
        TransportStopCoordinator owner = owner(() -> {
            stops.incrementAndGet();
            entered.countDown();
            await(release);
        });
        activate(owner);

        owner.stop(result -> { results.add(result); completed.countDown(); });
        assertTrue(entered.await(2, TimeUnit.SECONDS));
        owner.stop(result -> { results.add(result); completed.countDown(); });
        assertFalse(completed.await(100, TimeUnit.MILLISECONDS));

        release.countDown();
        assertTrue(completed.await(2, TimeUnit.SECONDS));
        assertEquals(1, stops.get());
        assertEquals(2, results.size());
        assertEquals(TransportStopCoordinator.Result.STOPPED, results.get(0));
        assertEquals(results.get(0), results.get(1));
    }

    @Test public void failureIsSharedWithoutSuccessfulCompletion() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch completed = new CountDownLatch(2);
        List<TransportStopCoordinator.Result> results =
                Collections.synchronizedList(new ArrayList<>());
        TransportStopCoordinator owner = owner(() -> {
            entered.countDown();
            await(release);
            throw new IllegalStateException("stop failed");
        });
        activate(owner);

        owner.stop(result -> { results.add(result); completed.countDown(); });
        assertTrue(entered.await(2, TimeUnit.SECONDS));
        owner.stop(result -> { results.add(result); completed.countDown(); });
        release.countDown();

        assertTrue(completed.await(2, TimeUnit.SECONDS));
        assertEquals(2, results.size());
        assertEquals(TransportStopCoordinator.Result.FAILED, results.get(0));
        assertEquals(results.get(0), results.get(1));
    }

    @Test public void nextStartDoesNotRunBeforePreviousStopReturns() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch started = new CountDownLatch(1);
        AtomicInteger nextStarts = new AtomicInteger();
        AtomicInteger teardowns = new AtomicInteger();
        TransportStopCoordinator old = owner(() -> {
            entered.countDown();
            await(release);
        });
        activate(old);

        TransportStopCoordinator next = owner(old, () -> { });
        next.start(Runnable::run, () -> {
            nextStarts.incrementAndGet();
            started.countDown();
        });
        assertFalse(started.await(100, TimeUnit.MILLISECONDS));
        assertEquals(0, nextStarts.get());
        assertEquals(1L, entered.getCount());

        old.stop(teardowns::incrementAndGet, null);
        assertTrue(entered.await(2, TimeUnit.SECONDS));
        assertEquals(1, teardowns.get());
        assertEquals(0, nextStarts.get());

        release.countDown();
        assertTrue(started.await(2, TimeUnit.SECONDS));
        assertEquals(1, nextStarts.get());
        CountDownLatch cleaned = new CountDownLatch(1);
        next.stop(result -> cleaned.countDown());
        assertTrue(cleaned.await(2, TimeUnit.SECONDS));
    }

    @Test public void unstartedMiddleGenerationCannotBypassActivePredecessor()
            throws Exception {
        CountDownLatch stopEntered = new CountDownLatch(1);
        CountDownLatch releaseStop = new CountDownLatch(1);
        CountDownLatch nextStarted = new CountDownLatch(1);
        AtomicInteger middleStops = new AtomicInteger();
        AtomicInteger firstTeardowns = new AtomicInteger();
        TransportStopCoordinator first = owner(() -> {
            stopEntered.countDown();
            await(releaseStop);
        });
        activate(first);
        TransportStopCoordinator middle = owner(first, middleStops::incrementAndGet);
        TransportStopCoordinator next = owner(middle, () -> { });

        next.start(Runnable::run, nextStarted::countDown);
        assertFalse(nextStarted.await(100, TimeUnit.MILLISECONDS));
        assertEquals(1L, stopEntered.getCount());
        assertEquals(0, middleStops.get());

        first.stop(firstTeardowns::incrementAndGet, null);
        assertTrue(stopEntered.await(2, TimeUnit.SECONDS));
        assertEquals(1, firstTeardowns.get());
        assertFalse(nextStarted.await(100, TimeUnit.MILLISECONDS));
        releaseStop.countDown();

        assertTrue(nextStarted.await(2, TimeUnit.SECONDS));
        assertEquals(0, middleStops.get());
        CountDownLatch cleaned = new CountDownLatch(1);
        next.stop(result -> cleaned.countDown());
        assertTrue(cleaned.await(2, TimeUnit.SECONDS));
    }

    @Test public void teardownFailureStillRunsStopAndReportsFailure() throws Exception {
        AtomicInteger stops = new AtomicInteger();
        CountDownLatch completed = new CountDownLatch(1);
        AtomicReference<TransportStopCoordinator.Result> result = new AtomicReference<>();
        TransportStopCoordinator owner = owner(stops::incrementAndGet);
        activate(owner);

        owner.stop(() -> { throw new IllegalStateException("teardown failed"); }, value -> {
            result.set(value);
            completed.countDown();
        });

        assertTrue(completed.await(2, TimeUnit.SECONDS));
        assertEquals(1, stops.get());
        assertEquals(TransportStopCoordinator.Result.FAILED, result.get());
    }

    @Test public void completionFailureIsContained() {
        AtomicInteger continued = new AtomicInteger();
        TransportStopCoordinator.completeSafely(result -> {
            throw new IllegalStateException("observer failed");
        }, TransportStopCoordinator.Result.STOPPED);
        continued.incrementAndGet();
        assertEquals(1, continued.get());
    }

    private static TransportStopCoordinator owner(Runnable effect) {
        return owner(null, effect);
    }

    private static TransportStopCoordinator owner(TransportStopCoordinator predecessor,
                                                  Runnable effect) {
        return new TransportStopCoordinator(predecessor, effect,
                action -> new Thread(action, "transport-stop-test").start());
    }

    private static void activate(TransportStopCoordinator owner) {
        CountDownLatch started = new CountDownLatch(1);
        owner.start(Runnable::run, started::countDown);
        await(started);
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(2, TimeUnit.SECONDS)) {
                throw new AssertionError("latch timed out");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError(interrupted);
        }
    }
}
