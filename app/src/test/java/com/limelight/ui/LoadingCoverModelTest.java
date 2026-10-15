package com.limelight.ui;

import com.limelight.console.transition.LaunchTransitionController;
import com.limelight.console.transition.LaunchTransitionSpec;
import com.limelight.console.transition.LaunchTransitionType;
import com.limelight.console.transition.PresentationBarrier;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

import static com.limelight.ui.LoadingCoverModel.Action.BACK;
import static com.limelight.ui.LoadingCoverModel.Action.CANCEL;
import static com.limelight.ui.LoadingCoverModel.Action.RETRY;
import static com.limelight.ui.LoadingCoverModel.Action.REVEAL;
import static com.limelight.ui.LoadingCoverModel.StepState.ATTENTION;
import static com.limelight.ui.LoadingCoverModel.StepState.CURRENT;
import static com.limelight.ui.LoadingCoverModel.StepState.DONE;
import static com.limelight.ui.LoadingCoverModel.StepState.PENDING;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The cover's one information card per state: kicker, title (and the game a switch leaves),
 * step segments, the real stage with elapsed time, the notice and the actions in display order.
 */
public class LoadingCoverModelTest {
    @Test public void startShowsKickerTitleCurrentSegmentStageElapsedAndCancel() {
        LoadingCoverModel model = new LoadingCoverModel("  Halo  ", 10_000L);
        assertTrue(model.step(3, "Starting the game", ""));

        assertEquals(LoadingCoverModel.Kicker.STARTING, model.kicker());
        assertEquals("Halo", model.title());
        assertEquals("", model.from());
        assertEquals("Starting the game", model.stage());
        assertEquals("", model.notice());
        assertEquals("1:05", model.elapsed(75_000L));
        assertEquals(Collections.singletonList(CANCEL), model.actions());
        assertEquals(LoadingCoverModel.Line.MESSAGE, model.line());
        assertTrue(model.stepsVisible());
        assertFalse(model.closing());
        assertEquals(DONE, model.stepState(1));
        assertEquals(CURRENT, model.stepState(2));
        assertEquals(PENDING, model.stepState(3));
        // A blank title keeps the game's; a later one replaces it (a switch's next game).
        assertFalse(model.setTitle(" "));
        assertTrue(model.setTitle("Doom"));
        assertEquals("Doom", model.title());
        // "Taking longer" is a notice under the stage, never part of it.
        model.step(3, "Starting the game", "Taking longer than usual");
        assertEquals("Starting the game", model.stage());
        assertEquals("Taking longer than usual", model.notice());
        assertEquals(LoadingCoverModel.Line.NOTICE, model.line());
        // Informational: the current segment keeps sweeping, never amber.
        assertEquals(CURRENT, model.stepState(2));
    }

    @Test public void elapsedTimeCountsFromTheTransitionAndRestartsWithANewCover() {
        LoadingCoverModel model = new LoadingCoverModel("Game", 0L);
        assertEquals("0:00", model.elapsed(-5L));
        assertEquals("0:59", model.elapsed(59_999L));
        assertEquals("1:00:05", model.elapsed(3_605_000L));
        model.restartClock(100_000L);
        assertEquals("0:02", model.elapsed(102_000L));
    }

    @Test public void revealAvailableStillStartsWithItsNoticeAndRevealFirst() {
        LoadingCoverModel model = new LoadingCoverModel("Resident Evil 3", 0L);
        model.step(4, "Waiting for the game window", "Game window is not fullscreen yet");
        assertTrue(model.manualReveal(true));

        assertEquals("not running before it is revealed",
                LoadingCoverModel.Kicker.STARTING, model.kicker());
        assertEquals("Waiting for the game window", model.stage());
        assertEquals("Game window is not fullscreen yet", model.notice());
        assertEquals(Arrays.asList(REVEAL, CANCEL), model.actions());
        assertEquals(DONE, model.stepState(2));
        assertEquals(CURRENT, model.stepState(3));
        assertEquals("the notice replaces the rotating message", LoadingCoverModel.Line.NOTICE, model.line());
        assertTrue(model.manualReveal(false));
        assertEquals(LoadingCoverModel.Kicker.STARTING, model.kicker());
    }

