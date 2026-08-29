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
        controller.overlayRendered("transition-1");
        return controller;
    }

    private static void transportReady(LaunchTransitionController controller) {
        controller.surfaceReady("transition-1");
        controller.inputPipelineReady("transition-1");
        controller.streamConnected("transition-1");
        controller.videoFrameRendered("transition-1");
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
        controller.videoFrameRendered("transition-1");
        assertTrue(controller.snapshot().revealAuthorized);
        assertEquals(LaunchTransitionState.PLAYNITE_FULLSCREEN_READY,
                controller.snapshot().state);
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
        controller.videoFrameRendered("transition-1");
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
        controller.videoFrameRendered("transition-1");
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
    public void lateReadinessAfterErrorCannotRevealStream() {
        LaunchTransitionController controller = started(LaunchTransitionType.GAME);
        controller.error("transition-1", "failed");
        controller.gatewayConnected("transition-1", HOST);
        controller.targetWindowReady("transition-1", HOST,
                LaunchTransitionType.GAME, GAME);
        transportReady(controller);

        assertEquals(LaunchTransitionState.ERROR, controller.snapshot().state);
        assertFalse(controller.snapshot().revealAuthorized);
        assertFalse(controller.snapshot().manualRevealAvailable);
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
        controller.timedOut("transition-1", "uncertain");
        controller.showStreamAnyway("transition-1");
        assertTrue(controller.snapshot().revealAuthorized);
    }

    @Test
    public void cancellationIgnoresLateReady() {
        LaunchTransitionController controller = started(LaunchTransitionType.GAME);
        controller.cancel("transition-1");
        controller.gatewayConnected("transition-1", HOST);
        controller.targetWindowReady("transition-1", HOST,
                LaunchTransitionType.GAME, GAME);
        transportReady(controller);
        assertEquals(LaunchTransitionState.CANCELLED, controller.snapshot().state);
        assertFalse(controller.snapshot().revealAuthorized);
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
        controller.overlayRendered("transition-1");
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
        controller.videoFrameRendered("transition-1");
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
        controller.overlayRendered("transition-1");
        transportReady(controller);
        controller.revealCompleted("transition-1");
        int afterFirst = snapshots.size();
        controller.revealCompleted("transition-1");
        assertEquals(afterFirst, snapshots.size());
    }

    @Test
    public void losingTheReadyGameWindowRecoversThePrivacyGate() {
        LaunchTransitionController controller = started(LaunchTransitionType.GAME);
        controller.gatewayConnected("transition-1", HOST);
        controller.targetWindowReady("transition-1", HOST,
                LaunchTransitionType.GAME, GAME);
        transportReady(controller);
        controller.revealCompleted("transition-1");
        assertFalse(controller.snapshot().overlayVisible);

        controller.targetWindowLost("transition-1", HOST,
                LaunchTransitionType.GAME, GAME, "window lost");

        assertTrue(controller.snapshot().overlayVisible);
        assertTrue(controller.snapshot().inputBlocked);
        assertFalse(controller.snapshot().revealAuthorized);
        assertEquals(LaunchTransitionState.GAME_WINDOW_STABILIZING,
                controller.snapshot().state);
    }

    @Test
    public void lostWindowRequiresFreshFrameBeforeManualReveal() {
        LaunchTransitionController controller = started(LaunchTransitionType.GAME);
        controller.gatewayConnected("transition-1", HOST);
        controller.targetWindowReady("transition-1", HOST,
                LaunchTransitionType.GAME, GAME);
        transportReady(controller);

        controller.targetWindowLost("transition-1", HOST,
                LaunchTransitionType.GAME, GAME, "Windows sign-in");

        assertFalse(controller.snapshot().revealAuthorized);
        assertFalse(controller.snapshot().manualRevealAvailable);
        controller.showStreamAnyway("transition-1");
        assertFalse(controller.snapshot().revealAuthorized);

        controller.videoFrameRendered("transition-1");
        assertTrue(controller.snapshot().manualRevealAvailable);
        assertFalse(controller.snapshot().revealAuthorized);
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
        controller.videoFrameRendered("transition-1");
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
        controller.videoFrameRendered("transition-1");
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
        controller.videoFrameRendered("transition-1");
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

        controller.videoFrameRendered("transition-1");
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
        controller.videoFrameRendered("transition-1");
        assertTrue(controller.snapshot().revealAuthorized);
    }

    @Test
    public void differentGameCannotReplaceAnActiveGameWithoutPlayniteReturn() {
        LaunchTransitionController controller = started(LaunchTransitionType.GAME);
        controller.targetStarting("transition-1", HOST,
                LaunchTransitionType.GAME, SECOND_GAME);
        assertEquals(LaunchTransitionState.CONNECTING_STREAM, controller.snapshot().state);
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
        controller.videoFrameRendered("transition-1");
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
}
