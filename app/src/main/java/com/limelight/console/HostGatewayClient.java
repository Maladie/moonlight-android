package com.limelight.console;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URLEncoder;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSession;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

final class HostGatewayClient {
    static final int DEFAULT_PORT = 8785;
    static final int REQUIRED_GAMEPLAY_PERMISSIONS = 0x07001F00;
    private static final int CONNECT_TIMEOUT_MS = 2_500;
    private static final int READ_TIMEOUT_MS = 5_000;

    static final class Connection {
        final String endpoint;
        final String token;
        final String certificateSha256;
        final String profileId;

        Connection(String endpoint, String token, String certificateSha256) {
            this(endpoint, token, certificateSha256, "default");
        }

        Connection(String endpoint, String token, String certificateSha256,
                   String profileId) {
            this.endpoint = trimSlash(endpoint);
            this.token = token;
            this.certificateSha256 = normalizeFingerprint(certificateSha256);
            String normalizedProfile = profileId == null || profileId.trim().isEmpty() ?
                    "default" : profileId.trim();
            if (!normalizedProfile.matches("[A-Za-z0-9._-]{1,64}")) {
                throw new IllegalArgumentException("Invalid integration profile ID");
            }
            this.profileId = normalizedProfile;
        }

        Connection withProfile(String profileId) {
            return new Connection(endpoint, token, certificateSha256, profileId);
        }
    }

    static final class Pairing {
        final Connection connection;
        final String clientId;
        final String streamPairTicket;

        Pairing(Connection connection, String clientId, String streamPairTicket) {
            this.connection = connection;
            this.clientId = clientId;
            this.streamPairTicket = streamPairTicket == null ? "" : streamPairTicket;
        }
    }

    static final class Capabilities {
        final boolean vibepolloFix;
        final boolean discord;
        final boolean virtualHere;
        final boolean playnite;

        Capabilities(boolean vibepolloFix, boolean discord, boolean virtualHere) {
            this(vibepolloFix, discord, virtualHere, false);
        }

        Capabilities(boolean vibepolloFix, boolean discord, boolean virtualHere,
                     boolean playnite) {
            this.vibepolloFix = vibepolloFix;
            this.discord = discord;
            this.virtualHere = virtualHere;
            this.playnite = playnite;
        }
    }

    static final class IntegrationProfile {
        final String id;
        final String name;
        final boolean discordBridgeOnline;
        final boolean discordRpcConnected;
        final boolean discordAuthenticated;
        final boolean vibepolloBridgeOnline;
        final boolean playniteBridgeOnline;
        final boolean virtualHereAvailable;

        IntegrationProfile(String id, String name, boolean discordBridgeOnline,
                           boolean discordRpcConnected, boolean discordAuthenticated,
                           boolean vibepolloBridgeOnline, boolean virtualHereAvailable) {
            this(id, name, discordBridgeOnline, discordRpcConnected,
                    discordAuthenticated, vibepolloBridgeOnline, false,
                    virtualHereAvailable);
        }

