package com.limelight.stream;

import android.content.Context;
import android.content.SharedPreferences;

public final class BackgroundStreamPreferences {
    public static final String STORE = "console_dashboard";
    public static final String KEY_RETENTION_MINUTES = "background_stream_retention_minutes";
    public static final int DEFAULT_RETENTION_MINUTES = 30;
    public static final int NEVER = -1;

    private BackgroundStreamPreferences() { }

    public static int readMinutes(Context context) {
        return readMinutes(context.getSharedPreferences(STORE, Context.MODE_PRIVATE));
    }

    static int readMinutes(SharedPreferences preferences) {
        return preferences.contains(KEY_RETENTION_MINUTES)
                ? preferences.getInt(KEY_RETENTION_MINUTES, DEFAULT_RETENTION_MINUTES)
                : DEFAULT_RETENTION_MINUTES;
    }

    public static void writeMinutes(Context context, int minutes) {
        writeMinutes(context.getSharedPreferences(STORE, Context.MODE_PRIVATE), minutes);
    }

    static void writeMinutes(SharedPreferences preferences, int minutes) {
        preferences.edit().putInt(KEY_RETENTION_MINUTES, minutes).apply();
    }
}
