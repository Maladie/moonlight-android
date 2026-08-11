package com.limelight.stream;

import android.content.Context;

import com.limelight.BuildConfig;

public final class BackgroundStreamPreferences {
    public static final String STORE = "console_dashboard";
    public static final String KEY_RETENTION_MINUTES = "background_stream_retention_minutes";
    public static final int NEVER = -1;

    private BackgroundStreamPreferences() { }

    public static int readMinutes(Context context) {
        return context.getSharedPreferences(STORE, Context.MODE_PRIVATE).getInt(
                KEY_RETENTION_MINUTES, BuildConfig.DEBUG ? 5 : 0);
    }
}
