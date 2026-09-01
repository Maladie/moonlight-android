package com.limelight.ui.overlay;

import com.limelight.gateway.GatewayConnection;
import com.limelight.gateway.GatewayTransport;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Small, certificate-pinned client for Discord controls exposed by Wake & Play Gateway. */
public final class DiscordGatewayClient {
    private static final int READ_TIMEOUT_MS = 12000;
    private final GatewayTransport transport = new GatewayTransport();

    public static final class Participant {
        public final String id;
        public final String name;
        public final int volume;
        public final boolean muted;
        public final boolean speaking;
        public final boolean self;

        Participant(String id, String name, int volume, boolean muted,
                    boolean speaking, boolean self) {
            this.id = id;
            this.name = name;
            this.volume = volume;
            this.muted = muted;
            this.speaking = speaking;
            this.self = self;
        }
    }

    public static final class VoiceState {
        public final boolean connected;
        public final String channelId;
        public final String channelName;
        public final String guildId;
        public final boolean muted;
        public final boolean deafened;
        public final List<Participant> participants;

        VoiceState(boolean connected, String channelId, String channelName, String guildId,
                   boolean muted, boolean deafened,
                   List<Participant> participants) {
            this.connected = connected;
            this.channelId = channelId;
            this.channelName = channelName;
            this.guildId = guildId;
            this.muted = muted;
            this.deafened = deafened;
            this.participants = Collections.unmodifiableList(participants);
        }
    }

    public static final class ChannelTarget {
        public final String channelId;
        public final String channelName;
        public final String guildId;
        public final String guildName;

        public ChannelTarget(String channelId, String channelName, String guildId, String guildName) {
            this.channelId = channelId;
            this.channelName = channelName;
            this.guildId = guildId;
            this.guildName = guildName;
        }
    }

    public static final class IntegrationProfile {
        public final String id;
        public final String name;
        public final boolean discordBridgeOnline;
        public final boolean discordRpcConnected;
        public final boolean discordAuthenticated;
        public final boolean vibepolloBridgeOnline;
        public final boolean playniteBridgeOnline;
        public final boolean virtualHereAvailable;

        public IntegrationProfile(String id, String name, boolean discordBridgeOnline,
                                  boolean discordRpcConnected, boolean discordAuthenticated,
                                  boolean vibepolloBridgeOnline,
                                  boolean virtualHereAvailable) {
            this(id, name, discordBridgeOnline, discordRpcConnected,
                    discordAuthenticated, vibepolloBridgeOnline, false,
                    virtualHereAvailable);
        }

        public IntegrationProfile(String id, String name, boolean discordBridgeOnline,
                                  boolean discordRpcConnected, boolean discordAuthenticated,
                                  boolean vibepolloBridgeOnline,
                                  boolean playniteBridgeOnline,
                                  boolean virtualHereAvailable) {
            this.id = id;
            this.name = name;
            this.discordBridgeOnline = discordBridgeOnline;
            this.discordRpcConnected = discordRpcConnected;
            this.discordAuthenticated = discordAuthenticated;
            this.vibepolloBridgeOnline = vibepolloBridgeOnline;
            this.playniteBridgeOnline = playniteBridgeOnline;
            this.virtualHereAvailable = virtualHereAvailable;
        }
    }

    public static final class IntegrationProfiles {
        public final List<IntegrationProfile> profiles;
        public final String suggestedProfileId;

        public IntegrationProfiles(List<IntegrationProfile> profiles,
                                   String suggestedProfileId) {
            this.profiles = Collections.unmodifiableList(new ArrayList<>(profiles));
            this.suggestedProfileId = suggestedProfileId;
        }
    }

