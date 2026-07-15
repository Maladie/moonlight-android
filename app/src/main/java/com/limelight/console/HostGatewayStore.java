package com.limelight.console;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

import com.limelight.PublicStreamIntent;

/**
 * Additive MoonWaker view of Wake & Play's host-keyed Gateway preference schema.
 * It never logs or exposes credentials to UI text.
 */
final class HostGatewayStore {
    private static final String PREFS = "host_gateway_connections";
    private final SharedPreferences preferences;

    HostGatewayStore(Context context) {
        preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    GatewayConnection load(String hostUuid) {
        if (hostUuid == null || !preferences.getBoolean(key(hostUuid, "paired"), false)) {
            return null;
        }
        try {
            return new GatewayConnection(
                    preferences.getString(key(hostUuid, "endpoint"), ""),
                    preferences.getString(key(hostUuid, "token"), ""),
                    preferences.getString(key(hostUuid, "certificate"), ""),
                    selectedIntegrationProfileId(hostUuid));
        }
        catch (IllegalArgumentException invalidStoredConnection) {
            return null;
        }
    }

    void save(String hostUuid, GatewayConnection connection) {
        if (hostUuid == null || hostUuid.isEmpty() || connection == null) return;
        preferences.edit()
                .putBoolean(key(hostUuid, "paired"), true)
                .putString(key(hostUuid, "endpoint"), connection.endpoint)
                .putString(key(hostUuid, "token"), connection.token)
                .putString(key(hostUuid, "certificate"), connection.certificateSha256)
                .putString(key(hostUuid, "integration_profile"), connection.profileId)
                .apply();
    }

    String selectedIntegrationProfileId(String hostUuid) {
        if (hostUuid == null) return GatewayConnection.DEFAULT_PROFILE_ID;
        try {
            return GatewayConnection.normalizeProfileId(preferences.getString(
                    key(hostUuid, "integration_profile"), GatewayConnection.DEFAULT_PROFILE_ID));
        }
        catch (IllegalArgumentException invalidStoredProfile) {
            return GatewayConnection.DEFAULT_PROFILE_ID;
        }
    }

    void setSelectedIntegrationProfileId(String hostUuid, String profileId) {
        if (hostUuid == null || hostUuid.isEmpty()) return;
        String normalized = GatewayConnection.normalizeProfileId(profileId);
        preferences.edit().putString(key(hostUuid, "integration_profile"), normalized).apply();
    }

    boolean putLaunchExtras(Intent intent, String hostUuid) {
        GatewayConnection connection = load(hostUuid);
        if (intent == null || connection == null) return false;
        intent.putExtra(PublicStreamIntent.EXTRA_HOST_GATEWAY_ENDPOINT, connection.endpoint);
        intent.putExtra(PublicStreamIntent.EXTRA_HOST_GATEWAY_TOKEN, connection.token);
        intent.putExtra(PublicStreamIntent.EXTRA_HOST_GATEWAY_CERTIFICATE,
                connection.certificateSha256);
        intent.putExtra(PublicStreamIntent.EXTRA_DISCORD_PROFILE_ID, connection.profileId);
        return true;
    }

    static String key(String hostUuid, String suffix) {
        return hostUuid + "." + suffix;
    }
}
