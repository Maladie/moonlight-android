package com.limelight.console;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class ConsoleAudioLevelsTest {
    private static final int SAMPLE_RATE = 22_050;

    private static double db(float gain) {
        return 20.0 * Math.log10(gain);
    }

    @Test public void ambientCurveIsMonotonicInDecibelsFromBarelyAudibleToHeadroom() {
        assertEquals(0f, ConsoleAudioEngine.ambientGain(0f), 0f);
        assertEquals(-42.0, db(ConsoleAudioEngine.ambientGain(0.1f)), 0.01);
        assertEquals(-10.0, db(ConsoleAudioEngine.ambientGain(1f)), 0.01);
        float previous = 0f;
        for (int step = 1; step <= ConsolePanel.STEPS; step++) {
            float gain = ConsoleAudioEngine.ambientGain(
                    ConsolePanel.percentFromStep(step) / 100f);
            assertTrue("step " + step, gain > previous);
            if (step > 1) assertEquals(32.0 / 9.0, db(gain) - db(previous), 0.01);
            previous = gain;
        }
        assertEquals(previous, ConsoleAudioEngine.ambientGain(1.4f), 0f);
    }

    @Test public void worstCaseMixAtMaximumVolumesStaysBelowFullScale() {
        // Music asset peaks at 0 dBFS; every cue may overlap it at full effects volume.
        double worst = ConsoleAudioEngine.ambientGain(1f);
        for (ConsoleAudioSynthesis.Cue cue : ConsoleAudioSynthesis.Cue.values()) {
            int peak = 0;
            for (short sample : ConsoleAudioSynthesis.renderCue(cue, SAMPLE_RATE)) {
                peak = Math.max(peak, Math.abs(sample));
            }
            double level = peak / (double) Short.MAX_VALUE;
            assertTrue(cue + " is shaped below the limiter knee",
                    level < ConsoleAudioSynthesis.LIMIT_KNEE);
            worst += level;
        }
        assertTrue("worst-case mix " + worst, worst <= 1.0);
    }

    @Test public void softLimiterIsTransparentBelowKneeAndBoundedAbove() {
        assertEquals(0.4, ConsoleAudioSynthesis.softLimit(0.4), 0.0);
        assertEquals(-0.5, ConsoleAudioSynthesis.softLimit(-0.5), 0.0);
        double previous = -2.0;
        for (double input = -20.0; input <= 20.0; input += 0.01) {
            double output = ConsoleAudioSynthesis.softLimit(input);
            assertTrue(Math.abs(output) <= ConsoleAudioSynthesis.LIMIT_CEILING);
            assertTrue(output >= previous);
            previous = output;
        }
    }
}
