package com.limelight.console;

import android.content.Context;

import com.limelight.preferences.AppPreferences;
import com.limelight.preferences.PreferenceConfiguration;

import java.util.Objects;

/** Shared selection of global, per-app, and Quick Launch stream preferences. */
public final class StreamPreferenceContext {
    private StreamPreferenceContext() { }

    public static PreferenceConfiguration load(Context context,
                                               StreamLaunchParameters parameters) {
        Objects.requireNonNull(parameters, "parameters");
        return load(context, parameters.computerUuid, parameters.appId,
                parameters.quickLaunchAppKey, parameters.applyPreferenceOverrides);
    }

    public static PreferenceConfiguration load(Context context,
                                               String computerUuid,
                                               int appId,
                                               String quickLaunchAppKey,
                                               boolean applyPreferenceOverrides) {
        return AppPreferences.getEffectivePreferences(
                Objects.requireNonNull(context, "context"),
                appKey(computerUuid, appId),
                quickLaunchAppKey,
                applyPreferenceOverrides);
    }

    static String appKey(String computerUuid, int appId) {
        return String.valueOf(computerUuid) + ":" + appId;
    }
}
