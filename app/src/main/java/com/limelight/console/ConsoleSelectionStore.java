package com.limelight.console;

import android.content.Context;
import android.content.SharedPreferences;

/** Additive, non-secret Home selection memory. */
final class ConsoleSelectionStore {
    private static final String PREFS = "moonwaker_console_selection";
    private static final String SELECTED_HOST = "selected_host_uuid";
    private final SharedPreferences preferences;

    ConsoleSelectionStore(Context context) {
        preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    String selectedHostUuid() {
        return preferences.getString(SELECTED_HOST, null);
    }

    void rememberHost(String hostUuid) {
        if (hostUuid == null || hostUuid.isEmpty()) return;
        preferences.edit().putString(SELECTED_HOST, hostUuid).apply();
    }

    int selectedAppId(String hostUuid) {
        if (hostUuid == null || hostUuid.isEmpty()) return -1;
        return preferences.getInt(appKey(hostUuid), -1);
    }

    void rememberApp(String hostUuid, int appId) {
        if (hostUuid == null || hostUuid.isEmpty() || appId < 0) return;
        preferences.edit().putInt(appKey(hostUuid), appId).apply();
    }

    static String appKey(String hostUuid) {
        return "selected_app." + hostUuid;
    }
}
