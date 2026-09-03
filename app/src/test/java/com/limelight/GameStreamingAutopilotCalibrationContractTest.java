package com.limelight;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class GameStreamingAutopilotCalibrationContractTest {
    @Test public void runtimeCandidateAndStructuredStatsFeedCalibration() throws IOException {
        String game = source("src/main/java/com/limelight/Game.java");
        String renderer = source(
                "src/main/java/com/limelight/binding/video/MediaCodecDecoderRenderer.java");
        String initialize = between(game,
                "private boolean initializeStreamingAutopilotCalibration(",
                "private void onStreamingAutopilotVideoStats(");

        assertTrue(initialize.contains("prefConfig.width = width"));
        assertTrue(initialize.contains("prefConfig.height = height"));
        assertTrue(initialize.contains("prefConfig.fps = fps"));
        assertTrue(initialize.contains("prefConfig.bitrate = runtimeBitrateKbps"));
        assertTrue(game.contains("renderer.setVideoStatsListener("));
        assertTrue(renderer.contains("new StreamingAutopilotCalibration.Sample("));
        assertTrue(renderer.contains("rttInfo < 0 ? 0"));
        assertTrue(renderer.contains("framesWithHostProcessingLatency / received"));
    }

    @Test public void playniteWaitsForGameReadyAndVisibleStream() throws IOException {
        String game = source("src/main/java/com/limelight/Game.java");
        String arm = between(game,
                "private void maybeArmStreamingAutopilotCalibration()",
                "private void updateStreamingAutopilotCalibrationProgress(");
        String transition = between(game,
                "private void applyTransitionSnapshot(",
                "private static boolean usesClosingPresentation(");

        assertTrue(arm.contains("!streamEverRevealed"));
        assertTrue(arm.contains("playniteGame && !autopilotGameReadySeen"));
        assertTrue(transition.contains("LaunchTransitionState.GAME_READY"));
        assertTrue(transition.contains("autopilotGameReadySeen = true"));
        assertTrue(transition.contains("maybeArmStreamingAutopilotCalibration()"));
        assertFalse(arm.contains("AlertDialog"));
        assertTrue(arm.contains("performanceOverlayView.setVisibility(View.VISIBLE)"));
    }

    @Test public void resultRequiresConsentAndCancellationNeverPersists() throws IOException {
        String game = source("src/main/java/com/limelight/Game.java");
        String result = between(game,
                "private void showStreamingAutopilotCalibrationResult(",
                "private static int calibrationFailureMessage(");
        String cancel = between(game,
                "private void cancelStreamingAutopilotCalibration()",
                "private void restoreStreamingAutopilotProgressOverlay()");

        assertTrue(game.contains("AUTOPILOT_RESULT_ACTION_DELAY_MS = 2_000L"));
        assertTrue(result.contains("StreamingAutopilotCalibration.adjustedSettings("));
        assertTrue(result.contains("ConsoleConfirmDialog.show(this"));
        assertTrue(result.contains("AUTOPILOT_RESULT_ACTION_DELAY_MS"));
        assertTrue(result.contains("AppPreferences.applyStreamSettings"));
        assertTrue(result.contains("setInputGrabState(false)"));
        assertTrue(result.contains("controllerHandler.releaseAllControllerInputsAndSuppress()"));
        assertTrue(result.contains("setInputGrabState(restoreInputGrab)"));
        assertTrue(result.contains("setInputSuppressed(restoreInputSuppression)"));
        assertFalse(cancel.contains("AppPreferences"));
        assertTrue(game.contains("protected void onStop()"));
        assertTrue(game.contains("public void connectionTerminated(final int errorCode)"));
        assertTrue(cancel.contains("restoreStreamingAutopilotProgressOverlay"));
    }

    private static String between(String source, String start, String end) {
        int from = source.indexOf(start);
        return source.substring(from, source.indexOf(end, from));
    }

    private static String source(String path) throws IOException {
        Path source = Paths.get(path);
        if (!Files.exists(source)) source = Paths.get("app").resolve(path);
        return new String(Files.readAllBytes(source), StandardCharsets.UTF_8);
    }
}
