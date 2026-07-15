package com.limelight.console;

import com.limelight.preferences.PreferenceConfiguration;

import org.junit.Test;

import static org.junit.Assert.assertSame;

public class StreamDisplayModePolicyTest {
    @Test public void keepsCurrentResolutionForOrdinarySixtyFpsStream() {
        StreamDisplayModePolicy.Mode current = mode(1, 3840, 2160, 60);
        StreamDisplayModePolicy.Mode highRefresh = mode(2, 1920, 1080, 120);

        assertSame(current, select(current, new StreamDisplayModePolicy.Mode[] {
                current, highRefresh
        }, 1920, 1080, 60, PreferenceConfiguration.FRAME_PACING_MIN_LATENCY,
                false, false, false));
    }

    @Test public void highFpsStreamMayUseLowerResolutionThatStillFits() {
        StreamDisplayModePolicy.Mode current = mode(1, 3840, 2160, 60);
        StreamDisplayModePolicy.Mode highRefresh = mode(2, 1920, 1080, 120);

        assertSame(highRefresh, select(current, new StreamDisplayModePolicy.Mode[] {
                current, highRefresh
        }, 1920, 1080, 120, PreferenceConfiguration.FRAME_PACING_MIN_LATENCY,
                false, false, false));
    }

    @Test public void reduciblePacingPrefersGoodLowerRefreshMatch() {
        StreamDisplayModePolicy.Mode current = mode(1, 3840, 2160, 90);
        StreamDisplayModePolicy.Mode sixty = mode(2, 3840, 2160, 60);

        assertSame(sixty, select(current, new StreamDisplayModePolicy.Mode[] {
                current, sixty
        }, 3840, 2160, 60, PreferenceConfiguration.FRAME_PACING_BALANCED,
                true, true, false));
    }

    @Test public void seamlessFrontendRejectsResolutionSwitch() {
        StreamDisplayModePolicy.Mode current = mode(1, 3840, 2160, 60);
        StreamDisplayModePolicy.Mode highRefresh = mode(2, 1920, 1080, 120);

        assertSame(current, select(current, new StreamDisplayModePolicy.Mode[] {
                current, highRefresh
        }, 1920, 1080, 120, PreferenceConfiguration.FRAME_PACING_MIN_LATENCY,
                false, false, true));
    }

    private static StreamDisplayModePolicy.Mode select(
            StreamDisplayModePolicy.Mode current,
            StreamDisplayModePolicy.Mode[] modes,
            int width, int height, int fps, int pacing,
            boolean reduce, boolean nativeResolution, boolean seamless) {
        return StreamDisplayModePolicy.select(current, modes, width, height, fps,
                pacing, reduce, nativeResolution, seamless);
    }

    private static StreamDisplayModePolicy.Mode mode(
            int id, int width, int height, float refreshRate) {
        return new StreamDisplayModePolicy.Mode(id, width, height, refreshRate);
    }
}