    @Test public void onlyARevealedGameShowsGameRunning() {
        LaunchTransitionController controller = new LaunchTransitionController(null);
        controller.begin(spec("t", LaunchTransitionType.GAME));
        controller.coverSettled("t", PresentationBarrier.Outcome.SUBMITTED);
        controller.surfaceReady("t");
        controller.inputPipelineReady("t");
        controller.streamConnected("t");
        controller.videoFrameRendered("t", controller.snapshot().frameEdge, System.nanoTime());
        LoadingCoverModel model = new LoadingCoverModel("Game", 0L);
        model.follow(controller.snapshot());
        assertEquals(LoadingCoverModel.Kicker.STARTING, model.kicker());

        controller.showStreamAnyway("t");
        controller.revealCompleted("t");
        controller.coverForUser("t", LaunchTransitionController.UserCover.HOME_RETURN);
        assertTrue(model.follow(controller.snapshot()));
        assertEquals(LoadingCoverModel.Kicker.RUNNING, model.kicker());
    }

    @Test public void switchShowsTheGameItLeavesAboveTheNextOne() {
        LoadingCoverModel model = new LoadingCoverModel("Resident Evil 3", 0L);
        model.setTitle("FEZ");
        assertTrue(model.setFrom(" Resident Evil 3 "));
        assertFalse(model.setFrom("Resident Evil 3"));
        model.step(1, "Preparing", "");

        assertEquals(LoadingCoverModel.Kicker.SWITCHING, model.kicker());
        assertEquals("FEZ", model.title());
        assertEquals("Resident Evil 3", model.from());
        assertEquals(Collections.singletonList(CANCEL), model.actions());
        assertEquals(LoadingCoverModel.Line.MESSAGE, model.line());
        // The switch ends: its cover gives the title back without "from".
        assertTrue(model.setFrom(""));
        assertEquals(LoadingCoverModel.Kicker.STARTING, model.kicker());
    }

    @Test public void desktopStartsLikeAGame() {
        LoadingCoverModel model = new LoadingCoverModel("Desktop", 0L);
        model.step(2, "Connecting the stream", "");
        assertEquals(LoadingCoverModel.Kicker.STARTING, model.kicker());
        assertEquals("Desktop", model.title());
        assertEquals(CURRENT, model.stepState(1));
        assertEquals(Collections.singletonList(CANCEL), model.actions());
        assertEquals(LoadingCoverModel.Line.MESSAGE, model.line());
    }

    @Test public void failureShowsItsReasonAsTheNoticeWithRetryFirst() {
        LoadingCoverModel model = new LoadingCoverModel("Game", 0L);
        model.step(3, "Starting the game", "");
        assertTrue(model.manualReveal(true));
        assertEquals(Arrays.asList(REVEAL, CANCEL), model.actions());

        assertTrue(model.error("Game failed", "No window in 90 s", "", false, true));
        assertEquals(LoadingCoverModel.Kicker.FAILED, model.kicker());
        assertEquals(Arrays.asList(RETRY, CANCEL), model.actions());
        assertEquals("Game failed", model.stage());
        assertEquals("No window in 90 s", model.notice());
        assertEquals(LoadingCoverModel.Line.NOTICE, model.line());
        assertEquals(ATTENTION, model.stepState(2));
        // The failure's offer stands until the next snapshot says otherwise.
        assertFalse(model.manualReveal(true));
        assertEquals(Arrays.asList(RETRY, CANCEL), model.actions());

        model.error("Game failed", "No window", "E42", true, true);
        assertEquals(Arrays.asList(RETRY, REVEAL, CANCEL), model.actions());
        assertEquals("E42", model.reasonCode());
        // A failure during a switch says so, not "Switching".
        model.setFrom("Doom");
        assertEquals(LoadingCoverModel.Kicker.FAILED, model.kicker());
    }

    @Test public void retryIsTakenOnceUntilAFailureOffersItAgain() {
        LoadingCoverModel model = new LoadingCoverModel("Game", 0L);
        model.error("Failed", "", "", false, true);
        assertTrue(model.takeRetry());
        assertEquals(Collections.singletonList(CANCEL), model.actions());
        assertFalse(model.takeRetry());
        model.error("Failed", "", "", false, true);
        assertTrue(model.takeRetry());
    }

