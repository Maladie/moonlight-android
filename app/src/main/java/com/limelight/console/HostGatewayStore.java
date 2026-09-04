package com.limelight.console;

import android.content.Context;
import android.content.SharedPreferences;

import com.limelight.gateway.GatewayConnection;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Additive MoonWaker view of Wake & Play's host-keyed Gateway preference schema.
 * It never logs or exposes credentials to UI text.
 */
public final class HostGatewayStore {
    private static final String PREFS = "host_gateway_connections";
    static final String DEFAULT_DISCORD_PROFILE_ID = "default";
    private final SharedPreferences preferences;

    static final class DiscordChannelSelection {
        final String channelId;
        final String guildId;
        final String guildName;
        final String channelName;

        DiscordChannelSelection(String channelId, String guildId,
                                String guildName, String channelName) {
            this.channelId = channelId;
            this.guildId = guildId;
            this.guildName = guildName;
            this.channelName = channelName;
        }
    }

    static final class ProfileSelection {
        final List<HostGatewayClient.IntegrationProfile> profiles;
        final HostGatewayClient.IntegrationProfile selected;
        final boolean showSelector;

        ProfileSelection(List<HostGatewayClient.IntegrationProfile> profiles,
                         HostGatewayClient.IntegrationProfile selected) {
            this.profiles = Collections.unmodifiableList(new ArrayList<>(profiles));
            this.selected = selected;
            this.showSelector = profiles.size() > 1;
        }
    }

