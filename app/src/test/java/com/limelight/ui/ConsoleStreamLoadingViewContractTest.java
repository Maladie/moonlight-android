package com.limelight.ui;

import com.limelight.SourceText;
import org.junit.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Wiring only: what the cover shows per state is {@link LoadingCoverModelTest}, its artwork
 * {@link FencedArtworkTest}. These guard that the view and its callers use them.
 */
public class ConsoleStreamLoadingViewContractTest {
    /**
     * One card in every background and state: built once, never moved between parents; each
     * state change redraws it from the model (what it shows: LoadingCoverModelTest).
     */
    @Test public void cardStaysInPlaceAndEveryStateRendersFromTheModel() throws IOException {
        String text = source();
        assertTrue(text.indexOf("addView(card, cardParams);")
                == text.lastIndexOf("addView(card, cardParams);"));
        assertFalse(text.contains("removeView("));
        String background = text.substring(text.indexOf("private void applyBackground()"),
                text.indexOf("private void showArtwork("));
        assertFalse(background.contains("stageView") || background.contains("titleView"));
        for (String method : new String[]{"public void setStep(int step, String status, String notice, int percent)",
                "private void showFailure(", "public void showLauncherInteraction(",
                "public void showWarning(", "public void showCancelling()",
                "public void showClosing(", "public void setManualRevealAvailable(",
                "public void setFrom("}) {
            String body = text.substring(text.indexOf(method));
            body = body.substring(0, body.indexOf("\n    }\n"));
            assertTrue(method, body.contains("render();"));
        }
    }

    /**
     * No state change moves the card: title, stage and the shared message/notice line are one
     * ellipsized line each, the shared line a fixed LINE_COUNT-line area whatever it shows.
     */
    @Test public void textLinesKeepOneFixedLineInEveryState() throws IOException {
        String text = source();
        for (String view : new String[]{"titleView", "stageView"}) {
            assertTrue(view, text.contains(view + ".setSingleLine(true);"));
            assertTrue(view, text.contains(view + ".setEllipsize(TextUtils.TruncateAt.END);"));
            assertFalse(view, text.contains(view + ".setMaxLines("));
            assertFalse(view, text.contains(view + ".setSingleLine(false"));
        }
        assertTrue(text.contains("lineView.setLines(LINE_COUNT);"));
        assertTrue(text.contains("lineView.setEllipsize(TextUtils.TruncateAt.END);"));
        assertFalse(text.contains("lineView.setSingleLine("));
        // TV 2026-10-15: the standard backdrop never shows for a frame before the artwork.
        assertTrue(text.contains("neutral || art || artwork.pending() ? GONE : VISIBLE"));
        assertFalse(text.contains("append('\\n')"));
    }

    /** A switch's cover names the game it leaves, and only while that switch covers. */
    @Test public void switchCoverNamesTheGameItLeaves() throws IOException {
        String game = gameSource();
        String present = game.substring(game.indexOf("private void presentSwitchCover("),
                game.indexOf("private void endSwitchCover("));
        String end = game.substring(game.indexOf("private void endSwitchCover("));
        end = end.substring(0, end.indexOf("\n    }\n"));
        assertTrue(present.contains("consoleLoadingView.setFrom("));
        assertTrue(end.contains("consoleLoadingView.setFrom(\"\");"));
    }
    /** Neither console nor stream screen decodes on the UI thread: both go through the request. */
    @Test public void artworkIsRequestedThroughTheFencedRequest() throws IOException {
        String text = source();
        assertFalse(text.contains("decodeSplashArtwork"));
        assertTrue(text.contains("artwork.request(artworkPath);"));
        String game = gameSource();
        assertTrue(game.contains(
                "consoleLoadingView.setSplashArtwork(operation.request.artworkPath);"));
        String console = SourceText.read(Paths.get(
                "src/main/java/com/limelight/console/ConsoleActivity.java"));
        assertTrue(console.contains("streamLoadingView.setSplashArtwork(loadingArtworkPath);"));
        assertTrue(console.contains("reducedMotion, streamLoadingEpoch);"));
        assertTrue(game.contains("getIntent().getBooleanExtra(EXTRA_CONSOLE_REDUCED_MOTION, false),\n"
                + "                    launchStartedAtMillis);"));
        assertTrue(game.contains("new ConsoleStreamLoadingView(\n                    this,\n"
                + "                    appDisplayName(getIntent().getStringExtra(EXTRA_APP_NAME)),"));
        assertTrue(console.contains("new ConsoleStreamLoadingView(this,\n"
                + "                appDisplayName(appName), null,"));
    }