    public VoiceState getVoice(GatewayConnection connection, boolean force) throws IOException {
        JSONObject response = request(connection,
                "/api/v1/discord/voice" + (force ? "?force=true" : ""), "GET", null);
        JSONObject voice = response.optJSONObject("voice");
        if (voice == null) voice = new JSONObject();
        JSONObject channel = voice.optJSONObject("channel");
        JSONArray values = voice.optJSONArray("participants");
        List<Participant> participants = new ArrayList<>();
        if (values != null) {
            for (int index = 0; index < values.length(); index++) {
                JSONObject value = values.optJSONObject(index);
                if (value == null) continue;
                String id = value.optString("id", "");
                if (!id.matches("[0-9]{5,32}")) continue;
                participants.add(new Participant(
                        id,
                        value.optString("name", "Discord user"),
                        Math.max(0, Math.min(200, value.optInt("volume", 100))),
                        value.optBoolean("muted", false),
                        value.optBoolean("speaking", false),
                        value.optBoolean("is_self", false)));
            }
        }
        return new VoiceState(
                voice.optBoolean("connected", false),
                channel != null ? channel.optString("id", "") : "",
                channel != null ? channel.optString("name", "") : "",
                channel != null ? channel.optString("guild_id", "") : "",
                voice.optBoolean("mute", false),
                voice.optBoolean("deafen", false),
                participants);
    }

    public ChannelTarget getRecentChannel(GatewayConnection connection) throws IOException {
        JSONObject response = request(connection, "/api/v1/discord/home", "GET", null);
        JSONObject home = response.optJSONObject("home");
        JSONArray recent = home != null ? home.optJSONArray("recent") : null;
        if (recent == null) return null;
        for (int index = 0; index < recent.length(); index++) {
            JSONObject value = recent.optJSONObject(index);
            if (value == null) continue;
            String channelId = value.optString("channel_id", "");
            String guildId = value.optString("guild_id", "");
            if (!channelId.matches("[0-9]{5,32}") || !guildId.matches("[0-9]{5,32}")) {
                continue;
            }
            return new ChannelTarget(channelId,
                    value.optString("channel_name", "Voice channel"), guildId,
                    value.optString("guild_name", "Discord"));
        }
        return null;
    }

    public IntegrationProfiles getIntegrationProfiles(GatewayConnection connection) throws IOException {
        JSONObject response = request(connection, "/api/v1/profiles", "GET", null);
        JSONArray values = response.optJSONArray("profiles");
        List<IntegrationProfile> profiles = new ArrayList<>();
        if (values != null) {
            for (int index = 0; index < values.length(); index++) {
                JSONObject value = values.optJSONObject(index);
                if (value == null) continue;
                String id = value.optString("id", "").trim();
                if (!id.matches("[A-Za-z0-9._-]{1,64}")) continue;
                String name = value.optString("name", id).trim();
                profiles.add(new IntegrationProfile(id, name.isEmpty() ? id : name,
                        value.optBoolean("discord_bridge_online", false),
                        value.optBoolean("discord_rpc_connected", false),
                        value.optBoolean("discord_authenticated", false),
                        value.optBoolean("vibepollo_bridge_online", false),
                        value.optBoolean("game_provider_bridge_online",
                                value.optBoolean("playnite_bridge_online", false)),
                        value.optBoolean("virtualhere_available", false)));
            }
        }
        return new IntegrationProfiles(profiles,
                response.optString("suggested_profile_id", ""));
    }

    public void joinChannel(GatewayConnection connection, ChannelTarget target) throws IOException {
        if (target == null || !target.channelId.matches("[0-9]{5,32}") ||
                !target.guildId.matches("[0-9]{5,32}")) {
            throw new IOException("No recent Discord channel is available.");
        }
        JSONObject body = new JSONObject();
        try {
            body.put("channel_id", target.channelId);
            body.put("channel_name", target.channelName);
            body.put("guild_id", target.guildId);
            body.put("guild_name", target.guildName);
        } catch (JSONException impossible) {
            throw new IOException(impossible);
        }
        request(connection, "/api/v1/discord/join", "POST", body);
    }

    public void toggleMute(GatewayConnection connection) throws IOException {
        JSONObject body = new JSONObject();
        try {
            body.put("value", "toggle");
        } catch (JSONException impossible) {
            throw new IOException(impossible);
        }
        request(connection, "/api/v1/discord/mute", "POST", body);
    }

    public void leaveVoice(GatewayConnection connection) throws IOException {
        request(connection, "/api/v1/discord/leave", "POST", new JSONObject());
    }

    private JSONObject request(GatewayConnection connection, String path, String method,
                               JSONObject body) throws IOException {
        return "POST".equals(method)
                ? transport.postJson(connection, path, body, READ_TIMEOUT_MS)
                : transport.getJson(connection, path, READ_TIMEOUT_MS);
    }
}
