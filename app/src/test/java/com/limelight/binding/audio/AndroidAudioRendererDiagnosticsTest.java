package com.limelight.binding.audio;

import com.limelight.SourceText;
import org.junit.Test;
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
        String source = SourceText.read(path);
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
