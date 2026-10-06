package com.limelight.console;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class ConsoleReducedMotionTest {
    private static final long MB = 1024L * 1024;

    @Test public void lowMemoryDevicesDefaultToReducedMotion() {
        // The BRAVIA VH22: 3 GB nominal (reported a little under 3 GiB), ~700 MB available.
        assertTrue(ConsoleReducedMotion.resolve(null, false, 2900 * MB, 700 * MB));
        assertTrue(ConsoleReducedMotion.resolve(null, true, 8192 * MB, 6000 * MB));
        // Plenty of RAM but under a quarter of it available.
        assertTrue(ConsoleReducedMotion.resolve(null, false, 8192 * MB, 1500 * MB));
        assertFalse(ConsoleReducedMotion.resolve(null, false, 4096 * MB, 2048 * MB));
    }

    @Test public void anExplicitChoiceAlwaysWins() {
        assertFalse(ConsoleReducedMotion.resolve(false, true, 2900 * MB, 100 * MB));
        assertTrue(ConsoleReducedMotion.resolve(true, false, 8192 * MB, 6000 * MB));
    }
}
