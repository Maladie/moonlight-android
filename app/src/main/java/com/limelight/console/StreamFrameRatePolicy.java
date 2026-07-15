package com.limelight.console;

import com.limelight.preferences.PreferenceConfiguration;

/** Pure frame pacing decision shared by the compatibility and unified runtimes. */
public final class StreamFrameRatePolicy {
    public enum Adjustment {
        NONE,
        CAP_TO_DISPLAY,
        FALL_BACK_ABOVE_DISPLAY,
        FALL_BACK_INVALID_REFRESH_RATE
    }

    public static final class Result {
        public final int frameRate;
        public final int framePacing;
        public final Adjustment adjustment;

        private Result(int frameRate, int framePacing, Adjustment adjustment) {
            this.frameRate = frameRate;
            this.framePacing = framePacing;
            this.adjustment = adjustment;
        }
    }

    private StreamFrameRatePolicy() { }

    public static Result evaluate(int requestedFrameRate,
                                  int framePacing,
                                  float displayRefreshRate) {
        int roundedRefreshRate = Math.round(displayRefreshRate);
        if (framePacing != PreferenceConfiguration.FRAME_PACING_CAP_FPS ||
                requestedFrameRate < roundedRefreshRate) {
            return new Result(requestedFrameRate, framePacing, Adjustment.NONE);
        }

        if (requestedFrameRate > roundedRefreshRate + 3) {
            return new Result(requestedFrameRate,
                    PreferenceConfiguration.FRAME_PACING_BALANCED,
                    Adjustment.FALL_BACK_ABOVE_DISPLAY);
        }
        if (roundedRefreshRate <= 49) {
            return new Result(requestedFrameRate,
                    PreferenceConfiguration.FRAME_PACING_BALANCED,
                    Adjustment.FALL_BACK_INVALID_REFRESH_RATE);
        }
        return new Result(roundedRefreshRate - 1, framePacing, Adjustment.CAP_TO_DISPLAY);
    }
}
