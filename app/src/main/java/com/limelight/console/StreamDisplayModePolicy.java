package com.limelight.console;

import com.limelight.preferences.PreferenceConfiguration;

import java.util.Objects;

/** Pure display-mode selection shared by Game and the unified Console runtime. */
public final class StreamDisplayModePolicy {
    public static final class Mode {
        public final int id;
        public final int width;
        public final int height;
        public final float refreshRate;

        public Mode(int id, int width, int height, float refreshRate) {
            this.id = id;
            this.width = width;
            this.height = height;
            this.refreshRate = refreshRate;
        }
    }

    private StreamDisplayModePolicy() { }

    public static Mode select(Mode current,
                              Mode[] supportedModes,
                              int streamWidth,
                              int streamHeight,
                              int streamFps,
                              int framePacing,
                              boolean reduceRefreshRate,
                              boolean nativeResolutionStream,
                              boolean keepCurrentResolution) {
        Objects.requireNonNull(current, "current");
        Objects.requireNonNull(supportedModes, "supportedModes");
        Mode best = current;
        boolean refreshRateIsGood = isGoodMatch(best.refreshRate, streamFps);
        boolean refreshRateIsEqual = isEqualMatch(best.refreshRate, streamFps);
        boolean mayReduce = mayReduceRefreshRate(framePacing, reduceRefreshRate);

        for (Mode candidate : supportedModes) {
            if (candidate == null) continue;
            boolean refreshRateReduced = candidate.refreshRate < best.refreshRate;
            boolean resolutionReduced = candidate.width < best.width ||
                    candidate.height < best.height;
            boolean resolutionFitsStream = candidate.width >= streamWidth &&
                    candidate.height >= streamHeight;

            if (candidate.width > 4096 && streamWidth <= 4096) continue;
            if (streamWidth < 3840 && streamFps <= 60 && !nativeResolutionStream &&
                    (current.width != candidate.width || current.height != candidate.height)) {
                continue;
            }
            if (resolutionReduced && !(streamFps > 60 && resolutionFitsStream)) continue;

            if (mayReduce && refreshRateIsEqual &&
                    !isEqualMatch(candidate.refreshRate, streamFps)) {
                continue;
            } else if (refreshRateIsGood) {
                if (!isGoodMatch(candidate.refreshRate, streamFps)) continue;
                if (mayReduce) {
                    if (candidate.refreshRate > best.refreshRate) continue;
                } else if (refreshRateReduced) {
                    continue;
                }
            } else if (!isGoodMatch(candidate.refreshRate, streamFps) && refreshRateReduced) {
                continue;
            }

            best = candidate;
            refreshRateIsGood = isGoodMatch(candidate.refreshRate, streamFps);
            refreshRateIsEqual = isEqualMatch(candidate.refreshRate, streamFps);
        }

        if (keepCurrentResolution && current.id != best.id &&
                (current.width != best.width || current.height != best.height)) {
            return current;
        }
        return best;
    }

    private static boolean isEqualMatch(float refreshRate, int fps) {
        return refreshRate >= fps && refreshRate <= fps + 3;
    }

    private static boolean isGoodMatch(float refreshRate, int fps) {
        return refreshRate >= fps && Math.round(refreshRate) % fps <= 3;
    }

    private static boolean mayReduceRefreshRate(int framePacing,
                                                boolean reduceRefreshRate) {
        return framePacing == PreferenceConfiguration.FRAME_PACING_CAP_FPS ||
                framePacing == PreferenceConfiguration.FRAME_PACING_MAX_SMOOTHNESS ||
                (framePacing == PreferenceConfiguration.FRAME_PACING_BALANCED &&
                        reduceRefreshRate);
    }
}
