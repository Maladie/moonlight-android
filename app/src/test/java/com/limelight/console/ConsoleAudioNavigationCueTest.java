package com.limelight.console;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class ConsoleAudioNavigationCueTest {
    @Test public void onlyKeysPressedOnTheResumedConsoleCount() {
        assertTrue(ConsoleAudioEngine.directionalInputCounts(true, 1_000L, 1_000L));
        assertTrue(ConsoleAudioEngine.directionalInputCounts(true, 1_000L, 1_500L));
        // Held or queued from the stream across the return.
        assertFalse(ConsoleAudioEngine.directionalInputCounts(true, 1_000L, 999L));
        assertFalse(ConsoleAudioEngine.directionalInputCounts(false, 1_000L, 1_500L));
    }

    @Test public void focusRestoredWithoutRecentDirectionalInputStaysSilent() {
        long never = Long.MIN_VALUE / 2;
        assertFalse(ConsoleAudioEngine.navigationCueDue(5_000L, never, never));
        assertFalse(ConsoleAudioEngine.navigationCueDue(5_000L,
                5_000L - ConsoleAudioEngine.NAVIGATION_INPUT_WINDOW_MS, never));
        assertTrue(ConsoleAudioEngine.navigationCueDue(5_000L, 4_900L, never));
        // Throttled after a tick.
        assertFalse(ConsoleAudioEngine.navigationCueDue(5_000L, 4_990L, 4_950L));
    }
}
