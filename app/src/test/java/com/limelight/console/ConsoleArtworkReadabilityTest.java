package com.limelight.console;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class ConsoleArtworkReadabilityTest {
    @Test
    public void brightArtworkGetsStrongerVignette() {
        int[] dark = ConsoleArtworkReadability.gradient(.08f, true);
        int[] bright = ConsoleArtworkReadability.gradient(.92f, true);
        for (int index = 0; index < dark.length; index++) {
            assertTrue(alpha(bright[index]) > alpha(dark[index]));
        }
    }

    @Test
    public void luminanceUsesPerceptualChannelWeights() {
        float green = ConsoleArtworkReadability.perceivedLuminance(0, 255, 0);
        float blue = ConsoleArtworkReadability.perceivedLuminance(0, 0, 255);
        assertTrue(green > blue);
        assertEquals(0f, ConsoleArtworkReadability.perceivedLuminance(0, 0, 0), .0001f);
    }

    private static int alpha(int color) {
        return color >>> 24;
    }
}
