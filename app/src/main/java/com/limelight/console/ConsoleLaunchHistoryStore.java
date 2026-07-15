package com.limelight.console;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Locale;

/** Wake-compatible, host-scoped launch history used only for Home presentation. */
final class ConsoleLaunchHistoryStore {
    private static final String PREFS = "launch_history";
    private static final String LAST_HOST_UUID = "last_host_uuid";
    private static final String LAST_APP_ID = "last_app_id";
    private static final String LAST_APP_NAME = "last_app_name";
    private static final String LAST_LAUNCH_AT = "last_launch_at";
    private final SharedPreferences preferences;

    ConsoleLaunchHistoryStore(Context context) {
        preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    void record(ConsoleDataRepository.Host host, ConsoleDataRepository.App app, long now) {
        if (host == null || app == null || now <= 0) return;
        preferences.edit()
                .putString(LAST_HOST_UUID, host.uuid)
                .putInt(LAST_APP_ID, app.id)
                .putString(LAST_APP_NAME, app.name)
                .putLong(LAST_LAUNCH_AT, now)
                .putLong(appKey(host.uuid, app.id), now)
                .putLong(hostKey(host.uuid), now)
                .apply();
    }

    long playedAt(String hostUuid, int appId) {
        long timestamp = preferences.getLong(appKey(hostUuid, appId), 0L);
        if (timestamp <= 0 && hostUuid != null &&
                hostUuid.equals(preferences.getString(LAST_HOST_UUID, null)) &&
                appId == preferences.getInt(LAST_APP_ID, -1)) {
            timestamp = preferences.getLong(LAST_LAUNCH_AT, 0L);
        }
        return timestamp;
    }

    String metadata(String hostUuid, int appId, long now) {
        long timestamp = playedAt(hostUuid, appId);
        return timestamp > 0 ? "LAST PLAYED · " +
                formatRelative(Math.max(0L, now - timestamp)).toUpperCase(Locale.ROOT) : "READY";
    }

    static String formatRelative(long milliseconds) {
        long minutes = Math.max(0L, milliseconds / 60_000L);
        if (minutes < 1) return "just now";
        if (minutes < 60) return minutes + "m ago";
        long hours = minutes / 60;
        if (hours < 24) return hours + "h ago";
        return (hours / 24) + "d ago";
    }

    static String appKey(String hostUuid, int appId) {
        return "played_at.app." + hostUuid + "." + appId;
    }

    static String hostKey(String hostUuid) {
        return "played_at.host." + hostUuid;
    }
}
