package com.limelight.console;

/** Parses the optional client refresh-rate override into Moonlight's X100 unit. */
public final class StreamRefreshRateOverridePolicy {
    private StreamRefreshRateOverridePolicy() { }

    public static int parseX100(String configuredRefreshRate) {
        if (configuredRefreshRate == null || configuredRefreshRate.isBlank()) {
            return 0;
        }

        try {
            float refreshRate = Float.parseFloat(configuredRefreshRate.trim());
            if (!Float.isFinite(refreshRate) || refreshRate <= 0) {
                return 0;
            }
            float refreshRateX100 = refreshRate * 100f;
            if (refreshRateX100 > Integer.MAX_VALUE) {
                return 0;
            }
            return (int) refreshRateX100;
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }
}