    @Test public void gameEndBypassesLaunchStepsAndRevealActions() throws IOException {
        String game = gameSource();
        String apply = game.substring(game.indexOf("private void applyTransitionSnapshot"),
                game.indexOf("static boolean shouldShowTransitionOverlay"));

        // Which states are the passive closing screen is the controller snapshot's decision.
        // A switch's cover shows the next game first; never the closing of the previous one.
        // The layer follows each snapshot first (LoadingCoverModel.follow): a closing screen
        // left by the transition before never blocks what this one allows.
        SourceText.assertOrdered(apply, "consoleLoadingView.followTransition(snapshot);",
                "if (switchCover) {",
                "consoleLoadingView.setStep(1, getString(R.string.transition_preparing_session));",
                "} else if (snapshot.closingPresentation()) {", "consoleLoadingView.showClosing(",
                "consoleLoadingView.setStep(snapshot.step, transitionStatus(snapshot),");

        assertTrue(apply.contains("R.string.playnite_ending_game"));
        assertTrue(apply.contains("R.string.transition_waiting_playnite_return"));
        assertTrue(apply.contains("LaunchTransitionState.HOST_SESSION_LOCKED"));
        assertTrue(apply.contains("consoleLoadingView.showWarning("));
        // A failure after that closing screen brings the cover's actions back.
        String failure = apply.substring(apply.indexOf(
                "snapshot.state == LaunchTransitionState.ERROR"));
        assertTrue(failure.indexOf("consoleLoadingView.showFullTransitionAppearance()")
                < failure.indexOf("consoleLoadingView.showError(getString(title)"));
    }

    @Test public void retryIsConsumedBeforeStartingAnotherDecoderGeneration()
            throws IOException {
        String text = source();
        String retry = text.substring(text.indexOf("private void requestRetryOnce()"),
                text.indexOf("public void showNeutralWarmUpAppearance()"));
        assertTrue(text.contains("retryView.setOnClickListener(view -> requestRetryOnce())"));
        SourceText.assertOrdered(retry, "!model.takeRetry()", "render();", "actions.onRetry()");

        String game = gameSource();
        String gameRetry = game.substring(
                game.indexOf("private SessionOrchestrator.State transitionState()"),
                game.indexOf("private void retryGameStart()"));
        assertTrue(gameRetry.contains("transitionSpec.id.equals(retryTransitionId)"));
        assertTrue(gameRetry.indexOf("transitionSpec.id.equals(retryTransitionId)")
                < gameRetry.indexOf("stopConnection("));
        assertTrue(gameRetry.contains("retryTransitionId = transitionSpec.id"));
        assertTrue(gameRetry.contains("if (!retryStillCurrent(retryId)) return;"));
    }

    @Test public void staleFadeCompletionIsRejectedAfterRecovery() throws IOException {
        String text = source();
        String reveal = text.substring(text.indexOf("public void revealStream(String fence, Runnable"),
                text.indexOf("public void showOpaque()"));
        String opaque = text.substring(text.indexOf("public void showOpaque()"),
                text.indexOf("public void stopAndHide()"));

        assertTrue(reveal.contains("generation != revealGeneration"));
        assertTrue(opaque.contains("revealGeneration++"));
        assertTrue(opaque.contains("!revealRequested && getAlpha() == 1f"));
        // A new presentation counts its time from its own start.
        assertTrue(opaque.contains("model.newPresentation(SystemClock.elapsedRealtime());"));
        // A cancelled reveal never blocks a later one ("Reveal now") until showOpaque.
        SourceText.assertOrdered(reveal,
                "if (!LaunchTransitionController.coverAllowsEffect(outcome)) {",
                "revealRequested = false;", "return;", "afterReveal.run()");
    }

    @Test public void gamepadHatMotionNavigatesActionsOncePerDirection() throws IOException {
        String text = source();
        String method = text.substring(text.indexOf("public boolean handleControllerMotion"),
                text.indexOf("public void waitingForVideo"));

        assertTrue(method.contains("MotionEvent.AXIS_HAT_X"));
        assertTrue(method.contains("MotionEvent.AXIS_HAT_Y"));
        assertTrue(method.contains("direction == activeMotionDirection"));
        assertTrue(method.contains("handleControllerKey(new KeyEvent"));
    }

