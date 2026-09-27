package com.limelight.console;

import android.content.Context;

import com.limelight.diagnostics.MoonWakerDiagnostics;
import com.limelight.gateway.GatewayConnection;
import com.limelight.gateway.ControlOperationClient;

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
    /** Immutable authorization identity for one Gateway operation. */
    public static final class ActorContext {
        public final String actorProfileId;
        public final String executionProfileId;
        public final boolean child;

        private ActorContext(String actorProfileId, String executionProfileId,
                             boolean child) {
            this.actorProfileId = GatewayConnection.normalizeProfileId(actorProfileId);
            this.executionProfileId = GatewayConnection.normalizeProfileId(executionProfileId);
            this.child = child;
            if (!child && !this.actorProfileId.equals(this.executionProfileId)) {
                throw new IllegalArgumentException(
                        "A parent actor must execute as its own profile");
            }
        }

        public static ActorContext parent(String profileId) {
            return new ActorContext(profileId, profileId, false);
        }

        public static ActorContext child(String actorProfileId,
                                         String executionProfileId) {
            return new ActorContext(actorProfileId, executionProfileId, true);
        }
    }

    public static final class Snapshot {
        public final boolean gatewayReady;
        public final boolean connectorReady;
        public final String gameState;
        public final String gameId;
        public final int processId;
        public final String processToken;
        public final boolean windowReady;
        public final String targetKind;
        public final int stableSamples;
        public final String reason;
        public final boolean hostGuideAllowed;
        public final String observationState;
        public final String gameEvidence;
        public final String processEvidence;
        public final long transportGeneration;
        final SessionSnapshot sessionSnapshot;

        Snapshot(boolean gatewayReady, boolean connectorReady, String gameState,
                 String gameId, int processId, boolean windowReady, String targetKind,
                 int stableSamples, String reason) {
            this(gatewayReady, connectorReady, gameState, gameId, processId, windowReady,
                    targetKind, stableSamples, reason, false, "");
        }

        Snapshot(boolean gatewayReady, boolean connectorReady, String gameState,
                 String gameId, int processId, boolean windowReady, String targetKind,
                 int stableSamples, String reason, boolean hostGuideAllowed) {
            this(gatewayReady, connectorReady, gameState, gameId, processId, windowReady,
                    targetKind, stableSamples, reason, hostGuideAllowed, "");
        }

        Snapshot(boolean gatewayReady, boolean connectorReady, String gameState,
                 String gameId, int processId, boolean windowReady, String targetKind,
                 int stableSamples, String reason, boolean hostGuideAllowed,
                 String processToken) {
            this.gatewayReady = gatewayReady;
            this.connectorReady = connectorReady;
            this.gameState = gameState;
            this.gameId = gameId;
            this.processId = processId;
            this.processToken = processToken == null ? "" : processToken;
            this.windowReady = windowReady;
            this.targetKind = targetKind;
            this.stableSamples = stableSamples;
            this.reason = reason;
            this.hostGuideAllowed = hostGuideAllowed;
            this.observationState = "UNAVAILABLE";
            this.gameEvidence = gameId == null || gameId.trim().isEmpty()
                    ? "ABSENT" : "VERIFIED";
            this.processEvidence = processId > 0 ? "VERIFIED" : "UNAVAILABLE";
            this.transportGeneration = 0L;
            this.sessionSnapshot = null;
        }

        /** Provider evidence of one observation already fenced by the caller's own control permit. */
        private Snapshot(SessionSnapshot projected,
                         HostGatewayClient.SessionObservation fenced) {
            this.gatewayReady = true;
            this.connectorReady = fenced.connectorEvidence == HostGatewayClient.Evidence.VERIFIED
                    || !fenced.requiresConnector;
            this.gameState = fenced.gameState;
            this.gameId = fenced.gameEvidence == HostGatewayClient.Evidence.VERIFIED
                    ? fenced.gameId : "";
            boolean exactProcess = fenced.processEvidence == HostGatewayClient.Evidence.VERIFIED;
            this.processId = exactProcess ? fenced.processId : 0;
            this.processToken = exactProcess && fenced.processToken != null
                    ? fenced.processToken : "";
            this.windowReady = fenced.windowEvidence == HostGatewayClient.Evidence.VERIFIED
                    && fenced.windowReady;
            this.targetKind = fenced.targetKind;
            this.stableSamples = fenced.stableSamples;
            this.reason = fenced.reason;
            this.hostGuideAllowed = fenced.hostGuideAllowed;
            this.observationState = projected.observationState.name();
            this.gameEvidence = fenced.gameEvidence.name();
            this.processEvidence = fenced.processEvidence.name();
            this.transportGeneration = projected.transportGeneration;
            this.sessionSnapshot = projected;
        }

        private Snapshot(SessionSnapshot projected,
                         HostGatewayClient.SessionObservation observation,
                         boolean gatewayReady) {
            this.gatewayReady = gatewayReady;
            this.connectorReady = observation != null
                    && (observation.connectorEvidence == HostGatewayClient.Evidence.VERIFIED
                    || !observation.requiresConnector);
            this.gameState = observation == null ? "unknown" : observation.gameState;
            this.gameId = projected.playniteGameId;
            this.processId = projected.processId;
            this.processToken = projected.processToken;
            this.windowReady = projected.observationState
                    == SessionSnapshot.ObservationState.READY;
            this.targetKind = observation == null ? "playnite" : observation.targetKind;
            this.stableSamples = observation == null ? 0 : observation.stableSamples;
            this.reason = projected.observationReason;
            this.hostGuideAllowed = observation != null && observation.hostGuideAllowed;
            this.observationState = projected.observationState.name();
            this.gameEvidence = observation == null
                    ? "UNAVAILABLE" : observation.gameEvidence.name();
            this.processEvidence = projected.processEvidence;
            this.transportGeneration = projected.transportGeneration;
            this.sessionSnapshot = projected;
        }

        /** Exact process proof from the one accepted canonical observation. */
        public boolean hasVerifiedProcessFor(String expectedGameId) {
            return "VERIFIED".equals(processEvidence)
                    && SessionSnapshot.normalize(expectedGameId).equals(
                    SessionSnapshot.normalize(gameId))
                    && processId > 0 && !processToken.isEmpty();
        }

        public boolean hasVerifiedGameFor(String expectedGameId) {
            return "VERIFIED".equals(gameEvidence)
                    && SessionSnapshot.normalize(expectedGameId).equals(
                    SessionSnapshot.normalize(gameId));
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

    private final HostGatewayClient client;
    private final GatewayConnection connection;
    private final ActorContext actorContext;
    private final String hostId;
    private final SessionStateResolver.ProjectionOwner projectionOwner;
    private final java.util.function.LongSupplier monotonicClock;

    private PlayniteTransitionGateway(GatewayConnection connection,
                                      ActorContext actorContext) {
        this(connection.endpoint(), connection, actorContext, new HostGatewayClient(),
                () -> System.nanoTime() / 1_000_000L);
    }

    PlayniteTransitionGateway(GatewayConnection connection, ActorContext actorContext,
                              HostGatewayClient client) {
        this(connection.endpoint(), connection, actorContext, client,
                () -> System.nanoTime() / 1_000_000L);
    }

    PlayniteTransitionGateway(String hostId, GatewayConnection connection,
                              ActorContext actorContext, HostGatewayClient client,
                              java.util.function.LongSupplier monotonicClock) {
        if (connection == null || actorContext == null || client == null) {
            throw new NullPointerException("Gateway context");
        }
        if (!connection.profileId().equals(actorContext.actorProfileId)) {
            throw new IllegalArgumentException("Gateway actor profile mismatch");
        }
        this.connection = connection;
        this.actorContext = actorContext;
        this.client = client;
        this.hostId = hostId == null ? connection.endpoint() : hostId;
        this.monotonicClock = monotonicClock;
        this.projectionOwner = SessionStateResolver.owner(this.hostId,
                actorContext.actorProfileId, actorContext.executionProfileId);
    }

    public static PlayniteTransitionGateway connect(Context context, String hostId,
                                                    String activeHost,
                                                    ActorContext actorContext) {
        GatewayConnection connection = new HostGatewayStore(context)
                .loadForHost(hostId, activeHost, actorContext.actorProfileId);
        if (connection == null) {
            MoonWakerDiagnostics.record("WARN", "android.playnite-transition",
                    "gateway.connect_failed", "host_id", hostId,
                    "profile_id", actorContext.actorProfileId,
                    "reason", "connection_missing");
            return null;
        }
        return new PlayniteTransitionGateway(hostId, connection, actorContext,
                new HostGatewayClient(), () -> System.nanoTime() / 1_000_000L);
    }

    public Snapshot snapshot() throws IOException {
        long ordinal = projectionOwner.nextRequestOrdinal();
        com.limelight.stream.RetainedStreamSessionCoordinator.Snapshot retained =
                com.limelight.stream.RetainedStreamSessionCoordinator.snapshot();
        String transition = retained.hostId.equalsIgnoreCase(hostId)
                && retained.executionProfileId.equals(actorContext.executionProfileId)
                ? retained.transitionId : "";
        long now = monotonicClock.getAsLong();
        try {
            HostGatewayClient.SessionObservation observation =
                    client.getSessionObservation(connection,
                            actorContext.executionProfileId, ordinal, transition);
            if (!projectionOwner.accept(observation, retained, now)) {
                SessionSnapshot projected = projectionOwner.current(retained, now);
                return new Snapshot(projected, null, false);
            }
            SessionSnapshot projected = projectionOwner.current(retained, now);
            boolean correlated = projected.observationCorrelated
                    && projected.requestOrdinal == observation.requestOrdinal;
            return new Snapshot(projected, correlated ? observation : null, correlated);
        } catch (IOException unavailable) {
            SessionSnapshot projected = projectionOwner.unavailable(
                    ordinal, retained, now, unavailable.getMessage());
            return new Snapshot(projected, null, false);
        }
    }

    /**
     * Observation for an exact control operation this client holds. Acquiring the permit
     * advances the Gateway control generation beyond the one bound to the retained stream,
     * so the ordinary projection correlation cannot match; the provider evidence is exposed
     * only when the accepted observation carries this permit's operation, generation and
     * stream session. The canonical projection is still updated through its single owner.
     */
    public Snapshot snapshotWithinControl(ControlOperationClient.Permit permit) throws IOException {
        long ordinal = projectionOwner.nextRequestOrdinal();
        com.limelight.stream.RetainedStreamSessionCoordinator.Snapshot retained =
                com.limelight.stream.RetainedStreamSessionCoordinator.snapshot();
        String transition = retained.hostId.equalsIgnoreCase(hostId)
                && retained.executionProfileId.equals(actorContext.executionProfileId)
                ? retained.transitionId : "";
        long now = monotonicClock.getAsLong();
        HostGatewayClient.SessionObservation observation;
        try {
            observation = client.getSessionObservation(connection,
                    actorContext.executionProfileId, ordinal, transition);
        } catch (IOException unavailable) {
            return new Snapshot(projectionOwner.unavailable(
                    ordinal, retained, now, unavailable.getMessage()), null, false);
        }
        boolean accepted = projectionOwner.accept(observation, retained, now);
        SessionSnapshot projected = projectionOwner.current(retained, now);
        boolean fenced = accepted && permit != null
                && !permit.operationId.isEmpty()
                && permit.operationId.equals(observation.operationId)
                && permit.generation > 0L
                && permit.generation == observation.controlGeneration
                && !permit.streamSessionId.isEmpty()
                && permit.streamSessionId.equals(observation.streamSessionId);
        if (fenced) return new Snapshot(projected, observation);
        // Outside the caller's exact permit nothing proves the stop target: fail closed.
        return new Snapshot(false, false, "unknown", "", 0, false, "playnite", 0,
                "control_correlation_mismatch");
    }

    public void startGame(String gameId) throws IOException {
        client.startGame(connection, gameId);
    }

    public void stopGame(String gameId, String processToken) throws IOException {
        client.stopGame(connection, gameId, processToken);
    }

    public void stopGameWithinControl(String gameId,
                                      ControlOperationClient.Permit permit) throws IOException {
        client.stopGameWithinControl(connection, gameId, permit);
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

    /** Returns the actor-bound request context used by this Gateway facade. */
    public GatewayConnection gatewayConnection() {
        return connection;
    }

    ActorContext actorContext() {
        return actorContext;
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
