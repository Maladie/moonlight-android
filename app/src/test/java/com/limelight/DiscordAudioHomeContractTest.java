package com.limelight;

import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class DiscordAudioHomeContractTest {
    @Test public void retainedMoonlightMixIsMutedOnHome() throws Exception {
        Path path = Paths.get("app/src/main/java/com/limelight/Game.java");
        if (!Files.exists(path)) path = Paths.get("..", path.toString());
        String source = new String(Files.readAllBytes(path));
        int start = source.indexOf("private void openConsoleHome()");
        int end = source.indexOf("private void openPreparingConsoleHome()", start);
        String method = source.substring(start, end);
        assertTrue(method.contains("streamAudioRenderer.setVolume(0f)"));
        assertFalse(method.contains(".12f"));
    }
}