    @Test public void coverTakenOverFromAnotherWindowStartsAsThatOneLooks() throws IOException {
        String text = source();
        // The message shown by the other window stays; its cached artwork is there at once.
        assertTrue(text.contains(
                "handedOver = initialMessage != null && !initialMessage.trim().isEmpty();"));
        assertTrue(text.contains("currentMessage = handedOver ? initialMessage"));
        String show = text.substring(text.indexOf("private void showArtwork("),
                text.indexOf("private static int progressForStage("));
        assertTrue(show.contains("if (handedOver || !isAttachedToWindow() || reducedMotion"));
        // Full-bleed artwork under one shade: never a dimmed band behind the text.
        assertTrue(text.contains("addView(artworkView, match());"));
        assertFalse(text.contains("/ 3.1f"));
        assertFalse(text.contains("recycle()"));
        assertTrue(gameSource().contains(
                "getIntent().getStringExtra(EXTRA_CONSOLE_LOADING_MESSAGE),\n"));
    }

    @Test public void seeThroughCoverDrawsNoBackground() throws IOException {
        String text = source();
        assertTrue(text.contains("public void setSeeThrough(boolean seeThrough) {\n"
                + "        setBackgroundColor(seeThrough ? Color.TRANSPARENT : Color.BLACK);"));
    }

    /**
     * TV 2026-10-16: a Play on the Home over a hidden warm-up showed the time since boot (the
     * warm-up's cover had epoch 0 and the switch kept it). One time base (elapsedRealtime) and
     * one start: the console stamps the user's action; every handoff carries that value.
     */
    @Test public void coverTimeCountsFromTheUsersActionOnOneTimeBase() throws IOException {
        String view = source();
        String game = gameSource();
        String console = SourceText.main("console/ConsoleActivity.java");
        String resume = SourceText.main("session/SessionResumeManager.java");
        String coordinator = SourceText.main("console/ConsoleStreamTransitionCoordinator.java");
        assertFalse(view.contains("uptimeMillis"));
        assertFalse(resume.contains("uptimeMillis"));
        assertTrue(coordinator.contains("android.os.SystemClock::elapsedRealtime,"));
        assertFalse(console.contains("streamLoadingEpoch = SystemClock.uptimeMillis()"));
        assertTrue(console.contains("streamLoadingEpoch = SystemClock.elapsedRealtime();"));
        assertFalse(game.contains("EXTRA_CONSOLE_LOADING_EPOCH, SystemClock.uptimeMillis()"));
        assertFalse(game.contains("createTransitionCoordinator(spec, SystemClock.uptimeMillis())"));
        // A warm-up no one asked for starts at its launch, never at a stale or absent Play.
        assertTrue(console.contains("autoWarmUp && pendingWarmUpGame == null\n"
                + "                        ? SystemClock.elapsedRealtime() : streamLoadingEpoch"));
        // A switch from the Home: in-process and by the attach intent, the Play's start goes.
        assertTrue(console.contains("\"switch\"), streamLoadingEpoch, 0L,"));
        assertTrue(console.contains(".putExtra(Game.EXTRA_CONSOLE_LOADING_EPOCH, streamLoadingEpoch);"));
        assertTrue(SourceText.main("PendingRetainedSwitch.java").contains(
                "intent.getLongExtra(Game.EXTRA_CONSOLE_LOADING_EPOCH, 0L)"));
        assertTrue(game.contains("pending.coverStartedAtMillis,\n"));
        // The switch cover takes it after the controller covered (a re-shown cover restarts).
        int presentAt = game.indexOf("private void presentSwitchCover(");
        String present = game.substring(presentAt, game.indexOf("/**", presentAt));
        SourceText.assertOrdered(present, "UserCover.SWITCH);",
                "consoleLoadingView.setStartedAt(operation.request.coverStartedAtMillis);");
        String pending = game.substring(game.indexOf("private void coverPendingSwitch("),
                game.indexOf("private void releasePendingSwitchCover()"));
        SourceText.assertOrdered(pending, "UserCover.SWITCH);",
                "consoleLoadingView.setStartedAt(pending.coverStartedAtMillis);");
    }

    /** The stage line has one writer while a transition owns the cover (TV 2026-10-16 blink). */
    @Test public void transitionCoverStageIsTheControllersOnly() throws IOException {
        String game = gameSource();
        String stage = game.substring(game.indexOf("public void stageStarting(final String stage)"),
                game.indexOf("public void stageComplete("));
        assertTrue(stage.contains("if (consoleLoadingView != null && transitionController == null) {"
                + "\n                    consoleLoadingView.setStage(stage);"));
    }

    private static String source() throws IOException {
        Path source = Paths.get("src/main/java/com/limelight/ui/ConsoleStreamLoadingView.java");
        return SourceText.read(source);
    }

    private static String gameSource() throws IOException {
        Path source = Paths.get("src/main/java/com/limelight/Game.java");
        return SourceText.read(source);
    }
}
