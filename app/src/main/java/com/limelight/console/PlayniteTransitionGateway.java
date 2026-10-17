package com.limelight.console;

import android.content.Context;

import com.limelight.diagnostics.MoonWakerDiagnostics;
import com.limelight.gateway.GatewayConnection;
import com.limelight.gateway.ControlOperationClient;
import com.limelight.session.SessionEvent;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.function.BooleanSupplier;
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
        /** The stale earlier launch's game and verified process token, when the host reports it. */
        public final String previousLaunchGameId;
        public final String previousLaunchProcessToken;
        /** Optional display name of that earlier launch's game; empty when the host omits it. */
        public final String previousLaunchName;
        /** Progress (0-100) of the game update the start waits on, -1 without byte counts. */
        public final int updateProgress;
        /** The update is in its patching phase (after the download). */
        public final boolean updatePatching;
        /**
         * How long the host's current state episode had lasted when it was observed
         * ({@code observed_at - state_since}, host clock only); -1 when unknown.
         */
        public final long stateAgeMs;
        final SessionSnapshot sessionSnapshot;
        /** The model accepted this read's own observation (not a rejected or failed read). */
        final boolean observationAccepted;

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
            this(gatewayReady, connectorReady, gameState, gameId, processId, windowReady,
                    targetKind, stableSamples, reason, hostGuideAllowed, processToken, "", "",
                    "", 0L);
        }

        Snapshot(boolean gatewayReady, boolean connectorReady, String gameState,
                 String gameId, int processId, boolean windowReady, String targetKind,
                 int stableSamples, String reason, boolean hostGuideAllowed,
                 String processToken, String previousLaunchGameId,
                 String previousLaunchProcessToken, String previousLaunchName,
                 long stateAgeMs) {
            this(gatewayReady, connectorReady, gameState, gameId, processId, windowReady,
                    targetKind, stableSamples, reason, hostGuideAllowed, processToken,
                    previousLaunchGameId, previousLaunchProcessToken, previousLaunchName,
                    stateAgeMs, -1);
        }

        Snapshot(boolean gatewayReady, boolean connectorReady, String gameState,
                 String gameId, int processId, boolean windowReady, String targetKind,
                 int stableSamples, String reason, boolean hostGuideAllowed,
                 String processToken, String previousLaunchGameId,
                 String previousLaunchProcessToken, String previousLaunchName,
                 long stateAgeMs, int updateProgress) {
            this(gatewayReady, connectorReady, gameState, gameId, processId, windowReady,
                    targetKind, stableSamples, reason, hostGuideAllowed, processToken,
                    previousLaunchGameId, previousLaunchProcessToken, previousLaunchName,
                    stateAgeMs, updateProgress, false);
        }

        Snapshot(boolean gatewayReady, boolean connectorReady, String gameState,
                 String gameId, int processId, boolean windowReady, String targetKind,
                 int stableSamples, String reason, boolean hostGuideAllowed,
                 String processToken, String previousLaunchGameId,
                 String previousLaunchProcessToken, String previousLaunchName,
                 long stateAgeMs, int updateProgress, boolean updatePatching) {
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
            this.previousLaunchGameId = previousLaunchGameId;
            this.previousLaunchProcessToken = previousLaunchProcessToken;
            this.previousLaunchName = previousLaunchName;
            this.stateAgeMs = stateAgeMs;
            this.updateProgress = updateProgress;
            this.updatePatching = updatePatching;
            this.sessionSnapshot = null;
            this.observationAccepted = false;
        }

        /** Provider evidence of one observation already fenced by the caller's own control permit. */
        private Snapshot(SessionSnapshot projected,
                         HostGatewayClient.SessionObservation fenced,
                         boolean observationAccepted) {
            this.observationAccepted = observationAccepted;
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
            this.previousLaunchGameId = fenced.previousLaunchGameId;
            this.previousLaunchProcessToken = fenced.previousLaunchProcessToken;
            this.previousLaunchName = fenced.previousLaunchGameName;
            this.stateAgeMs = fenced.observedAtUnixMs - fenced.stateSinceUnixMs;
            this.updateProgress = fenced.updateProgress;
            this.updatePatching = fenced.updatePatching;
            this.sessionSnapshot = projected;
        }

        private Snapshot(SessionSnapshot projected,
                         HostGatewayClient.SessionObservation observation,
                         boolean gatewayReady, boolean observationAccepted) {
            this.observationAccepted = observationAccepted;
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
            this.previousLaunchGameId = observation == null ? "" : observation.previousLaunchGameId;
            this.previousLaunchProcessToken = observation == null
                    ? "" : observation.previousLaunchProcessToken;
            this.previousLaunchName = observation == null
                    ? "" : observation.previousLaunchGameName;
            this.stateAgeMs = observation == null
                    ? -1L : observation.observedAtUnixMs - observation.stateSinceUnixMs;
            this.updateProgress = observation == null ? -1 : observation.updateProgress;
            this.updatePatching = observation != null && observation.updatePatching;
            this.sessionSnapshot = projected;
        }

        /** Exact process proof from the one accepted canonical observation. */
        public boolean hasVerifiedProcessFor(String expectedGameId) {
            return "VERIFIED".equals(processEvidence)
                    && SessionSnapshot.normalize(expectedGameId).equals(
                    SessionSnapshot.normalize(gameId))
                    && processId > 0 && !processToken.isEmpty();
        }

        /** The expected game's start is starting or failed and has no process to stop. */
        public boolean hasStartWithoutProcessFor(String expectedGameId) {
            String state = SessionSnapshot.normalize(gameState);
            return ("starting".equals(state) || "failed".equals(state))
                    && SessionSnapshot.normalize(expectedGameId).equals(
                    SessionSnapshot.normalize(gameId))
                    && processId <= 0 && processToken.isEmpty();
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
    private final com.limelight.session.SessionModel model;
    private final java.util.function.LongSupplier monotonicClock;

    PlayniteTransitionGateway(com.limelight.session.SessionModel model,
                              GatewayConnection connection, ActorContext actorContext,
                              HostGatewayClient client) {
        this(model, connection.endpoint(), connection, actorContext, client,
                () -> System.nanoTime() / 1_000_000L);
    }

    PlayniteTransitionGateway(com.limelight.session.SessionModel model, String hostId,
                              GatewayConnection connection,
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
        this.model = model;
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
        return new PlayniteTransitionGateway(com.limelight.session.SessionModel.of(context),
                hostId, connection, actorContext,
                new HostGatewayClient(), () -> System.nanoTime() / 1_000_000L);
    }

    /** Reads that may lose the owner's dispatch ordering to a concurrent poll before giving up. */
    static final int SNAPSHOT_DISPATCH_ATTEMPTS = 3;

    public Snapshot snapshot() throws IOException {
        for (int attempt = 1; ; attempt++) {
            com.limelight.session.SessionModel.ObservationRequest request = awaitModel(
                    new SessionEvent.ObservationDispatched(hostId,
                            actorContext.actorProfileId, actorContext.executionProfileId));
            try {
                HostGatewayClient.SessionObservation observation =
                        client.getSessionObservation(connection,
                                actorContext.executionProfileId, request.ordinal);
                com.limelight.session.SessionModel.ObservationResult result = observed(observation);
                if (result.accepted == null) {
                    // The dashboard polls the same projection: a read that lost only the
                    // dispatch ordering carries no evidence, so read again instead of deciding
                    // on the older projection (lab formal133: the retained switch failed).
                    if (attempt < SNAPSHOT_DISPATCH_ATTEMPTS && result.lostDispatchOrdering) {
                        continue;
                    }
                    return new Snapshot(result.projection, null, false, false);
                }
                boolean correlated = result.accepted.observationCorrelated;
                return new Snapshot(result.accepted, correlated ? observation : null, correlated,
                        true);
            } catch (IOException unavailable) {
                // A cancelled read (interrupt restored by the transport) observed nothing.
                if (Thread.currentThread().isInterrupted()) throw unavailable;
                return new Snapshot(unavailable(request.ordinal, unavailable.getMessage()),
                        null, false, false);
            }
        }
    }

    private com.limelight.session.SessionModel.ObservationResult observed(
            HostGatewayClient.SessionObservation observation) throws IOException {
        return awaitModel(new SessionEvent.HostObserved(hostId, actorContext.actorProfileId,
                actorContext.executionProfileId, observation));
    }

    private SessionSnapshot unavailable(long ordinal, String reason) throws IOException {
        return awaitModel(new SessionEvent.ObservationUnavailable(hostId,
                actorContext.actorProfileId, actorContext.executionProfileId, ordinal, reason));
    }

    /**
     * The model's answer, or an IOException this read reports as unavailable: a transition
     * that failed (or a projection the model no longer holds) answers null, and an interrupt
     * is the read's cancellation (the interrupt stays set).
     */
    private <R> R awaitModel(SessionEvent<R> event) throws IOException {
        R result;
        try {
            result = model.awaitInterruptibly(event);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new java.io.InterruptedIOException("session_model_interrupted");
        }
        if (result == null) throw new IOException("session_model_failed");
        return result;
    }


    /**
     * Observation for an exact control operation this client holds. The provider evidence is
     * exposed
     * only when the observation carries this permit's operation, generation, stream session
     * and actor lease epoch. The canonical projection is still updated by the session model.
     * The dashboard polls the same projection, typically at the moment a close returns to it,
     * so this read can lose the dispatch ordering; the permit fence already proves the
     * observation was produced within this operation, so its evidence stays usable when the
     * model admits its scope and producer boot. The returned projection is then the current
     * one; the stop decision reads only the fenced observation's evidence.
     */
    public Snapshot snapshotWithinControl(ControlOperationClient.Permit permit) throws IOException {
        com.limelight.session.SessionModel.ObservationRequest request = awaitModel(
                new SessionEvent.ObservationDispatched(hostId,
                        actorContext.actorProfileId, actorContext.executionProfileId));
        HostGatewayClient.SessionObservation observation;
        try {
            observation = client.getSessionObservation(connection,
                    actorContext.executionProfileId, request.ordinal);
        } catch (IOException unavailable) {
            if (Thread.currentThread().isInterrupted()) throw unavailable;
            return new Snapshot(unavailable(request.ordinal, unavailable.getMessage()),
                    null, false, false);
        }
        // The model returns the projection of this exact observation, never a newer one.
        com.limelight.session.SessionModel.ObservationResult result = observed(observation);
        boolean permitted = permit != null
                && !permit.operationId.isEmpty()
                && permit.operationId.equals(observation.operationId)
                && permit.generation > 0L
                && permit.generation == observation.controlGeneration
                && !permit.streamSessionId.isEmpty()
                && permit.streamSessionId.equals(observation.streamSessionId)
                && permit.actorLeaseEpoch == observation.actorLeaseEpoch;
        if (permitted && result.accepted != null) {
            return new Snapshot(result.accepted, observation, true);
        }
        // A read that lost only the dispatch ordering may still name the exact running
        // process for the permit/token-checked stop. It never proves absence: a newer
        // accepted observation may show the game running, so idle or unverified evidence
        // from it would let the stop be skipped.
        if (permitted && result.admitsPermitEvidence
                && observation.gameEvidence == HostGatewayClient.Evidence.VERIFIED
                && observation.processEvidence == HostGatewayClient.Evidence.VERIFIED
                && observation.processToken != null && !observation.processToken.isEmpty()) {
            return new Snapshot(result.projection, observation, false);
        }
        // Outside the caller's exact permit nothing proves the stop target: fail closed.
        return new Snapshot(false, false, "unknown", "", 0, false, "playnite", 0,
                "control_correlation_mismatch");
    }
    static final long STOP_EVIDENCE_RETRY_MILLIS = 250L;

    /**
     * The within-control read a stop decision uses. A catalog refresh (the dashboard refreshes
     * as a close returns to it) briefly makes the provider's running scan stale: the game stays
     * verified while its process evidence is pending. Re-read under the same permit, bounded,
     * until the answer is idle or names the expected game's verified process; any other final
     * answer is returned unchanged and the caller still fails closed on it.
     */
    public Snapshot stopEvidenceWithinControl(ControlOperationClient.Permit permit,
                                              String expectedGameId, long timeoutMillis)
            throws IOException, InterruptedException {
        long deadline = monotonicClock.getAsLong() + timeoutMillis;
        Snapshot current = snapshotWithinControl(permit);
        String expected = expectedGameId == null ? "" : expectedGameId.trim();
        while (!expected.isEmpty() && !settledForStop(current, expected)
                && monotonicClock.getAsLong() < deadline) {
            Thread.sleep(STOP_EVIDENCE_RETRY_MILLIS);
            current = snapshotWithinControl(permit);
        }
        return current;
    }

    static boolean settledForStop(Snapshot snapshot, String expectedGameId) {
        boolean idle = "idle".equalsIgnoreCase(snapshot.gameState)
                && (snapshot.gameId == null || snapshot.gameId.trim().isEmpty());
        return idle || snapshot.hasVerifiedProcessFor(expectedGameId);
    }

    public void startGame(String gameId) throws IOException {
        client.startGame(connection, gameId);
    }

    public void stopGame(String gameId, String processToken) throws IOException {
        client.stopGame(connection, gameId, processToken);
    }

    public void abandonStart(String gameId) throws IOException {
        client.abandonStart(connection, gameId);
    }

    /**
     * False: the host answered and refused the stop (the provider's process changed or did not
     * exit, or the Gateway fenced the request), so nothing of it is uncertain. An
     * {@link IOException} is an answer that may be lost: the stop may still have taken effect.
     */
    public boolean stopGameWithinControl(String gameId,
                                         ControlOperationClient.Permit permit) throws IOException {
        try {
            client.stopGameWithinControl(connection, gameId, permit);
            return true;
        } catch (HostGatewayClient.GatewayException answered) {
            // 0: a received refusal; 4xx: refused before any effect. A 5xx (the provider's
            // answer did not reach the Gateway) or an unreadable 2xx stays uncertain.
            int status = answered.statusCode;
            if (status != 0 && (status < 400 || status >= 500)) throw answered;
            return false;
        }
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

    static final long HOST_UNLOCK_TIMEOUT_MILLIS = 30_000L;

    /** Outcome of one session ensure. */
    public enum HostUnlock {
        /** An unlock attempt ran and the host reports the session ready. */
        UNLOCKED,
        /** The host answered ready without starting any attempt: it saw no lock. */
        READY_WITHOUT_ATTEMPT,
        /** Refused, failed, uncorrelated, cancelled or timed out. */
        NOT_READY
    }

    /**
     * One {@code system/session/ensure} for this actor, followed by its attempt until the host
     * reports the session ready, refuses, or the bound expires (the attempt is then cancelled).
     * The Gateway applies the actor's own entitlements. An answer for another request or actor,
     * the caller's cancellation, the bound or a failed poll cancel the live attempt, so the
     * broker never unlocks Windows for a launch that no longer wants it.
     */
    public HostUnlock unlockHostSession(BooleanSupplier cancelled)
            throws IOException, InterruptedException {
        String requestId = "android-unlock-" + UUID.randomUUID();
        HostLaunchPreflight.Session session = HostLaunchPreflight.Session.from(
                client.ensureWindowsSession(connection, requestId));
        String attemptId = session.attemptId;
        long deadline = monotonicClock.getAsLong() + HOST_UNLOCK_TIMEOUT_MILLIS;
        boolean settled = false;
        try {
            if (!correlated(session, requestId, attemptId)) return HostUnlock.NOT_READY;
            while (session.canPoll()) {
                if (cancelled.getAsBoolean()
                        || monotonicClock.getAsLong() >= deadline) return HostUnlock.NOT_READY;
                Thread.sleep(session.retryAfterMs);
                if (cancelled.getAsBoolean()) return HostUnlock.NOT_READY;
                session = HostLaunchPreflight.Session.from(client.getWindowsSessionStatus(
                        connection, requestId, attemptId));
                if (!correlated(session, requestId, attemptId)) return HostUnlock.NOT_READY;
            }
            settled = true;
            if (!session.isReady()) return HostUnlock.NOT_READY;
            return attemptId.isEmpty() ? HostUnlock.READY_WITHOUT_ATTEMPT : HostUnlock.UNLOCKED;
        } finally {
            if (!settled && !attemptId.isEmpty()) cancelUnlock(requestId, attemptId);
        }
    }

    private boolean correlated(HostLaunchPreflight.Session session, String requestId,
                               String attemptId) {
        return (session.requestId.isEmpty() || session.requestId.equals(requestId))
                && (session.actorProfileId.isEmpty()
                        || session.actorProfileId.equals(actorContext.actorProfileId))
                && (session.executionProfileId.isEmpty()
                        || session.executionProfileId.equals(actorContext.executionProfileId))
                && (session.attemptId.isEmpty() || session.attemptId.equals(attemptId));
    }

    private void cancelUnlock(String requestId, String attemptId) {
        try {
            client.cancelWindowsSession(connection, requestId, attemptId);
        } catch (IOException | RuntimeException ignored) {
            // Best effort: Broker expiry remains authoritative.
        }
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
