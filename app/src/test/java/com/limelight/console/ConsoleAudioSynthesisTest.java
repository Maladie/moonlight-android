package com.limelight.console;

import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class ConsoleAudioSynthesisTest {
    @Test
    public void everyCueHasAudioAndReturnsToSilence() {
        for (ConsoleAudioSynthesis.Cue cue : ConsoleAudioSynthesis.Cue.values()) {
            short[] samples = ConsoleAudioSynthesis.renderCue(cue, 22_050);
            int peak = 0;
            for (short sample : samples) peak = Math.max(peak, Math.abs((int) sample));
            assertTrue(cue.name(), peak > 500);
            assertTrue(cue.name(), Math.abs((int) samples[samples.length - 1]) < 80);
        }
    }
}
