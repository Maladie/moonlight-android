package com.limelight.console;

/** Shared safety bounds for initial and runtime-adjusted stream bitrate. */
public final class StreamBitratePolicy {
    public static final int MIN_KBPS = 1_000;
    public static final int MAX_KBPS = 150_000;

    private StreamBitratePolicy() { }

    public static int clamp(int bitrateKbps) {
        return Math.max(MIN_KBPS, Math.min(MAX_KBPS, bitrateKbps));
    }
}