    @Test public void launcherPromptAndLockedSessionOfferRevealAndBackOnly() {
        LoadingCoverModel launcher = new LoadingCoverModel("Game", 0L);
        assertTrue(launcher.launcher("Waiting for the launcher", "Confirm on the PC", true));
        assertEquals(Arrays.asList(REVEAL, BACK), launcher.actions());
        assertEquals("Waiting for the launcher", launcher.stage());
        assertEquals("Confirm on the PC", launcher.notice());
        assertEquals(LoadingCoverModel.Kicker.STARTING, launcher.kicker());

        LoadingCoverModel locked = new LoadingCoverModel("Game", 0L);
        assertTrue(locked.warning("Session locked", "Unlock the PC", false));
        assertEquals(Collections.singletonList(BACK), locked.actions());
        assertEquals(LoadingCoverModel.Phase.WARNING, locked.phase());
        assertEquals(LoadingCoverModel.Kicker.STARTING, locked.kicker());
    }

    @Test public void aConnectionStageNeverOverwritesAFailure() {
        LoadingCoverModel model = new LoadingCoverModel("Game", 0L);
        assertTrue(model.connectionStage("Connecting the stream", 3));
        assertEquals(3, model.step());
        model.error("Failed", "Details", "", false, true);
        assertFalse(model.connectionStage("Connecting the stream", 3));
        assertEquals("Failed", model.stage());
        // The next step after a failure loads again.
        assertTrue(model.step(4, "Verifying", ""));
        assertEquals(Collections.singletonList(CANCEL), model.actions());
        assertEquals("", model.notice());
    }

    @Test public void cancellingAndClosingOfferNoActionAndClosingIsTheQuietCard() {
        LoadingCoverModel cancelling = new LoadingCoverModel("Game", 0L);
        cancelling.manualReveal(true);
        cancelling.cancelling("Cancelling…");
        assertTrue(cancelling.actions().isEmpty());
        assertTrue(cancelling.stepsVisible());
        assertEquals(CURRENT, cancelling.stepState(0));

        LoadingCoverModel closing = new LoadingCoverModel("Game", 0L);
        closing.step(3, "Starting", "Taking longer");
        closing.closing("Ending the game…");
        assertEquals(LoadingCoverModel.Kicker.CLOSING, closing.kicker());
        assertTrue(closing.closing());
        assertTrue(closing.actions().isEmpty());
        assertFalse(closing.stepsVisible());
        assertEquals(LoadingCoverModel.Line.NONE, closing.line());
        assertEquals("Game", closing.title());
        assertEquals("Ending the game…", closing.stage());
        assertEquals("", closing.notice());
        // The closing screen stays passive: steps and failures wait until it gives way.
        assertFalse(closing.step(2, "Connecting", ""));
        assertFalse(closing.error("Failed", "", "", true, true));
        assertFalse(closing.manualReveal(true));
        closing.leaveClosing();
        assertTrue(closing.error("Failed", "", "", true, true));
        assertEquals(Arrays.asList(RETRY, REVEAL, CANCEL), closing.actions());
    }
    /**
     * The message and the notice share one line, the actions keep their slot: every stepped
     * state shows a notice or a message, never an empty line. The closing card has none.
     */
    @Test public void everySteppedStateShowsANoticeOrAMessageAndKeepsTheActionSlot() {
        LoadingCoverModel loading = new LoadingCoverModel("Game", 0L);
        loading.step(2, "Connecting", "");
        LoadingCoverModel notice = new LoadingCoverModel("Game", 0L);
        notice.step(3, "Starting", "Taking longer");
        LoadingCoverModel reveal = new LoadingCoverModel("Game", 0L);
        reveal.step(4, "Waiting", "");
        reveal.manualReveal(true);
        LoadingCoverModel failed = new LoadingCoverModel("Game", 0L);
        failed.error("Failed", "", "E42", false, true);
        LoadingCoverModel launcher = new LoadingCoverModel("Game", 0L);
        launcher.launcher("Launcher", "Confirm", true);
        LoadingCoverModel warning = new LoadingCoverModel("Game", 0L);
        warning.warning("Locked", "", false);
        LoadingCoverModel cancelling = new LoadingCoverModel("Game", 0L);
        cancelling.cancelling("Cancelling");
        for (LoadingCoverModel model : Arrays.asList(
                loading, notice, reveal, failed, launcher, warning, cancelling)) {
            assertTrue(model.stepsVisible());
            assertTrue(model.line() != LoadingCoverModel.Line.NONE);
            assertTrue(model.actionsSlot() != LoadingCoverModel.Slot.NONE);
        }
        assertEquals(LoadingCoverModel.Line.MESSAGE, loading.line());
        assertEquals(LoadingCoverModel.Line.NOTICE, notice.line());
        assertEquals("reveal available keeps the messages", LoadingCoverModel.Line.MESSAGE,
                reveal.line());
        assertEquals(LoadingCoverModel.Line.NOTICE, failed.line());
        assertEquals(LoadingCoverModel.Line.NOTICE, launcher.line());
        assertEquals(LoadingCoverModel.Line.MESSAGE, warning.line());
        assertEquals(LoadingCoverModel.Line.MESSAGE, cancelling.line());
        assertEquals(LoadingCoverModel.Slot.RESERVED, cancelling.actionsSlot());
        // Amber and static only for a failure; a taking-longer notice keeps the sweep.
        assertEquals(CURRENT, notice.stepState(2));
        assertEquals(ATTENTION, failed.stepState(0));
        assertEquals(ATTENTION, launcher.stepState(0));

        LoadingCoverModel closing = new LoadingCoverModel("Game", 0L);
        closing.closing("Ending");
        assertEquals(LoadingCoverModel.Line.NONE, closing.line());
        assertEquals(LoadingCoverModel.Slot.NONE, closing.actionsSlot());
    }

