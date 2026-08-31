package com.limelight.console;

import android.content.SharedPreferences;

import java.util.Locale;

final class HostAutoWarmUpPreferences {
    private static final String KEY_PREFIX = "auto_stream_warm_up.";

    private HostAutoWarmUpPreferences() { }

    static boolean isEnabled(SharedPreferences preferences, String hostUuid) {
        return preferences.getBoolean(key(hostUuid), false);
    }

    static void setEnabled(SharedPreferences preferences, String hostUuid, boolean enabled) {
        preferences.edit().putBoolean(key(hostUuid), enabled).apply();
    }

    static String key(String hostUuid) {
        return KEY_PREFIX + (hostUuid == null ? "" : hostUuid.trim().toLowerCase(Locale.ROOT));
    }
}
