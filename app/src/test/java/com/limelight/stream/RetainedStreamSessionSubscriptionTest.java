package com.limelight.stream;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class RetainedStreamSessionSubscriptionTest {
    private final List<Runnable> mainThread = new ArrayList<>();
    private final List<RetainedStreamSessionCoordinator.Listener> subscribed = new ArrayList<>();
    private final RetainedStreamSessionCoordinator.Controller owner =
            new RetainedStreamSessionCoordinator.Controller() {
                @Override public boolean isRetainedTransportLive() { return true; }
                @Override public boolean parkRetainedTransport() { return true; }
                @Override public void disconnectRetainedStream(
                        RetainedStreamSessionCoordinator.TerminationCallback completion) { }
                @Override public void terminateRetainedSession(
                        RetainedStreamSessionCoordinator.TerminationCallback completion) { }
                @Override public void switchGame(
                        RetainedStreamSessionCoordinator.SwitchRequest request,
                        RetainedStreamSessionCoordinator.SwitchCallback completion) { }
            };

    private static final class Recorder implements RetainedStreamSessionCoordinator.Listener {
        final List<RetainedStreamSessionCoordinator.Snapshot> received = new ArrayList<>();
        @Override public void onSnapshot(RetainedStreamSessionCoordinator.Snapshot snapshot) {
            received.add(snapshot);
        }
    }

    @Before public void setUp() {
        RetainedStreamSessionCoordinator.clear();
        RetainedStreamSessionCoordinator.setMainThreadForTests(mainThread::add);
    }

    @After public void tearDown() {
        for (RetainedStreamSessionCoordinator.Listener listener : subscribed) {
            RetainedStreamSessionCoordinator.unsubscribe(listener);
        }
        RetainedStreamSessionCoordinator.clear();
        RetainedStreamSessionCoordinator.setMainThreadForTests(null);
    }

    private RetainedStreamSessionCoordinator.Snapshot subscribe(
            RetainedStreamSessionCoordinator.Listener listener) {
        subscribed.add(listener);
        return RetainedStreamSessionCoordinator.subscribe(listener);
    }

    private void runMainThread() {
        while (!mainThread.isEmpty()) mainThread.remove(0).run();
    }

    private void enterHome(String sessionId, String gameId) {
        RetainedStreamSessionCoordinator.enterHome(
                owner, sessionId, "host", "default", 7, gameId);
    }

    @Test public void subscribeReturnsCurrentSnapshotWithoutDelivery() {
        enterHome("session-a", "game-a");
        Recorder recorder = new Recorder();

        RetainedStreamSessionCoordinator.Snapshot current = subscribe(recorder);

        assertEquals(RetainedStreamSessionCoordinator.State.HOME_LIVE, current.state);
        assertEquals("session-a", current.streamSessionId);
        assertEquals("game-a", current.playniteGameId);
        runMainThread();
        assertTrue(recorder.received.isEmpty());
    }

    @Test public void eachChangeIsDeliveredExactlyOnceAndNoChangeIsNotDelivered() {
        Recorder recorder = new Recorder();
        subscribe(recorder);

        enterHome("session-a", "game-a");
        runMainThread();
        RetainedStreamSessionCoordinator.markParked("other-session");
        RetainedStreamSessionCoordinator.markParked("session-a");
        runMainThread();
        RetainedStreamSessionCoordinator.markParked("session-a");
        runMainThread();

        assertEquals(2, recorder.received.size());
        assertEquals(RetainedStreamSessionCoordinator.State.HOME_LIVE,
                recorder.received.get(0).state);
        assertEquals(RetainedStreamSessionCoordinator.State.PARKED_LIVE,
                recorder.received.get(1).state);
    }

    @Test public void fieldOnlyChangeIsDelivered() {
        enterHome("session-a", "game-a");
        Recorder recorder = new Recorder();
        subscribe(recorder);

        assertTrue(RetainedStreamSessionCoordinator.updateGameIfMatches(
                "session-a", "host", 7, "game-a", "game-b"));
        runMainThread();

        assertEquals(1, recorder.received.size());
        assertEquals("game-b", recorder.received.get(0).playniteGameId);
    }

    @Test public void multiStepMutationDeliversOnlyItsFinalSnapshot() {
        Recorder recorder = new Recorder();
        subscribe(recorder);
        assertTrue(RetainedStreamSessionCoordinator.beginPreparing(owner, "session-a", "host",
                7, "", "transition-1", 1L));
        runMainThread();

        assertTrue(RetainedStreamSessionCoordinator.completePreparing(owner, "session-a", "host",
                7, "transition-1", 1L));
        runMainThread();

        assertEquals(2, recorder.received.size());
        RetainedStreamSessionCoordinator.Snapshot completed = recorder.received.get(1);
        assertEquals(RetainedStreamSessionCoordinator.State.HOME_LIVE, completed.state);
        assertEquals("", completed.transitionId);
        assertEquals(0L, completed.attempt);
    }

    @Test public void nothingIsDeliveredAfterUnsubscribe() {
        Recorder recorder = new Recorder();
        subscribe(recorder);

        enterHome("session-a", "game-a");
        RetainedStreamSessionCoordinator.unsubscribe(recorder);
        runMainThread();
        RetainedStreamSessionCoordinator.clearIfMatches("session-a");
        runMainThread();

        assertTrue(recorder.received.isEmpty());
        assertTrue(mainThread.isEmpty());
    }

    @Test public void pendingChangesCoalesceToTheLatestSnapshot() {
        Recorder recorder = new Recorder();
        subscribe(recorder);

        enterHome("session-a", "game-a");
        RetainedStreamSessionCoordinator.markParked("session-a");
        RetainedStreamSessionCoordinator.clearIfMatches("session-a");
        assertEquals(1, mainThread.size());
        runMainThread();

        assertEquals(1, recorder.received.size());
        assertEquals(RetainedStreamSessionCoordinator.State.NONE,
                recorder.received.get(0).state);
    }

    @Test public void olderPendingSnapshotIsNotDeliveredAfterNewerSubscribeResult() {
        Recorder early = new Recorder();
        subscribe(early);
        enterHome("session-a", "game-a");

        Recorder late = new Recorder();
        RetainedStreamSessionCoordinator.Snapshot current = subscribe(late);
        runMainThread();

        assertEquals(RetainedStreamSessionCoordinator.State.HOME_LIVE, current.state);
        assertTrue(late.received.isEmpty());
        assertEquals(1, early.received.size());
        assertSame(current, early.received.get(0));
    }

    @Test public void deliveriesFollowPublicationOrder() {
        Recorder recorder = new Recorder();
        subscribe(recorder);
        RetainedStreamSessionCoordinator.Listener publishDuringDelivery = snapshot -> {
            if (snapshot.state == RetainedStreamSessionCoordinator.State.HOME_LIVE) {
                RetainedStreamSessionCoordinator.markParked("session-a");
            }
        };
        subscribe(publishDuringDelivery);

        enterHome("session-a", "game-a");
        runMainThread();

        assertEquals(2, recorder.received.size());
        assertEquals(RetainedStreamSessionCoordinator.State.HOME_LIVE,
                recorder.received.get(0).state);
        assertEquals(RetainedStreamSessionCoordinator.State.PARKED_LIVE,
                recorder.received.get(1).state);
    }

    @Test public void throwingSubscriberDoesNotStopDeliveryToOthers() {
        subscribe(snapshot -> { throw new IllegalStateException("render failed"); });
        Recorder recorder = new Recorder();
        subscribe(recorder);

        enterHome("session-a", "game-a");
        runMainThread();

        assertEquals(1, recorder.received.size());
        assertEquals("session-a", recorder.received.get(0).streamSessionId);
    }
}
