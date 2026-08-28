package com.limelight.console;

import android.content.Context;

/** Process-global, host-independent Discord notification preference. */
final class DiscordDmNotificationPreferences {
    static final String KEY = "discord_dm_notifications";
    private static final String FILE = "discord_notifications";

    private DiscordDmNotificationPreferences() { }

    static boolean isEnabled(Context context) {
        return context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
                .getBoolean(KEY, true);
    }

    static void setEnabled(Context context, boolean enabled) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY, enabled).apply();
    }
}
