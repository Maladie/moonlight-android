package com.limelight.stream;

import org.junit.After;
import org.junit.Test;

import java.util.concurrent.atomic.AtomicReference;

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
        boolean live = true;
        RetainedStreamSessionCoordinator.TerminationCallback terminationCompletion;
        RetainedStreamSessionCoordinator.SwitchCallback switchCompletion;
        int switches;

        @Override public boolean isRetainedTransportLive() { return live; }
        @Override public boolean parkRetainedTransport() { return parkResult; }
        @Override public void terminateRetainedSession(
                RetainedStreamSessionCoordinator.TerminationCallback completion) {
            terminated = true;
            terminationCompletion = completion;
            if (completeTermination && completion != null) {
                completion.complete(terminationResult);
            }
        }
        @Override public void switchGame(
                RetainedStreamSessionCoordinator.SwitchRequest request,
                RetainedStreamSessionCoordinator.SwitchCallback completion) {
            switches++;
            switchCompletion = completion;
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

    @Test public void deadOwnerCannotResumeSwitchOrTerminate() {
        FakeController controller = new FakeController();
        controller.live = false;
        RetainedStreamSessionCoordinator.enterHome(
                controller, SESSION_A, "host", 7, "game");

        assertFalse(RetainedStreamSessionCoordinator.canResumeInstantly());
        assertEquals(RetainedStreamSessionCoordinator.State.RECONNECT_REQUIRED,
                RetainedStreamSessionCoordinator.state());
        assertFalse(RetainedStreamSessionCoordinator.canSwitchGame("host", 7));
        assertEquals(RetainedStreamSessionCoordinator.TerminationResult.NO_CONTROLLER,
                RetainedStreamSessionCoordinator.terminate(SESSION_A, null));
        assertFalse(controller.terminated);
    }

    @Test public void deadRetainedReplacementRestoresReconnectAfterFailedClose() {
        FakeController controller = new FakeController();
        RetainedStreamSessionCoordinator.enterHome(
                controller, SESSION_A, "host", 7, "game");
        RetainedStreamSessionCoordinator.markReconnectRequired(SESSION_A);

        assertTrue(RetainedStreamSessionCoordinator.markTerminating(
                SESSION_A, "host", 7, "game"));
        assertFalse(RetainedStreamSessionCoordinator.restoreReconnectIfTerminating(
                SESSION_A, "other-host", 7, "game"));
        assertEquals(RetainedStreamSessionCoordinator.State.TERMINATING,
                RetainedStreamSessionCoordinator.state());
        assertTrue(RetainedStreamSessionCoordinator.restoreReconnectIfTerminating(
                SESSION_A, "host", 7, "game"));

        RetainedStreamSessionCoordinator.Snapshot retained =
                RetainedStreamSessionCoordinator.snapshot();
        assertEquals(RetainedStreamSessionCoordinator.State.RECONNECT_REQUIRED,
                retained.state);
        assertEquals(SESSION_A, retained.streamSessionId);
        assertEquals("host", retained.hostId);
        assertEquals(7, retained.appId);
        assertEquals("game", retained.playniteGameId);
    }

    @Test public void failedTerminationRestoresNeutralizedLiveOwner() {
        FakeController controller = new FakeController();
        controller.completeTermination = false;
        RetainedStreamSessionCoordinator.enterHome(
                controller, SESSION_A, "host", 7, "game");
        assertEquals(RetainedStreamSessionCoordinator.TerminationResult.STARTED,
                RetainedStreamSessionCoordinator.terminate(SESSION_A, null));
        assertTrue(RetainedStreamSessionCoordinator.clearTerminatingGameIfMatches(
                SESSION_A, "host", 7, "game"));

        controller.terminationCompletion.complete(false);

        RetainedStreamSessionCoordinator.Snapshot retained =
                RetainedStreamSessionCoordinator.snapshot();
        assertEquals(RetainedStreamSessionCoordinator.State.HOME_LIVE, retained.state);
        assertEquals("", retained.playniteGameId);
        assertTrue(RetainedStreamSessionCoordinator.canResumeInstantly());
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

    @Test public void directStreamQuitIsVisibleUntilMatchingQuitCompletes() {
        assertTrue(RetainedStreamSessionCoordinator.markTerminating(
                SESSION_A, "host", 7, "game"));

        RetainedStreamSessionCoordinator.Snapshot snapshot =
                RetainedStreamSessionCoordinator.snapshot();
        assertEquals(RetainedStreamSessionCoordinator.State.TERMINATING, snapshot.state);
        assertEquals("host", snapshot.hostId);
        assertEquals(7, snapshot.appId);

        assertFalse(RetainedStreamSessionCoordinator.clearIfMatches("other-session"));
        assertEquals(RetainedStreamSessionCoordinator.State.TERMINATING,
                RetainedStreamSessionCoordinator.state());
        assertTrue(RetainedStreamSessionCoordinator.clearIfMatches(SESSION_A));
        assertEquals(RetainedStreamSessionCoordinator.State.NONE,
                RetainedStreamSessionCoordinator.state());
    }

    @Test public void directStreamQuitCannotOverwriteAnotherSession() {
        RetainedStreamSessionCoordinator.enterHome(
                new FakeController(), "session-b", "other-host", 8, "other");

        assertFalse(RetainedStreamSessionCoordinator.markTerminating(
                SESSION_A, "host", 7, "game"));
        assertEquals("session-b",
                RetainedStreamSessionCoordinator.snapshot().streamSessionId);
        assertEquals(RetainedStreamSessionCoordinator.State.HOME_LIVE,
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

    @Test public void onlyOneCorrelatedSwitchCanBeInFlight() {
        FakeController controller = new FakeController();
        RetainedStreamSessionCoordinator.enterHome(
                controller, SESSION_A, "host", 7, "old");

        assertEquals(RetainedStreamSessionCoordinator.SwitchResult.STARTED,
                RetainedStreamSessionCoordinator.switchGame(
                        "host", 7, "new", "New", "MoonWaker Stream", "",
                        () -> false, null));
        assertEquals(RetainedStreamSessionCoordinator.SwitchResult.NOT_ELIGIBLE,
                RetainedStreamSessionCoordinator.switchGame(
                        "host", 7, "other", "Other", "MoonWaker Stream", "",
                        () -> false, null));
        assertEquals(1, controller.switches);

        controller.switchCompletion.complete(
                RetainedStreamSessionCoordinator.SwitchOutcome.REUSED, "");
        assertFalse(RetainedStreamSessionCoordinator.canSwitchGame("host", 7));
        assertEquals(RetainedStreamSessionCoordinator.SwitchResult.NOT_ELIGIBLE,
                RetainedStreamSessionCoordinator.switchGame(
                        "host", 7, "other", "Other", "MoonWaker Stream", "",
                        () -> false, null));
        assertEquals(1, controller.switches);

        assertTrue(RetainedStreamSessionCoordinator.finishSwitch(SESSION_A, controller));
        assertTrue(RetainedStreamSessionCoordinator.canSwitchGame("host", 7));
    }

    @Test public void staleSwitchCannotRewriteNewerSessionIdentity() {
        FakeController controller = new FakeController();
        RetainedStreamSessionCoordinator.enterHome(
                controller, SESSION_A, "host", 7, "old");
        assertTrue(RetainedStreamSessionCoordinator.updateGameIfMatches(
                SESSION_A, "host", 7, "old", "new"));

        RetainedStreamSessionCoordinator.enterHome(
                new FakeController(), "session-b", "host", 7, "other");

        assertFalse(RetainedStreamSessionCoordinator.updateGameIfMatches(
                SESSION_A, "host", 7, "new", "stale"));
        assertEquals("other", RetainedStreamSessionCoordinator.snapshot().playniteGameId);
    }

    @Test public void parkedDifferentHostAndDifferentAppCannotReuseTransport() {
        FakeController controller = new FakeController();
        RetainedStreamSessionCoordinator.enterHome(
                controller, SESSION_A, "host", 7, "old");
        assertFalse(RetainedStreamSessionCoordinator.canSwitchGame("other", 7));
        assertFalse(RetainedStreamSessionCoordinator.canSwitchGame("host", 8));

        RetainedStreamSessionCoordinator.markParked(SESSION_A);

        assertFalse(RetainedStreamSessionCoordinator.canSwitchGame("host", 7));
    }

    @Test public void terminalCallbackReleasesSwitchButNotBeforeItArrives() {
        FakeController controller = new FakeController();
        RetainedStreamSessionCoordinator.enterHome(
                controller, SESSION_A, "host", 7, "old");
        assertEquals(RetainedStreamSessionCoordinator.SwitchResult.STARTED,
                RetainedStreamSessionCoordinator.switchGame(
                        "host", 7, "new", "New", "MoonWaker Stream", "",
                        () -> false, null));

        assertEquals(RetainedStreamSessionCoordinator.SwitchResult.NOT_ELIGIBLE,
                RetainedStreamSessionCoordinator.switchGame(
                        "host", 7, "other", "Other", "MoonWaker Stream", "",
                        () -> false, null));

        controller.switchCompletion.complete(
                RetainedStreamSessionCoordinator.SwitchOutcome.FAILED, "cleanup_failed");
        assertTrue(RetainedStreamSessionCoordinator.canSwitchGame("host", 7));
    }

    @Test public void neutralLiveStreamCanSwitchToManagedGame() {
        FakeController controller = new FakeController();
        RetainedStreamSessionCoordinator.enterHome(
                controller, SESSION_A, "host", 7, "");

        assertTrue(RetainedStreamSessionCoordinator.canSwitchGame("host", 7));
        assertEquals(RetainedStreamSessionCoordinator.SwitchResult.STARTED,
                RetainedStreamSessionCoordinator.switchGame(
                        "host", 7, "new", "New", "MoonWaker Stream", "",
                        () -> false, null));
        assertEquals("", RetainedStreamSessionCoordinator.snapshot().playniteGameId);
        assertEquals(1, controller.switches);
    }

    @Test public void foregroundNaturalStopClaimsAnEmptyCoordinatorAsNeutralHome() {
        FakeController controller = new FakeController();

        assertTrue(RetainedStreamSessionCoordinator.retainNeutralAfterGameStopped(
                controller, SESSION_A, "host", 7, "old"));

        RetainedStreamSessionCoordinator.Snapshot retained =
                RetainedStreamSessionCoordinator.snapshot();
        assertEquals(RetainedStreamSessionCoordinator.State.HOME_LIVE, retained.state);
        assertEquals(SESSION_A, retained.streamSessionId);
        assertEquals("host", retained.hostId);
        assertEquals(7, retained.appId);
        assertEquals("", retained.playniteGameId);
        assertTrue(RetainedStreamSessionCoordinator.canSwitchGame("host", 7));
    }

    @Test public void duplicateNaturalStopCannotReplaceTheSettledNeutralSession() {
        FakeController controller = new FakeController();
        assertTrue(RetainedStreamSessionCoordinator.retainNeutralAfterGameStopped(
                controller, SESSION_A, "host", 7, "old"));

        assertFalse(RetainedStreamSessionCoordinator.retainNeutralAfterGameStopped(
                controller, SESSION_A, "host", 7, "old"));

        RetainedStreamSessionCoordinator.Snapshot retained =
                RetainedStreamSessionCoordinator.snapshot();
        assertEquals(RetainedStreamSessionCoordinator.State.HOME_LIVE, retained.state);
        assertEquals(SESSION_A, retained.streamSessionId);
        assertEquals("", retained.playniteGameId);
        assertTrue(RetainedStreamSessionCoordinator.canSwitchGame("host", 7));
    }

    @Test public void parkedNaturalStopClearsOnlyTheExactGameAndKeepsParking() {
        FakeController controller = new FakeController();
        RetainedStreamSessionCoordinator.enterHome(
                controller, SESSION_A, "host", 7, "old");
        RetainedStreamSessionCoordinator.markParked(SESSION_A);

        assertTrue(RetainedStreamSessionCoordinator.retainNeutralAfterGameStopped(
                controller, SESSION_A, "host", 7, "old"));

        RetainedStreamSessionCoordinator.Snapshot retained =
                RetainedStreamSessionCoordinator.snapshot();
        assertEquals(RetainedStreamSessionCoordinator.State.PARKED_LIVE, retained.state);
        assertEquals(SESSION_A, retained.streamSessionId);
        assertEquals(7, retained.appId);
        assertEquals("", retained.playniteGameId);
    }

    @Test public void naturalStopCannotTakeOverAForeignRetainedSession() {
        FakeController foreign = new FakeController();
        RetainedStreamSessionCoordinator.enterHome(
                foreign, "session-b", "other-host", 8, "other");

        assertFalse(RetainedStreamSessionCoordinator.retainNeutralAfterGameStopped(
                new FakeController(), SESSION_A, "host", 7, "old"));

        RetainedStreamSessionCoordinator.Snapshot retained =
                RetainedStreamSessionCoordinator.snapshot();
        assertEquals("session-b", retained.streamSessionId);
        assertEquals("other-host", retained.hostId);
        assertEquals(8, retained.appId);
        assertEquals("other", retained.playniteGameId);
    }

    @Test public void naturalStopCannotMutateReconnectOrTerminatingSession() {
        FakeController controller = new FakeController();
        RetainedStreamSessionCoordinator.enterHome(
                controller, SESSION_A, "host", 7, "old");
        RetainedStreamSessionCoordinator.markReconnectRequired(SESSION_A);

        assertFalse(RetainedStreamSessionCoordinator.retainNeutralAfterGameStopped(
                controller, SESSION_A, "host", 7, "old"));
        assertEquals(RetainedStreamSessionCoordinator.State.RECONNECT_REQUIRED,
                RetainedStreamSessionCoordinator.state());
        assertEquals("old", RetainedStreamSessionCoordinator.snapshot().playniteGameId);

        RetainedStreamSessionCoordinator.clear();
        assertTrue(RetainedStreamSessionCoordinator.markTerminating(
                SESSION_A, "host", 7, "old"));
        assertFalse(RetainedStreamSessionCoordinator.retainNeutralAfterGameStopped(
                controller, SESSION_A, "host", 7, "old"));
        assertEquals(RetainedStreamSessionCoordinator.State.TERMINATING,
                RetainedStreamSessionCoordinator.state());
        assertEquals("old", RetainedStreamSessionCoordinator.snapshot().playniteGameId);
    }

    @Test public void replacedSessionSurvivesStaleSwitchCompletion() {
        FakeController old = new FakeController();
        AtomicReference<RetainedStreamSessionCoordinator.SwitchOutcome> outcome =
                new AtomicReference<>();
        RetainedStreamSessionCoordinator.enterHome(
                old, SESSION_A, "host", 7, "old");
        assertEquals(RetainedStreamSessionCoordinator.SwitchResult.STARTED,
                RetainedStreamSessionCoordinator.switchGame(
                        "host", 7, "new", "New", "MoonWaker Stream", "",
                        () -> false, (result, error) -> outcome.set(result)));

        RetainedStreamSessionCoordinator.enterHome(
                new FakeController(), "session-b", "host", 8, "other");
        old.switchCompletion.complete(
                RetainedStreamSessionCoordinator.SwitchOutcome.FAILED, "stale");

        assertEquals(RetainedStreamSessionCoordinator.SwitchOutcome.FAILED, outcome.get());
        RetainedStreamSessionCoordinator.Snapshot retained =
                RetainedStreamSessionCoordinator.snapshot();
        assertEquals("session-b", retained.streamSessionId);
        assertEquals(8, retained.appId);
        assertEquals("other", retained.playniteGameId);
    }
}