    private static final String HOST = "host-1";

    private static LaunchTransitionSpec spec(String id, LaunchTransitionType type) {
        return new LaunchTransitionSpec(id, HOST, type, 42, "", 1000L);
    }

    /**
     * The TV dead end: the kept neutral stream settled to the dashboard (its closing screen),
     * then the user's return from the Home began a plain transition on it. The layer follows
     * that snapshot: steps, Cancel and "Reveal now" are back, never a permanent closing screen.
     */
    @Test public void aKeptStreamTransitionAfterItsClosingScreenGetsItsActionsBack() {
        LaunchTransitionController controller = new LaunchTransitionController(null);
        LaunchTransitionSpec neutral = spec("neutral", LaunchTransitionType.GAME_CONNECTION);
        controller.beginOnKeptTransport(neutral);
        controller.returningToDashboard("neutral");
        LoadingCoverModel model = new LoadingCoverModel("Game", 0L);
        assertTrue(controller.snapshot().closingPresentation());
        model.closing("Ending the game…");
        assertFalse("a settling kept stream stays its closing screen",
                model.follow(controller.snapshot()));

        controller.beginOnKeptTransport(spec("plain", LaunchTransitionType.GENERIC));
        model.follow(controller.snapshot());

        assertEquals(LoadingCoverModel.Phase.LOADING, model.phase());
        assertTrue(model.step(2, "Connecting", ""));
        assertEquals(Collections.singletonList(CANCEL), model.actions());
        assertTrue(model.manualReveal(true));
        assertEquals(Arrays.asList(REVEAL, CANCEL), model.actions());
    }

    /**
     * On the way to the Home and while a released user cover waits for the revealed stream's
     * fresh frame, the cover offers no Cancel: nothing there is a launch to cancel.
     */
    @Test public void aHomeReturnOrReleasedUserCoverOffersNoCancel() {
        LaunchTransitionController controller = new LaunchTransitionController(null);
        controller.begin(spec("t", LaunchTransitionType.GENERIC));
        controller.coverSettled("t", PresentationBarrier.Outcome.SUBMITTED);
        controller.surfaceReady("t");
        controller.inputPipelineReady("t");
        controller.streamConnected("t");
        controller.videoFrameRendered("t", controller.snapshot().frameEdge, System.nanoTime());
        controller.revealCompleted("t");
        LoadingCoverModel model = new LoadingCoverModel("Game", 0L);

        controller.coverForUser("t", LaunchTransitionController.UserCover.HOME_RETURN);
        assertTrue(model.follow(controller.snapshot()));
        assertTrue(model.actions().isEmpty());

        controller.releaseUserCover("t", LaunchTransitionController.UserCover.HOME_RETURN);
        model.follow(controller.snapshot());
        assertTrue(controller.snapshot().overlayVisible);
        assertTrue(model.actions().isEmpty());

        // A launch cover of its own offers Cancel again.
        controller.begin(spec("next", LaunchTransitionType.GAME));
        assertTrue(model.follow(controller.snapshot()));
        assertEquals(Collections.singletonList(CANCEL), model.actions());
    }
}