    public HostGatewayStore(Context context) {
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

    public GatewayConnection loadForHost(String hostUuid, String activeHost) {
        GatewayConnection stored = load(hostUuid);
        if (stored == null || activeHost == null || activeHost.trim().isEmpty()) return stored;
        try {
            URI endpoint = new URI(stored.endpoint());
            String host = activeHost.trim();
            if (host.startsWith("[") && host.endsWith("]")) {
                host = host.substring(1, host.length() - 1);
            }
            URI rebound = new URI(endpoint.getScheme(), endpoint.getUserInfo(), host,
                    endpoint.getPort(), endpoint.getPath(), endpoint.getQuery(),
                    endpoint.getFragment());
            return new GatewayConnection(rebound.toString(), stored.token(),
                    stored.certificateSha256(), stored.profileId());
        }
        catch (URISyntaxException | IllegalArgumentException invalidActiveAddress) {
            return stored;
        }
    }

    public GatewayConnection loadForHost(String hostUuid, String activeHost,
                                         String profileId) {
        GatewayConnection connection = loadForHost(hostUuid, activeHost);
        return connection == null ? null : connection.forProfile(profileId);
    }

    void save(String hostUuid, GatewayConnection connection) {
        if (hostUuid == null || hostUuid.isEmpty() || connection == null) return;
        preferences.edit()
                .putBoolean(key(hostUuid, "paired"), true)
                .putString(key(hostUuid, "endpoint"), connection.endpoint())
                .putString(key(hostUuid, "token"), connection.token())
                .putString(key(hostUuid, "certificate"), connection.certificateSha256())
                .putString(key(hostUuid, "integration_profile"), connection.profileId())
                .apply();
    }

    void remove(String hostUuid) {
        if (hostUuid == null) return;
        SharedPreferences.Editor editor = preferences.edit()
                .remove(key(hostUuid, "paired"))
                .remove(key(hostUuid, "endpoint"))
                .remove(key(hostUuid, "token"))
                .remove(key(hostUuid, "certificate"))
                .remove(key(hostUuid, "integration_profile"))
                .remove(key(hostUuid, "profiles"))
                .remove(key(hostUuid, "discord_auto_connect"))
                .remove(key(hostUuid, "playnite_library_filter"))
                .remove(key(hostUuid, "playnite_installed_only"))
                .remove(key(hostUuid, "playnite_library_sources_configured"))
                .remove(key(hostUuid, "playnite_library_sources"))
                .remove(key(hostUuid, "playnite_library_sort"));
        String discordPrefix = hostUuid + ".discord.";
        for (String preferenceKey : preferences.getAll().keySet()) {
            if (preferenceKey.startsWith(discordPrefix)) editor.remove(preferenceKey);
        }
        editor.apply();
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

    void saveProfiles(String hostUuid, HostGatewayClient.IntegrationProfiles profiles) {
        if (hostUuid == null || hostUuid.isEmpty() || profiles == null) return;
        JSONObject root = new JSONObject();
        JSONArray values = new JSONArray();
        try {
            for (HostGatewayClient.IntegrationProfile profile : profiles.profiles) {
                if (!profile.useProfile) continue;
                JSONObject value = new JSONObject();
                value.put("id", profile.id);
                value.put("name", profile.name);
                value.put("permissions", new JSONObject()
                        .put("use_profile", true)
                        .put("remote_sign_in", profile.remoteSignIn));
                value.put("session_state", profile.sessionState);
                value.put("remote_sign_in_state", profile.remoteSignInState);
                value.put("discord_bridge_online", profile.discordBridgeOnline);
                value.put("discord_rpc_connected", profile.discordRpcConnected);
                value.put("discord_authenticated", profile.discordAuthenticated);
                value.put("vibepollo_bridge_online", profile.vibepolloBridgeOnline);
                value.put("playnite_bridge_online", profile.playniteBridgeOnline);
                value.put("playnite_connector_connected",
                        profile.playniteConnectorConnected);
                value.put("virtualhere_available", profile.virtualHereAvailable);
                values.put(value);
            }
            root.put("profiles", values);
            root.put("suggested_profile_id", profiles.suggestedProfileId);
        } catch (JSONException impossible) {
            throw new IllegalStateException(impossible);
        }
        preferences.edit().putString(key(hostUuid, "profiles"), root.toString()).apply();
    }

    HostGatewayClient.IntegrationProfiles profiles(String hostUuid) {
        if (hostUuid == null || hostUuid.isEmpty()) {
            return new HostGatewayClient.IntegrationProfiles(
                    Collections.emptyList(), "");
        }
        String raw = preferences.getString(key(hostUuid, "profiles"), "");
        if (raw == null || raw.isEmpty()) {
            return new HostGatewayClient.IntegrationProfiles(
                    Collections.emptyList(), "");
        }
        try {
            return HostGatewayClient.parseIntegrationProfiles(new JSONObject(raw));
        } catch (JSONException invalidCache) {
            return new HostGatewayClient.IntegrationProfiles(
                    Collections.emptyList(), "");
        }
    }

    ProfileSelection profileSelection(String hostUuid) {
        HostGatewayClient.IntegrationProfiles cached = profiles(hostUuid);
        return projectProfiles(cached.profiles, selectedIntegrationProfileId(hostUuid),
                cached.suggestedProfileId);
    }

    static ProfileSelection projectProfiles(
            List<HostGatewayClient.IntegrationProfile> profiles,
            String preferredProfileId, String suggestedProfileId) {
        List<HostGatewayClient.IntegrationProfile> allowed = new ArrayList<>();
        if (profiles != null) {
            for (HostGatewayClient.IntegrationProfile profile : profiles) {
                if (profile != null && profile.useProfile) allowed.add(profile);
            }
        }
        HostGatewayClient.IntegrationProfile selected = find(allowed, preferredProfileId);
        if (selected == null) selected = find(allowed, suggestedProfileId);
        if (selected == null && !allowed.isEmpty()) selected = allowed.get(0);
        return new ProfileSelection(allowed, selected);
    }

    private static HostGatewayClient.IntegrationProfile find(
            List<HostGatewayClient.IntegrationProfile> profiles, String profileId) {
        if (profileId == null) return null;
        for (HostGatewayClient.IntegrationProfile profile : profiles) {
            if (profile.id.equals(profileId)) return profile;
        }
        return null;
    }

    boolean isPlayniteInstalledOnly(String hostUuid) {
        return playniteLibraryFilter(hostUuid) == PlayniteLibraryFilter.INSTALLED;
    }

    void setPlayniteInstalledOnly(String hostUuid, boolean installedOnly) {
        setPlayniteLibraryFilter(hostUuid, installedOnly
                ? PlayniteLibraryFilter.INSTALLED : PlayniteLibraryFilter.ALL);
    }

    PlayniteLibraryFilter playniteLibraryFilter(String hostUuid) {
        if (hostUuid == null) return PlayniteLibraryFilter.ALL;
        String filterKey = key(hostUuid, "playnite_library_filter");
        String stored = preferences.contains(filterKey)
                ? preferences.getString(filterKey, null) : null;
        boolean legacyInstalledOnly = preferences.getBoolean(
                key(hostUuid, "playnite_installed_only"), false);
        return PlayniteLibraryFilter.fromPreference(stored, legacyInstalledOnly);
    }

    void setPlayniteLibraryFilter(String hostUuid, PlayniteLibraryFilter filter) {
        if (hostUuid == null || hostUuid.isEmpty()) return;
        PlayniteLibraryFilter safe = filter == null ? PlayniteLibraryFilter.ALL : filter;
        preferences.edit()
                .putString(key(hostUuid, "playnite_library_filter"), safe.preferenceValue)
                .putBoolean(key(hostUuid, "playnite_installed_only"),
                        safe == PlayniteLibraryFilter.INSTALLED)
                .apply();
    }

    Set<String> playniteLibrarySources(String hostUuid) {
        if (hostUuid == null || hostUuid.isEmpty()
                || !preferences.getBoolean(key(hostUuid,
                "playnite_library_sources_configured"), false)) return null;
        Set<String> stored = preferences.getStringSet(
                key(hostUuid, "playnite_library_sources"), null);
        return stored == null ? new HashSet<>() : new HashSet<>(stored);
    }

    void setPlayniteLibrarySources(String hostUuid, Set<String> sources) {
        if (hostUuid == null || hostUuid.isEmpty()) return;
        Set<String> safe = sources == null ? new HashSet<>() : new HashSet<>(sources);
        preferences.edit()
                .putBoolean(key(hostUuid, "playnite_library_sources_configured"), true)
                .putStringSet(key(hostUuid, "playnite_library_sources"), safe)
                .apply();
    }

    void clearPlayniteLibrarySources(String hostUuid) {
        if (hostUuid == null || hostUuid.isEmpty()) return;
        preferences.edit()
                .remove(key(hostUuid, "playnite_library_sources_configured"))
                .remove(key(hostUuid, "playnite_library_sources"))
                .apply();
    }

    PlayniteLibrarySort playniteLibrarySort(String hostUuid) {
        if (hostUuid == null) return PlayniteLibrarySort.RECENT;
        return PlayniteLibrarySort.fromPreference(preferences.getString(
                key(hostUuid, "playnite_library_sort"), null));
    }

    void setPlayniteLibrarySort(String hostUuid, PlayniteLibrarySort sort) {
        if (hostUuid == null || hostUuid.isEmpty()) return;
        PlayniteLibrarySort safe = sort == null ? PlayniteLibrarySort.RECENT : sort;
        preferences.edit().putString(key(hostUuid, "playnite_library_sort"),
                safe.preferenceValue).apply();
    }

    boolean isDiscordAutoConnectEnabled(String hostUuid, String profileId) {
        if (hostUuid == null || profileId == null) return false;
        String profileKey = discordKey(hostUuid, profileId, "auto_connect");
        if (preferences.contains(profileKey)) return preferences.getBoolean(profileKey, false);
        return DEFAULT_DISCORD_PROFILE_ID.equals(profileId) &&
                preferences.getBoolean(key(hostUuid, "discord_auto_connect"), false);
    }

    boolean isDiscordEnabled(String hostUuid, String profileId) {
        if (!validProfile(hostUuid, profileId)) return false;
        return preferences.getBoolean(discordKey(hostUuid, profileId, "enabled"), true);
    }

    void setDiscordEnabled(String hostUuid, String profileId, boolean enabled) {
        if (!validProfile(hostUuid, profileId)) return;
        preferences.edit().putBoolean(discordKey(hostUuid, profileId, "enabled"), enabled).apply();
    }

    String loadLastDiscordGuildId(String hostUuid, String profileId) {
        if (!validProfile(hostUuid, profileId)) return "";
        return preferences.getString(discordKey(hostUuid, profileId, "last_guild_id"), "");
    }

    void saveLastDiscordGuild(String hostUuid, String profileId,
                              String guildId, String guildName) {
        if (!validProfile(hostUuid, profileId) || guildId == null
                || !guildId.matches("[0-9]{5,32}")) return;
        preferences.edit()
                .putString(discordKey(hostUuid, profileId, "last_guild_id"), guildId)
                .putString(discordKey(hostUuid, profileId, "last_guild_name"),
                        guildName == null ? "" : guildName)
                .apply();
    }

    void setDiscordAutoConnectEnabled(String hostUuid, String profileId, boolean enabled) {
        if (!validProfile(hostUuid, profileId)) return;
        SharedPreferences.Editor editor = preferences.edit()
                .putBoolean(discordKey(hostUuid, profileId, "auto_connect"), enabled);
        if (DEFAULT_DISCORD_PROFILE_ID.equals(profileId)) {
            editor.remove(key(hostUuid, "discord_auto_connect"));
        }
        editor.apply();
    }

    boolean isDiscordAutoJoinLastEnabled(String hostUuid, String profileId) {
        return validProfile(hostUuid, profileId) && preferences.getBoolean(
                discordKey(hostUuid, profileId, "auto_join_last"), false);
    }

    void setDiscordAutoJoinLastEnabled(String hostUuid, String profileId, boolean enabled) {
        if (!validProfile(hostUuid, profileId)) return;
        preferences.edit().putBoolean(
                discordKey(hostUuid, profileId, "auto_join_last"), enabled).apply();
    }

    DiscordChannelSelection loadLastDiscordChannel(String hostUuid, String profileId) {
        if (!validProfile(hostUuid, profileId)) return null;
        String channelId = preferences.getString(
                discordKey(hostUuid, profileId, "last_channel_id"), "");
        String guildId = preferences.getString(
                discordKey(hostUuid, profileId, "last_guild_id"), "");
        String channelName = preferences.getString(
                discordKey(hostUuid, profileId, "last_channel_name"), "");
        if (channelId.isEmpty() || guildId.isEmpty() || channelName.isEmpty()) return null;
        return new DiscordChannelSelection(channelId, guildId,
                preferences.getString(discordKey(hostUuid, profileId, "last_guild_name"), ""),
                channelName);
    }

    void saveLastDiscordChannel(String hostUuid, String profileId,
                                String channelId, String guildId,
                                String guildName, String channelName) {
        if (!validProfile(hostUuid, profileId) || channelId == null || channelId.isEmpty() ||
                guildId == null || guildId.isEmpty() ||
                channelName == null || channelName.isEmpty()) return;
        preferences.edit()
                .putString(discordKey(hostUuid, profileId, "last_channel_id"), channelId)
                .putString(discordKey(hostUuid, profileId, "last_guild_id"), guildId)
                .putString(discordKey(hostUuid, profileId, "last_guild_name"),
                        guildName != null ? guildName : "")
                .putString(discordKey(hostUuid, profileId, "last_channel_name"), channelName)
                .apply();
    }

    static String key(String hostUuid, String suffix) {
        return hostUuid + "." + suffix;
    }

    static String discordKey(String hostUuid, String profileId, String suffix) {
        return hostUuid + ".discord." + profileId + "." + suffix;
    }

    private static boolean validProfile(String hostUuid, String profileId) {
        return hostUuid != null && !hostUuid.isEmpty() && profileId != null &&
                profileId.matches("[A-Za-z0-9._-]{1,64}");
    }
}
