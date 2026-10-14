package com.limelight;

import org.junit.Test;

import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.Assert.assertTrue;

public class GameAudioRevealContractTest {
    @Test public void revealingGameAlwaysRestoresMoonlightAudio() throws Exception {
        Path path = Paths.get("src/main/java/com/limelight/Game.java");
        String source = SourceText.read(path);
        int reveal = source.indexOf("consoleLoadingView.revealStream(() -> {");
        int revealed = source.indexOf("streamEverRevealed = true;", reveal);
        assertTrue(reveal >= 0 && revealed > reveal);
        assertTrue(source.substring(reveal, revealed)
                .contains("runtime.setAudioVolume(attachment, 1f)"));
    }
}
