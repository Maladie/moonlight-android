package com.limelight;

import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.Assert.assertTrue;

public class GameAudioRevealContractTest {
    @Test public void revealingGameAlwaysRestoresMoonlightAudio() throws Exception {
        Path path = Paths.get("app/src/main/java/com/limelight/Game.java");
        if (!Files.exists(path)) path = Paths.get("..", path.toString());
        String source = new String(Files.readAllBytes(path));
        int reveal = source.indexOf("consoleLoadingView.revealStream(() -> {");
        int revealed = source.indexOf("streamEverRevealed = true;", reveal);
        assertTrue(reveal >= 0 && revealed > reveal);
        assertTrue(source.substring(reveal, revealed)
                .contains("streamAudioRenderer.setVolume(1f)"));
    }
}
