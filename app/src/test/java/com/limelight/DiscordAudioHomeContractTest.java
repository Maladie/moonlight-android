package com.limelight;

import org.junit.Test;

import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class DiscordAudioHomeContractTest {
    @Test public void retainedMoonlightMixIsMutedOnHome() throws Exception {
        Path path = Paths.get("src/main/java/com/limelight/Game.java");
        String source = SourceText.read(path);
        int start = source.indexOf("private boolean openConsoleHome()");
        int end = source.indexOf("private void openPreparingConsoleHome()", start);
        String method = source.substring(start, end);
        assertTrue(method.indexOf("streamHomeVisible = true;")
                < method.indexOf("applyStreamAudio(currentTransitionSnapshot());"));
        assertFalse(method.contains(".12f"));
    }
}
