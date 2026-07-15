package com.limelight.console;

import com.limelight.preferences.PreferenceConfiguration;

import java.util.Objects;

/** Immutable non-UI inputs required to create a session video renderer. */
public final class StreamRendererConfiguration {
    public final PreferenceConfiguration preferences;
    public final int consecutiveCrashCount;
    public final boolean meteredData;
    public final boolean requestedHdr;
    public final String glRenderer;

    public StreamRendererConfiguration(PreferenceConfiguration preferences,
                                       int consecutiveCrashCount,
                                       boolean meteredData,
                                       boolean requestedHdr,
                                       String glRenderer) {
        this.preferences = Objects.requireNonNull(preferences, "preferences");
        this.consecutiveCrashCount = Math.max(0, consecutiveCrashCount);
        this.meteredData = meteredData;
        this.requestedHdr = requestedHdr;
        this.glRenderer = glRenderer == null ? "" : glRenderer;
    }
}
