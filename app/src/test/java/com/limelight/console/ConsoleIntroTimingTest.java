package com.limelight.console;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class ConsoleIntroTimingTest {
    private static final long MIN = ConsoleIntroTiming.MINIMUM_MS;

    @Test public void waitsForTheMinimumAndForHome() {
        assertFalse(ConsoleIntroTiming.leave(MIN - 1, MIN, true, -1L));
        assertTrue(ConsoleIntroTiming.leave(MIN, MIN, true, -1L));
        assertFalse(ConsoleIntroTiming.leave(2_000L, MIN, false, -1L));
        assertTrue(ConsoleIntroTiming.leave(ConsoleIntroTiming.LIMIT_MS, MIN, false, -1L));
        assertTrue(ConsoleIntroTiming.leave(ConsoleIntroTiming.HOST_CHANGE_MS,
                ConsoleIntroTiming.HOST_CHANGE_MS, true, -1L));
    }

    @Test public void aKeySkipsOnlyAReadyIntroAfter300Ms() {
        assertFalse(ConsoleIntroTiming.leave(500L, MIN, true, 299L));
        assertTrue(ConsoleIntroTiming.leave(500L, MIN, true, 300L));
        assertFalse(ConsoleIntroTiming.leave(500L, MIN, false, 400L));
        // A key pressed before home was ready skips as soon as it is.
        assertTrue(ConsoleIntroTiming.leave(700L, MIN, true, 400L));
    }

    @Test public void easingMatchesTheMockupCurves() {
        assertEquals(0f, ConsoleIntroTiming.easeOut(-1f), 0f);
        assertEquals(.875f, ConsoleIntroTiming.easeOut(.5f), 1e-6f);
        assertEquals(1f, ConsoleIntroTiming.easeOut(2f), 0f);
        assertEquals(.5f, ConsoleIntroTiming.easeInOut(.5f), 1e-6f);
        assertEquals(.032f, ConsoleIntroTiming.easeInOut(.2f), 1e-6f);
        assertEquals(1f, ConsoleIntroTiming.TIME_SCALE, 0f);
    }
}
