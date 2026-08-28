package com.limelight.stream;

import org.junit.After;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class RetainedStreamSessionCoordinatorTest {
    private static final String SESSION_A = "session-a";

    private static class FakeController
            implements RetainedStreamSessionCoordinator.Controller {
        boolean parkResult = true;
        boolean terminated;
        boolean completeTermination = true;
        boolean terminationResult = true;
        RetainedStreamSessionCoordinator.TerminationCallback terminationCompletion;

        @Override public boolean parkRetainedTransport() { return parkResult; }
        @Override public void terminateRetainedSession(
                RetainedStreamSessionCoordinator.TerminationCallback completion) {
            terminated = true;
            terminationCompletion = completion;
            if (completeTermination && completion != null) {
                completion.complete(terminationResult);
            }
        }
    }

    @After public void reset() {
        RetainedStreamSessionCoordinator.clear();
    }

    @Test public void homeParksAndRemainsInstantlyResumable() {
        FakeController controller = new FakeController();
        RetainedStreamSessionCoordinator.enterHome(controller, SESSION_A, "host", 7, "game");

        assertTrue(RetainedStreamSessionCoordinator.parkForBackground(SESSION_A));
        assertEquals(RetainedStreamSessionCoordinator.State.PARKED_LIVE,
                RetainedStreamSessionCoordinator.state());
        assertTrue(RetainedStreamSessionCoordinator.canResumeInstantly());
    }

    @Test public void failedParkRequiresReconnect() {
        FakeController controller = new FakeController();
        controller.parkResult = false;
        RetainedStreamSessionCoordinator.enterHome(controller, SESSION_A, "host", 7, "game");

        assertFalse(RetainedStreamSessionCoordinator.parkForBackground(SESSION_A));
        assertEquals(RetainedStreamSessionCoordinator.State.RECONNECT_REQUIRED,
                RetainedStreamSessionCoordinator.state());
        assertFalse(RetainedStreamSessionCoordinator.canResumeInstantly());
    }

    @Test public void terminateInvokesOwnerAndRemovesResumeState() {
        FakeController controller = new FakeController();
        RetainedStreamSessionCoordinator.enterHome(controller, SESSION_A, "host", 7, "game");

        assertEquals(RetainedStreamSessionCoordinator.TerminationResult.STARTED,
                RetainedStreamSessionCoordinator.terminate(SESSION_A, null));
        assertTrue(controller.terminated);
        assertFalse(RetainedStreamSessionCoordinator.hasRetainedSession());
        assertFalse(RetainedStreamSessionCoordinator.canResumeInstantly());
    }

    @Test public void lostOwnerFallsBackToReconnect() {
        RetainedStreamSessionCoordinator.enterHome(
                new FakeController(), SESSION_A, "host", 7, "game");
        RetainedStreamSessionCoordinator.markReconnectRequired(SESSION_A);

        assertEquals(RetainedStreamSessionCoordinator.State.RECONNECT_REQUIRED,
                RetainedStreamSessionCoordinator.state());
        assertFalse(RetainedStreamSessionCoordinator.canResumeInstantly());
    }

    @Test public void snapshotKeepsRetainedGameIdentity() {
        RetainedStreamSessionCoordinator.enterHome(
                new FakeController(), SESSION_A, "host", 7, "game");
        RetainedStreamSessionCoordinator.Snapshot snapshot =
                RetainedStreamSessionCoordinator.snapshot();
        assertEquals(SESSION_A, snapshot.streamSessionId);
        assertEquals("host", snapshot.hostId);
        assertEquals(7, snapshot.appId);
        assertEquals("game", snapshot.playniteGameId);
    }

    @Test public void repeatedTerminationDoesNotStartASecondRequest() {
        FakeController controller = new FakeController();
        controller.completeTermination = false;
        RetainedStreamSessionCoordinator.enterHome(controller, SESSION_A, "host", 7, "game");

        assertEquals(RetainedStreamSessionCoordinator.TerminationResult.STARTED,
                RetainedStreamSessionCoordinator.terminate(SESSION_A, null));
        assertEquals(RetainedStreamSessionCoordinator.TerminationResult.IN_PROGRESS,
                RetainedStreamSessionCoordinator.terminate(SESSION_A, null));
        assertEquals(RetainedStreamSessionCoordinator.State.TERMINATING,
                RetainedStreamSessionCoordinator.state());

        controller.terminationCompletion.complete(true);
        assertEquals(RetainedStreamSessionCoordinator.State.NONE,
                RetainedStreamSessionCoordinator.state());
    }

    @Test public void staleParkResultCannotOverwriteNewSession() {
        FakeController old = new FakeController() {
            @Override public boolean parkRetainedTransport() {
                RetainedStreamSessionCoordinator.enterHome(
                        new FakeController(), "session-b", "host", 8, "other");
                return true;
            }
        };
        RetainedStreamSessionCoordinator.enterHome(old, SESSION_A, "host", 7, "game");

        assertFalse(RetainedStreamSessionCoordinator.parkForBackground(SESSION_A));
        RetainedStreamSessionCoordinator.Snapshot snapshot =
                RetainedStreamSessionCoordinator.snapshot();
        assertEquals("session-b", snapshot.streamSessionId);
        assertEquals(RetainedStreamSessionCoordinator.State.HOME_LIVE, snapshot.state);
    }

    @Test public void staleTerminationCompletionCannotClearNewSession() {
        FakeController old = new FakeController();
        old.completeTermination = false;
        RetainedStreamSessionCoordinator.enterHome(old, SESSION_A, "host", 7, "game");
        assertEquals(RetainedStreamSessionCoordinator.TerminationResult.STARTED,
                RetainedStreamSessionCoordinator.terminate(SESSION_A, null));
        RetainedStreamSessionCoordinator.enterHome(
                new FakeController(), "session-b", "host", 8, "other");

        old.terminationCompletion.complete(true);

        assertEquals("session-b", RetainedStreamSessionCoordinator.snapshot().streamSessionId);
        assertEquals(RetainedStreamSessionCoordinator.State.HOME_LIVE,
                RetainedStreamSessionCoordinator.state());
    }

    @Test public void terminationWithoutControllerDoesNotRemainWedged() {
        RetainedStreamSessionCoordinator.enterHome(
                new FakeController(), SESSION_A, "host", 7, "game");
        RetainedStreamSessionCoordinator.markReconnectRequired(SESSION_A);

        assertEquals(RetainedStreamSessionCoordinator.TerminationResult.NO_CONTROLLER,
                RetainedStreamSessionCoordinator.terminate(SESSION_A, null));
        assertEquals(RetainedStreamSessionCoordinator.State.RECONNECT_REQUIRED,
                RetainedStreamSessionCoordinator.state());
        assertEquals(RetainedStreamSessionCoordinator.TerminationResult.NO_CONTROLLER,
                RetainedStreamSessionCoordinator.terminate(SESSION_A, null));
    }

    @Test public void rejectedTerminationRestoresTheLiveOwner() {
        FakeController controller = new FakeController();
        controller.terminationResult = false;
        RetainedStreamSessionCoordinator.enterHome(controller, SESSION_A, "host", 7, "game");

        assertEquals(RetainedStreamSessionCoordinator.TerminationResult.STARTED,
                RetainedStreamSessionCoordinator.terminate(SESSION_A, null));

        assertEquals(RetainedStreamSessionCoordinator.State.HOME_LIVE,
                RetainedStreamSessionCoordinator.state());
        assertTrue(RetainedStreamSessionCoordinator.canResumeInstantly());
        assertTrue(RetainedStreamSessionCoordinator.hasRetainedSession());
    }
}
