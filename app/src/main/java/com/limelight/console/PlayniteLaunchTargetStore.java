package com.limelight.console;

import android.content.Context;
import android.content.SharedPreferences;

/** Explicit host-scoped Playnite game -> Sunshine app mappings. */
final class PlayniteLaunchTargetStore {
    private static final String PREFS = "playnite_launch_targets";
    private final SharedPreferences preferences;

    PlayniteLaunchTargetStore(Context context) {
        preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    Integer gameTarget(String hostUuid, String gameId) {
        String key = gameKey(hostUuid, gameId);
        if (key == null || !preferences.contains(key)) return null;
        int id = preferences.getInt(key, 0);
        return id > 0 ? id : null;
    }

    void setGameTarget(String hostUuid, String gameId, int appId) {
        String key = gameKey(hostUuid, gameId);
        if (key == null || appId <= 0) return;
        if (preferences.getInt(key, 0) == appId) return;
        preferences.edit().putInt(key, appId).apply();
    }

    void clearGameTarget(String hostUuid, String gameId) {
        String key = gameKey(hostUuid, gameId);
        if (key != null && preferences.contains(key)) {
            preferences.edit().remove(key).apply();
        }
    }

    Integer playniteTarget(String hostUuid) {
        if (!validHost(hostUuid)) return null;
        int id = preferences.getInt(hostUuid + ".fullscreen", 0);
        return id > 0 ? id : null;
    }

    void setPlayniteTarget(String hostUuid, int appId) {
        if (!validHost(hostUuid) || appId <= 0) return;
        String key = hostUuid + ".fullscreen";
        if (preferences.getInt(key, 0) == appId) return;
        preferences.edit().putInt(key, appId).apply();
    }

    void clearPlayniteTarget(String hostUuid) {
        String key = hostUuid + ".fullscreen";
        if (validHost(hostUuid) && preferences.contains(key)) {
            preferences.edit().remove(key).apply();
        }
    }

    private static String gameKey(String hostUuid, String gameId) {
        return validHost(hostUuid) && HostGatewayClient.isPlayniteId(gameId)
                ? hostUuid + ".game." + gameId.toLowerCase(java.util.Locale.ROOT) : null;
    }

    private static boolean validHost(String value) {
        return value != null && value.matches("[A-Za-z0-9._-]{1,128}");
    }
}
