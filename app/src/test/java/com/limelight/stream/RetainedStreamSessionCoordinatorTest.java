package com.limelight.stream;

import org.junit.After;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class RetainedStreamSessionCoordinatorTest {
    private static final class FakeController
            implements RetainedStreamSessionCoordinator.Controller {
        boolean parkResult = true;
        boolean terminated;
        boolean completeTermination = true;
        Runnable terminationCompletion;

        @Override public boolean parkRetainedTransport() { return parkResult; }
        @Override public void terminateRetainedSession(Runnable completion) {
            terminated = true;
            terminationCompletion = completion;
            if (completeTermination && completion != null) completion.run();
        }
    }

    @After
    public void reset() {
        RetainedStreamSessionCoordinator.clear();
    }

    @Test
    public void homeParksAndRemainsInstantlyResumable() {
        FakeController controller = new FakeController();
        RetainedStreamSessionCoordinator.enterHome(controller, "host", 7, "game");

        assertTrue(RetainedStreamSessionCoordinator.parkForBackground());
        assertEquals(RetainedStreamSessionCoordinator.State.PARKED_LIVE,
                RetainedStreamSessionCoordinator.state());
        assertTrue(RetainedStreamSessionCoordinator.canResumeInstantly());
    }

    @Test
    public void failedParkRequiresReconnect() {
        FakeController controller = new FakeController();
        controller.parkResult = false;
        RetainedStreamSessionCoordinator.enterHome(controller, "host", 7, "game");

        assertFalse(RetainedStreamSessionCoordinator.parkForBackground());
        assertEquals(RetainedStreamSessionCoordinator.State.RECONNECT_REQUIRED,
                RetainedStreamSessionCoordinator.state());
        assertFalse(RetainedStreamSessionCoordinator.canResumeInstantly());
    }

    @Test
    public void terminateInvokesOwnerAndRemovesResumeState() {
        FakeController controller = new FakeController();
        RetainedStreamSessionCoordinator.enterHome(controller, "host", 7, "game");

        assertEquals(RetainedStreamSessionCoordinator.TerminationResult.STARTED,
                RetainedStreamSessionCoordinator.terminate(null));
        assertTrue(controller.terminated);
        assertFalse(RetainedStreamSessionCoordinator.hasRetainedSession());
        assertFalse(RetainedStreamSessionCoordinator.canResumeInstantly());
    }

    @Test
    public void lostOwnerFallsBackToReconnect() {
        RetainedStreamSessionCoordinator.enterHome(new FakeController(), "host", 7, "game");
        RetainedStreamSessionCoordinator.markReconnectRequired();

        assertEquals(RetainedStreamSessionCoordinator.State.RECONNECT_REQUIRED,
                RetainedStreamSessionCoordinator.state());
        assertFalse(RetainedStreamSessionCoordinator.canResumeInstantly());
    }

    @Test
    public void snapshotKeepsRetainedGameIdentity() {
        RetainedStreamSessionCoordinator.enterHome(new FakeController(), "host", 7, "game");
        RetainedStreamSessionCoordinator.Snapshot snapshot =
                RetainedStreamSessionCoordinator.snapshot();
        assertEquals("host", snapshot.hostId);
        assertEquals(7, snapshot.appId);
        assertEquals("game", snapshot.playniteGameId);
    }

    @Test
    public void repeatedTerminationDoesNotStartASecondRequest() {
        FakeController controller = new FakeController();
        controller.completeTermination = false;
        RetainedStreamSessionCoordinator.enterHome(controller, "host", 7, "game");

        assertEquals(RetainedStreamSessionCoordinator.TerminationResult.STARTED,
                RetainedStreamSessionCoordinator.terminate(null));
        assertEquals(RetainedStreamSessionCoordinator.TerminationResult.IN_PROGRESS,
                RetainedStreamSessionCoordinator.terminate(null));
        assertEquals(RetainedStreamSessionCoordinator.State.TERMINATING,
                RetainedStreamSessionCoordinator.state());

        controller.terminationCompletion.run();
        assertEquals(RetainedStreamSessionCoordinator.State.NONE,
                RetainedStreamSessionCoordinator.state());
    }
}
