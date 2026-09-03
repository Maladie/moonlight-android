package com.limelight.preferences;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

public class AppPreferencesTest {
    @Test public void streamSettingsCopyPreservesUnrelatedAppSettings() {
        AppPreferences.AppSettings current = new AppPreferences.AppSettings(
                "1280x720", 30, "smoothness", 10_000, 119.88,
                "true", "false", true);

        AppPreferences.AppSettings updated = AppPreferences.copyWithStreamSettings(
                current, 2560, 1440, 120, 40_000);

        assertEquals("2560x1440", updated.resolution);
        assertEquals(120, updated.fps);
        assertEquals(40_000, updated.bitrate);
        assertFalse(updated.useGlobalSettings);
        assertEquals("smoothness", updated.framePacing);
        assertEquals(119.88, updated.actualDisplayRefreshRate, 0);
        assertEquals("true", updated.enableHdr);
        assertEquals("false", updated.enablePerfOverlay);
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsBitrateBelowMinimum() {
        AppPreferences.copyWithStreamSettings(
                new AppPreferences.AppSettings(), 1280, 720, 60, 499);
    }
}
