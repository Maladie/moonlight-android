package com.limelight;

import org.junit.Test;

import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class GameAudioRevealContractTest {
    /**
     * TV 2026-10-15 (loud sounds at console entry and stream return): the stream's audio has
     * one owner. It is heard only once the cover is off the stream (LaunchTransitionController
     * .streamAudible), applied with every snapshot; nothing else sets it.
     */
    @Test public void streamAudioFollowsTheCoverFromOnePlace() throws Exception {
        Path path = Paths.get("src/main/java/com/limelight/Game.java");
        String source = SourceText.read(path);
        assertEquals(1, count(source, "runtime.setAudioVolume("));
        String apply = source.substring(source.indexOf("private void applyStreamAudio("),
                source.indexOf("private void applyImmediateTerminalPrivacy()"));
        assertTrue(apply.contains("streamHomeVisible || userInitiatedDisconnect"));
        assertTrue(apply.contains("LaunchTransitionController.streamAudio(snapshot, isOwnedHiddenAutoWarmUp())"));
        assertTrue(apply.contains("? COVERED_STREAM_GAIN : 0f"));
        String snapshot = source.substring(source.indexOf("private void applyTransitionSnapshot("),
                source.indexOf("private void persistRevealedTransitionForRecovery()"));
        assertTrue(snapshot.contains("applyStreamAudio(snapshot);"));
        int start = source.indexOf("decoderRenderer.setRenderTarget(holder);");
        assertTrue(source.indexOf("applyStreamAudio(currentTransitionSnapshot());", start)
                < source.indexOf("runtime.start();", start));
    }

    private static int count(String text, String needle) {
        int n = 0;
        for (int i = text.indexOf(needle); i >= 0; i = text.indexOf(needle, i + 1)) n++;
        return n;
    }
}
