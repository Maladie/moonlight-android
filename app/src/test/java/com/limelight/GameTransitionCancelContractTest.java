package com.limelight;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class GameTransitionCancelContractTest {
    @Test public void cancelDoesNotWaitForAnotherVideoFrame() throws IOException {
        Path source = Paths.get("src/main/java/com/limelight/Game.java");
        if (!Files.exists(source)) source = Paths.get("app/src/main/java/com/limelight/Game.java");
        String text = new String(Files.readAllBytes(source), StandardCharsets.UTF_8);
        String method = text.substring(text.indexOf("private void cancelTransition()"),
                text.indexOf("private void retryTransition()"));

        assertFalse(method.contains("doAfterNextFrame"));
        assertTrue(method.contains("SessionResumeManager.clearIfMatches"));
        assertTrue(method.contains("stopConnection(finishOnce)"));
    }
}