        IntegrationProfile(String id, String name, boolean discordBridgeOnline,
                           boolean discordRpcConnected, boolean discordAuthenticated,
                           boolean vibepolloBridgeOnline, boolean playniteBridgeOnline,
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

    static final class PlayniteGame {
        final String id;
        final String name;
        final boolean installed;
        final boolean installing;
        final boolean hidden;
        final boolean favorite;
        final String cover;
        final String background;
        final String lastPlayed;
        final String description;
        final int playCount;
        final String source;
        final String genres;
        final String artworkVersion;
        final boolean installRequiresAttention;
        final String installAttentionReason;
        final String installWindowTitle;
        final String installLauncher;
        final String operationState;
        final int operationProgress;
        final boolean uninstalling;
        final long playtimeSeconds;
        /** Compatibility view for older console code. New code uses seconds. */
        final long playtimeMinutes;

        PlayniteGame(String id, String name, boolean installed, boolean favorite,
                     String cover, String background, String lastPlayed,
                     long playtimeMinutes) {
            this(id, name, installed, false, favorite, cover, background, lastPlayed,
                    "", 0, "", "", Math.max(0L, playtimeMinutes) * 60L);
        }

        PlayniteGame(String id, String name, boolean installed, boolean hidden,
                     boolean favorite, String cover, String background, String lastPlayed,
                     String source, String artworkVersion, long playtimeSeconds) {
            this(id, name, installed, hidden, favorite, cover, background, lastPlayed,
                    "", 0, source, artworkVersion, playtimeSeconds);
        }

        PlayniteGame(String id, String name, boolean installed, boolean hidden,
                     boolean favorite, String cover, String background, String lastPlayed,
                     String description, String source, String artworkVersion,
                     long playtimeSeconds) {
            this(id, name, installed, hidden, favorite, cover, background, lastPlayed,
                    description, 0, source, artworkVersion, playtimeSeconds);
        }

        PlayniteGame(String id, String name, boolean installed, boolean hidden,
                     boolean favorite, String cover, String background, String lastPlayed,
                     String description, int playCount, String source, String artworkVersion,
                     long playtimeSeconds) {
            this(id, name, installed, false, hidden, favorite, cover, background, lastPlayed,
                    description, playCount, source, artworkVersion, playtimeSeconds);
        }

        PlayniteGame(String id, String name, boolean installed, boolean installing,
                     boolean hidden, boolean favorite, String cover, String background,
                     String lastPlayed, String description, int playCount, String source,
                     String artworkVersion, long playtimeSeconds) {
            this(id, name, installed, installing, hidden, favorite, cover, background,
                    lastPlayed, description, playCount, source, "", artworkVersion,
                    playtimeSeconds);
        }

        PlayniteGame(String id, String name, boolean installed, boolean installing,
                     boolean hidden, boolean favorite, String cover, String background,
                     String lastPlayed, String description, int playCount, String source,
                     String genres, String artworkVersion, long playtimeSeconds) {
            this(id, name, installed, installing, hidden, favorite, cover, background,
                    lastPlayed, description, playCount, source, genres, artworkVersion,
                    playtimeSeconds, false, "", "", "");
        }

        PlayniteGame(String id, String name, boolean installed, boolean installing,
                     boolean hidden, boolean favorite, String cover, String background,
                     String lastPlayed, String description, int playCount, String source,
                     String genres, String artworkVersion, long playtimeSeconds,
                     boolean installRequiresAttention, String installAttentionReason,
                     String installWindowTitle, String installLauncher) {
            this(id, name, installed, installing, hidden, favorite, cover, background,
                    lastPlayed, description, playCount, source, genres, artworkVersion,
                    playtimeSeconds, installRequiresAttention, installAttentionReason,
                    installWindowTitle, installLauncher, "", -1, false);
        }

        PlayniteGame(String id, String name, boolean installed, boolean installing,
                     boolean hidden, boolean favorite, String cover, String background,
                     String lastPlayed, String description, int playCount, String source,
                     String genres, String artworkVersion, long playtimeSeconds,
                     boolean installRequiresAttention, String installAttentionReason,
                     String installWindowTitle, String installLauncher,
                     String operationState, int operationProgress, boolean uninstalling) {
            this.id = id;
            this.name = name;
            this.installed = installed;
            this.installing = installing;
            this.hidden = hidden;
            this.favorite = favorite;
            this.cover = cover;
            this.background = background;
            this.lastPlayed = lastPlayed;
            this.description = description;
            this.playCount = Math.max(0, playCount);
            this.source = source;
            this.genres = genres;
            this.artworkVersion = artworkVersion;
            this.installRequiresAttention = installRequiresAttention;
            this.installAttentionReason = installAttentionReason;
            this.installWindowTitle = installWindowTitle == null ? "" : installWindowTitle;
            this.installLauncher = installLauncher == null ? "" : installLauncher;
            this.operationState = operationState == null ? "" : operationState;
            this.operationProgress = operationProgress;
            this.uninstalling = uninstalling;
            this.playtimeSeconds = Math.max(0L, playtimeSeconds);
            this.playtimeMinutes = this.playtimeSeconds / 60L;
        }
    }

    static final class PlayniteLibrary {
        final List<PlayniteGame> games;
        final String nextCursor;
        final int total;
        final String revision;
        final String apiVersion;

        PlayniteLibrary(List<PlayniteGame> games, String nextCursor, int total) {
            this(games, nextCursor, total, "", "");
        }

        PlayniteLibrary(List<PlayniteGame> games, String nextCursor, int total,
                        String revision, String apiVersion) {
            this.games = Collections.unmodifiableList(games);
            this.nextCursor = nextCursor;
            this.total = total;
            this.revision = revision == null ? "" : revision;
            this.apiVersion = apiVersion == null ? "" : apiVersion;
        }
    }

    static final class PlayniteReadiness {
        final boolean ready;
        final String reason;
        final String targetKind;
        final int stableSamples;
        final int processId;
        final String display;

        PlayniteReadiness(boolean ready, String reason, String targetKind,
                          int stableSamples, int processId, String display) {
            this.ready = ready;
            this.reason = reason;
            this.targetKind = targetKind;
            this.stableSamples = stableSamples;
            this.processId = processId;
            this.display = display;
        }
    }

    static final class PlayniteHealth {
        final boolean connectorConnected;
        final int connectorGeneration;

        PlayniteHealth(boolean connectorConnected, int connectorGeneration) {
            this.connectorConnected = connectorConnected;
            this.connectorGeneration = Math.max(0, connectorGeneration);
        }
    }

    static final class PlayniteCurrentGame {
        final String state;
        final String id;
        final String title;
        final int processId;

        PlayniteCurrentGame(String state, String id, String title, int processId) {
            this.state = state;
            this.id = id;
            this.title = title;
            this.processId = processId;
        }
    }

    static final class PlayniteEvent {
        final long sequence;
        final String name;
        final String gameId;
        final String gameName;

        PlayniteEvent(long sequence, String name, String gameId, String gameName) {
            this.sequence = sequence;
            this.name = name;
            this.gameId = gameId;
            this.gameName = gameName == null ? "" : gameName.trim();
        }
    }

    static final class PlayniteEvents {
        final List<PlayniteEvent> events;
        final long latestSequence;

        PlayniteEvents(List<PlayniteEvent> events, long latestSequence) {
            this.events = Collections.unmodifiableList(events);
            this.latestSequence = latestSequence;
        }
    }

    static final class IntegrationProfiles {
        final List<IntegrationProfile> profiles;
        final String suggestedProfileId;

        IntegrationProfiles(List<IntegrationProfile> profiles, String suggestedProfileId) {
            this.profiles = Collections.unmodifiableList(profiles);
            this.suggestedProfileId = suggestedProfileId;
        }

        IntegrationProfile find(String profileId) {
            for (IntegrationProfile profile : profiles) {
                if (profile.id.equals(profileId)) return profile;
            }
            return null;
        }
    }

    static final class RepairStatus {
        final boolean online;
        final String version;
        final String error;

        RepairStatus(boolean online, String version, String error) {
            this.online = online;
            this.version = version;
            this.error = error;
        }
    }

    static final class DiscordStatus {
        final boolean bridgeOnline;
        final boolean rpcConnected;
        final boolean authenticated;
        final String error;

        DiscordStatus(boolean bridgeOnline, boolean rpcConnected, boolean authenticated, String error) {
            this.bridgeOnline = bridgeOnline;
            this.rpcConnected = rpcConnected;
            this.authenticated = authenticated;
            this.error = error;
        }
    }

    static final class DiscordGuild {
        final String id;
        final String name;

        DiscordGuild(String id, String name) {
            this.id = id;
            this.name = name;
        }
    }

    static final class DiscordChannel {
        final String id;
        final String guildId;
        final String guildName;
        final String name;
        final int people;
        final boolean favorite;

        DiscordChannel(String id, String guildId, String guildName, String name,
                       int people, boolean favorite) {
            this.id = id;
            this.guildId = guildId;
            this.guildName = guildName;
            this.name = name;
            this.people = people;
            this.favorite = favorite;
        }
    }

    static final class DiscordHome {
        final List<DiscordChannel> favorites;
        final List<DiscordChannel> recent;
        final List<DiscordGuild> guilds;

        DiscordHome(List<DiscordChannel> favorites, List<DiscordChannel> recent,
                    List<DiscordGuild> guilds) {
            this.favorites = Collections.unmodifiableList(favorites);
            this.recent = Collections.unmodifiableList(recent);
            this.guilds = Collections.unmodifiableList(guilds);
        }
    }

    static final class DiscordVoice {
        final boolean connected;
        final String channelId;
        final String channelName;
        final String guildId;
        final boolean muted;
        final boolean deafened;
        final int participants;
        final List<DiscordParticipant> participantList;

        DiscordVoice(boolean connected, String channelId, String channelName, String guildId,
                     boolean muted, boolean deafened, int participants) {
            this(connected, channelId, channelName, guildId, muted, deafened,
                    participants, Collections.emptyList());
        }

        DiscordVoice(boolean connected, String channelId, String channelName, String guildId,
                     boolean muted, boolean deafened, int participants,
                     List<DiscordParticipant> participantList) {
            this.connected = connected;
            this.channelId = channelId;
            this.channelName = channelName;
            this.guildId = guildId;
            this.muted = muted;
            this.deafened = deafened;
            this.participants = participants;
            this.participantList = Collections.unmodifiableList(participantList);
        }
    }

    static final class DiscordParticipant {
        final String id;
        final String name;
        final String username;
        final int volume;
        final boolean muted;
        final boolean deafened;
        final boolean speaking;
        final boolean self;
        final boolean bot;
        final boolean canSetVolume;
        final String audioError;

        DiscordParticipant(String id, String name, int volume, boolean muted,
                           boolean speaking, boolean self) {
            this(id, name, "", volume, muted, false, speaking, self,
                    false, true, "");
        }

        DiscordParticipant(String id, String name, String username, int volume,
                           boolean muted, boolean deafened, boolean speaking,
                           boolean self, boolean bot, boolean canSetVolume,
                           String audioError) {
            this.id = id;
            this.name = name;
            this.username = username;
            this.volume = volume;
            this.muted = muted;
            this.deafened = deafened;
            this.speaking = speaking;
            this.self = self;
            this.bot = bot;
            this.canSetVolume = canSetVolume;
            this.audioError = audioError;
        }
    }

    static final class VirtualHereDevice {
        final String address;
        final String name;
        final boolean available;
        final boolean inUse;
        final boolean inUseByMe;
        final boolean autoUse;
        final String boundHostname;

        VirtualHereDevice(String address, String name, boolean available,
                          boolean inUse, boolean inUseByMe, boolean autoUse,
                          String boundHostname) {
            this.address = address;
            this.name = name;
            this.available = available;
            this.inUse = inUse;
            this.inUseByMe = inUseByMe;
            this.autoUse = autoUse;
            this.boundHostname = boundHostname;
        }
    }

    static final class VirtualHereServer {
        final String name;
        final String hostname;
        final List<VirtualHereDevice> devices;

        VirtualHereServer(String name, String hostname, List<VirtualHereDevice> devices) {
            this.name = name;
            this.hostname = hostname;
            this.devices = Collections.unmodifiableList(devices);
        }
    }

    static final class VirtualHereState {
        final boolean installed;
        final boolean running;
        final List<VirtualHereServer> servers;
        final String error;

        VirtualHereState(boolean installed, boolean running,
                         List<VirtualHereServer> servers, String error) {
            this.installed = installed;
            this.running = running;
            this.servers = Collections.unmodifiableList(servers);
            this.error = error;
        }
    }

    static final class AudioDevice {
        final String id;
        final String name;
        final String flow;
        final boolean current;
        final boolean system;

        AudioDevice(String id, String name, String flow,
                    boolean current, boolean system) {
            this.id = id;
            this.name = name;
            this.flow = flow;
            this.current = current;
            this.system = system;
        }
    }

    static final class DiscordAudioState {
        final boolean systemAvailable;
        final int systemVolume;
        final boolean systemMuted;
        final List<AudioDevice> systemDevices;
        final List<AudioDevice> discordDevices;
        final String error;

        DiscordAudioState(boolean systemAvailable, int systemVolume,
                          boolean systemMuted, List<AudioDevice> systemDevices,
                          List<AudioDevice> discordDevices, String error) {
            this.systemAvailable = systemAvailable;
            this.systemVolume = systemVolume;
            this.systemMuted = systemMuted;
            this.systemDevices = Collections.unmodifiableList(systemDevices);
            this.discordDevices = Collections.unmodifiableList(discordDevices);
            this.error = error;
        }
    }

    static final class GatewayException extends IOException {
        final int statusCode;

        GatewayException(String message, int statusCode) {
            super(message);
            this.statusCode = statusCode;
        }
    }

    private static final HostnameVerifier PINNED_HOSTNAME_VERIFIER =
            new HostnameVerifier() {
                @Override
                public boolean verify(String hostname, SSLSession session) {
                    // Identity is verified by the pinned leaf certificate. The
                    // generated host certificate intentionally does not depend on
                    // a DHCP address that may change later.
                    return true;
                }
            };

    static String endpointForHost(String address) {
        String host = address == null ? "" : address.trim();
        if (host.startsWith("[")) {
            return "https://" + host + ":" + DEFAULT_PORT;
        }
        if (host.indexOf(':') >= 0) {
            return "https://[" + host + "]:" + DEFAULT_PORT;
        }
        return "https://" + host + ":" + DEFAULT_PORT;
    }

    Pairing pair(String endpoint, String code, String clientName) throws IOException {
        if (code == null || !code.matches("[0-9]{6}")) {
            throw new GatewayException("The pairing code must contain six digits.", 0);
        }
        PairingTrustManager trustManager = new PairingTrustManager(null, true);
        JSONObject request = new JSONObject();
        try {
            request.put("code", code);
            request.put("client_name", clientName);
        } catch (JSONException impossible) {
            throw new IOException(impossible);
        }
        JSONObject response = request(trimSlash(endpoint), "/api/v1/pair", "POST",
                request, null, trustManager, READ_TIMEOUT_MS);
        String fingerprint = trustManager.seenFingerprint;
        if (fingerprint == null || fingerprint.isEmpty()) {
            throw new GatewayException("The gateway did not present a certificate.", 0);
        }
        String token = response.optString("token", "");
        if (token.isEmpty()) throw new GatewayException("The gateway returned no client token.", 0);
        return new Pairing(new Connection(endpoint, token, fingerprint),
                response.optString("client_id", ""),
                response.optString("stream_pair_ticket", ""));
    }

    String requestVibepolloPairingTicket(Connection connection) throws IOException {
        JSONObject response = request(connection.endpoint,
                "/api/v1/vibepollo/pair/ticket", "POST", new JSONObject(),
                connection, pinnedTrust(connection), READ_TIMEOUT_MS);
        String ticket = response.optString("stream_pair_ticket", "");
        if (ticket.isEmpty()) {
            throw new GatewayException("The gateway returned no stream pairing ticket.", 0);
        }
        return ticket;
    }

    JSONObject pairVibepolloClient(Connection connection, String ticket,
                                   String pin, String name) throws IOException {
        if (ticket == null || ticket.trim().isEmpty()) {
            throw new IllegalArgumentException("Missing stream pairing ticket");
        }
        if (pin == null || !pin.matches("[0-9]{4}")) {
            throw new IllegalArgumentException("Invalid Moonlight pairing PIN");
        }
        String safeName = name == null ? "" : name.trim();
        if (safeName.isEmpty() || safeName.length() > 80 ||
                safeName.matches(".*[\\x00-\\x1f\\x7f].*")) {
            throw new IllegalArgumentException("Invalid Moonlight client name");
        }
        JSONObject body = new JSONObject();
        try {
            body.put("ticket", ticket);
            body.put("pin", pin);
            body.put("name", safeName);
        } catch (JSONException impossible) {
            throw new IOException(impossible);
        }
        JSONObject response = request(connection.endpoint,
                "/api/v1/vibepollo/pair", "POST", body, connection,
                pinnedTrust(connection), 16_000);
        if (!response.optBoolean("ok", false)) {
            throw new GatewayException(response.optString("error",
                    "The Moonlight client could not be paired."), 0);
        }
        return response;
    }

    Capabilities getCapabilities(Connection connection) throws IOException {
        JSONObject response = request(connection.endpoint, "/api/v1/capabilities", "GET",
                null, connection, pinnedTrust(connection), READ_TIMEOUT_MS);
        JSONObject capabilities = response.optJSONObject("capabilities");
        return new Capabilities(available(capabilities, "vibepollo_fix"),
                available(capabilities, "discord"),
                available(capabilities, "virtualhere"),
                available(capabilities, "playnite"));
    }

    IntegrationProfiles getIntegrationProfiles(Connection connection) throws IOException {
        JSONObject response = request(connection.endpoint, "/api/v1/profiles", "GET",
                null, connection, pinnedTrust(connection), 15_000);
        JSONArray values = response.optJSONArray("profiles");
        List<IntegrationProfile> profiles = new ArrayList<>();
        if (values != null) {
            for (int index = 0; index < values.length(); index++) {
                JSONObject value = values.optJSONObject(index);
                if (value == null) continue;
                String id = value.optString("id", "").trim();
                if (!id.matches("[A-Za-z0-9._-]{1,64}")) continue;
                String name = value.optString("name", id).trim();
                profiles.add(new IntegrationProfile(
                        id, name.isEmpty() ? id : name,
                        value.optBoolean("discord_bridge_online", false),
                        value.optBoolean("discord_rpc_connected", false),
                        value.optBoolean("discord_authenticated", false),
                        value.optBoolean("vibepollo_bridge_online", false),
                        value.optBoolean("playnite_bridge_online", false),
                        value.optBoolean("virtualhere_available", false)));
            }
        }
        return new IntegrationProfiles(profiles,
                response.optString("suggested_profile_id", ""));
    }

    RepairStatus getVibepolloRepairStatus(Connection connection) throws IOException {
        JSONObject response = request(connection.endpoint, "/api/v1/vibepollo/repair/status", "GET",
                null, connection, pinnedTrust(connection), READ_TIMEOUT_MS);
        JSONObject host = response.optJSONObject("host");
        JSONObject bridge = response.optJSONObject("bridge");
        return new RepairStatus(response.optBoolean("ok", false) &&
                host != null && host.optBoolean("online", false),
                host != null ? host.optString("version", "") : "",
                bridge != null ? bridge.optString("api_error", "") : "");
    }

    JSONObject runVibepolloRepair(Connection connection, String action) throws IOException {
        if (!"restart".equals(action) && !"reset-display".equals(action) &&
                !"export-logs".equals(action)) {
            throw new IllegalArgumentException("Unknown repair action");
        }
        return request(connection.endpoint, "/api/v1/vibepollo/repair/" + action, "POST",
                new JSONObject(), connection, pinnedTrust(connection),
                "export-logs".equals(action) ? 25_000 : 8_000);
    }

    JSONObject ensureVibepolloPlayniteApp(Connection connection, String gameId, String name)
            throws IOException {
        if (!isPlayniteId(gameId)) {
            throw new IllegalArgumentException("Invalid Playnite game ID");
        }
        String normalizedName = name == null ? "" : name.trim();
        if (normalizedName.isEmpty() || normalizedName.length() > 200 ||
                normalizedName.matches(".*[\\x00-\\x1f\\x7f].*")) {
            throw new IllegalArgumentException("Invalid application name");
        }
        JSONObject body = new JSONObject();
        try {
            body.put("playnite_game_id", gameId.toLowerCase(Locale.ROOT));
            body.put("name", normalizedName);
        } catch (JSONException impossible) {
            throw new IOException(impossible);
        }
        JSONObject response = request(connection.endpoint,
                "/api/v1/vibepollo/apps/ensure", "POST", body, connection,
                pinnedTrust(connection), 15_000);
        if (!response.optBoolean("ok", false)) {
            throw new GatewayException(response.optString("error",
                    "Vibepollo application was not created."), 0);
        }
        return response.optJSONObject("app") != null
                ? response.optJSONObject("app") : new JSONObject();
    }

    static Integer parseVibepolloAppId(JSONObject app) {
        if (app == null) return null;
        long value = app.optLong("app_id", 0L);
        return value > 0L && value <= Integer.MAX_VALUE ? (int) value : null;
    }

    static String parseVibepolloAppUuid(JSONObject app) {
        if (app == null) return "";
        String value = app.optString("uuid", "").trim();
        return value.matches("(?i)[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
                ? value.toLowerCase(Locale.ROOT) : "";
    }

    JSONObject sleepHost(Connection connection) throws IOException {
        return request(connection.endpoint, "/api/v1/system/sleep", "POST",
                new JSONObject(), connection, pinnedTrust(connection), READ_TIMEOUT_MS);
    }

    JSONObject suspendSession(Connection connection, JSONObject session) throws IOException {
        return request(connection.endpoint, "/api/v1/system/suspend-session", "POST",
                session, connection, pinnedTrust(connection), READ_TIMEOUT_MS);
    }

    PlayniteLibrary getPlayniteLibrary(Connection connection, String cursor, int limit)
            throws IOException {
        if (limit < 1 || limit > 100) throw new IllegalArgumentException("Invalid page size");
        String safeCursor = cursor == null ? "" : cursor;
        String encoded = URLEncoder.encode(safeCursor, StandardCharsets.UTF_8.name());
        JSONObject response = request(connection.endpoint,
                "/api/v1/playnite/library/list?cursor=" + encoded + "&limit=" + limit,
                "GET", null, connection, pinnedTrust(connection), 12_000);
        return parsePlayniteLibrary(response.optJSONObject("library"));
    }

    String refreshPlayniteLibrary(Connection connection) throws IOException {
        JSONObject response = request(connection.endpoint,
                "/api/v1/playnite/library/refresh", "POST", new JSONObject(),
                connection, pinnedTrust(connection), 8_000);
        if (!response.optBoolean("ok", false)) {
            throw new GatewayException(response.optString("error",
                    "The Playnite library refresh could not be started."), 0);
        }
        JSONObject result = response.optJSONObject("result");
        return result == null ? "" : result.optString("previous_revision", "");
    }

    byte[] getPlayniteArtwork(Connection connection, String gameId, String kind)
            throws IOException {
        if (!isPlayniteId(gameId)) throw new IllegalArgumentException("Invalid Playnite game ID");
        if (!"cover".equals(kind) && !"background".equals(kind) && !"icon".equals(kind)) {
            throw new IllegalArgumentException("Invalid Playnite artwork kind");
        }
        return requestBytes(connection.endpoint,
                "/api/v1/playnite/artwork?game_id=" + gameId + "&kind=" + kind,
                connection, pinnedTrust(connection), 12_000);
    }

    PlayniteCurrentGame getPlayniteCurrentGame(Connection connection) throws IOException {
        JSONObject response = request(connection.endpoint, "/api/v1/playnite/game/current",
                "GET", null, connection, pinnedTrust(connection), READ_TIMEOUT_MS);
        JSONObject current = response.optJSONObject("current");
        if (current == null) current = new JSONObject();
        return new PlayniteCurrentGame(current.optString("state", "idle"),
                current.optString("id", ""), current.optString("title", ""),
                current.optInt("processId", current.optInt("process_id", 0)));
    }

    PlayniteHealth getPlayniteHealth(Connection connection) throws IOException {
        JSONObject response = request(connection.endpoint, "/api/v1/playnite/health",
                "GET", null, connection, pinnedTrust(connection), READ_TIMEOUT_MS);
        JSONObject bridge = response.optJSONObject("bridge");
        if (bridge == null) bridge = new JSONObject();
        return new PlayniteHealth(bridge.optBoolean("connector_connected", false),
                bridge.optInt("connector_generation", 0));
    }

    PlayniteReadiness getPlayniteReadiness(Connection connection) throws IOException {
        JSONObject response = request(connection.endpoint,
                "/api/v1/playnite/window/readiness", "GET", null, connection,
                pinnedTrust(connection), READ_TIMEOUT_MS);
        return parsePlayniteReadiness(response.optJSONObject("readiness"));
    }

    void showPlayniteFullscreen(Connection connection) throws IOException {
        JSONObject response = request(connection.endpoint,
                "/api/v1/playnite/show-fullscreen", "POST", new JSONObject(),
                connection, pinnedTrust(connection), 10_000);
        if (!response.optBoolean("ok", false)) {
            throw new GatewayException(response.optString("error",
                    "Playnite Fullscreen could not be restored."), 0);
        }
    }

    void focusPlayniteGame(Connection connection) throws IOException {
        JSONObject response = request(connection.endpoint,
                "/api/v1/playnite/game/focus", "POST", new JSONObject(),
                connection, pinnedTrust(connection), 8_000);
        if (!response.optBoolean("ok", false)) {
            throw new GatewayException(response.optString("error",
                    "The game window could not be focused."), 0);
        }
    }

    void installPlayniteGame(Connection connection, String gameId) throws IOException {
        if (!isPlayniteId(gameId)) {
            throw new IllegalArgumentException("Invalid Playnite game ID");
        }
        JSONObject body = new JSONObject();
        try {
            body.put("game_id", gameId.toLowerCase(Locale.ROOT));
        } catch (JSONException impossible) {
            throw new IOException(impossible);
        }
        JSONObject response = request(connection.endpoint,
                "/api/v1/playnite/game/install", "POST", body, connection,
                pinnedTrust(connection), 15_000);
        if (!response.optBoolean("ok", false)) {
            throw new GatewayException(response.optString("error",
                    "The game installation could not be started."), 0);
        }
    }

    void uninstallPlayniteGame(Connection connection, String gameId) throws IOException {
        if (!isPlayniteId(gameId)) {
            throw new IllegalArgumentException("Invalid Playnite game ID");
        }
        JSONObject body = new JSONObject();
        try {
            body.put("game_id", gameId.toLowerCase(Locale.ROOT));
        } catch (JSONException impossible) {
            throw new IOException(impossible);
        }
        JSONObject response = request(connection.endpoint,
                "/api/v1/playnite/game/uninstall", "POST", body, connection,
                pinnedTrust(connection), 15_000);
        if (!response.optBoolean("ok", false)) {
            throw new GatewayException(response.optString("error",
                    "The game could not be uninstalled."), 0);
        }
    }

    void focusPlayniteInstallation(Connection connection, String gameId) throws IOException {
        if (!isPlayniteId(gameId)) {
            throw new IllegalArgumentException("Invalid Playnite game ID");
        }
        JSONObject body = new JSONObject();
        try {
            body.put("game_id", gameId.toLowerCase(Locale.ROOT));
        } catch (JSONException impossible) {
            throw new IOException(impossible);
        }
        JSONObject response = request(connection.endpoint,
                "/api/v1/playnite/game/install/focus", "POST", body, connection,
                pinnedTrust(connection), 8_000);
        if (!response.optBoolean("ok", false)) {
            throw new GatewayException(response.optString("error",
                    "The installation window could not be opened."), 0);
        }
    }

    boolean verifyPlayniteInstallation(Connection connection, String gameId) throws IOException {
        if (!isPlayniteId(gameId)) {
            throw new IllegalArgumentException("Invalid Playnite game ID");
        }
        JSONObject body = new JSONObject();
        try {
            body.put("game_id", gameId.toLowerCase(Locale.ROOT));
        } catch (JSONException impossible) {
            throw new IOException(impossible);
        }
        JSONObject response = request(connection.endpoint,
                "/api/v1/playnite/game/install/verify", "POST", body, connection,
                pinnedTrust(connection), 10_000);
        if (!response.optBoolean("ok", false)) {
            throw new GatewayException(response.optString("error",
                    "The installation could not be verified."), 0);
        }
        JSONObject result = response.optJSONObject("result");
        return result != null && !result.optBoolean("requires_attention", true);
    }

    PlayniteEvents getPlayniteEvents(Connection connection, long after) throws IOException {
        return getPlayniteEvents(connection, after, "");
    }

    PlayniteEvents getPlayniteEvents(Connection connection, long after,
                                     String transitionId) throws IOException {
        if (after < 0) throw new IllegalArgumentException("Invalid Playnite event sequence");
        String correlation = transitionId == null ? "" : transitionId.trim();
        if (correlation.length() > 128
                || !correlation.matches("[A-Za-z0-9._:-]*")) {
            throw new IllegalArgumentException("Invalid transition ID");
        }
        JSONObject response = request(connection.endpoint,
                "/api/v1/playnite/events?after=" + after + "&transition_id=" +
                        URLEncoder.encode(correlation, StandardCharsets.UTF_8.name()),
                "GET", null, connection,
                pinnedTrust(connection), 25_000);
        String echoedCorrelation = response.optString("transition_id", "");
        if (!echoedCorrelation.isEmpty() && !correlation.equals(echoedCorrelation)) {
            throw new GatewayException("Mismatched transition response.", 409);
        }
        JSONObject envelope = response.optJSONObject("events");
        JSONArray values = envelope != null ? envelope.optJSONArray("events") : null;
        List<PlayniteEvent> result = new ArrayList<>();
        long latest = after;
        if (values != null) {
            for (int index = 0; index < values.length(); index++) {
                JSONObject value = values.optJSONObject(index);
                if (value == null) continue;
                long sequence = value.optLong("sequence", -1);
                if (sequence <= after) continue;
                JSONObject payload = value.optJSONObject("payload");
                String gameId = payload != null ? payload.optString("id", "") : "";
                result.add(new PlayniteEvent(sequence,
                        value.optString("event", ""), gameId,
                        payload != null ? payload.optString("name", "") : ""));
                latest = Math.max(latest, sequence);
            }
        }
        return new PlayniteEvents(result, latest);
    }

    static PlayniteLibrary parsePlayniteLibrary(JSONObject library) {
        JSONObject safe = library == null ? new JSONObject() : library;
        JSONArray values = safe.optJSONArray("games");
        List<PlayniteGame> games = new ArrayList<>();
        if (values != null) {
            for (int index = 0; index < values.length(); index++) {
                JSONObject value = values.optJSONObject(index);
                if (value == null) continue;
                String id = value.optString("id", "");
                if (!isPlayniteId(id)) continue;
                String name = value.optString("name", "").trim();
                if (name.isEmpty()) continue;
                long seconds = value.has("playtimeSeconds")
                        ? value.optLong("playtimeSeconds", 0L)
                        : value.has("playtime_seconds")
                        ? value.optLong("playtime_seconds", 0L)
                        : value.has("playtimeMinutes")
                        ? value.optLong("playtimeMinutes", 0L) * 60L
                        : value.has("playtime_minutes")
                        ? value.optLong("playtime_minutes", 0L) * 60L
                        : value.optLong("playtime", 0L);
                games.add(new PlayniteGame(id.toLowerCase(Locale.ROOT), name,
                        value.optBoolean("installed", value.optBoolean("isInstalled", false)),
                        value.optBoolean("installing", value.optBoolean("isInstalling", false)),
                        value.optBoolean("hidden", value.optBoolean("isHidden", false)),
                        value.optBoolean("favorite", value.optBoolean("isFavorite", false)),
                        firstText(value, "cover", "coverImage", "cover_image", "boxArtPath"),
                        firstText(value, "background", "backgroundImage", "background_image",
                                "backgroundImagePath"),
                        firstText(value, "lastPlayed", "last_played", "lastActivity"),
                        firstText(value, "description", "overview", "summary"),
                        Math.max(0, value.has("playCount")
                                ? value.optInt("playCount", 0)
                                : value.optInt("play_count", 0)),
                        firstText(value, "source", "sourceName", "source_name"),
                        joinedText(value, "genres", "genre"),
                        firstText(value, "artworkVersion", "artwork_version", "artworkHash",
                                "artwork_hash", "cover"),
                        Math.max(0L, seconds),
                        value.optBoolean("installRequiresAttention",
                                value.optBoolean("install_requires_attention", false)),
                        firstText(value, "installAttentionReason",
                                "install_attention_reason"),
                        firstText(value, "installWindowTitle", "install_window_title"),
                        firstText(value, "installLauncher", "install_launcher"),
                        firstText(value, "operationState", "operation_state"),
                        value.isNull("operationProgress") ? -1
                                : value.optInt("operationProgress", -1),
                        value.optBoolean("uninstalling", false)));
            }
        }
        return new PlayniteLibrary(games, safe.optString("next_cursor", ""),
                safe.optInt("total", games.size()),
                firstText(safe, "revision", "library_revision", "hash"),
                firstText(safe, "api_version", "apiVersion", "version"));
    }

    static PlayniteReadiness parsePlayniteReadiness(JSONObject value) {
        JSONObject safe = value == null ? new JSONObject() : value;
        return new PlayniteReadiness(safe.optBoolean("ready", false),
                safe.optString("reason", "window_probe_pending"),
                safe.optString("target_kind", "playnite"),
                safe.optInt("stable_samples", 0),
                safe.optInt("process_id", safe.optInt("processId", 0)),
                safe.optString("display", ""));
    }

    private static String firstText(JSONObject value, String... keys) {
        for (String key : keys) {
            String result = value.optString(key, "").trim();
            if (!result.isEmpty()) return result;
        }
        return "";
    }

    private static String joinedText(JSONObject value, String... keys) {
        for (String key : keys) {
            JSONArray array = value.optJSONArray(key);
            if (array != null) {
                StringBuilder result = new StringBuilder();
                for (int index = 0; index < array.length(); index++) {
                    String item = array.optString(index, "").trim();
                    if (item.isEmpty()) continue;
                    if (result.length() > 0) result.append(", ");
                    result.append(item);
                }
                if (result.length() > 0) return result.toString();
            }
            String text = value.optString(key, "").trim();
            if (!text.isEmpty()) return text;
        }
        return "";
    }

    static boolean isPlayniteId(String value) {
        return value != null && value.matches(
                "(?i)[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    }

    DiscordStatus getDiscordStatus(Connection connection) throws IOException {
        JSONObject response = request(connection.endpoint, "/api/v1/discord/status", "GET",
                null, connection, pinnedTrust(connection), READ_TIMEOUT_MS);
        return new DiscordStatus(
                response.optBoolean("bridge_online", false),
                response.optBoolean("rpc_connected", false),
                response.optBoolean("authenticated", false),
                response.optString("error", ""));
    }

    DiscordHome getDiscordHome(Connection connection, boolean force) throws IOException {
        JSONObject response = request(connection.endpoint,
                "/api/v1/discord/home" + (force ? "?force=true" : ""), "GET",
                null, connection, pinnedTrust(connection), 12_000);
        JSONObject home = response.optJSONObject("home");
        return new DiscordHome(
                parseSavedChannels(home != null ? home.optJSONArray("favorites") : null, true),
                parseSavedChannels(home != null ? home.optJSONArray("recent") : null, false),
                parseGuilds(home != null ? home.optJSONArray("guilds") : null));
    }

    List<DiscordChannel> getDiscordChannels(Connection connection, DiscordGuild guild,
                                            boolean force) throws IOException {
        if (!isDiscordId(guild.id)) throw new IllegalArgumentException("Invalid Discord guild ID");
        JSONObject response = request(connection.endpoint,
                "/api/v1/discord/channels?guild_id=" + guild.id + (force ? "&force=true" : ""), "GET",
                null, connection, pinnedTrust(connection), 15_000);
        JSONObject value = response.optJSONObject("channels");
        JSONArray channels = value != null ? value.optJSONArray("channels") : null;
        List<DiscordChannel> result = new ArrayList<>();
        if (channels == null) return result;
        for (int index = 0; index < channels.length(); index++) {
            JSONObject channel = channels.optJSONObject(index);
            if (channel == null) continue;
            String id = channel.optString("id", "");
            if (!isDiscordId(id)) continue;
            result.add(new DiscordChannel(id, guild.id, guild.name,
                    channel.optString("name", "Voice channel"),
                    channel.optInt("people", 0), channel.optBoolean("favorite", false)));
        }
        return result;
    }

    DiscordVoice getDiscordVoice(Connection connection, boolean force) throws IOException {
        JSONObject response = request(connection.endpoint,
                "/api/v1/discord/voice" + (force ? "?force=true" : ""), "GET",
                null, connection, pinnedTrust(connection), 12_000);
        JSONObject voice = response.optJSONObject("voice");
        if (voice == null) voice = new JSONObject();
        JSONObject channel = voice.optJSONObject("channel");
        JSONArray participants = voice.optJSONArray("participants");
        List<DiscordParticipant> participantList = new ArrayList<>();
        if (participants != null) {
            for (int index = 0; index < participants.length(); index++) {
                JSONObject participant = participants.optJSONObject(index);
                if (participant == null) continue;
                String id = participant.optString("id", "");
                if (!isDiscordId(id)) continue;
                participantList.add(new DiscordParticipant(id,
                        participant.optString("name", "Discord user"),
                        participant.optString("username", ""),
                        participant.optInt("volume", 100),
                        participant.optBoolean("muted", false),
                        participant.optBoolean("deafened", false),
                        participant.optBoolean("speaking", false),
                        participant.optBoolean("is_self", false),
                        participant.optBoolean("bot", false),
                        participant.optBoolean("can_set_volume", true),
                        participant.optString("audio_error", "")));
            }
        }
        return new DiscordVoice(
                voice.optBoolean("connected", false),
                channel != null ? channel.optString("id", "") : "",
                channel != null ? channel.optString("name", "") : "",
                channel != null ? channel.optString("guild_id", "") : "",
                voice.optBoolean("mute", false),
                voice.optBoolean("deafen", false),
                participantList.size(), participantList);
    }

    JSONObject connectDiscord(Connection connection, boolean force) throws IOException {
        JSONObject body = new JSONObject();
        try {
            body.put("force", force);
        } catch (JSONException impossible) {
            throw new IOException(impossible);
        }
        return discordAction(connection, "connect", body, 25_000);
    }

    JSONObject startDiscord(Connection connection) throws IOException {
        return discordAction(connection, "start", new JSONObject(), 12_000);
    }

    JSONObject joinDiscordChannel(Connection connection, DiscordChannel channel) throws IOException {
        return joinDiscordChannel(connection, channel.id, channel.guildId,
                channel.guildName, channel.name);
    }

    JSONObject joinDiscordChannel(Connection connection, String channelId, String guildId,
                                  String guildName, String channelName) throws IOException {
        if (!isDiscordId(channelId) || !isDiscordId(guildId)) {
            throw new IllegalArgumentException("Invalid Discord channel");
        }
        JSONObject body = new JSONObject();
        try {
            body.put("channel_id", channelId);
            body.put("guild_id", guildId);
            body.put("guild_name", guildName != null ? guildName : "");
            body.put("channel_name", channelName != null ? channelName : "");
        } catch (JSONException impossible) {
            throw new IOException(impossible);
        }
        return discordAction(connection, "join", body, 25_000);
    }

    JSONObject leaveDiscordChannel(Connection connection) throws IOException {
        return discordAction(connection, "leave", new JSONObject(), 15_000);
    }

    JSONObject setDiscordVoiceFlag(Connection connection, String action, String value) throws IOException {
        if (!"mute".equals(action) && !"deafen".equals(action)) {
            throw new IllegalArgumentException("Unknown Discord voice action");
        }
        JSONObject body = new JSONObject();
        try {
            body.put("value", value);
        } catch (JSONException impossible) {
            throw new IOException(impossible);
        }
        return discordAction(connection, action, body, 12_000);
    }

    JSONObject changeDiscordParticipantVolume(Connection connection, String userId,
                                               int delta) throws IOException {
        if (!isDiscordId(userId) || (delta != -10 && delta != 10)) {
            throw new IllegalArgumentException("Invalid Discord participant volume change");
        }
        JSONObject body = new JSONObject();
        try {
            body.put("user_id", userId);
            body.put("delta", delta);
        } catch (JSONException impossible) {
            throw new IOException(impossible);
        }
        return discordAction(connection, "user-volume", body, 12_000);
    }

    JSONObject setDiscordParticipantVolume(Connection connection, String userId,
                                            int volume) throws IOException {
        if (!isDiscordId(userId)
                || volume < DiscordFeatureContract.MIN_PARTICIPANT_VOLUME
                || volume > DiscordFeatureContract.MAX_PARTICIPANT_VOLUME
                || volume % DiscordFeatureContract.PARTICIPANT_VOLUME_STEP != 0) {
            throw new IllegalArgumentException("Invalid Discord participant volume");
        }
        JSONObject body = new JSONObject();
        try {
            body.put("user_id", userId);
            body.put("volume", volume);
        } catch (JSONException impossible) {
            throw new IOException(impossible);
        }
        return discordAction(connection, "user-volume", body, 12_000);
    }

    JSONObject toggleDiscordParticipantMute(Connection connection, String userId)
            throws IOException {
        if (!isDiscordId(userId)) {
            throw new IllegalArgumentException("Invalid Discord participant");
        }
        JSONObject body = new JSONObject();
        try {
            body.put("user_id", userId);
        } catch (JSONException impossible) {
            throw new IOException(impossible);
        }
        return discordAction(connection, "user-mute", body, 12_000);
    }

    VirtualHereState getVirtualHereState(Connection connection, boolean force) throws IOException {
        JSONObject response = request(connection.endpoint,
                "/api/v1/virtualhere/state" + (force ? "?force=true" : ""), "GET",
                null, connection, pinnedTrust(connection), 12_000);
        JSONObject state = response.optJSONObject("virtualhere");
        if (state == null) state = new JSONObject();
        List<VirtualHereServer> servers = new ArrayList<>();
        JSONArray serverValues = state.optJSONArray("servers");
        if (serverValues != null) {
            for (int serverIndex = 0; serverIndex < serverValues.length(); serverIndex++) {
                JSONObject server = serverValues.optJSONObject(serverIndex);
                if (server == null) continue;
                List<VirtualHereDevice> devices = new ArrayList<>();
                JSONArray deviceValues = server.optJSONArray("devices");
                if (deviceValues != null) {
                    for (int deviceIndex = 0; deviceIndex < deviceValues.length(); deviceIndex++) {
                        JSONObject device = deviceValues.optJSONObject(deviceIndex);
                        if (device == null) continue;
                        String address = device.optString("address", "");
                        if (!address.matches("[A-Za-z0-9._:-]{1,160}")) continue;
                        devices.add(new VirtualHereDevice(address,
                                device.optString("name", "USB device"),
                                device.optBoolean("available", false),
                                device.optBoolean("in_use", false),
                                device.optBoolean("in_use_by_me", false),
                                device.optBoolean("auto_use", false),
                                device.optString("bound_hostname", "")));
                    }
                }
                servers.add(new VirtualHereServer(
                        server.optString("name", "VirtualHere server"),
                        server.optString("hostname", ""), devices));
            }
        }
        return new VirtualHereState(state.optBoolean("installed", false),
                state.optBoolean("running", false), servers,
                state.optString("error", ""));
    }

    JSONObject runVirtualHereAction(Connection connection, String action,
                                    String address) throws IOException {
        if (!"use".equals(action) && !"stop".equals(action) &&
                !"auto".equals(action) && !"restart".equals(action)) {
            throw new IllegalArgumentException("Unknown VirtualHere action");
        }
        JSONObject body = new JSONObject();
        try {
            if (!"restart".equals(action)) {
                if (address == null || !address.matches("[A-Za-z0-9._:-]{1,160}")) {
                    throw new IllegalArgumentException("Invalid VirtualHere device address");
                }
                body.put("address", address);
            }
        } catch (JSONException impossible) {
            throw new IOException(impossible);
        }
        return request(connection.endpoint, "/api/v1/virtualhere/" + action, "POST",
                body, connection, pinnedTrust(connection),
                "restart".equals(action) ? 15_000 : 12_000);
    }

    DiscordAudioState getDiscordAudioState(Connection connection) throws IOException {
        JSONObject response = request(connection.endpoint, "/api/v1/discord/audio", "GET",
                null, connection, pinnedTrust(connection), 15_000);
        JSONObject audio = response.optJSONObject("audio");
        if (audio == null) audio = new JSONObject();
        return new DiscordAudioState(audio.optBoolean("system_available", false),
                audio.optInt("system_volume", 0),
                audio.optBoolean("system_muted", false),
                parseAudioDevices(audio.optJSONArray("system_devices"), true),
                parseAudioDevices(audio.optJSONArray("discord_devices"), false),
                audio.optString("system_error", ""));
    }

    JSONObject selectAudioDevice(Connection connection, AudioDevice device)
            throws IOException {
        if (device == null || device.id == null ||
                !device.id.matches("[A-Za-z0-9._:{}-]{1,220}") ||
                (!"input".equals(device.flow) && !"output".equals(device.flow))) {
            throw new IllegalArgumentException("Invalid audio device");
        }
        JSONObject body = new JSONObject();
        try {
            body.put("scope", device.system ? "system" : "discord");
            body.put("kind", device.flow);
            body.put("device_id", device.id);
        } catch (JSONException impossible) {
            throw new IOException(impossible);
        }
        return discordAction(connection, "audio/select", body, 15_000);
    }

    JSONObject changeSystemVolume(Connection connection, int delta) throws IOException {
        if (delta != -5 && delta != 5) {
            throw new IllegalArgumentException("Invalid system volume change");
        }
        JSONObject body = new JSONObject();
        try {
            body.put("delta", delta);
        } catch (JSONException impossible) {
            throw new IOException(impossible);
        }
        return discordAction(connection, "audio/volume", body, 12_000);
    }

    JSONObject toggleSystemMute(Connection connection) throws IOException {
        return discordAction(connection, "audio/mute", new JSONObject(), 12_000);
    }

    private JSONObject discordAction(Connection connection, String action, JSONObject body,
                                     int timeoutMs) throws IOException {
        return request(connection.endpoint, "/api/v1/discord/" + action, "POST",
                body, connection, pinnedTrust(connection), timeoutMs);
    }

    private static List<DiscordGuild> parseGuilds(JSONArray values) {
        List<DiscordGuild> result = new ArrayList<>();
        if (values == null) return result;
        for (int index = 0; index < values.length(); index++) {
            JSONObject value = values.optJSONObject(index);
            if (value == null) continue;
            String id = value.optString("id", "");
            if (!isDiscordId(id)) continue;
            result.add(new DiscordGuild(id, value.optString("name", "Discord server")));
        }
        return result;
    }

    private static List<AudioDevice> parseAudioDevices(JSONArray values, boolean system) {
        List<AudioDevice> result = new ArrayList<>();
        if (values == null) return result;
        for (int index = 0; index < values.length(); index++) {
            JSONObject value = values.optJSONObject(index);
            if (value == null) continue;
            String id = value.optString("id", "");
            String flow = value.optString("flow", "");
            if (!id.matches("[A-Za-z0-9._:{}-]{1,220}") ||
                    (!"input".equals(flow) && !"output".equals(flow))) continue;
            result.add(new AudioDevice(id, value.optString("name", "Audio device"),
                    flow, value.optBoolean("is_default", false), system));
        }
        return result;
    }

    private static List<DiscordChannel> parseSavedChannels(JSONArray values, boolean favorite) {
        List<DiscordChannel> result = new ArrayList<>();
        if (values == null) return result;
        for (int index = 0; index < values.length(); index++) {
            JSONObject value = values.optJSONObject(index);
            if (value == null) continue;
            String id = value.optString("channel_id", "");
            String guildId = value.optString("guild_id", "");
            if (!isDiscordId(id) || !isDiscordId(guildId)) continue;
            result.add(new DiscordChannel(id, guildId,
                    value.optString("guild_name", "Discord"),
                    value.optString("channel_name", "Voice channel"), -1, favorite));
        }
        return result;
    }

    static boolean isDiscordId(String value) {
        return value != null && value.matches("[0-9]{5,32}");
    }

    private static boolean available(JSONObject capabilities, String name) {
        if (capabilities == null) return false;
        JSONObject capability = capabilities.optJSONObject(name);
        return capability != null && capability.optBoolean("available", false);
    }

    private static PairingTrustManager pinnedTrust(Connection connection) {
        if (connection.certificateSha256.isEmpty()) {
            throw new IllegalArgumentException("Missing gateway certificate pin");
        }
        return new PairingTrustManager(connection.certificateSha256, false);
    }

    private static JSONObject request(String endpoint, String path, String method,
                                      JSONObject body, Connection connection,
                                      PairingTrustManager trustManager,
                                      int readTimeoutMs) throws IOException {
        HttpsURLConnection http = null;
        try {
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(null, new TrustManager[]{trustManager}, null);
            http = (HttpsURLConnection) new URL(trimSlash(endpoint) + path).openConnection();
            http.setSSLSocketFactory(context.getSocketFactory());
            http.setHostnameVerifier(PINNED_HOSTNAME_VERIFIER);
            http.setConnectTimeout(CONNECT_TIMEOUT_MS);
            http.setReadTimeout(readTimeoutMs);
            http.setRequestMethod(method);
            http.setRequestProperty("Accept", "application/json");
            http.setRequestProperty("Connection", "close");
            if (connection != null) {
                http.setRequestProperty("Authorization", "Bearer " + connection.token);
                http.setRequestProperty("X-WakePlay-Profile", connection.profileId);
            }
            if ("POST".equals(method)) {
                byte[] payload = (body != null ? body : new JSONObject()).toString()
                        .getBytes(StandardCharsets.UTF_8);
                http.setDoOutput(true);
                http.setFixedLengthStreamingMode(payload.length);
                http.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                http.setRequestProperty("X-Request-Id", UUID.randomUUID().toString());
                try (OutputStream output = http.getOutputStream()) {
                    output.write(payload);
                }
            }

            int status = http.getResponseCode();
            InputStream input = status >= 400 ? http.getErrorStream() : http.getInputStream();
            String raw = input != null ? readUtf8(input) : "";
            JSONObject response;
            try {
                response = raw.isEmpty() ? new JSONObject() : new JSONObject(raw);
            } catch (JSONException error) {
                throw new GatewayException("Invalid response from host gateway.", status);
            }
            if (status < HttpURLConnection.HTTP_OK || status >= 300) {
                throw new GatewayException(response.optString("error", "Host gateway request failed."), status);
            }
            return response;
        } catch (GeneralSecurityException error) {
            throw new IOException("Unable to initialize gateway TLS.", error);
        } finally {
            if (http != null) http.disconnect();
        }
    }

    private static byte[] requestBytes(String endpoint, String path, Connection connection,
                                       PairingTrustManager trustManager, int readTimeoutMs)
            throws IOException {
        HttpsURLConnection http = null;
        try {
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(null, new TrustManager[]{trustManager}, null);
            http = (HttpsURLConnection) new URL(trimSlash(endpoint) + path).openConnection();
            http.setSSLSocketFactory(context.getSocketFactory());
            http.setHostnameVerifier(PINNED_HOSTNAME_VERIFIER);
            http.setConnectTimeout(CONNECT_TIMEOUT_MS);
            http.setReadTimeout(readTimeoutMs);
            http.setRequestMethod("GET");
            http.setRequestProperty("Accept", "image/*");
            http.setRequestProperty("Connection", "close");
            http.setRequestProperty("Authorization", "Bearer " + connection.token);
            http.setRequestProperty("X-WakePlay-Profile", connection.profileId);
            int status = http.getResponseCode();
            if (status < HttpURLConnection.HTTP_OK || status >= 300) {
                throw new GatewayException("Playnite artwork is unavailable.", status);
            }
            try (InputStream input = http.getInputStream();
                 ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[8192];
                int read;
                int total = 0;
                while ((read = input.read(buffer)) >= 0) {
                    total += read;
                    if (total > 8 * 1024 * 1024) {
                        throw new IOException("Playnite artwork is too large.");
                    }
                    output.write(buffer, 0, read);
                }
                return output.toByteArray();
            }
        } catch (GeneralSecurityException error) {
            throw new IOException("Unable to initialize gateway TLS.", error);
        } finally {
            if (http != null) http.disconnect();
        }
    }

    private static String readUtf8(InputStream input) throws IOException {
        try (InputStream stream = input; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int read;
            int total = 0;
            while ((read = stream.read(buffer)) >= 0) {
                total += read;
                if (total > 1024 * 1024) throw new IOException("Gateway response is too large.");
                output.write(buffer, 0, read);
            }
            return output.toString(StandardCharsets.UTF_8.name());
        }
    }

    private static String fingerprint(X509Certificate certificate) throws CertificateException {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(certificate.getEncoded());
            StringBuilder value = new StringBuilder(digest.length * 2);
            for (byte item : digest) value.append(String.format(Locale.US, "%02x", item & 0xff));
            return value.toString();
        } catch (GeneralSecurityException error) {
            throw new CertificateException(error);
        }
    }

    static String normalizeFingerprint(String value) {
        return value == null ? "" : value.replace(":", "").trim().toLowerCase(Locale.US);
    }

    private static String trimSlash(String value) {
        String result = value == null ? "" : value.trim();
        while (result.endsWith("/")) result = result.substring(0, result.length() - 1);
        return result;
    }

    private static final class PairingTrustManager implements X509TrustManager {
        private final String expectedFingerprint;
        private final boolean trustOnFirstUse;
        volatile String seenFingerprint;

        PairingTrustManager(String expectedFingerprint, boolean trustOnFirstUse) {
            this.expectedFingerprint = normalizeFingerprint(expectedFingerprint);
            this.trustOnFirstUse = trustOnFirstUse;
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
            throw new CertificateException("Client certificates are not supported.");
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
            if (chain == null || chain.length == 0) throw new CertificateException("Missing server certificate.");
            String actual = fingerprint(chain[0]);
            seenFingerprint = actual;
            if (!trustOnFirstUse && !MessageDigest.isEqual(
                    actual.getBytes(StandardCharsets.US_ASCII),
                    expectedFingerprint.getBytes(StandardCharsets.US_ASCII))) {
                throw new CertificateException("Host gateway certificate changed. Pair the host again.");
            }
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            return new X509Certificate[0];
        }
    }
}
