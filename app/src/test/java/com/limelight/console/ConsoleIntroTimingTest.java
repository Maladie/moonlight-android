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

    @Test public void theEntranceEndsWithTheGreetingShownBeforeTheIntroMayLeave() {
        // Flight and fade to 650 ms, band to 950 ms, mark and greeting to 1100 ms: no step
        // of home runs while any of them moves.
        assertFalse(ConsoleIntroTiming.entered(650f));
        assertFalse(ConsoleIntroTiming.entered(1_099f));
        assertTrue(ConsoleIntroTiming.entered(1_100f));
        // Phase time runs MINIMUM_MS / minimum faster on a host change: over by ~367 ms.
        float hostChangeSpeed = (float) MIN / ConsoleIntroTiming.HOST_CHANGE_MS;
        assertTrue(ConsoleIntroTiming.ENTRANCE_MS / hostChangeSpeed
                < ConsoleIntroTiming.HOST_CHANGE_MS - 200f);
        // Room for the leading cards and the profile answer before the minimum.
        assertTrue(ConsoleIntroTiming.ENTRANCE_MS <= MIN - 700f);
        // Until the waiting steps have run the intro is not ready, so only the limit ends it.
        assertFalse(ConsoleIntroTiming.leave(MIN, MIN, false, 400L));
    }

    @Test public void theOpeningFrameStandsUntilHomeIsRenderedOrTheLimit() {
        // Two frames at least; then until home's first render is in and no step waits.
        assertTrue(ConsoleIntroTiming.standsStill(0L, 0, false, true));
        assertTrue(ConsoleIntroTiming.standsStill(16L, 1, false, true));
        assertFalse(ConsoleIntroTiming.standsStill(33L, 2, false, true));
        assertTrue(ConsoleIntroTiming.standsStill(200L, 9, false, false));
        assertTrue(ConsoleIntroTiming.standsStill(200L, 9, true, true));
        // The limit is hard: nothing holds the intro past it, and the last step that may
        // start leaves a card's build of room before it.
        assertEquals(1_000L, ConsoleIntroTiming.STILL_LIMIT_MS);
        long lastStart = ConsoleIntroTiming.STILL_LIMIT_MS - ConsoleIntroTiming.STILL_STEP_MS;
        assertTrue(ConsoleIntroTiming.standsStill(lastStart - 1L, 20, true, false));
        assertFalse(ConsoleIntroTiming.standsStill(lastStart, 20, true, false));
        assertFalse(ConsoleIntroTiming.standsStill(lastStart, 0, false, false));
        assertTrue(ConsoleIntroTiming.STILL_STEP_MS >= 65L);
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
