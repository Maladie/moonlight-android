package com.limelight.ui;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ConsoleStreamLoadingViewContractTest {
    @Test public void launcherFailureOffersOnlyRevealAndBack() throws IOException {
        Path source = Paths.get("src/main/java/com/limelight/ui/ConsoleStreamLoadingView.java");
        if (!Files.exists(source)) {
            source = Paths.get("app/src/main/java/com/limelight/ui/ConsoleStreamLoadingView.java");
        }
        String text = new String(Files.readAllBytes(source), StandardCharsets.UTF_8).replace("\r\n", "\n");
        String method = text.substring(text.indexOf("public void showLauncherInteraction"),
                text.indexOf("public void setManualRevealAvailable"));

        assertTrue(method.contains("retryView.setVisibility(GONE)"));
        assertTrue(method.contains("showAnywayView.setVisibility(allowReveal ? VISIBLE : GONE)"));
        assertTrue(method.contains("cancelView.setText(R.string.transition_back)"));
        assertFalse(method.contains("transition_retry"));
    }

    @Test public void cancellingShowsSpinnerAndNoActions() throws IOException {
        String text = source();
        String method = text.substring(text.indexOf("public void showCancelling()"),
                text.indexOf("public void setManualRevealAvailable"));

        assertTrue(method.contains("transition_cancelling"));
        assertTrue(method.contains("activityView.setVisibility(VISIBLE)"));
        assertTrue(method.contains("retryView.setVisibility(GONE)"));
        assertTrue(method.contains("showAnywayView.setVisibility(GONE)"));
        assertTrue(method.contains("actionsRow.setVisibility(GONE)"));
    }

    @Test public void endedGameShowsPassiveClosingScreen() throws IOException {
        String text = source();
        String method = text.substring(text.indexOf("public void showClosing("),
                text.indexOf("public void setManualRevealAvailable"));

        assertTrue(method.contains("handler.removeCallbacks(rotateMessage)"));
        assertTrue(method.contains("messageView.setText(title)"));
        assertTrue(method.contains("statusView.setText(status)"));
        assertTrue(method.contains("stepsView.setVisibility(GONE)"));
        assertTrue(method.contains("showAnywayView.setVisibility(GONE)"));
        assertTrue(method.contains("actionsRow.setVisibility(GONE)"));
        assertTrue(method.contains("showSplashArtworkPresentation(true)"));
        assertFalse(method.contains("requestDefaultActionFocus"));
        assertTrue(text.contains("if (stopped || error || closingPresentation) return"));

        String setStage = text.substring(text.indexOf("public void setStage("),
                text.indexOf("public void setStep("));
        String setStep = text.substring(text.indexOf("public void setStep("),
                text.indexOf("public String getCurrentMessage("));
        String showError = text.substring(text.indexOf("public void showError(String title, String details)"),
                text.indexOf("public void showLauncherInteraction("));
        String launcherInteraction = text.substring(text.indexOf(
                        "public void showLauncherInteraction("),
                text.indexOf("public void showCancelling("));
        String reveal = text.substring(text.indexOf("public void revealStream(Runnable"),
                text.indexOf("public void showOpaque("));
        assertTrue(setStage.contains("if (stopped || closingPresentation) return"));
        assertTrue(setStep.contains("if (stopped || closingPresentation) return"));
        assertFalse(setStep.contains("closingPresentation = false"));
        assertTrue(showError.contains("if (stopped || closingPresentation) return"));
        assertTrue(launcherInteraction.contains("if (stopped || closingPresentation) return"));
        assertTrue(reveal.contains("stopped || revealRequested || closingPresentation"));

        String beginNextLaunch = text.substring(text.indexOf(
                        "public void showFullTransitionAppearance("),
                text.indexOf("public void setSplashArtwork("));
        assertTrue(beginNextLaunch.contains("closingPresentation = false"));
        assertTrue(beginNextLaunch.contains("stepsView.setVisibility(VISIBLE)"));

        String keys = text.substring(text.indexOf("public boolean handleControllerKey"),
                text.indexOf("public boolean handleControllerMotion"));
        assertTrue(keys.contains(
                "if (actionsRow.getVisibility() != VISIBLE) return isLoadingActionKey(keyCode)"));
    }

    @Test public void gameEndBypassesLaunchStepsAndRevealActions() throws IOException {
        String game = gameSource();
        String apply = game.substring(game.indexOf("private void applyTransitionSnapshot"),
                game.indexOf("static boolean shouldShowTransitionOverlay"));

        // Which states are the passive closing screen is the controller snapshot's decision.
        assertTrue(apply.contains("if (snapshot.closingPresentation())"));
        assertTrue(apply.contains("consoleLoadingView.showClosing("));
        assertTrue(apply.indexOf("consoleLoadingView.showClosing(")
                < apply.indexOf("consoleLoadingView.setStep("));
        assertTrue(apply.contains("R.string.transition_game_ended"));
        assertTrue(apply.contains("R.string.transition_returning_library"));
        // A failure after that closing screen brings the cover's actions back.
        String failure = apply.substring(apply.indexOf(
                "snapshot.state == LaunchTransitionState.ERROR"));
        assertTrue(failure.indexOf("consoleLoadingView.showFullTransitionAppearance()")
                < failure.indexOf("consoleLoadingView.showError(getString(title)"));
    }

    @Test public void revealAvailabilityDoesNotStealFocus() throws IOException {
        String text = source();
        String method = text.substring(text.indexOf("public void setManualRevealAvailable"),
                text.indexOf("public boolean handleControllerKey"));

        assertFalse(method.contains("showAnywayView.requestFocus()"));
        assertTrue(method.contains("if (!changed) return"));
    }

    @Test public void lockedSessionUsesWarningColorsAndRevealAction() throws IOException {
        String text = source();
        String method = text.substring(text.indexOf("public void showWarning("),
                text.indexOf("public void showCancelling("));

        assertTrue(method.contains("messageView.setTextColor(0xFFFFD166)"));
        assertTrue(method.contains("statusView.setTextColor(0xFFFFE3A3)"));
        assertTrue(method.contains("showAnywayView.setVisibility(allowReveal ? VISIBLE : GONE)"));
        assertTrue(method.contains("retryView.setVisibility(GONE)"));

        String game = gameSource();
        String apply = game.substring(game.indexOf("private void applyTransitionSnapshot"),
                game.indexOf("static boolean shouldShowTransitionOverlay"));
        assertTrue(apply.contains("LaunchTransitionState.HOST_SESSION_LOCKED"));
        assertTrue(apply.contains("consoleLoadingView.showWarning("));
        assertTrue(apply.contains("transition_host_session_locked_title"));
    }

    @Test public void repeatedErrorSnapshotPreservesVisibleActionFocus() throws IOException {
        String text = source();
        String method = text.substring(text.indexOf("public void showError(String title,"
                        + " String details, boolean allowShowAnyway)"),
                text.indexOf("public void showLauncherInteraction"));

        assertTrue(method.contains("else if (!isVisibleAction(findFocus())) {\n"
                + "            (allowRetry ? retryView : allowShowAnyway ? showAnywayView : cancelView)\n"
                + "                    .requestFocus();"));
        assertFalse(method.contains("renderSteps();\n        retryView.requestFocus();"));
    }

    @Test public void retryIsConsumedBeforeStartingAnotherDecoderGeneration()
            throws IOException {
        String text = source();
        String constructor = text.substring(text.indexOf("retryView = action("),
                text.indexOf("configureForPlaynite(false)"));
        String retry = text.substring(text.indexOf("private void requestRetryOnce()"),
                text.indexOf("public void showNeutralWarmUpAppearance()"));
        String showError = text.substring(text.indexOf("public void showError(String title,"),
                text.indexOf("public void showLauncherInteraction"));

        assertTrue(constructor.contains("retryView.setOnClickListener(view -> requestRetryOnce())"));
        assertTrue(retry.contains("!retryView.isEnabled()"));
        assertTrue(retry.contains("retryView.setEnabled(false)"));
        assertTrue(retry.indexOf("retryView.setEnabled(false)")
                < retry.indexOf("actions.onRetry()"));
        assertTrue(showError.contains("retryView.setEnabled(true)"));

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
        String reveal = text.substring(text.indexOf("public void revealStream(Runnable"),
                text.indexOf("public void showOpaque()"));
        String opaque = text.substring(text.indexOf("public void showOpaque()"),
                text.indexOf("public void stopAndHide()"));

        assertTrue(reveal.contains("generation != revealGeneration"));
        assertTrue(opaque.contains("revealGeneration++"));
        assertTrue(opaque.contains("!revealRequested && getAlpha() == 1f"));
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

    @Test public void retainedIdentityCanReplaceArtworkAndClearToNeutral()
            throws IOException {
        String text = source();
        String artwork = text.substring(text.indexOf("public void setSplashArtwork("),
                text.indexOf("private Bitmap decodeSplashArtwork("));
        String splashPresentation = text.substring(text.indexOf("private void showSplashArtworkPresentation("),
                text.indexOf("private void applySplashArtwork("));
        String apply = text.substring(text.indexOf("private void applySplashArtwork("),
                text.indexOf("private void showSplashMessageImmediately("));
        String title = text.substring(text.indexOf("public void setTitle("),
                text.indexOf("private Bitmap decodeSplashArtwork("));

        assertTrue(artwork.contains("Bitmap bitmap = decodeSplashArtwork(path)"));
        assertTrue(artwork.contains("if (sameArtwork && hasSplashArtwork())"));
        assertTrue(artwork.contains("if (!sameArtwork) hideSplashArtwork()"));
        assertTrue(splashPresentation.contains("defaultBackdrop.setVisibility(GONE)"));
        assertTrue(splashPresentation.contains("defaultShade.setVisibility(GONE)"));
        assertTrue(splashPresentation.contains("defaultContent.setVisibility(GONE)"));
        assertTrue(text.contains("splashArtworkView.setImageDrawable(null)"));
        assertTrue(text.contains("splashArtworkView.setVisibility(GONE)"));
        assertTrue(text.contains("setBackgroundColor(Color.BLACK)"));
        assertTrue(apply.contains("splashArtworkView.setVisibility(VISIBLE)"));
        assertTrue(apply.contains("splashArtworkView.setImageBitmap(bitmap)"));
        assertTrue(apply.contains("artwork.setScaleType(ImageView.ScaleType.CENTER_CROP)"));
        assertTrue(apply.contains("Math.round(screenWidth / 3.1f)"));
        assertTrue(apply.contains("Gravity.CENTER"));
        assertTrue(title.contains("messageView.setText(title.trim())"));
    }

    @Test public void neutralWarmUpGateStaysOpaqueAndCanRestoreTransitionUi()
            throws IOException {
        String text = source();
        String neutral = text.substring(text.indexOf(
                        "public void showNeutralWarmUpAppearance()"),
                text.indexOf("public void showFullTransitionAppearance()"));
        String full = text.substring(text.indexOf(
                        "public void showFullTransitionAppearance()"),
                text.indexOf("public void setSplashArtwork("));

        assertTrue(neutral.contains("defaultBackdrop.setVisibility(GONE)"));
        assertTrue(neutral.contains("defaultShade.setVisibility(GONE)"));
        assertTrue(neutral.contains("defaultContent.setVisibility(GONE)"));
        assertFalse(neutral.contains("setAlpha("));
        assertFalse(neutral.contains("ConsoleStreamLoadingView.this.setVisibility"));
        assertTrue(full.contains("showDefaultTransitionPresentation()"));
        assertTrue(full.contains("showSplashArtworkPresentation(false)"));
        assertTrue(text.contains("defaultBackdrop.setVisibility(VISIBLE)"));
        assertTrue(text.contains("defaultShade.setVisibility(VISIBLE)"));
        assertTrue(text.contains("defaultContent.setVisibility(VISIBLE)"));
    }

    @Test public void coverTakenOverFromAnotherWindowStartsAsThatOneLooks() throws IOException {
        String text = source();
        String constructor = text.substring(text.indexOf(
                        "public ConsoleStreamLoadingView(Context context, String title,"),
                text.indexOf("public void setActions("));
        String apply = text.substring(text.indexOf("private void applySplashArtwork("),
                text.indexOf("private void showSplashMessageImmediately()"));
        String decode = text.substring(text.indexOf("private Bitmap decodeSplashArtwork("),
                text.indexOf("public void configureForPlaynite("));

        // The message shown by the other window stays; no new one is drawn at once.
        assertTrue(constructor.contains(
                "handedOver = initialMessage != null && !initialMessage.trim().isEmpty();"));
        assertTrue(constructor.contains("messageView = text(handedOver ? initialMessage"));
        assertTrue(constructor.contains("if (handedOver) {\n"
                + "            lastMessageIndex = Arrays.asList(messages).indexOf(initialMessage);\n"
                + "            if (!reducedMotion) handler.postDelayed(rotateMessage,"
                + " MESSAGE_INTERVAL_MS);\n"
                + "            requestDefaultActionFocus();\n"
                + "        } else {\n"
                + "            scheduleLoadingMessage(false);"));
        assertTrue(apply.contains("if (!handedOver) showSplashMessageImmediately();"));
        // Its artwork is the bitmap already decoded and is there in the first frame.
        assertTrue(apply.contains("artwork.setAlpha(handedOver ? 1f : 0f);"));
        assertTrue(apply.contains("if (!handedOver) {\n"
                + "            artwork.animate().alpha(1f)"));
        assertFalse(text.contains("recycle()"));
        assertTrue(decode.contains(
                "if (decodedSplash != null && path.equals(decodedSplashPath)) return decodedSplash;"));
        // Once attached it is an ordinary cover again.
        assertTrue(constructor.contains("protected void onAttachedToWindow() {\n"
                + "        super.onAttachedToWindow();"));
        assertTrue(constructor.contains("handedOver = false;"));
        // The default backdrop does not depend on when the view was built.
        assertFalse(text.contains("SystemClock"));

        String game = gameSource();
        assertTrue(game.contains(
                "getIntent().getStringExtra(EXTRA_CONSOLE_LOADING_MESSAGE),\n"));
    }

    @Test public void seeThroughCoverDrawsNoBackground() throws IOException {
        String text = source();
        assertTrue(text.contains("public void setSeeThrough(boolean seeThrough) {\n"
                + "        setBackgroundColor(seeThrough ? Color.TRANSPARENT : Color.BLACK);"));
    }

    private static String source() throws IOException {
        Path source = Paths.get("src/main/java/com/limelight/ui/ConsoleStreamLoadingView.java");
        if (!Files.exists(source)) {
            source = Paths.get("app/src/main/java/com/limelight/ui/ConsoleStreamLoadingView.java");
        }
        return new String(Files.readAllBytes(source), StandardCharsets.UTF_8).replace("\r\n", "\n");
    }

    private static String gameSource() throws IOException {
        Path source = Paths.get("src/main/java/com/limelight/Game.java");
        if (!Files.exists(source)) {
            source = Paths.get("app/src/main/java/com/limelight/Game.java");
        }
        return new String(Files.readAllBytes(source), StandardCharsets.UTF_8).replace("\r\n", "\n");
    }
}
