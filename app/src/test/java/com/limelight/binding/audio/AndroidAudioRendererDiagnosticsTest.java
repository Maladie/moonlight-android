package com.limelight.binding.audio;

import org.junit.Test;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import static org.junit.Assert.*;

public class AndroidAudioRendererDiagnosticsTest {
    @Test public void detectsNonzeroPcmWithoutRetainingSamples() {
        assertFalse(AndroidAudioRenderer.containsNonzeroSample(new short[0]));
        assertFalse(AndroidAudioRenderer.containsNonzeroSample(new short[] {0, 0}));
        assertTrue(AndroidAudioRenderer.containsNonzeroSample(new short[] {0, -1, 0}));
    }

    @Test public void telemetryIsLifecycleOnlyAndCountsSuccessfulWrites() throws Exception {
        Path path = Paths.get("src/main/java/com/limelight/binding/audio/AndroidAudioRenderer.java");
        if (!Files.exists(path)) path = Paths.get("app").resolve(path);
        String source = new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
        String pcm = source.substring(source.indexOf("public void playDecodedAudio("),
                source.indexOf("public void start()"));
        assertFalse(pcm.contains("logAudioState("));
        assertTrue(pcm.contains("if (lastWriteResult > 0)"));
        assertTrue(pcm.contains("samplesWritten += lastWriteResult"));
        for (String reason : new String[] {"setup", "volume", "start", "stop", "cleanup"}) {
            assertTrue(source.contains("logAudioState(\"" + reason + "\")"));
        }
    }
}
