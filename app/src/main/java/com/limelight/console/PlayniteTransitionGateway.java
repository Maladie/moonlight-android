package com.limelight.console;

import android.content.Context;

import com.limelight.gateway.GatewayConnection;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Narrow, credential-safe transition view of the host Gateway. It keeps raw
 * Gateway response parsing out of the streaming Activity.
 */
public final class PlayniteTransitionGateway {
    public static final class Snapshot {
        public final boolean gatewayReady;
        public final boolean connectorReady;
        public final String gameState;
        public final String gameId;
        public final int processId;
        public final boolean windowReady;
        public final String targetKind;
        public final int stableSamples;
        public final String reason;

        Snapshot(boolean gatewayReady, boolean connectorReady, String gameState,
                 String gameId, int processId, boolean windowReady, String targetKind,
                 int stableSamples, String reason) {
            this.gatewayReady = gatewayReady;
            this.connectorReady = connectorReady;
            this.gameState = gameState;
            this.gameId = gameId;
            this.processId = processId;
            this.windowReady = windowReady;
            this.targetKind = targetKind;
            this.stableSamples = stableSamples;
            this.reason = reason;
        }
    }

    public static final class Event {
        public final long sequence;
        public final String name;
        public final String gameId;
        public final String gameName;

        Event(long sequence, String name, String gameId, String gameName) {
            this.sequence = sequence;
            this.name = name;
            this.gameId = gameId;
            this.gameName = gameName;
        }
    }

    public static final class Events {
        public final List<Event> values;
        public final long latestSequence;

        Events(List<Event> values, long latestSequence) {
            this.values = Collections.unmodifiableList(values);
            this.latestSequence = latestSequence;
        }
    }

    public static final class SuspendAcceptance {
        public final boolean accepted;
        public final String suspendId;
        public final int sunshineAppId;
        public final String playniteGameId;

        SuspendAcceptance(boolean accepted, String suspendId, int sunshineAppId,
                          String playniteGameId) {
            this.accepted = accepted;
            this.suspendId = suspendId == null ? "" : suspendId.trim();
            this.sunshineAppId = sunshineAppId;
            this.playniteGameId = playniteGameId == null ? ""
                    : playniteGameId.trim().toLowerCase(java.util.Locale.ROOT);
        }

        public boolean matches(String expectedSuspendId, int expectedAppId,
                               String expectedGameId) {
            String gameId = expectedGameId == null ? ""
                    : expectedGameId.trim().toLowerCase(java.util.Locale.ROOT);
            if (!accepted) return false;
            // Older Gateway versions acknowledge suspend but do not echo correlation fields.
            if (suspendId.isEmpty() && sunshineAppId == 0 && playniteGameId.isEmpty()) {
                return true;
            }
            return suspendId.equals(expectedSuspendId) && sunshineAppId == expectedAppId
                    && playniteGameId.equals(gameId);
        }
    }

    private final HostGatewayClient client = new HostGatewayClient();
    private final GatewayConnection connection;

    private PlayniteTransitionGateway(GatewayConnection connection) {
        this.connection = connection;
    }

    public static PlayniteTransitionGateway connect(Context context, String hostId,
                                                    String activeHost) {
        GatewayConnection connection = new HostGatewayStore(context)
                .loadForHost(hostId, activeHost);
        return connection == null ? null : new PlayniteTransitionGateway(connection);
    }

    public Snapshot snapshot() throws IOException {
        HostGatewayClient.PlayniteHealth health = client.getPlayniteHealth(connection);
        HostGatewayClient.PlayniteCurrentGame current =
                client.getPlayniteCurrentGame(connection);
        HostGatewayClient.PlayniteReadiness readiness =
                client.getPlayniteReadiness(connection);
        return new Snapshot(true, health.connectorConnected, current.state, current.id,
                current.processId, readiness.ready, readiness.targetKind,
                readiness.stableSamples, readiness.reason);
    }

    public Events awaitEvents(long after, String transitionId) throws IOException {
        HostGatewayClient.PlayniteEvents result =
                client.getPlayniteEvents(connection, after, transitionId);
        List<Event> values = new ArrayList<>();
        for (HostGatewayClient.PlayniteEvent event : result.events) {
            values.add(new Event(event.sequence, event.name, event.gameId, event.gameName));
        }
        return new Events(values, result.latestSequence);
    }

    public void showFullscreen() throws IOException {
        client.showPlayniteFullscreen(connection);
    }

    public void focusGame() throws IOException {
        client.focusPlayniteGame(connection);
    }

    public void focusInstallation(String gameId) throws IOException {
        client.focusPlayniteInstallation(connection, gameId);
    }

    public boolean verifyInstallation(String gameId) throws IOException {
        return client.verifyPlayniteInstallation(connection, gameId);
    }

    public void ensureInstalledGameTarget(String gameId, String name) throws IOException {
        client.ensureVibepolloPlayniteApp(connection, gameId, name);
    }

    public SuspendAcceptance suspendSession(String suspendId, int sunshineAppId,
                                             String playniteGameId, String title)
            throws IOException {
        JSONObject body = new JSONObject();
        try {
            body.put("suspend_id", suspendId);
            body.put("sunshine_app_id", sunshineAppId);
            body.put("playnite_game_id", playniteGameId == null ? "" : playniteGameId);
            body.put("title", title == null ? "" : title);
        } catch (JSONException impossible) {
            throw new IOException(impossible);
        }
        JSONObject response = client.suspendSession(connection, body, suspendId);
        JSONObject session = response.optJSONObject("session");
        return new SuspendAcceptance(response.optBoolean("accepted", false),
                response.optString("suspend_id", ""),
                session == null ? 0 : session.optInt("sunshine_app_id", 0),
                session == null ? "" : session.optString("playnite_game_id", ""));
    }
}
