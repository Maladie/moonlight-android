package com.limelight.console;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.Assert.assertTrue;

public class ConsoleActivityStreamingAutopilotContractTest {
    @Test public void globalAndBothGameMenusExposeAutopilotWithoutSilentApply()
            throws IOException {
        String source = consoleActivitySource();
        String options = between(source, "private void showOptionsPanel()",
                "private void resetScreenSaverTimer()");
        String playnite = between(source, "private void showPlayniteGameActions(",
                "private void confirmEndPlayniteGame(");
        String classic = between(source, "private void showAppActions(",
                "private void confirmQuitAction(");
        String preview = between(source, "private void showStreamingAutopilotPreview(",
                "private String streamingAutopilotNetworkSummary(");

        assertTrue(options.contains("R.string.console_streaming_autopilot_global"));
        assertTrue(options.contains("requestStreamingAutopilot("));
        assertTrue(playnite.contains("R.string.console_streaming_autopilot_game"));
        assertTrue(playnite.contains("playniteStreamSettingsKey(host.uuid, item.stableId())"));
        assertTrue(playnite.contains("PlayIntent.providerGame"));
        assertTrue(classic.contains("\"app.streaming_autopilot\""));
        assertTrue(classic.contains("host.uuid + \":\" + app.getAppId()"));
        assertTrue(classic.contains("PlayIntent.sunshineApp"));
        assertTrue(preview.contains("StreamingAutopilotController.applyGlobal"));
        assertTrue(preview.contains("StreamingAutopilotController.applyForApp"));
        assertTrue(preview.contains("sessionOrchestrator.play(calibrationIntent.withCalibration("));
        assertTrue(preview.contains("console_autopilot_preview_details"));
        assertTrue(preview.contains("console_autopilot_calibration_preview_details"));
    }

    @Test public void meteredDeclineCancellationAndLifecycleInvalidateTheWork()
            throws IOException {
        String source = consoleActivitySource();
        String request = between(source, "private void requestStreamingAutopilot(",
                "private void showOptionsPanel()");
        String destroy = between(source, "protected void onDestroy()",
                "public void onBackPressed()");
        String selectHost = between(source, "private void selectHost(",
                "private void cancelOwnedWarmUp(");

        assertTrue(request.contains("isActiveNetworkMetered()"));
        assertTrue(request.contains("targetName, calibrationIntent, null)"));
        assertTrue(request.contains("executor.submit"));
        assertTrue(request.contains("task.cancel(true)"));
        assertTrue(request.contains("isFinishing() && !isDestroyed()"));
        assertTrue(destroy.contains("cancelStreamingAutopilot(false)"));
        assertTrue(selectHost.contains("cancelStreamingAutopilot(false)"));
    }

    private static String between(String source, String start, String end) {
        return source.substring(source.indexOf(start), source.indexOf(end, source.indexOf(start)));
    }

    private static String consoleActivitySource() throws IOException {
        Path source = Paths.get("src/main/java/com/limelight/console/ConsoleActivity.java");
        if (!Files.exists(source)) {
            source = Paths.get("app/src/main/java/com/limelight/console/ConsoleActivity.java");
        }
        return new String(Files.readAllBytes(source), StandardCharsets.UTF_8);
    }
}
