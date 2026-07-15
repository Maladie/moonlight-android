package com.limelight.console;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ConsoleSessionSummaryTest {
    @Test public void unavailableSessionNeverOffersReturnToGame() {
        ConsoleSessionSummary summary = ConsoleSessionSummary.from(null);

        assertEquals("SESSION · STATUS UNAVAILABLE", summary.label);
        assertFalse(summary.alive);
    }

    @Test public void liveSessionIncludesAppAndVideoMode() {
        ConsoleSessionSummary summary = ConsoleSessionSummary.from(
                new ConsoleDataRepository.Session("streaming", "ready", "Host",
                        "Baba Is You", 1920, 1080, 60, true));

        assertEquals("SESSION · STREAMING · Baba Is You · 1920×1080 @ 60", summary.label);
        assertTrue(summary.alive);
    }

    @Test public void missingVideoModeDoesNotRenderZeroDimensions() {
        ConsoleSessionSummary summary = ConsoleSessionSummary.from(
                new ConsoleDataRepository.Session("connecting", null, null,
                        null, 0, 0, 0, false));

        assertEquals("SESSION · CONNECTING", summary.label);
        assertFalse(summary.alive);
    }
}
