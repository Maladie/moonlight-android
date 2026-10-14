package com.limelight.console;

import com.limelight.session.SessionModel;
import com.limelight.session.SessionModelDriver;


import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ConsoleSessionSubscriberTest {
    private final SessionModelDriver m = new SessionModelDriver();
    private final List<Integer> renders = new ArrayList<>();
    private final ConsoleSessionSubscriber subscriber = new ConsoleSessionSubscriber(renders::add);
    private final SessionModel.Controller owner =
            new SessionModel.Controller() {
                @Override public boolean isRetainedTransportLive() { return true; }
                private final long fakeGeneration =
                        com.limelight.session.SessionModelDriver.nextTransportGeneration();
                @Override public long transportGeneration() { return fakeGeneration; }
                @Override public SessionModel.ParkOutcome parkRetainedTransport() { return SessionModel.ParkOutcome.PARKED; }
                @Override public void disconnectRetainedStream(
                        SessionModel.TerminationCallback completion) { }
                @Override public void terminateRetainedSession(
                        String gameId, SessionModel.TerminationCallback completion) { }
                @Override public void switchGame(
                        SessionModel.SwitchRequest request,
                        SessionModel.SwitchCallback completion) { }
            };

    private void deliverCurrent() {
        subscriber.onSnapshot(m.snapshot());
    }

    @Test public void nothingIsHeldBeforeTheFirstDelivery() {
        m.enterHome(owner, "session", "host", 7, "game-a");

        assertFalse(subscriber.holdsTarget("host", 7, "game-a"));
        assertFalse(subscriber.reconnectRequired("host"));
        assertEquals(0, subscriber.warmUpStatus("host", true));
    }

    @Test public void retainedTargetFollowsTheDeliveredGameNotTheHomeLaunch() {
        m.enterHome(owner, "session", "host", 7, "game-a");
        deliverCurrent();
        assertTrue(subscriber.holdsTarget("host", 0, "game-a"));

        // The Home was launched for game-a; the stream now runs game-b.
        m.enterHome(owner, "session", "host", 7, "game-b");
        deliverCurrent();

        assertTrue(subscriber.holdsTarget("HOST", 0, "game-b"));
        assertFalse(subscriber.holdsTarget("host", 0, "game-a"));
        assertTrue(subscriber.holdsTarget("host", 7, "other"));
        assertFalse(subscriber.holdsTarget("other-host", 7, "game-b"));
        assertEquals(2, renders.size());
    }

    @Test public void endedRetainedStreamHoldsNoTarget() {
        m.enterHome(owner, "session", "host", 7, "game-a");
        deliverCurrent();
        assertTrue(m.clearIfMatches("session"));
        deliverCurrent();

        assertFalse(subscriber.holdsTarget("host", 7, "game-a"));
        assertFalse(subscriber.holdsTarget("", 0, ""));
    }

    @Test public void sameSnapshotDeliveredAgainDoesNotRenderTwice() {
        SessionModel.Snapshot current =
                m.snapshot();
        subscriber.onSnapshot(current);
        subscriber.onSnapshot(current);

        assertEquals(1, renders.size());
        assertEquals(ConsoleUpdateChannels.HOST_SELECTION | ConsoleUpdateChannels.SELECTED_HOST
                | ConsoleUpdateChannels.SESSION, (int) renders.get(0));
    }
}
