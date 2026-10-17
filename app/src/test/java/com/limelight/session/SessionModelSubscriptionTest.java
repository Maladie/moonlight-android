package com.limelight.session;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/** Main-thread delivery of the published snapshot (ported from the K1 coordinator tests). */
public class SessionModelSubscriptionTest {
    private final SessionModelDriver driver = new SessionModelDriver();
    private final SessionModel model = driver.model;
    private final SessionModel.Controller owner = new SessionModel.Controller() {
        @Override public boolean isRetainedTransportLive() { return true; }
        private final long fakeGeneration =
                com.limelight.session.SessionModelDriver.nextTransportGeneration();
        @Override public long transportGeneration() { return fakeGeneration; }
        @Override public SessionModel.ParkOutcome parkRetainedTransport() { return SessionModel.ParkOutcome.PARKED; }
        @Override public void disconnectRetainedStream(
                SessionModel.TerminationCallback completion) { }
        @Override public void terminateRetainedSession(
                String gameId, SessionModel.TerminationCallback completion) { }
        @Override public void switchGame(SessionModel.SwitchRequest request,
                                         SessionModel.SwitchCallback completion) { }
    };

    private static final class Recorder implements SessionModel.Listener {
        final List<SessionModel.Snapshot> received = new ArrayList<>();
        @Override public void onSnapshot(SessionModel.Snapshot snapshot) {
            received.add(snapshot);
        }
    }

    /** Applies an event on the model thread only; nothing reaches the main thread yet. */
    private void apply(SessionEvent<?> event) {
        model.post(event);
        while (!driver.modelQueue.isEmpty()) driver.modelQueue.poll().run();
    }

    /** Runs main-thread work; events it posts are applied as the model thread would. */
    private void runMainThread() {
        while (!driver.mainQueue.isEmpty()) {
            driver.mainQueue.poll().run();
            while (!driver.modelQueue.isEmpty()) driver.modelQueue.poll().run();
        }
    }

    private void enterHome(String sessionId, String gameId) {
        apply(new SessionEvent.EnterHome(owner, sessionId, "host", "default", 7, gameId));
    }

    @Test public void subscribeReturnsCurrentSnapshotWithoutDelivery() {
        enterHome("session-a", "game-a");
        runMainThread();
        Recorder recorder = new Recorder();

        SessionModel.Snapshot current = model.subscribe(recorder);

        assertEquals(SessionModel.State.HOME_LIVE, current.state);
        assertEquals("session-a", current.streamSessionId);
        assertEquals("game-a", current.playniteGameId);
        runMainThread();
        assertTrue(recorder.received.isEmpty());
    }

    @Test public void eachChangeIsDeliveredExactlyOnceAndNoChangeIsNotDelivered() {
        Recorder recorder = new Recorder();
        model.subscribe(recorder);

        enterHome("session-a", "game-a");
        runMainThread();
        apply(new SessionEvent.Parked("other-session"));
        apply(new SessionEvent.Parked("session-a"));
        runMainThread();
        apply(new SessionEvent.Parked("session-a"));
        runMainThread();

        assertEquals(2, recorder.received.size());
        assertEquals(SessionModel.State.HOME_LIVE, recorder.received.get(0).state);
        assertEquals(SessionModel.State.PARKED_LIVE, recorder.received.get(1).state);
    }

    @Test public void fieldOnlyChangeIsDelivered() {
        enterHome("session-a", "game-a");
        runMainThread();
        Recorder recorder = new Recorder();
        model.subscribe(recorder);

        apply(new SessionEvent.ControlGenerationBound(owner, "session-a", 9L));
        runMainThread();

        assertEquals(1, recorder.received.size());
        assertEquals(9L, recorder.received.get(0).controlGeneration);
    }

    @Test public void multiStepMutationDeliversOnlyItsFinalSnapshot() {
        Recorder recorder = new Recorder();
        model.subscribe(recorder);
        apply(new SessionEvent.PreparingBegun(owner, "session-a", "host", "default", 7, ""));
        runMainThread();
        long operation = recorder.received.get(0).preparingOperation;

        apply(new SessionEvent.PreparingCompleted(owner, "session-a", "host", 7, operation));
        runMainThread();

        assertEquals(2, recorder.received.size());
        SessionModel.Snapshot completed = recorder.received.get(1);
        assertEquals(SessionModel.State.HOME_LIVE, completed.state);
        assertEquals(operation, completed.preparingOperation);
    }

    @Test public void nothingIsDeliveredAfterUnsubscribe() {
        Recorder recorder = new Recorder();
        model.subscribe(recorder);

        enterHome("session-a", "game-a");
        model.unsubscribe(recorder);
        runMainThread();
        apply(new SessionEvent.Cleared("session-a"));
        runMainThread();

        assertTrue(recorder.received.isEmpty());
        assertTrue(driver.mainQueue.isEmpty());
    }

    @Test public void pendingChangesCoalesceToTheLatestSnapshot() {
        Recorder recorder = new Recorder();
        model.subscribe(recorder);

        enterHome("session-a", "game-a");
        apply(new SessionEvent.Parked("session-a"));
        apply(new SessionEvent.Cleared("session-a"));
        assertEquals(1, driver.mainQueue.size());
        runMainThread();

        assertEquals(1, recorder.received.size());
        assertEquals(SessionModel.State.NONE, recorder.received.get(0).state);
    }

    @Test public void olderPendingSnapshotIsNotDeliveredAfterNewerSubscribeResult() {
        Recorder early = new Recorder();
        model.subscribe(early);
        enterHome("session-a", "game-a");

        Recorder late = new Recorder();
        SessionModel.Snapshot current = model.subscribe(late);
        runMainThread();

        assertEquals(SessionModel.State.HOME_LIVE, current.state);
        assertTrue(late.received.isEmpty());
        assertEquals(1, early.received.size());
        assertSame(current, early.received.get(0));
    }

    @Test public void deliveriesFollowPublicationOrder() {
        Recorder recorder = new Recorder();
        model.subscribe(recorder);
        model.subscribe(snapshot -> {
            if (snapshot.state == SessionModel.State.HOME_LIVE) {
                model.post(new SessionEvent.Parked("session-a"));
            }
        });

        enterHome("session-a", "game-a");
        runMainThread();

        assertEquals(2, recorder.received.size());
        assertEquals(SessionModel.State.HOME_LIVE, recorder.received.get(0).state);
        assertEquals(SessionModel.State.PARKED_LIVE, recorder.received.get(1).state);
    }

    @Test public void throwingSubscriberDoesNotStopDeliveryToOthers() {
        model.subscribe(snapshot -> { throw new IllegalStateException("render failed"); });
        Recorder recorder = new Recorder();
        model.subscribe(recorder);

        enterHome("session-a", "game-a");
        runMainThread();

        assertEquals(1, recorder.received.size());
        assertEquals("session-a", recorder.received.get(0).streamSessionId);
    }
}
