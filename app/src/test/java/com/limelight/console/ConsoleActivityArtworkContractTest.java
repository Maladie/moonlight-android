package com.limelight.console;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ConsoleActivityArtworkContractTest {
    @Test public void sunshineLaunchAddsOnlyThePresentationArtworkIdentity() throws IOException {
        Path source = Paths.get("src/main/java/com/limelight/console/ConsoleActivity.java");
        if (!Files.exists(source)) {
            source = Paths.get("app/src/main/java/com/limelight/console/ConsoleActivity.java");
        }
        String text = new String(Files.readAllBytes(source), StandardCharsets.UTF_8);
        String method = text.substring(text.indexOf("private void playSunshineApp"),
                text.indexOf("private void playPlayniteGame"));

        assertTrue(method.contains("uniquePlayniteGameIdForRunningApp(host, app)"));
        assertTrue(method.contains("PlayIntent.sunshineApp"));
        assertFalse(method.contains("PlayIntent.playniteGame"));
    }
}
