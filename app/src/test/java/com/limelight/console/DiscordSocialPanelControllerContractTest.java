package com.limelight.console;

import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.Test;

public class DiscordSocialPanelControllerContractTest {
    @Test public void staleVoiceCallbacksCannotUpdateTheConsoleIndicator() throws Exception {
        String source = source();
        assertVoiceUpdateFollowsGenerationGuard(source, "private void join(",
                "    private void refreshVoice(");
        assertVoiceUpdateFollowsGenerationGuard(source, "private void refreshVoice(",
                "    private HostGatewayClient.DiscordChannel findVoiceChannel(");
        assertVoiceUpdateFollowsGenerationGuard(source, "private void voiceAction(",
                "    private void openOptions(");
    }

    private static void assertVoiceUpdateFollowsGenerationGuard(String source,
                                                                  String startMarker,
                                                                  String endMarker) {
        int start = source.indexOf(startMarker);
        int end = source.indexOf(endMarker, start + startMarker.length());
        assertTrue(start >= 0 && end > start);
        String body = source.substring(start, end);
        int guard = body.indexOf("if (!visible || expected != generation) return;");
        int update = body.indexOf("ui.voiceChanged(");
        assertTrue(guard >= 0 && update > guard);
    }

    private static String source() throws Exception {
        Path path = Paths.get("src/main/java/com/limelight/console/"
                + "DiscordSocialPanelController.java");
        if (!Files.exists(path)) {
            path = Paths.get("app/src/main/java/com/limelight/console/"
                    + "DiscordSocialPanelController.java");
        }
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }
}
