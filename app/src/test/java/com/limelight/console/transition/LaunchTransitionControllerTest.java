package com.limelight.console.transition;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class LaunchTransitionControllerTest {
    private static final String HOST = "host-1";
    private static final String GAME =
            "11111111-2222-3333-4444-555555555555";
    private static final String SECOND_GAME =
            "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee";

    private static LaunchTransitionSpec spec(LaunchTransitionType type) {
        return new LaunchTransitionSpec("transition-1", HOST, type, 42,
                type == LaunchTransitionType.GAME
                        || type == LaunchTransitionType.GAME_CONNECTION ? GAME : "", 1000L);
    }

    private static LaunchTransitionController started(LaunchTransitionType type) {
        LaunchTransitionController controller = new LaunchTransitionController(null);
        controller.begin(spec(type));
        controller.coverSettled("transition-1", PresentationBarrier.Outcome.SUBMITTED);
        return controller;
    }

    private static void transportReady(LaunchTransitionController controller) {
        controller.surfaceReady("transition-1");
        controller.inputPipelineReady("transition-1");
        controller.streamConnected("transition-1");
        controller.videoFrameRendered("transition-1", controller.snapshot().frameEdge, System.nanoTime());
    }

    /**
     * The TV dead end: a neutral stream settling back to the dashboard got a failure (an
     * offered authorization retry). The failure must not stay the passive closing screen.
     */
    @Test
    public void failureOverANeutralStreamReturningToTheDashboardOffersItsWayOut() {
        LaunchTransitionController controller = new LaunchTransitionController(null);
        controller.begin(new LaunchTransitionSpec("neutral", HOST,
                LaunchTransitionType.GAME_CONNECTION, 42, "", 1000L));
        controller.coverSettled("neutral", PresentationBarrier.Outcome.SUBMITTED);
        controller.surfaceReady("neutral");
        controller.inputPipelineReady("neutral");
        controller.streamConnected("neutral");
        controller.returningToDashboard("neutral");
        assertTrue(controller.snapshot().closingPresentation());

        controller.error("neutral", "authorization pending");
        LaunchTransitionSnapshot failed = controller.snapshot();
        assertEquals(LaunchTransitionState.ERROR, failed.state);
        assertTrue("the cover keeps the input for its own actions", failed.inputBlocked);
        assertFalse("a failure always shows its actions", failed.closingPresentation());

        controller.videoFrameRendered("neutral", controller.snapshot().frameEdge, System.nanoTime());
        assertTrue(controller.snapshot().manualRevealAvailable);
        assertFalse(controller.snapshot().closingPresentation());
        controller.showStreamAnyway("neutral");
        controller.revealCompleted("neutral");
        assertFalse(controller.snapshot().inputBlocked);
        assertFalse(controller.snapshot().overlayVisible);
    }

    /**
     * Hidden input isolation: while the cover holds the input, or for a hidden warm-up behind
     * the Home, no controller, keyboard, mouse or remote input reaches the host.
     */
    @Test
    public void inputReachesTheHostOnlyOnceTheCoverReleasesIt() {
        LaunchTransitionController controller = started(LaunchTransitionType.GAME);
        assertFalse(LaunchTransitionController.inputReachesHost(controller.snapshot(), false));
        transportReady(controller);
        controller.showStreamAnyway("transition-1");
        assertFalse("not before the reveal completed",
                LaunchTransitionController.inputReachesHost(controller.snapshot(), false));
        controller.revealCompleted("transition-1");
        assertTrue(LaunchTransitionController.inputReachesHost(controller.snapshot(), false));
        assertFalse("a hidden warm-up stays private",
                LaunchTransitionController.inputReachesHost(controller.snapshot(), true));
        // A user cover (the Home return) takes the input back.
        controller.coverForUser("transition-1", LaunchTransitionController.UserCover.HOME_RETURN);
        assertFalse(LaunchTransitionController.inputReachesHost(controller.snapshot(), false));
        // A stream without a transition (a classic launch) is never gated.
        assertTrue(LaunchTransitionController.inputReachesHost(null, false));
        assertFalse(LaunchTransitionController.inputReachesHost(null, true));
    }

    @Test
    public void timeoutIsNeverThePassiveClosingScreen() {
        LaunchTransitionController controller = started(LaunchTransitionType.GAME);
        controller.closingStream("transition-1");
        assertTrue(controller.snapshot().closingPresentation());
        controller.streamClosingFailed("transition-1", "close failed");
        assertEquals(LaunchTransitionState.ERROR, controller.snapshot().state);
        assertFalse(controller.snapshot().closingPresentation());
        controller.begin(spec(LaunchTransitionType.GAME));
        controller.timedOut("transition-1", "slow");
        assertFalse(controller.snapshot().closingPresentation());
    }

    @Test
    public void playniteRequiresFullscreenReadinessAndTransport() {
        LaunchTransitionController controller = started(LaunchTransitionType.PLAYNITE);
        transportReady(controller);
        assertFalse(controller.snapshot().revealAuthorized);

        controller.gatewayConnected("transition-1", HOST);
        controller.targetProcessRunning("transition-1", HOST,
                LaunchTransitionType.PLAYNITE, "");
        assertFalse(controller.snapshot().revealAuthorized);

        controller.targetWindowReady("transition-1", HOST,
                LaunchTransitionType.PLAYNITE, "");
        assertFalse(controller.snapshot().revealAuthorized);
        controller.videoFrameRendered("transition-1", controller.snapshot().frameEdge, System.nanoTime());
        assertTrue(controller.snapshot().revealAuthorized);
        assertEquals(LaunchTransitionState.PLAYNITE_FULLSCREEN_READY,
                controller.snapshot().state);
    }

    @Test
    public void streamConnectingAfterAConfirmedProcessKeepsPhaseTwo() {
        // A provider that starts the game before the stream: phase 2 never falls back to 1.
        LaunchTransitionController controller = started(LaunchTransitionType.GAME);
        controller.gatewayConnected("transition-1", HOST);
        controller.targetProcessRunning("transition-1", HOST,
                LaunchTransitionType.GAME, GAME);

        controller.streamConnected("transition-1");

        assertEquals(LaunchTransitionState.GAME_PROCESS_RUNNING, controller.snapshot().state);
    }

    @Test
    public void frameRenderedBeforeTargetWindowReadinessCannotRevealDesktop() {
        LaunchTransitionController controller = started(LaunchTransitionType.GAME);
        transportReady(controller);
        controller.gatewayConnected("transition-1", HOST);
        controller.targetProcessRunning("transition-1", HOST,
                LaunchTransitionType.GAME, GAME);

        controller.targetWindowReady("transition-1", HOST,
                LaunchTransitionType.GAME, GAME);

        assertFalse(controller.snapshot().revealAuthorized);
        assertTrue(controller.snapshot().overlayVisible);
        controller.videoFrameRendered("transition-1", controller.snapshot().frameEdge, System.nanoTime());
        assertTrue(controller.snapshot().revealAuthorized);
    }

    @Test
    public void gameRequiresMatchingProcessWindowAndAllLocalGates() {
        LaunchTransitionController controller = started(LaunchTransitionType.GAME);
        controller.gatewayConnected("transition-1", HOST);
        controller.targetProcessRunning("transition-1", HOST,
                LaunchTransitionType.GAME, GAME);
        controller.targetWindowReady("transition-1", HOST,
                LaunchTransitionType.GAME, GAME);
        assertFalse(controller.snapshot().revealAuthorized);

        transportReady(controller);
        assertTrue(controller.snapshot().revealAuthorized);
        assertEquals(LaunchTransitionState.GAME_READY, controller.snapshot().state);
    }

    @Test
    public void processAloneNeverMarksGameReady() {
        LaunchTransitionController controller = started(LaunchTransitionType.GAME);
        transportReady(controller);
        controller.gatewayConnected("transition-1", HOST);
        controller.targetProcessRunning("transition-1", HOST,
                LaunchTransitionType.GAME, GAME);
        assertFalse(controller.snapshot().revealAuthorized);
        assertEquals(LaunchTransitionState.GAME_PROCESS_RUNNING,
                controller.snapshot().state);
    }

    @Test
    public void firstFrameAloneNeverMarksGameReady() {
        LaunchTransitionController controller = started(LaunchTransitionType.GAME);
        controller.videoFrameRendered("transition-1", controller.snapshot().frameEdge, System.nanoTime());
        assertFalse(controller.snapshot().revealAuthorized);
        assertTrue(controller.snapshot().overlayVisible);
    }

    @Test
    public void timeoutKeepsPrivacyAndInputGateClosed() {
        LaunchTransitionController controller = started(LaunchTransitionType.GAME);
        controller.timedOut("transition-1", "window timeout");
        LaunchTransitionSnapshot snapshot = controller.snapshot();
        assertEquals(LaunchTransitionState.TIMED_OUT, snapshot.state);
        assertTrue(snapshot.overlayVisible);
        assertTrue(snapshot.inputBlocked);
        assertFalse(snapshot.revealAuthorized);
        assertTrue(snapshot.uncertain);
        assertEquals(2, snapshot.step);
    }

    @Test
    public void timeoutRetainsTheStepThatActuallyFailed() {
        LaunchTransitionController controller = started(LaunchTransitionType.PLAYNITE);
        controller.streamConnected("transition-1");
        assertEquals(3, controller.snapshot().step);

        controller.timedOut("transition-1", "process timeout");

        assertEquals(LaunchTransitionState.TIMED_OUT, controller.snapshot().state);
        assertEquals(3, controller.snapshot().step);
    }

    @Test
    public void lateReadinessAfterTimeoutRequiresExplicitReveal() {
        LaunchTransitionController controller = started(LaunchTransitionType.GAME);
        controller.timedOut("transition-1", "uncertain");
        controller.gatewayConnected("transition-1", HOST);
        controller.targetWindowReady("transition-1", HOST,
                LaunchTransitionType.GAME, GAME);
        transportReady(controller);

        assertEquals(LaunchTransitionState.TIMED_OUT, controller.snapshot().state);
        assertFalse(controller.snapshot().revealAuthorized);
        assertTrue(controller.snapshot().manualRevealAvailable);
    }

    @Test
    public void lateReadinessAfterErrorOnlyOffersTheManualReveal() {
        LaunchTransitionController controller = started(LaunchTransitionType.GAME);
        controller.error("transition-1", "failed");
        controller.gatewayConnected("transition-1", HOST);
        controller.targetWindowReady("transition-1", HOST,
                LaunchTransitionType.GAME, GAME);
        transportReady(controller);

        assertEquals(LaunchTransitionState.ERROR, controller.snapshot().state);
        assertFalse("no automatic reveal after a failure",
                controller.snapshot().revealAuthorized);
        assertTrue("the cosmetic cover never rejects the user",
                controller.snapshot().manualRevealAvailable);
    }

    @Test
    public void manualRevealFromErrorRevealsTheStream() {
        LaunchTransitionController controller = started(LaunchTransitionType.GAME);
        transportReady(controller);
        controller.error("transition-1", "start timeout", true);

        LaunchTransitionSnapshot failed = controller.snapshot();
        assertEquals(LaunchTransitionState.ERROR, failed.state);
        assertTrue(failed.manualRevealAvailable);
        assertTrue(failed.revealPreferred);

        controller.showStreamAnyway("transition-1");
        assertTrue(controller.snapshot().revealAuthorized);
        controller.revealCompleted("transition-1");
        assertEquals(LaunchTransitionState.GAME_RUNNING, controller.snapshot().state);
        assertFalse(controller.snapshot().overlayVisible);
        assertFalse(controller.snapshot().inputBlocked);
    }

    @Test
    public void failureAfterManualRevealIsANoticeNotTheCover() {
        LaunchTransitionController controller = started(LaunchTransitionType.GAME);
        transportReady(controller);
        controller.showStreamAnyway("transition-1");
        controller.revealCompleted("transition-1");

        controller.timedOut("transition-1", "unconfirmed");
        controller.error("transition-1", "failed");

        LaunchTransitionSnapshot current = controller.snapshot();
        assertEquals(LaunchTransitionState.GAME_RUNNING, current.state);
        assertFalse(current.overlayVisible);
        assertFalse(current.inputBlocked);
        assertEquals("failed", current.notice);

        controller.targetWindowLost("transition-1", HOST, LaunchTransitionType.GAME, GAME,
                "window lost");
        assertFalse(controller.snapshot().overlayVisible);
    }

    @Test
    public void previousGameRunningOffersRevealAndReturnsToStartingAfterStop() {
        LaunchTransitionController controller = started(LaunchTransitionType.GAME);
        transportReady(controller);
        controller.previousGameRunning("transition-1", HOST, "previous game");

        assertEquals(LaunchTransitionState.PREVIOUS_GAME_RUNNING, controller.snapshot().state);
        assertTrue(controller.snapshot().overlayVisible);
        assertTrue(controller.snapshot().manualRevealAvailable);
        assertFalse(controller.snapshot().revealAuthorized);

        controller.targetStarting("transition-1", HOST, LaunchTransitionType.GAME, GAME);
        assertEquals(LaunchTransitionState.GAME_STARTING, controller.snapshot().state);
    }

    @Test
    public void gameUpdatingKeepsTheCoverOffersRevealAndContinuesAsALaunch() {
        LaunchTransitionController controller = started(LaunchTransitionType.GAME);
        transportReady(controller);
        controller.gameUpdating("transition-1", HOST, 40, false);

        LaunchTransitionSnapshot updating = controller.snapshot();
        assertEquals(LaunchTransitionState.GAME_UPDATING, updating.state);
        assertEquals(40, updating.updateProgress);
        assertEquals(3, updating.step);
        assertTrue(updating.overlayVisible);
        assertTrue(updating.inputBlocked);
        assertTrue("the user can always reveal Big Picture", updating.manualRevealAvailable);
        assertFalse(updating.startTakingLonger);

        controller.gameUpdating("transition-1", HOST, -1, true);
        assertTrue(controller.snapshot().updatePatching);
        assertEquals(-1, controller.snapshot().updateProgress);

        controller.gameUpdateFinished("transition-1");
        assertEquals(LaunchTransitionState.GAME_STARTING, controller.snapshot().state);
        assertEquals(-1, controller.snapshot().updateProgress);
    }

    @Test
    public void gameUpdatingIgnoresOtherTransitionsAndNeverCoversARevealedStream() {
        LaunchTransitionController controller = started(LaunchTransitionType.GAME);
        transportReady(controller);
        controller.gameUpdating("stale-transition", HOST, 10, false);
        controller.gameUpdating("transition-1", "other-host", 10, false);
        assertFalse(controller.snapshot().state == LaunchTransitionState.GAME_UPDATING);

        controller.showStreamAnyway("transition-1");
        assertTrue(controller.snapshot().revealAuthorized);
        controller.gameUpdating("transition-1", HOST, 10, false);
        assertFalse("after a reveal the cover does not return",
                controller.snapshot().state == LaunchTransitionState.GAME_UPDATING);
    }

    @Test
    public void explicitRevealIsAvailableAfterTransportButBeforeHostReadiness() {
        LaunchTransitionController controller = started(LaunchTransitionType.GAME);
        controller.showStreamAnyway("transition-1");
        assertFalse(controller.snapshot().revealAuthorized);
        transportReady(controller);
        assertTrue(controller.snapshot().manualRevealAvailable);
        controller.showStreamAnyway("transition-1");
        assertTrue(controller.snapshot().revealAuthorized);
    }

    @Test
    public void explicitRevealRemainsAvailableAfterAnUncertainTimeout() {
        LaunchTransitionController controller = started(LaunchTransitionType.GAME);
        transportReady(controller);
        controller.timedOut("transition-1", "uncertain");
        assertTrue(controller.snapshot().manualRevealAvailable);
        controller.showStreamAnyway("transition-1");
        assertTrue(controller.snapshot().revealAuthorized);
    }

    @Test
    public void timeoutWithoutFramesOffersNothingToReveal() {
        LaunchTransitionController controller = started(LaunchTransitionType.GAME);
        controller.timedOut("transition-1", "uncertain");
        assertFalse(controller.snapshot().manualRevealAvailable);
        controller.showStreamAnyway("transition-1");
        assertFalse(controller.snapshot().revealAuthorized);
    }

    @Test
    public void cancellationIgnoresLateReady() {
        LaunchTransitionController controller = started(LaunchTransitionType.GAME);
        LaunchTransitionState before = controller.snapshot().state;
        assertEquals(before, controller.cancel("transition-1").state);
        assertEquals(LaunchTransitionState.CANCELLED, controller.cancel("other").state);
        controller.gatewayConnected("transition-1", HOST);
        controller.targetWindowReady("transition-1", HOST,
                LaunchTransitionType.GAME, GAME);
        transportReady(controller);
        assertEquals(LaunchTransitionState.CANCELLED, controller.snapshot().state);
        assertFalse(controller.snapshot().revealAuthorized);
        assertTrue(controller.snapshot().inputBlocked);
    }

    @Test
    public void cancelDuringRevealRevokesItAndALateRevealCompletionCannotHideTheOverlay() {
        LaunchTransitionController controller = started(LaunchTransitionType.GAME);
        transportReady(controller);
        controller.gatewayConnected("transition-1", HOST);
        controller.targetWindowReady("transition-1", HOST, LaunchTransitionType.GAME, GAME);
        controller.videoFrameRendered("transition-1", controller.snapshot().frameEdge, System.nanoTime());
        assertTrue(controller.snapshot().revealAuthorized);

        controller.cancel("transition-1");
        controller.revealCompleted("transition-1");

        assertEquals(LaunchTransitionState.CANCELLED, controller.snapshot().state);
        assertFalse(controller.snapshot().revealAuthorized);
        assertTrue(controller.snapshot().overlayVisible);
        assertTrue(controller.snapshot().inputBlocked);
    }

    @Test
    public void staleTransitionAndWrongHostAreIgnored() {
        LaunchTransitionController controller = started(LaunchTransitionType.GAME);
        transportReady(controller);
        controller.gatewayConnected("old-transition", HOST);
        controller.gatewayConnected("transition-1", "other-host");
        controller.targetWindowReady("old-transition", HOST,
                LaunchTransitionType.GAME, GAME);
        assertFalse(controller.snapshot().revealAuthorized);
    }

    @Test
    public void wrongGameIsIgnored() {
        LaunchTransitionController controller = started(LaunchTransitionType.GAME);
        transportReady(controller);
        controller.gatewayConnected("transition-1", HOST);
        controller.targetWindowReady("transition-1", HOST,
                LaunchTransitionType.GAME,
                "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");
        assertFalse(controller.snapshot().revealAuthorized);
    }

    @Test
    public void operationIsAuthorizedOnlyAfterOverlayFrame() {
        LaunchTransitionController controller = new LaunchTransitionController(null);
        controller.begin(spec(LaunchTransitionType.PLAYNITE));
        assertFalse(controller.snapshot().operationAuthorized);
        controller.coverSettled("transition-1", PresentationBarrier.Outcome.SUBMITTED);
        assertTrue(controller.snapshot().operationAuthorized);
    }

    @Test
    public void inputUnblocksOnlyAfterRevealAnimationCompletes() {
        LaunchTransitionController controller = started(LaunchTransitionType.GENERIC);
        transportReady(controller);
        assertTrue(controller.snapshot().revealAuthorized);
        assertTrue(controller.snapshot().inputBlocked);
        controller.revealCompleted("transition-1");
        assertFalse(controller.snapshot().inputBlocked);
        assertFalse(controller.snapshot().overlayVisible);
    }

    @Test
    public void existingGameConnectionRevalidatesTargetAndKeepsGameIdentity() {
        LaunchTransitionController controller = started(LaunchTransitionType.GAME_CONNECTION);

        transportReady(controller);

        assertFalse(controller.snapshot().revealAuthorized);
        controller.gatewayConnected("transition-1", HOST);
        controller.targetProcessRunning("transition-1", HOST,
                LaunchTransitionType.GAME, GAME);
        controller.targetWindowReady("transition-1", HOST,
                LaunchTransitionType.GAME, GAME);
        controller.videoFrameRendered("transition-1", controller.snapshot().frameEdge, System.nanoTime());
        assertTrue(controller.snapshot().revealAuthorized);
        controller.revealCompleted("transition-1");
        assertEquals(LaunchTransitionState.GAME_RUNNING, controller.snapshot().state);
    }

    @Test
    public void revealCompletionIsIdempotent() {
        List<LaunchTransitionSnapshot> snapshots = new ArrayList<>();
        LaunchTransitionController controller =
                new LaunchTransitionController(snapshots::add);
        controller.begin(spec(LaunchTransitionType.GENERIC));
        controller.coverSettled("transition-1", PresentationBarrier.Outcome.SUBMITTED);
        transportReady(controller);
        controller.revealCompleted("transition-1");
        int afterFirst = snapshots.size();
        controller.revealCompleted("transition-1");
        assertEquals(afterFirst, snapshots.size());
    }

    /**
     * TV 2026-10-14T13:51:04Z: a flapping host windowReady covered a playing game for ~3 s after
     * its automatic reveal. After any reveal a lost window is only a notice.
     */
    @Test
    public void lostWindowAfterAutomaticRevealIsANoticeNotTheCover() {
        LaunchTransitionController controller = started(LaunchTransitionType.GAME);
        controller.gatewayConnected("transition-1", HOST);
        controller.targetWindowReady("transition-1", HOST,
                LaunchTransitionType.GAME, GAME);
        transportReady(controller);
        assertTrue(controller.snapshot().revealAuthorized);
        controller.revealCompleted("transition-1");
        assertFalse(controller.snapshot().overlayVisible);

        for (int sample = 0; sample < 2; sample++) {
            controller.targetWindowLost("transition-1", HOST,
                    LaunchTransitionType.GAME, GAME, "window lost");
            assertFalse(controller.snapshot().overlayVisible);
            assertFalse(controller.snapshot().inputBlocked);
            assertFalse(controller.snapshot().manualRevealAvailable);
            assertEquals("window lost", controller.snapshot().notice);
            controller.targetWindowReady("transition-1", HOST,
                    LaunchTransitionType.GAME, GAME);
            controller.hostSessionLocked("transition-1", HOST,
                    LaunchTransitionType.GAME, GAME, "locked");
            controller.timedOut("transition-1", "slow");
            assertFalse(controller.snapshot().overlayVisible);
            assertFalse(controller.snapshot().inputBlocked);
        }
    }

    /** "Reveal now" chosen, its reveal still under way: a lost window must not cancel it. */
    @Test
    public void lostWindowBetweenManualRevealAndItsCompletionDoesNotCover() {
        LaunchTransitionController controller = started(LaunchTransitionType.GAME);
        controller.gatewayConnected("transition-1", HOST);
        transportReady(controller);
        controller.showStreamAnyway("transition-1");
        assertTrue(controller.snapshot().revealAuthorized);

        controller.targetWindowLost("transition-1", HOST,
                LaunchTransitionType.GAME, GAME, "window lost");
        assertTrue("the pending reveal is kept", controller.snapshot().revealAuthorized);
        assertEquals("window lost", controller.snapshot().notice);

        controller.revealCompleted("transition-1");
        assertFalse(controller.snapshot().overlayVisible);
        assertFalse(controller.snapshot().inputBlocked);
    }

    /** Only a new transition the user starts (end game, close, switch) may cover again. */
    @Test
    public void newTransitionAfterARevealCoversAgain() {
        LaunchTransitionController stop = started(LaunchTransitionType.GAME);
        stop.gatewayConnected("transition-1", HOST);
        stop.targetWindowReady("transition-1", HOST, LaunchTransitionType.GAME, GAME);
        transportReady(stop);
        stop.revealCompleted("transition-1");
        assertTrue(stop.gameStopping("transition-1", HOST, GAME));
        assertTrue(stop.snapshot().overlayVisible);
        assertTrue(stop.snapshot().inputBlocked);

        LaunchTransitionController close = started(LaunchTransitionType.GAME);
        transportReady(close);
        close.showStreamAnyway("transition-1");
        close.revealCompleted("transition-1");
        close.closingStream("transition-1");
        assertTrue(close.snapshot().overlayVisible);
        assertTrue(close.snapshot().inputBlocked);

        LaunchTransitionController playnite = started(LaunchTransitionType.PLAYNITE);
        playnite.gatewayConnected("transition-1", HOST);
        playnite.targetWindowReady("transition-1", HOST, LaunchTransitionType.PLAYNITE, "");
        transportReady(playnite);
        playnite.revealCompleted("transition-1");
        assertFalse(playnite.snapshot().overlayVisible);
        playnite.targetStarting("transition-1", HOST, LaunchTransitionType.GAME, GAME);
        assertTrue(playnite.snapshot().overlayVisible);
        playnite.targetWindowLost("transition-1", HOST, LaunchTransitionType.GAME, GAME,
                "window lost");
        assertTrue("the switched game is not revealed yet",
                playnite.snapshot().overlayVisible);
        assertTrue(playnite.snapshot().inputBlocked);
        playnite.error("transition-1", "failed");
        assertEquals("a failure of the new game is its cover, not a notice",
                LaunchTransitionState.ERROR, playnite.snapshot().state);
        assertEquals("", playnite.snapshot().notice);

        stop.begin(spec(LaunchTransitionType.GAME));
        assertTrue(stop.snapshot().overlayVisible);
        assertTrue(stop.snapshot().inputBlocked);
    }

    @Test
    public void lostWindowKeepsManualRevealButRequiresFreshFrameForAutomaticReveal() {
        LaunchTransitionController controller = started(LaunchTransitionType.GAME);
        controller.gatewayConnected("transition-1", HOST);
        controller.targetWindowReady("transition-1", HOST,
                LaunchTransitionType.GAME, GAME);
        transportReady(controller);

        controller.targetWindowLost("transition-1", HOST,
                LaunchTransitionType.GAME, GAME, "Windows sign-in");

        assertFalse(controller.snapshot().revealAuthorized);
        assertTrue("the live stream keeps Reveal now",
                controller.snapshot().manualRevealAvailable);

        controller.targetWindowReady("transition-1", HOST,
                LaunchTransitionType.GAME, GAME);
        assertFalse("automatic reveal waits for a frame after the readiness edge",
                controller.snapshot().revealAuthorized);
        controller.videoFrameRendered("transition-1", controller.snapshot().frameEdge, System.nanoTime());
        assertTrue(controller.snapshot().revealAuthorized);
    }

    @Test
    public void lockedSessionKeepsRevealAvailableAcrossRepeatedHostSamples() {
        LaunchTransitionController controller = started(LaunchTransitionType.GAME);
        transportReady(controller);
        assertTrue(controller.snapshot().manualRevealAvailable);

        controller.hostSessionLocked("transition-1", HOST,
                LaunchTransitionType.GAME, GAME, "Windows session locked");

        assertEquals(LaunchTransitionState.HOST_SESSION_LOCKED,
                controller.snapshot().state);
        assertTrue(controller.snapshot().manualRevealAvailable);

        controller.hostSessionLocked("transition-1", HOST,
                LaunchTransitionType.GAME, GAME, "Windows session locked");

        assertTrue(controller.snapshot().manualRevealAvailable);
        controller.showStreamAnyway("transition-1");
        assertTrue(controller.snapshot().revealAuthorized);
    }

    @Test
    public void explicitRevealKeepsTheSameTransitionVisibleAfterAnotherLostWindowSample() {
        LaunchTransitionController controller = started(LaunchTransitionType.GAME);
        controller.gatewayConnected("transition-1", HOST);
        transportReady(controller);
        controller.targetWindowLost("transition-1", HOST,
                LaunchTransitionType.GAME, GAME, "launcher");
        controller.videoFrameRendered("transition-1", controller.snapshot().frameEdge, System.nanoTime());
        controller.showStreamAnyway("transition-1");
        controller.revealCompleted("transition-1");

        controller.targetWindowLost("transition-1", HOST,
                LaunchTransitionType.GAME, GAME, "repeated launcher sample");

        assertFalse(controller.snapshot().overlayVisible);
        assertFalse(controller.snapshot().inputBlocked);
        assertFalse(controller.snapshot().manualRevealAvailable);
    }

    @Test
    public void gameReturnToPlayniteRequiresFreshFrameAndFullscreenWindow() {
        LaunchTransitionController controller = started(LaunchTransitionType.GAME);
        transportReady(controller);
        controller.gatewayConnected("transition-1", HOST);
        controller.targetWindowReady("transition-1", HOST,
                LaunchTransitionType.GAME, GAME);
        controller.revealCompleted("transition-1");

        controller.gameStopping("transition-1", HOST, GAME);
        controller.playniteReturning("transition-1", HOST);
        controller.targetWindowReady("transition-1", HOST,
                LaunchTransitionType.PLAYNITE, "");
        assertFalse(controller.snapshot().revealAuthorized);
        controller.videoFrameRendered("transition-1", controller.snapshot().frameEdge, System.nanoTime());
        assertTrue(controller.snapshot().revealAuthorized);
        assertEquals(LaunchTransitionState.PLAYNITE_FULLSCREEN_READY,
                controller.snapshot().state);
    }

    @Test
    public void differentGameCanStartAfterReturningToPlayniteInSameStream() {
        LaunchTransitionController controller = started(LaunchTransitionType.GAME);
        transportReady(controller);
        controller.gatewayConnected("transition-1", HOST);
        controller.targetWindowReady("transition-1", HOST,
                LaunchTransitionType.GAME, GAME);
        controller.revealCompleted("transition-1");

        controller.gameStopping("transition-1", HOST, GAME);
        controller.playniteReturning("transition-1", HOST);
        controller.targetWindowReady("transition-1", HOST,
                LaunchTransitionType.PLAYNITE, "");
        controller.videoFrameRendered("transition-1", controller.snapshot().frameEdge, System.nanoTime());
        controller.revealCompleted("transition-1");

        controller.targetStarting("transition-1", HOST,
                LaunchTransitionType.GAME, SECOND_GAME);
        LaunchTransitionSnapshot starting = controller.snapshot();
        assertEquals(LaunchTransitionState.GAME_STARTING, starting.state);
        assertTrue(starting.overlayVisible);
        assertTrue(starting.inputBlocked);
        assertFalse(starting.revealAuthorized);

        controller.targetProcessRunning("transition-1", HOST,
                LaunchTransitionType.GAME, SECOND_GAME);
        controller.targetWindowReady("transition-1", HOST,
                LaunchTransitionType.GAME, SECOND_GAME);
        assertFalse(controller.snapshot().revealAuthorized);

        controller.videoFrameRendered("transition-1", controller.snapshot().frameEdge, System.nanoTime());
        assertTrue(controller.snapshot().revealAuthorized);
        controller.revealCompleted("transition-1");
        assertEquals(LaunchTransitionState.GAME_RUNNING, controller.snapshot().state);
        assertFalse(controller.snapshot().overlayVisible);
        assertFalse(controller.snapshot().inputBlocked);
    }

    @Test
    public void rapidSecondGameStartDoesNotWaitForPlayniteReveal() {
        LaunchTransitionController controller = started(LaunchTransitionType.GAME);
        transportReady(controller);
        controller.gatewayConnected("transition-1", HOST);
        controller.targetWindowReady("transition-1", HOST,
                LaunchTransitionType.GAME, GAME);
        controller.revealCompleted("transition-1");

        controller.gameStopping("transition-1", HOST, GAME);
        controller.playniteReturning("transition-1", HOST);
        controller.targetStarting("transition-1", HOST,
                LaunchTransitionType.GAME, SECOND_GAME);
        controller.targetProcessRunning("transition-1", HOST,
                LaunchTransitionType.GAME, SECOND_GAME);
        controller.targetWindowReady("transition-1", HOST,
                LaunchTransitionType.GAME, SECOND_GAME);

        assertFalse(controller.snapshot().revealAuthorized);
        controller.videoFrameRendered("transition-1", controller.snapshot().frameEdge, System.nanoTime());
        assertTrue(controller.snapshot().revealAuthorized);
    }

    @Test
    public void lateRunningSignalsCannotReopenAStoppingGame() {
        LaunchTransitionController controller = started(LaunchTransitionType.GAME);
        transportReady(controller);
        controller.gatewayConnected("transition-1", HOST);
        controller.targetWindowReady("transition-1", HOST,
                LaunchTransitionType.GAME, GAME);
        controller.videoFrameRendered("transition-1", controller.snapshot().frameEdge, System.nanoTime());
        controller.revealCompleted("transition-1");

        controller.gameStopping("transition-1", HOST, GAME);
        controller.targetProcessRunning("transition-1", HOST,
                LaunchTransitionType.GAME, GAME);
        controller.targetWindowReady("transition-1", HOST,
                LaunchTransitionType.GAME, GAME);
        controller.videoFrameRendered("transition-1", controller.snapshot().frameEdge, System.nanoTime());

        LaunchTransitionSnapshot stopped = controller.snapshot();
        assertEquals(LaunchTransitionState.GAME_STOPPING, stopped.state);
        assertTrue(stopped.overlayVisible);
        assertTrue(stopped.inputBlocked);
        assertFalse(stopped.revealAuthorized);
        assertFalse(stopped.manualRevealAvailable);
    }

    @Test
    public void differentGameCannotReplaceAnActiveGameWithoutPlayniteReturn() {
        LaunchTransitionController controller = started(LaunchTransitionType.GAME);
        controller.targetStarting("transition-1", HOST,
                LaunchTransitionType.GAME, SECOND_GAME);
        assertEquals(LaunchTransitionState.CONNECTING_STREAM, controller.snapshot().state);
    }

    @Test
    public void rejectedLaunchKeepsInteractionAcrossLateTransportCallbacks() {
        LaunchTransitionController controller = started(LaunchTransitionType.GAME);
        controller.launcherInteractionRequired("transition-1", HOST, GAME, "attention");
        controller.surfaceReady("transition-1");
        assertEquals(LaunchTransitionState.LAUNCHER_INTERACTION_REQUIRED,
                controller.snapshot().state);
        transportReady(controller);
        controller.streamConnected("transition-1");
        assertEquals(LaunchTransitionState.LAUNCHER_INTERACTION_REQUIRED,
                controller.snapshot().state);
        assertTrue(controller.snapshot().manualRevealAvailable);
        assertFalse(controller.snapshot().revealAuthorized);
        controller.showStreamAnyway("transition-1");
        assertTrue(controller.snapshot().revealAuthorized);
    }

    /**
     * TV 2026-10-15 15:56:10: a fresh frame authorized the warm-up's generic reveal while the
     * user's Play was already handed to that stream, and the switch covered it again 150 ms
     * later (one flash). The handed Play holds the switch cover first: no reveal at all.
     */
    @Test
    public void warmUpWithAPendingPlayNeverRevealsOnItsFreshFrame() {
        LaunchTransitionController controller = started(LaunchTransitionType.GENERIC);
        controller.surfaceReady("transition-1");
        controller.inputPipelineReady("transition-1");
        controller.streamConnected("transition-1");

        assertTrue(controller.coverForUser("transition-1",
                LaunchTransitionController.UserCover.SWITCH));
        controller.videoFrameRendered("transition-1", controller.snapshot().frameEdge,
                System.nanoTime());

        LaunchTransitionSnapshot covered = controller.snapshot();
        assertFalse(covered.revealAuthorized);
        assertFalse(covered.manualRevealAvailable);
        assertTrue(covered.overlayVisible);
        assertTrue(covered.inputBlocked);
        controller.revealCompleted("transition-1");
        assertTrue("no reveal under the pending Play", controller.snapshot().overlayVisible);

        // The Play did not go ahead: the warm-up's own presentation returns.
        controller.releaseUserCover("transition-1", LaunchTransitionController.UserCover.SWITCH);
        assertTrue(controller.snapshot().revealAuthorized);
    }

    /** Owner 2026-10-15: attenuated under any cover, full once revealed, muted when hidden. */
    @Test
    public void streamAudioIsAttenuatedUnderACoverAndFullOnceRevealed() {
        LaunchTransitionController.StreamAudio full = LaunchTransitionController.StreamAudio.FULL;
        LaunchTransitionController.StreamAudio covered =
                LaunchTransitionController.StreamAudio.COVERED;
        assertEquals(full, LaunchTransitionController.streamAudio(null, false));
        assertEquals(LaunchTransitionController.StreamAudio.MUTED,
                LaunchTransitionController.streamAudio(null, true));
        LaunchTransitionController controller = started(LaunchTransitionType.GAME);
        transportReady(controller);
        assertEquals("under the launch cover", covered,
                LaunchTransitionController.streamAudio(controller.snapshot(), false));
        assertEquals("a hidden warm-up", LaunchTransitionController.StreamAudio.MUTED,
                LaunchTransitionController.streamAudio(controller.snapshot(), true));
        controller.showStreamAnyway("transition-1");
        assertEquals("during the reveal", covered,
                LaunchTransitionController.streamAudio(controller.snapshot(), false));
        controller.revealCompleted("transition-1");
        assertEquals(full, LaunchTransitionController.streamAudio(controller.snapshot(), false));
        controller.coverForUser("transition-1", LaunchTransitionController.UserCover.HOME_RETURN);
        assertEquals("under the Home-return cover", covered,
                LaunchTransitionController.streamAudio(controller.snapshot(), false));
        controller.closingStream("transition-1");
        assertEquals("closing", covered,
                LaunchTransitionController.streamAudio(controller.snapshot(), false));
    }

    private static final long MS = 1_000_000L;

    private static LaunchTransitionController launcherObserved(long[] nanos) {
        LaunchTransitionController controller =
                new LaunchTransitionController(null, () -> nanos[0]);
        controller.begin(spec(LaunchTransitionType.GAME));
        controller.coverSettled("transition-1", PresentationBarrier.Outcome.SUBMITTED);
        transportReady(controller);
        controller.gatewayConnected("transition-1", HOST);
        controller.targetStarting("transition-1", HOST, LaunchTransitionType.GAME, GAME);
        return controller;
    }

    @Test
    public void transientLauncherSignalNeverShowsTheNotice() {
        long[] nanos = {0L};
        LaunchTransitionController controller = launcherObserved(nanos);

        long generation = controller.launcherInteractionObserved(
                "transition-1", HOST, GAME, "launcher");
        nanos[0] = 1_000L * MS;
        controller.launcherInteractionObserved("transition-1", HOST, GAME, "launcher");
        assertEquals("the step keeps animating", LaunchTransitionState.GAME_STARTING,
                controller.snapshot().state);
        nanos[0] = 2_000L * MS;
        controller.launcherInteractionCleared("transition-1");
        nanos[0] = 2_500L * MS;
        controller.launcherAttentionDue("transition-1", generation);

        assertEquals(LaunchTransitionState.GAME_STARTING, controller.snapshot().state);
        assertEquals("", controller.snapshot().detail);
    }

    @Test
    public void persistentLauncherSignalShowsTheNoticeAndLeavesWhenItClears() {
        long[] nanos = {0L};
        LaunchTransitionController controller = launcherObserved(nanos);
        long generation = controller.launcherInteractionObserved(
                "transition-1", HOST, GAME, "launcher");
        nanos[0] = 2_500L * MS;
        controller.launcherAttentionDue("transition-1", generation);
        assertEquals(LaunchTransitionState.LAUNCHER_INTERACTION_REQUIRED,
                controller.snapshot().state);
        assertEquals("launcher", controller.snapshot().detail);
        assertTrue(controller.snapshot().manualRevealAvailable);

        controller.launcherInteractionCleared("transition-1");
        assertEquals(LaunchTransitionState.GAME_STARTING, controller.snapshot().state);
        assertEquals("", controller.snapshot().detail);

        // Repeated observations over the debounce enter without a deadline.
        nanos[0] = 3_000L * MS;
        controller.launcherInteractionObserved("transition-1", HOST, GAME, "again");
        nanos[0] = 5_400L * MS;
        controller.launcherInteractionObserved("transition-1", HOST, GAME, "again");
        assertEquals(LaunchTransitionState.GAME_STARTING, controller.snapshot().state);
        nanos[0] = 5_500L * MS;
        controller.launcherInteractionObserved("transition-1", HOST, GAME, "again");
        assertEquals(LaunchTransitionState.LAUNCHER_INTERACTION_REQUIRED,
                controller.snapshot().state);
    }

    @Test
    public void pendingLauncherSignalNeverDelaysOrReplacesFailureAndCancel() {
        long[] nanos = {0L};
        LaunchTransitionController controller = launcherObserved(nanos);
        long generation = controller.launcherInteractionObserved(
                "transition-1", HOST, GAME, "launcher");
        controller.error("transition-1", "failed");
        assertEquals(LaunchTransitionState.ERROR, controller.snapshot().state);
        nanos[0] = 2_500L * MS;
        controller.launcherAttentionDue("transition-1", generation);
        assertEquals(LaunchTransitionState.ERROR, controller.snapshot().state);

        LaunchTransitionController cancelled = launcherObserved(nanos);
        long pending = cancelled.launcherInteractionObserved(
                "transition-1", HOST, GAME, "launcher");
        cancelled.cancel("transition-1");
        nanos[0] = 5_000L * MS;
        cancelled.launcherAttentionDue("transition-1", pending);
        assertEquals(LaunchTransitionState.CANCELLED, cancelled.snapshot().state);
    }

    @Test
    public void launcherInteractionRequiresExplicitRevealOrCancel() {
        LaunchTransitionController controller = started(LaunchTransitionType.GAME);
        transportReady(controller);
        controller.gatewayConnected("transition-1", HOST);

        controller.launcherInteractionRequired(
                "transition-1", HOST, GAME, "reveal launcher");

        assertEquals(LaunchTransitionState.LAUNCHER_INTERACTION_REQUIRED,
                controller.snapshot().state);
        assertTrue(controller.snapshot().overlayVisible);
        assertTrue(controller.snapshot().inputBlocked);
        assertTrue(controller.snapshot().manualRevealAvailable);
        assertFalse(controller.snapshot().revealAuthorized);
        controller.showStreamAnyway("transition-1");
        assertTrue(controller.snapshot().revealAuthorized);
        controller.launcherInteractionRequired(
                "transition-1", HOST, GAME, "repeated host sample");
        assertTrue(controller.snapshot().revealAuthorized);
        controller.revealCompleted("transition-1");
        controller.launcherInteractionRequired(
                "transition-1", HOST, GAME, "late host sample");
        assertEquals(LaunchTransitionState.GAME_RUNNING, controller.snapshot().state);
        assertFalse(controller.snapshot().overlayVisible);
    }

    @Test
    public void recreationStartsCoveredWithoutAuthorizingAnotherOperation() {
        LaunchTransitionController recreated = new LaunchTransitionController(null);
        recreated.begin(spec(LaunchTransitionType.PLAYNITE));
        assertTrue(recreated.snapshot().overlayVisible);
        assertTrue(recreated.snapshot().inputBlocked);
        assertFalse(recreated.snapshot().operationAuthorized);
    }

    @Test
    public void failedCloseRestoresRevealedStreamAndInput() {
        LaunchTransitionController controller = started(LaunchTransitionType.GAME);
        transportReady(controller);
        controller.gatewayConnected("transition-1", HOST);
        controller.targetWindowReady("transition-1", HOST,
                LaunchTransitionType.GAME, GAME);
        controller.videoFrameRendered("transition-1", controller.snapshot().frameEdge, System.nanoTime());
        controller.revealCompleted("transition-1");

        controller.closingStream("transition-1");
        assertTrue(controller.snapshot().overlayVisible);
        assertTrue(controller.snapshot().inputBlocked);

        assertTrue(controller.streamClosingFailed("transition-1", "stop failed"));
        LaunchTransitionSnapshot restored = controller.snapshot();
        assertEquals(LaunchTransitionState.GAME_RUNNING, restored.state);
        assertFalse(restored.overlayVisible);
        assertFalse(restored.inputBlocked);
    }

    @Test
    public void failedCloseDuringLaunchKeepsPrivacyAndInputGatesClosed() {
        LaunchTransitionController controller = started(LaunchTransitionType.GAME);

        controller.closingStream("transition-1");
        assertFalse(controller.streamClosingFailed("transition-1", "stop failed"));

        LaunchTransitionSnapshot failed = controller.snapshot();
        assertEquals(LaunchTransitionState.ERROR, failed.state);
        assertTrue(failed.overlayVisible);
        assertTrue(failed.inputBlocked);
        assertEquals("stop failed", failed.detail);
    }

    @Test
    public void startTakingLongerIsANoticeForTheExactPendingStartOnly() {
        LaunchTransitionController controller = started(LaunchTransitionType.GAME);
        transportReady(controller);
        assertEquals(LaunchTransitionState.GAME_START_REQUESTED, controller.snapshot().state);

        controller.startTakingLonger("stale-transition");
        assertFalse(controller.snapshot().startTakingLonger);

        controller.startTakingLonger("transition-1");
        LaunchTransitionSnapshot slow = controller.snapshot();
        assertTrue(slow.startTakingLonger);
        assertEquals(LaunchTransitionState.GAME_START_REQUESTED, slow.state);
        assertTrue(slow.overlayVisible);
        assertFalse(slow.uncertain);
        // The manual reveal of a confirmed stream is unchanged by the notice.
        assertTrue(slow.manualRevealAvailable);

        controller.targetProcessRunning("transition-1", HOST, LaunchTransitionType.GAME, GAME);
        assertTrue(controller.snapshot().startTakingLonger);
        controller.gatewayConnected("transition-1", HOST);
        controller.targetWindowReady("transition-1", HOST, LaunchTransitionType.GAME, GAME);
        assertFalse(controller.snapshot().startTakingLonger);

        controller.begin(spec(LaunchTransitionType.GAME));
        assertFalse(controller.snapshot().startTakingLonger);
    }

    @Test
    public void windowLossOfARunningGameIsNotASlowStart() {
        LaunchTransitionController controller = started(LaunchTransitionType.GAME);
        transportReady(controller);
        assertFalse(controller.gameReadyConfirmed());
        controller.gatewayConnected("transition-1", HOST);
        controller.targetWindowReady("transition-1", HOST, LaunchTransitionType.GAME, GAME);
        assertTrue(controller.gameReadyConfirmed());

        controller.targetWindowLost("transition-1", HOST, LaunchTransitionType.GAME, GAME,
                "target_not_foreground");
        assertEquals(LaunchTransitionState.GAME_WINDOW_STABILIZING, controller.snapshot().state);
        controller.startTakingLonger("transition-1");
        assertFalse(controller.snapshot().startTakingLonger);
        assertTrue(controller.gameReadyConfirmed());

        controller.begin(spec(LaunchTransitionType.GAME));
        assertFalse(controller.gameReadyConfirmed());
    }

    /** A restart after a dead transport never starts a running game a second time. */
    @Test
    public void restartOfAConfirmedCommittedGameOnlyReconnectsToIt() {
        LaunchTransitionController controller = started(LaunchTransitionType.GAME);
        transportReady(controller);
        // Not confirmed ready: the start is replayed, committed or not.
        assertEquals(LaunchTransitionType.GAME,
                controller.restartType(LaunchTransitionType.GAME, true));
        controller.gatewayConnected("transition-1", HOST);
        controller.targetWindowReady("transition-1", HOST, LaunchTransitionType.GAME, GAME);
        assertEquals(LaunchTransitionType.GAME_CONNECTION,
                controller.restartType(LaunchTransitionType.GAME, true));
        // An uncommitted launch is the one the dead transport cleans up: started again.
        assertEquals(LaunchTransitionType.GAME,
                controller.restartType(LaunchTransitionType.GAME, false));
        for (LaunchTransitionType type : LaunchTransitionType.values()) {
            if (type != LaunchTransitionType.GAME) {
                assertEquals(type, controller.restartType(type, true));
            }
        }
    }

    /**
     * TV 2026-10-14 13:55: a failed switch of the neutral stream kept a GAME_CONNECTION without a
     * game, which waited for a game window and timed out under its cover. The neutral stream is
     * kept as the plain neutral stream; a stream with a game observes that game again.
     */
    @Test
    public void failedSwitchKeepsANeutralStreamNeutralAndARevealedGameRecreatesAsObservation() {
        assertEquals(LaunchTransitionType.GENERIC,
                LaunchTransitionController.retainedRecoveryType(""));
        assertEquals(LaunchTransitionType.GAME_CONNECTION,
                LaunchTransitionController.retainedRecoveryType("steam:952060"));
        assertEquals(LaunchTransitionType.GAME_CONNECTION, LaunchTransitionController
                .recoveredTransitionType(LaunchTransitionType.GAME, true));
        assertEquals(LaunchTransitionType.GAME, LaunchTransitionController
                .recoveredTransitionType(LaunchTransitionType.GAME, false));
        assertEquals(LaunchTransitionType.GAME_CONNECTION, LaunchTransitionController
                .recoveredTransitionType(LaunchTransitionType.GAME_CONNECTION, true));
    }

    @Test
    public void manualRevealDoesNotConfirmTheGameReady() {
        LaunchTransitionController controller = started(LaunchTransitionType.GAME);
        transportReady(controller);
        assertTrue(controller.snapshot().manualRevealAvailable);
        controller.showStreamAnyway("transition-1");
        controller.revealCompleted("transition-1");
        assertFalse(controller.gameReadyConfirmed());
    }

    /**
     * TV 2026-10-14: a retained warm-up reconnected the running game to GAME_READY without
     * any frame armed. Taking it over must see that the cover still needs a fresh frame, and
     * that frame alone brings the automatic reveal.
     */
    @Test
    public void coverAwaitsAFrameAfterTheReadinessEdgeUntilOneRenders() {
        LaunchTransitionController controller = started(LaunchTransitionType.GAME_CONNECTION);
        controller.surfaceReady("transition-1");
        controller.inputPipelineReady("transition-1");
        controller.streamConnected("transition-1");
        controller.gatewayConnected("transition-1", HOST);
        assertTrue(controller.snapshot().awaitingVideoFrame);
        controller.videoFrameRendered("transition-1", controller.snapshot().frameEdge, System.nanoTime());
        controller.targetWindowReady("transition-1", HOST, LaunchTransitionType.GAME, GAME);

        LaunchTransitionSnapshot ready = controller.snapshot();
        assertEquals(LaunchTransitionState.GAME_READY, ready.state);
        assertFalse(ready.revealAuthorized);
        assertTrue("the readiness edge needs a later frame", ready.awaitingVideoFrame);

        controller.videoFrameRendered("transition-1", controller.snapshot().frameEdge, System.nanoTime());
        assertTrue(controller.snapshot().revealAuthorized);
        assertFalse(controller.snapshot().awaitingVideoFrame);
    }

    /**
     * TV 2026-10-14: End game over a manually revealed stream failed at the host. The stream
     * comes back exactly as it was, input included, and the cover does not return.
     */
    @Test
    public void failedGameStopRestoresAManuallyRevealedStream() {
        LaunchTransitionController controller = started(LaunchTransitionType.GAME_CONNECTION);
        transportReady(controller);
        controller.showStreamAnyway("transition-1");
        controller.revealCompleted("transition-1");

        controller.gameStopping("transition-1", HOST, GAME);
        assertEquals(LaunchTransitionState.GAME_STOPPING, controller.snapshot().state);
        assertTrue(controller.snapshot().overlayVisible);

        assertTrue(controller.streamClosingFailed("transition-1", "end game failed"));
        LaunchTransitionSnapshot restored = controller.snapshot();
        assertEquals(LaunchTransitionState.GAME_RUNNING, restored.state);
        assertFalse(restored.overlayVisible);
        assertFalse(restored.inputBlocked);
        assertFalse(restored.revealAuthorized);
        assertTrue(restored.manuallyRevealed);

        // A later readiness failure stays a notice over the stream.
        controller.timedOut("transition-1", "slow");
        assertFalse(controller.snapshot().overlayVisible);
    }

    /** A stop failing under a cover keeps the user's way out once the stream renders. */
    @Test
    public void failedGameStopUnderTheCoverStillOffersManualReveal() {
        LaunchTransitionController controller = started(LaunchTransitionType.GAME_CONNECTION);
        transportReady(controller);
        controller.gameStopping("transition-1", HOST, GAME);
        assertFalse(controller.streamClosingFailed("transition-1", "end game failed"));
        assertEquals(LaunchTransitionState.ERROR, controller.snapshot().state);
        assertTrue(controller.snapshot().awaitingVideoFrame);
        controller.videoFrameRendered("transition-1", controller.snapshot().frameEdge, System.nanoTime());
        assertTrue(controller.snapshot().manualRevealAvailable);
    }

    /** A close failing under the cover over a stream that already renders offers "Reveal now" at once. */
    @Test
    public void failedCloseUnderTheCoverWithARenderedFrameOffersManualRevealAtOnce() {
        LaunchTransitionController controller = started(LaunchTransitionType.GAME_CONNECTION);
        transportReady(controller);
        controller.closingStream("transition-1");
        assertFalse(controller.snapshot().manualRevealAvailable);

        assertFalse(controller.streamClosingFailed("transition-1", "suspend failed"));
        LaunchTransitionSnapshot failed = controller.snapshot();
        assertEquals(LaunchTransitionState.ERROR, failed.state);
        assertTrue(failed.overlayVisible);
        assertTrue(failed.manualRevealAvailable);
    }

    /** A new transition never inherits the "revealed before closing" of an earlier stop. */
    @Test
    public void beginForgetsThePresentationAnEarlierStopWouldRestore() {
        LaunchTransitionController controller = started(LaunchTransitionType.GAME_CONNECTION);
        transportReady(controller);
        controller.showStreamAnyway("transition-1");
        controller.revealCompleted("transition-1");
        assertTrue(controller.gameStopping("transition-1", HOST, GAME));

        controller.begin(spec(LaunchTransitionType.GAME_CONNECTION));
        controller.coverSettled("transition-1", PresentationBarrier.Outcome.SUBMITTED);
        assertFalse(controller.streamClosingFailed("transition-1", "close failed"));
        assertTrue(controller.snapshot().overlayVisible);
        assertTrue(controller.snapshot().inputBlocked);
    }

    @Test
    public void gameStoppingReportsWhetherItCoveredTheStop() {
        LaunchTransitionController controller = started(LaunchTransitionType.GAME_CONNECTION);
        transportReady(controller);
        assertFalse(controller.gameStopping("transition-1", HOST, "other-game"));
        assertFalse(controller.snapshot().state == LaunchTransitionState.GAME_STOPPING);
        assertTrue(controller.gameStopping("transition-1", HOST, GAME));
        assertEquals(LaunchTransitionState.GAME_STOPPING, controller.snapshot().state);
    }

    private static final class Clock {
        long nanos = 1_000L;
    }

    /** A GAME transition at its readiness edge (taken at 100 ns) with a live transport. */
    private static LaunchTransitionController atReadinessEdge(Clock clock,
                                                              boolean framesBeforeEdge) {
        LaunchTransitionController controller =
                new LaunchTransitionController(null, () -> clock.nanos);
        controller.begin(spec(LaunchTransitionType.GAME));
        controller.coverSettled("transition-1", PresentationBarrier.Outcome.SUBMITTED);
        controller.surfaceReady("transition-1");
        controller.inputPipelineReady("transition-1");
        controller.streamConnected("transition-1");
        controller.gatewayConnected("transition-1", HOST);
        long startEdge = controller.snapshot().frameEdge;
        if (framesBeforeEdge) {
            controller.videoFrameRendered("transition-1", startEdge, 1_050L);
            assertTrue(controller.snapshot().manualRevealAvailable);
        }
        clock.nanos = 2_000L;
        controller.targetWindowReady("transition-1", HOST, LaunchTransitionType.GAME, GAME);
        assertTrue(controller.snapshot().frameEdge != startEdge);
        assertFalse(controller.snapshot().revealAuthorized);
        assertTrue(controller.snapshot().awaitingVideoFrame);
        return controller;
    }

    /** A frame armed before the readiness edge but delivered after it shows the old picture. */
    @Test
    public void preEdgeFrameDeliveredAfterTheEdgeIsRejected() {
        Clock clock = new Clock();
        LaunchTransitionController controller = atReadinessEdge(clock, true);
        long edge = controller.snapshot().frameEdge;

        controller.videoFrameRendered("transition-1", edge - 1L, 2_500L);
        assertFalse("armed for the previous edge", controller.snapshot().revealAuthorized);
        controller.videoFrameRendered("transition-1", edge, 1_900L);
        assertFalse("rendered before the edge", controller.snapshot().revealAuthorized);
        assertTrue(controller.snapshot().awaitingVideoFrame);

        controller.videoFrameRendered("transition-1", edge, 2_100L);
        assertTrue(controller.snapshot().revealAuthorized);
    }

    @Test
    public void freshFrameAfterTheEdgeRevealsAutomatically() {
        Clock clock = new Clock();
        LaunchTransitionController controller = atReadinessEdge(clock, false);

        controller.videoFrameRendered("transition-1", controller.snapshot().frameEdge, 2_000L);

        LaunchTransitionSnapshot ready = controller.snapshot();
        assertTrue(ready.revealAuthorized);
        assertFalse(ready.awaitingVideoFrame);
        assertEquals(LaunchTransitionState.GAME_READY, ready.state);
    }

    /** The target never produces a fresh frame: past the deadline only "Reveal now" is offered. */
    @Test
    public void noFreshFrameWithinTheDeadlineStillOffersManualReveal() {
        Clock clock = new Clock();
        LaunchTransitionController controller = atReadinessEdge(clock, false);
        long edge = controller.snapshot().frameEdge;
        // The only frame was armed before the edge: it proves the transport, not the target.
        controller.videoFrameRendered("transition-1", edge - 1L, 2_500L);
        assertFalse(controller.snapshot().manualRevealAvailable);

        controller.freshFrameDeadline("transition-1", edge - 1L);
        assertFalse("a stale edge's deadline", controller.snapshot().manualRevealAvailable);
        controller.freshFrameDeadline("transition-1", edge);

        LaunchTransitionSnapshot overdue = controller.snapshot();
        assertTrue(overdue.manualRevealAvailable);
        assertFalse("no delayed automatic reveal", overdue.revealAuthorized);
        assertTrue(overdue.overlayVisible);
        controller.showStreamAnyway("transition-1");
        assertTrue(controller.snapshot().revealAuthorized);
    }

    /**
     * The kept neutral stream shown again from the Home (a GENERIC transition over the same
     * transport): a desktop that sends no new frame still gets "Reveal now" at the deadline,
     * never a cover with only Cancel. A new frame still reveals it automatically.
     */
    @Test
    public void keptNeutralStreamWithoutAFreshFrameOffersManualRevealAtTheDeadline() {
        Clock clock = new Clock();
        LaunchTransitionController controller =
                new LaunchTransitionController(null, () -> clock.nanos);
        controller.begin(spec(LaunchTransitionType.GAME_CONNECTION));
        controller.coverSettled("transition-1", PresentationBarrier.Outcome.SUBMITTED);
        controller.videoFrameRendered("transition-1", 0L, 1_050L);
        clock.nanos = 2_000L;

        LaunchTransitionSpec plain = new LaunchTransitionSpec("transition-2", HOST,
                LaunchTransitionType.GENERIC, 42, "", 2000L);
        controller.beginOnKeptTransport(plain);
        controller.coverSettled("transition-2", PresentationBarrier.Outcome.SUBMITTED);
        controller.surfaceReady("transition-2");
        controller.inputPipelineReady("transition-2");
        controller.streamConnected("transition-2");
        LaunchTransitionSnapshot waiting = controller.snapshot();
        assertTrue("a readiness edge: IDR and deadline", waiting.frameEdge > 0L);
        assertTrue(waiting.awaitingVideoFrame);
        assertFalse(waiting.manualRevealAvailable);

        controller.freshFrameDeadline("transition-2", waiting.frameEdge);
        assertTrue(controller.snapshot().manualRevealAvailable);
        assertTrue(controller.snapshot().overlayVisible);

        clock.nanos = 3_000L;
        controller.videoFrameRendered("transition-2", waiting.frameEdge, 2_500L);
        assertTrue(controller.snapshot().revealAuthorized);
    }

    /** A failure after the readiness edge never takes "Reveal now" from a rendering stream. */
    @Test
    public void timeoutAndErrorAfterTheEdgeKeepManualRevealWhileTheTransportHasFrames() {
        Clock clock = new Clock();
        LaunchTransitionController timedOut = atReadinessEdge(clock, true);
        timedOut.timedOut("transition-1", "no fresh frame");
        assertEquals(LaunchTransitionState.TIMED_OUT, timedOut.snapshot().state);
        assertTrue(timedOut.snapshot().manualRevealAvailable);

        LaunchTransitionController failed = atReadinessEdge(clock, true);
        failed.error("transition-1", "failed");
        assertEquals(LaunchTransitionState.ERROR, failed.snapshot().state);
        assertTrue(failed.snapshot().manualRevealAvailable);
        assertFalse(failed.snapshot().revealAuthorized);
    }

    /** After "Reveal now" the cover never returns within this transition, edge or not. */
    @Test
    public void manualRevealThenLostWindowNeverBringsTheCoverBack() {
        Clock clock = new Clock();
        LaunchTransitionController controller = atReadinessEdge(clock, true);
        controller.timedOut("transition-1", "no fresh frame");
        controller.showStreamAnyway("transition-1");
        controller.revealCompleted("transition-1");
        assertFalse(controller.snapshot().overlayVisible);

        controller.targetWindowLost("transition-1", HOST, LaunchTransitionType.GAME, GAME,
                "window lost");
        assertFalse(controller.snapshot().overlayVisible);
        assertFalse(controller.snapshot().inputBlocked);
        clock.nanos = 3_000L;
        controller.targetWindowReady("transition-1", HOST, LaunchTransitionType.GAME, GAME);
        assertFalse(controller.snapshot().overlayVisible);
        assertFalse("no frame armed for a revealed stream",
                controller.snapshot().awaitingVideoFrame);
        controller.freshFrameDeadline("transition-1", controller.snapshot().frameEdge);
        assertFalse(controller.snapshot().overlayVisible);
        assertFalse(controller.snapshot().inputBlocked);
    }

    /** Readiness that stays uncertain: the awaited frame is what offers "Reveal now". */
    @Test
    public void uncertainReadinessOffersManualRevealOnceTheAwaitedFrameRenders() {
        LaunchTransitionController controller = started(LaunchTransitionType.GAME_CONNECTION);
        controller.surfaceReady("transition-1");
        controller.inputPipelineReady("transition-1");
        controller.streamConnected("transition-1");
        controller.timedOut("transition-1", "no window");
        assertFalse(controller.snapshot().manualRevealAvailable);
        assertTrue(controller.snapshot().awaitingVideoFrame);

        controller.videoFrameRendered("transition-1", controller.snapshot().frameEdge, System.nanoTime());
        assertTrue(controller.snapshot().manualRevealAvailable);

        controller.closingStream("transition-1");
        assertFalse("closing never arms a frame", controller.snapshot().awaitingVideoFrame);
    }

    @Test public void settledCoverAuthorizesAPreparingTransitionEvenWithoutAFrame() {
        for (PresentationBarrier.Outcome outcome : new PresentationBarrier.Outcome[] {
                PresentationBarrier.Outcome.SUBMITTED, PresentationBarrier.Outcome.UNAVAILABLE}) {
            LaunchTransitionController controller = new LaunchTransitionController(null);
            controller.begin(spec(LaunchTransitionType.GAME));

            assertTrue(controller.coverSettled("transition-1", outcome));
            LaunchTransitionSnapshot snapshot = controller.snapshot();
            assertTrue(snapshot.operationAuthorized);
            assertEquals(LaunchTransitionState.CONNECTING_STREAM, snapshot.state);
            assertTrue("never a reveal", snapshot.overlayVisible);
            assertFalse(snapshot.revealAuthorized);
        }
    }

    @Test public void cancelledCoverOrCancelledTransitionRunsNothing() {
        LaunchTransitionController controller = new LaunchTransitionController(null);
        controller.begin(spec(LaunchTransitionType.GAME));
        assertFalse(controller.coverSettled("transition-1", PresentationBarrier.Outcome.CANCELLED));
        assertFalse(controller.snapshot().operationAuthorized);

        controller.cancel("transition-1");
        assertFalse("the user's Cancel wins over a frame",
                controller.coverSettled("transition-1", PresentationBarrier.Outcome.SUBMITTED));
        assertFalse(controller.snapshot().operationAuthorized);
        assertEquals(LaunchTransitionState.CANCELLED, controller.snapshot().state);
    }

    @Test public void settledCoverOfAClosingOrStoppingTransitionKeepsItsState() {
        LaunchTransitionController closing = new LaunchTransitionController(null);
        closing.begin(spec(LaunchTransitionType.GAME));
        closing.closingStream("transition-1");
        assertTrue(closing.coverSettled("transition-1", PresentationBarrier.Outcome.UNAVAILABLE));
        assertEquals(LaunchTransitionState.CLOSING_STREAM, closing.snapshot().state);
        assertFalse("a close never authorizes a start", closing.snapshot().operationAuthorized);

        LaunchTransitionController stopping = started(LaunchTransitionType.GAME);
        assertTrue(stopping.gameStopping("transition-1", HOST, GAME));
        assertTrue(stopping.coverSettled("transition-1", PresentationBarrier.Outcome.UNAVAILABLE));
        assertEquals(LaunchTransitionState.GAME_STOPPING, stopping.snapshot().state);
    }

    @Test public void settledCoverOfAnotherTransitionRunsNothing() {
        LaunchTransitionController controller = new LaunchTransitionController(null);
        controller.begin(spec(LaunchTransitionType.GAME));
        assertFalse("a stale transition's effect never runs",
                controller.coverSettled("other", PresentationBarrier.Outcome.SUBMITTED));
        assertFalse(controller.snapshot().operationAuthorized);
        assertEquals(LaunchTransitionState.PREPARING_SESSION, controller.snapshot().state);
    }

    /**
     * The first traversal creates the surface before that frame's commit settles the start
     * cover (TV 2026-10-15): the start is still authorized, for a fresh start and a warm-up
     * whose Home frame comes late.
     */
    @Test public void surfaceBeforeTheSettledCoverStillAuthorizesTheStart() {
        for (PresentationBarrier.Outcome outcome : new PresentationBarrier.Outcome[] {
                PresentationBarrier.Outcome.SUBMITTED, PresentationBarrier.Outcome.UNAVAILABLE}) {
            LaunchTransitionController controller = new LaunchTransitionController(null);
            controller.begin(spec(LaunchTransitionType.GAME));
            controller.surfaceReady("transition-1");
            controller.inputPipelineReady("transition-1");
            assertEquals(LaunchTransitionState.WAITING_FOR_VIDEO_SURFACE,
                    controller.snapshot().state);

            assertTrue(controller.coverSettled("transition-1", outcome));
            assertTrue(controller.snapshot().operationAuthorized);
            assertTrue("never a reveal", controller.snapshot().overlayVisible);
        }
    }

    @Test public void cancelledTransitionWithItsSurfaceReadyIsNeverAuthorized() {
        LaunchTransitionController controller = new LaunchTransitionController(null);
        controller.begin(spec(LaunchTransitionType.GAME));
        controller.surfaceReady("transition-1");
        controller.cancel("transition-1");
        assertFalse(controller.coverSettled("transition-1", PresentationBarrier.Outcome.SUBMITTED));
        assertFalse(controller.snapshot().operationAuthorized);
    }

    // ---- S09 C3: user covers (Home return, switch) and the stop flow -------------------------

    private static final LaunchTransitionController.UserCover HOME =
            LaunchTransitionController.UserCover.HOME_RETURN;
    private static final LaunchTransitionController.UserCover SWITCH =
            LaunchTransitionController.UserCover.SWITCH;

    /** A game start the host confirmed, revealed automatically. */
    private static LaunchTransitionController revealedGame() {
        LaunchTransitionController controller = started(LaunchTransitionType.GAME);
        transportReady(controller);
        controller.gatewayConnected("transition-1", HOST);
        controller.targetProcessRunning("transition-1", HOST, LaunchTransitionType.GAME, GAME);
        controller.targetWindowReady("transition-1", HOST, LaunchTransitionType.GAME, GAME);
        controller.videoFrameRendered("transition-1", controller.snapshot().frameEdge,
                System.nanoTime());
        controller.revealCompleted("transition-1");
        assertFalse(controller.snapshot().overlayVisible);
        return controller;
    }

    private static void freshFrame(LaunchTransitionController controller) {
        controller.videoFrameRendered("transition-1", controller.snapshot().frameEdge,
                System.nanoTime());
    }

    @Test public void homeReturnCoversARevealedStreamUntilAFreshFrameAfterTheReturn() {
        LaunchTransitionController controller = revealedGame();

        assertTrue(controller.coverForUser("transition-1", HOME));
        LaunchTransitionSnapshot covered = controller.snapshot();
        assertTrue(covered.overlayVisible);
        assertTrue("the stream gets no input on the way to the Home", covered.inputBlocked);
        assertFalse(covered.revealAuthorized);
        assertFalse(covered.manualRevealAvailable);
        assertEquals(HOME, covered.userCover);
        assertFalse("a second Back is the same Home return",
                controller.coverForUser("transition-1", HOME));

        controller.releaseUserCover("transition-1", HOME);
        long returnEdge = controller.snapshot().frameEdge;
        assertTrue("a parked decoder has no frame yet", controller.snapshot().overlayVisible);
        assertTrue(controller.snapshot().awaitingVideoFrame);
        controller.videoFrameRendered("transition-1", returnEdge - 1L, System.nanoTime());
        assertTrue("a frame from before the return proves nothing",
                controller.snapshot().overlayVisible);

        controller.videoFrameRendered("transition-1", returnEdge, System.nanoTime());
        LaunchTransitionSnapshot back = controller.snapshot();
        assertFalse(back.overlayVisible);
        assertFalse(back.inputBlocked);
        assertEquals(LaunchTransitionController.UserCover.NONE, back.userCover);
        assertEquals(LaunchTransitionState.GAME_RUNNING, back.state);
    }

    @Test public void releasedUserCoverNeverWaitsPastItsFrameDeadline() {
        LaunchTransitionController controller = revealedGame();
        controller.coverForUser("transition-1", HOME);
        controller.releaseUserCover("transition-1", HOME);

        controller.freshFrameDeadline("transition-1", controller.snapshot().frameEdge);

        assertFalse("cosmetic: never stuck for want of a frame",
                controller.snapshot().overlayVisible);
        assertFalse(controller.snapshot().inputBlocked);
    }

    @Test public void userCoverOverACoveredStartWithholdsItsRevealUntilReleased() {
        LaunchTransitionController controller = started(LaunchTransitionType.GAME);
        transportReady(controller);
        controller.coverForUser("transition-1", HOME);
        controller.gatewayConnected("transition-1", HOST);
        controller.targetProcessRunning("transition-1", HOST, LaunchTransitionType.GAME, GAME);
        controller.targetWindowReady("transition-1", HOST, LaunchTransitionType.GAME, GAME);
        freshFrame(controller);

        assertFalse("nothing reveals under the user cover",
                controller.snapshot().revealAuthorized);
        controller.revealCompleted("transition-1");
        assertTrue(controller.snapshot().overlayVisible);

        controller.releaseUserCover("transition-1", HOME);
        assertTrue("the start's own reveal follows", controller.snapshot().revealAuthorized);
        assertTrue(controller.snapshot().overlayVisible);
        controller.revealCompleted("transition-1");
        assertFalse(controller.snapshot().overlayVisible);
    }

    @Test public void onlyTheOperationsOwnCoverIsReleased() {
        LaunchTransitionController controller = revealedGame();
        controller.coverForUser("transition-1", HOME);
        assertTrue("a switch started from the Home takes over its cover",
                controller.coverForUser("transition-1", SWITCH));

        controller.releaseUserCover("transition-1", HOME);
        controller.freshFrameDeadline("transition-1", controller.snapshot().frameEdge);
        assertTrue("returning from the Home never uncovers a running switch",
                controller.snapshot().overlayVisible);
        assertEquals(SWITCH, controller.snapshot().userCover);
    }

    /** Review S09 C3: a Home return never replaces a running switch's cover. */
    @Test public void homeReturnNeverTakesOverASwitchCover() {
        LaunchTransitionController controller = revealedGame();
        assertTrue(controller.coverForUser("transition-1", SWITCH));
        assertFalse(controller.coverForUser("transition-1", HOME));
        assertEquals(SWITCH, controller.snapshot().userCover);

        // Once the switch released its cover, a Home return covers again.
        controller.releaseUserCover("transition-1", SWITCH);
        assertTrue(controller.coverForUser("transition-1", HOME));
        assertEquals(HOME, controller.snapshot().userCover);
    }

    @Test public void cancelEndsTheUserCover() {
        LaunchTransitionController controller = revealedGame();
        controller.coverForUser("transition-1", SWITCH);
        controller.cancel("transition-1");
        LaunchTransitionSnapshot cancelled = controller.snapshot();
        assertEquals(LaunchTransitionController.UserCover.NONE, cancelled.userCover);
        assertTrue("the cancel's own cover stays", cancelled.overlayVisible);
    }

    /**
     * Review S09 C3: a worker's publish is posted to the main thread, a main-thread publish
     * applies at once. The older posted snapshot (no user cover, revealed) must not land over
     * the newer switch cover and uncover the old game's stream.
     */
    @Test public void aStalePostedSnapshotNeverOverridesANewerOne() {
        List<Runnable> posted = new ArrayList<>();
        List<LaunchTransitionSnapshot> applied = new ArrayList<>();
        LaunchTransitionSnapshot.InOrder order = new LaunchTransitionSnapshot.InOrder();
        boolean[] onMain = {true};
        LaunchTransitionController controller = new LaunchTransitionController(snapshot -> {
            Runnable apply = () -> {
                if (order.admit(snapshot)) applied.add(snapshot);
            };
            if (onMain[0]) apply.run();
            else posted.add(apply);
        });
        controller.begin(spec(LaunchTransitionType.GAME));
        controller.coverSettled("transition-1", PresentationBarrier.Outcome.SUBMITTED);
        transportReady(controller);
        controller.gatewayConnected("transition-1", HOST);
        controller.targetProcessRunning("transition-1", HOST, LaunchTransitionType.GAME, GAME);
        controller.targetWindowReady("transition-1", HOST, LaunchTransitionType.GAME, GAME);
        freshFrame(controller);
        controller.revealCompleted("transition-1");

        onMain[0] = false;
        controller.inputPipelineReady("transition-1");   // a worker's publish, posted
        onMain[0] = true;
        controller.coverForUser("transition-1", SWITCH); // the main thread's, applied now
        for (Runnable run : posted) run.run();

        LaunchTransitionSnapshot last = applied.get(applied.size() - 1);
        assertEquals(SWITCH, last.userCover);
        assertTrue(last.overlayVisible);
        assertTrue(last.inputBlocked);
    }

    @Test public void switchCoverShowsTheNextGameNeverAClosingScreen() {
        LaunchTransitionController controller = revealedGame();
        assertTrue(controller.coverForUser("transition-1", SWITCH));
        assertFalse(controller.snapshot().closingPresentation());
        assertTrue(controller.snapshot().inputBlocked);

        // The previous game stopped: the kept neutral stream continues under the switch.
        controller.begin(new LaunchTransitionSpec("neutral", HOST,
                LaunchTransitionType.GAME_CONNECTION, 42, "", 1000L));
        assertEquals("a new transition starts without a user cover",
                LaunchTransitionController.UserCover.NONE, controller.snapshot().userCover);
        controller.returningToDashboard("neutral");
        assertTrue(controller.snapshot().closingPresentation());
        assertTrue(controller.coverForUser("neutral", SWITCH));
        assertFalse("no jump to the closing cover between A and B",
                controller.snapshot().closingPresentation());

        // The switch ended without a new game: the neutral stream's own closing cover shows.
        controller.releaseUserCover("neutral", SWITCH);
        assertTrue(controller.snapshot().overlayVisible);
        assertTrue(controller.snapshot().closingPresentation());
    }

    @Test public void cancelledSwitchGivesTheRevealedStreamBack() {
        LaunchTransitionController controller = revealedGame();
        controller.coverForUser("transition-1", SWITCH);
        controller.targetWindowLost("transition-1", HOST, LaunchTransitionType.GAME, GAME,
                "minimized");
        assertTrue(controller.snapshot().overlayVisible);

        controller.releaseUserCover("transition-1", SWITCH);
        freshFrame(controller);

        assertFalse(controller.snapshot().overlayVisible);
        assertFalse(controller.snapshot().inputBlocked);
        assertEquals("minimized", controller.snapshot().notice);
    }

    @Test public void userCancelWinsOverAUserCover() {
        LaunchTransitionController controller = started(LaunchTransitionType.GAME);
        controller.coverForUser("transition-1", HOME);

        controller.cancel("transition-1");

        assertEquals(LaunchTransitionState.CANCELLED, controller.snapshot().state);
        assertFalse(controller.coverForUser("transition-1", HOME));
        controller.releaseUserCover("transition-1", HOME);
        assertTrue("the cancelled transition stays covered", controller.snapshot().overlayVisible);
        assertFalse(controller.coverSettled("transition-1", PresentationBarrier.Outcome.SUBMITTED));
    }

    /** Variant A: after a reveal only a transition the user (or the game end) starts covers. */
    @Test public void afterARevealOnlyUserStartedTransitionsCoverAgain() {
        LaunchTransitionController controller = revealedGame();
        controller.targetWindowLost("transition-1", HOST, LaunchTransitionType.GAME, GAME, "lost");
        controller.timedOut("transition-1", "slow");
        controller.error("transition-1", "failed");
        controller.hostSessionLocked("transition-1", HOST, LaunchTransitionType.GAME, GAME,
                "locked");
        assertFalse(controller.snapshot().overlayVisible);
        assertFalse(controller.snapshot().inputBlocked);

        assertTrue(controller.coverForUser("transition-1", HOME));
        assertTrue(controller.snapshot().overlayVisible);
        controller.releaseUserCover("transition-1", HOME);
        freshFrame(controller);
        assertFalse(controller.snapshot().overlayVisible);

        assertTrue(controller.gameStopping("transition-1", HOST, GAME));
        assertTrue(controller.snapshot().overlayVisible);
        assertTrue(controller.snapshot().closingPresentation());
    }

    /** Stop flow: one closing cover takes over any user cover; a failed stop gives it back. */
    @Test public void gameStopTakesOverAUserCoverAndAFailedStopShowsTheStreamAgain() {
        LaunchTransitionController controller = revealedGame();
        controller.coverForUser("transition-1", HOME);

        assertTrue(controller.gameStopping("transition-1", HOST, GAME));
        LaunchTransitionSnapshot closing = controller.snapshot();
        assertEquals(LaunchTransitionController.UserCover.NONE, closing.userCover);
        assertEquals(LaunchTransitionState.GAME_STOPPING, closing.state);
        assertTrue(closing.closingPresentation());
        assertFalse(closing.awaitingVideoFrame);

        assertTrue(controller.streamClosingFailed("transition-1", "stop failed"));
        assertFalse("never stuck behind a failed stop", controller.snapshot().overlayVisible);
        assertEquals(LaunchTransitionState.GAME_RUNNING, controller.snapshot().state);
    }

    /** Decision 3: a child's time running out over a manual reveal may show the closing screen. */
    @Test public void childExpiryOverAManualRevealShowsTheClosingScreen() {
        LaunchTransitionController controller = started(LaunchTransitionType.GAME);
        transportReady(controller);
        controller.timedOut("transition-1", "slow");
        controller.showStreamAnyway("transition-1");
        controller.revealCompleted("transition-1");
        assertTrue(controller.snapshot().manuallyRevealed);
        assertFalse(controller.snapshot().overlayVisible);

        controller.closingStream("transition-1");

        assertTrue(controller.snapshot().overlayVisible);
        assertTrue(controller.snapshot().inputBlocked);
        assertTrue(controller.snapshot().closingPresentation());
        assertFalse("the parent's manual reveal is no way around the limit",
                controller.snapshot().manualRevealAvailable);
    }

    // ---- S09 review: released user covers and the warm-up takeover ---------------------------

    /** A released user cover over the revealed stream is a fact the table and the cover read. */
    @Test public void aReleasedUserCoverReportsItWaitsOnlyForItsFreshFrame() {
        LaunchTransitionController controller = revealedGame();
        controller.coverForUser("transition-1", HOME);
        assertFalse(controller.snapshot().userCoverReleased);

        controller.releaseUserCover("transition-1", HOME);
        LaunchTransitionSnapshot waiting = controller.snapshot();
        assertTrue(waiting.userCoverReleased);
        assertTrue(waiting.overlayVisible);

        freshFrame(controller);
        assertFalse(controller.snapshot().userCoverReleased);
        assertFalse(controller.snapshot().overlayVisible);
    }

    /**
     * TAKE_OVER_WARM_UP: the hidden warm-up never armed a frame, so its transport proved
     * nothing. Taken over, its showing is a readiness edge (IDR and deadline issued once), and
     * the first frame proves the transport: "Reveal now" for a game not confirmed ready yet.
     */
    @Test public void aTakenOverWarmUpStartsAReadinessEdgeAndItsFrameOffersReveal() {
        Clock clock = new Clock();
        LaunchTransitionController controller =
                new LaunchTransitionController(null, () -> clock.nanos);
        controller.begin(spec(LaunchTransitionType.GAME_CONNECTION));
        controller.coverSettled("transition-1", PresentationBarrier.Outcome.SUBMITTED);
        controller.surfaceReady("transition-1");
        controller.inputPipelineReady("transition-1");
        controller.streamConnected("transition-1");
        FrameArmingPolicy arming = new FrameArmingPolicy();
        assertEquals("hidden: nothing armed", null, arming.arm(controller.snapshot(), true));

        clock.nanos = 2_000L;
        controller.takenOver("transition-1");
        LaunchTransitionSnapshot shown = controller.snapshot();
        assertTrue(shown.frameEdge > 0L);
        assertTrue(shown.awaitingVideoFrame);
        FrameArmingPolicy.Arm arm = arming.arm(shown, false);
        assertTrue("an IDR and the fresh-frame deadline", arm.issueIdrAndDeadline);

        controller.videoFrameRendered("transition-1", shown.frameEdge, 2_100L);
        assertTrue(controller.snapshot().manualRevealAvailable);
        assertTrue(controller.snapshot().overlayVisible);
    }
}