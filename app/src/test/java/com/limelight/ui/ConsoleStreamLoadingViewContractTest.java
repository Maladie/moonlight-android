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
        String text = new String(Files.readAllBytes(source), StandardCharsets.UTF_8);
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

        assertTrue(apply.contains("if (usesClosingPresentation(snapshot))"));
        assertTrue(apply.contains("consoleLoadingView.showClosing("));
        assertTrue(apply.indexOf("consoleLoadingView.showClosing(")
                < apply.indexOf("consoleLoadingView.setStep("));
        assertTrue(apply.contains("R.string.transition_game_ended"));
        assertTrue(apply.contains("R.string.transition_returning_library"));
        String predicate = game.substring(game.indexOf(
                        "private static boolean usesClosingPresentation"),
                game.indexOf("static boolean shouldShowTransitionOverlay"));
        assertTrue(predicate.contains("LaunchTransitionType.GAME_CONNECTION"));
        assertTrue(predicate.contains("snapshot.spec.playniteGameId.isEmpty()"));
    }

    @Test public void revealAvailabilityDoesNotStealFocus() throws IOException {
        String text = source();
        String method = text.substring(text.indexOf("public void setManualRevealAvailable"),
                text.indexOf("public boolean handleControllerKey"));

        assertFalse(method.contains("showAnywayView.requestFocus()"));
        assertTrue(method.contains("if (!changed) return"));
    }

    @Test public void repeatedErrorSnapshotPreservesVisibleActionFocus() throws IOException {
        String text = source();
        String method = text.substring(text.indexOf("public void showError(String title,"
                        + " String details, boolean allowShowAnyway)"),
                text.indexOf("public void showLauncherInteraction"));

        assertTrue(method.contains(
                "if (!isVisibleAction(findFocus())) retryView.requestFocus()"));
        assertFalse(method.contains("renderSteps();\n        retryView.requestFocus();"));
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
        String apply = text.substring(text.indexOf("private void applySplashArtwork("),
                text.indexOf("private void showSplashMessageImmediately("));
        String title = text.substring(text.indexOf("public void setTitle("),
                text.indexOf("private Bitmap decodeSplashArtwork("));

        assertTrue(artwork.contains("splashArtworkView.setImageDrawable(null)"));
        assertTrue(artwork.contains("splashArtworkView.setVisibility(GONE)"));
        assertTrue(apply.contains("splashArtworkView.setVisibility(VISIBLE)"));
        assertTrue(apply.contains("splashArtworkView.setImageBitmap(bitmap)"));
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
        assertTrue(full.contains("defaultBackdrop.setVisibility(VISIBLE)"));
        assertTrue(full.contains("defaultShade.setVisibility(VISIBLE)"));
        assertTrue(full.contains("defaultContent.setVisibility(VISIBLE)"));
    }

    private static String source() throws IOException {
        Path source = Paths.get("src/main/java/com/limelight/ui/ConsoleStreamLoadingView.java");
        if (!Files.exists(source)) {
            source = Paths.get("app/src/main/java/com/limelight/ui/ConsoleStreamLoadingView.java");
        }
        return new String(Files.readAllBytes(source), StandardCharsets.UTF_8);
    }

    private static String gameSource() throws IOException {
        Path source = Paths.get("src/main/java/com/limelight/Game.java");
        if (!Files.exists(source)) {
            source = Paths.get("app/src/main/java/com/limelight/Game.java");
        }
        return new String(Files.readAllBytes(source), StandardCharsets.UTF_8);
    }
}
