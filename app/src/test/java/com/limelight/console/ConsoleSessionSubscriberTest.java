package com.limelight.console;

import com.limelight.stream.RetainedStreamSessionCoordinator;

import org.junit.After;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ConsoleSessionSubscriberTest {
    private final List<Integer> renders = new ArrayList<>();
    private final ConsoleSessionSubscriber subscriber = new ConsoleSessionSubscriber(renders::add);
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

    @After public void tearDown() throws ReflectiveOperationException {
        java.lang.reflect.Method clear = RetainedStreamSessionCoordinator.class
                .getDeclaredMethod("clear");
        clear.setAccessible(true);
        clear.invoke(null);
    }

    private void deliverCurrent() {
        subscriber.onSnapshot(RetainedStreamSessionCoordinator.snapshot());
    }

    @Test public void nothingIsHeldBeforeTheFirstDelivery() {
        RetainedStreamSessionCoordinator.enterHome(owner, "session", "host", 7, "game-a");

        assertFalse(subscriber.holdsTarget("host", 7, "game-a"));
        assertFalse(subscriber.reconnectRequired("host"));
        assertEquals(0, subscriber.warmUpStatus("host", true));
    }

    @Test public void retainedTargetFollowsTheDeliveredGameNotTheHomeLaunch() {
        RetainedStreamSessionCoordinator.enterHome(owner, "session", "host", 7, "game-a");
        deliverCurrent();
        assertTrue(subscriber.holdsTarget("host", 0, "game-a"));

        // The Home was launched for game-a; the stream now runs game-b.
        assertTrue(RetainedStreamSessionCoordinator.updateGameIfMatches(
                "session", "host", 7, "game-a", "game-b"));
        deliverCurrent();

        assertTrue(subscriber.holdsTarget("HOST", 0, "game-b"));
        assertFalse(subscriber.holdsTarget("host", 0, "game-a"));
        assertTrue(subscriber.holdsTarget("host", 7, "other"));
        assertFalse(subscriber.holdsTarget("other-host", 7, "game-b"));
        assertEquals(2, renders.size());
    }

    @Test public void endedRetainedStreamHoldsNoTarget() {
        RetainedStreamSessionCoordinator.enterHome(owner, "session", "host", 7, "game-a");
        deliverCurrent();
        assertTrue(RetainedStreamSessionCoordinator.clearIfMatches("session"));
        deliverCurrent();

        assertFalse(subscriber.holdsTarget("host", 7, "game-a"));
        assertFalse(subscriber.holdsTarget("", 0, ""));
    }

    @Test public void sameSnapshotDeliveredAgainDoesNotRenderTwice() {
        RetainedStreamSessionCoordinator.Snapshot current =
                RetainedStreamSessionCoordinator.snapshot();
        subscriber.onSnapshot(current);
        subscriber.onSnapshot(current);

        assertEquals(1, renders.size());
        assertEquals(ConsoleUpdateChannels.HOST_SELECTION | ConsoleUpdateChannels.SELECTED_HOST
                | ConsoleUpdateChannels.SESSION, (int) renders.get(0));
    }
}
