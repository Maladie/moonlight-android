package com.limelight.console;

import com.limelight.preferences.PreferenceConfiguration;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class StreamFrameRatePolicyTest {
    @Test public void leavesNonCappedPacingUnchanged() {
        StreamFrameRatePolicy.Result result = StreamFrameRatePolicy.evaluate(
                120, PreferenceConfiguration.FRAME_PACING_MIN_LATENCY, 60f);

        assertEquals(120, result.frameRate);
        assertEquals(PreferenceConfiguration.FRAME_PACING_MIN_LATENCY, result.framePacing);
        assertEquals(StreamFrameRatePolicy.Adjustment.NONE, result.adjustment);
    }

    @Test public void leavesFrameRateBelowDisplayRefreshUnchanged() {
        StreamFrameRatePolicy.Result result = capped(60, 120f);

        assertEquals(60, result.frameRate);
        assertEquals(StreamFrameRatePolicy.Adjustment.NONE, result.adjustment);
    }

    @Test public void capsNearRefreshRateOneFrameBelowDisplay() {
        StreamFrameRatePolicy.Result result = capped(60, 59.94f);

        assertEquals(59, result.frameRate);
        assertEquals(PreferenceConfiguration.FRAME_PACING_CAP_FPS, result.framePacing);
        assertEquals(StreamFrameRatePolicy.Adjustment.CAP_TO_DISPLAY, result.adjustment);
    }

    @Test public void fallsBackWhenRequestedRateIsFarAboveDisplay() {
        StreamFrameRatePolicy.Result result = capped(120, 60f);

        assertEquals(120, result.frameRate);
        assertEquals(PreferenceConfiguration.FRAME_PACING_BALANCED, result.framePacing);
        assertEquals(StreamFrameRatePolicy.Adjustment.FALL_BACK_ABOVE_DISPLAY,
                result.adjustment);
    }

    @Test public void fallsBackForImplausiblyLowRefreshRate() {
        StreamFrameRatePolicy.Result result = capped(48, 48f);

        assertEquals(48, result.frameRate);
        assertEquals(PreferenceConfiguration.FRAME_PACING_BALANCED, result.framePacing);
        assertEquals(StreamFrameRatePolicy.Adjustment.FALL_BACK_INVALID_REFRESH_RATE,
                result.adjustment);
    }

    private static StreamFrameRatePolicy.Result capped(int requestedFrameRate,
                                                        float displayRefreshRate) {
        return StreamFrameRatePolicy.evaluate(requestedFrameRate,
                PreferenceConfiguration.FRAME_PACING_CAP_FPS, displayRefreshRate);
    }
}
