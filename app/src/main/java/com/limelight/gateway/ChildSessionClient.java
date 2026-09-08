package com.limelight.gateway;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.Locale;
import java.util.UUID;

/**
 * Small client for the host-owned child session contract.
 *
 * <p>The client only transports host state. It does not keep a local allowance,
 * derive a deadline, or change the connection's execution profile.</p>
 */
public final class ChildSessionClient {
    private static final int REQUEST_TIMEOUT_MS = 25_000;
    private static final String DEFAULT_END_REASON = "client_closed";

    private static final String ELIGIBILITY_PATH = "/api/v1/child/session/eligibility";
    private static final String START_PATH = "/api/v1/child/session/start";
    private static final String STATUS_PATH = "/api/v1/child/session/status";
    private static final String RESUME_PATH = "/api/v1/child/session/resume";
    private static final String HEARTBEAT_PATH = "/api/v1/child/session/heartbeat";
    private static final String END_PATH = "/api/v1/child/session/end";

    @FunctionalInterface
    interface Poster {
        JSONObject postJson(GatewayConnection connection, String path, JSONObject body,
                            int readTimeoutMs) throws IOException;
    }

    private final Poster poster;

    public ChildSessionClient(GatewayTransport transport) {
        if (transport == null) throw new NullPointerException("transport");
        this.poster = transport::postJson;
    }

    ChildSessionClient(Poster poster) {
        if (poster == null) throw new NullPointerException("poster");
        this.poster = poster;
    }

    public State eligibility(GatewayConnection connection, String gameId) throws IOException {
        return request(connection, ELIGIBILITY_PATH,
                object("game_id", requireGameId(gameId)), null, true);
    }

    public State start(GatewayConnection connection, String gameId, String sessionId,
                       String requestId) throws IOException {
        String session = requireSessionId(sessionId);
        JSONObject body = new JSONObject();
        put(body, "game_id", requireGameId(gameId));
        put(body, "session_id", session);
        put(body, "request_id", GatewayTransport.requireRequestId(requestId));
        return request(connection, START_PATH, body, session, true);
    }

    public State status(GatewayConnection connection, String sessionId) throws IOException {
        return sessionRequest(connection, STATUS_PATH, sessionId);
    }

    public State resume(GatewayConnection connection, String sessionId) throws IOException {
        return sessionRequest(connection, RESUME_PATH, sessionId);
    }

    public State heartbeat(GatewayConnection connection, String sessionId) throws IOException {
        return sessionRequest(connection, HEARTBEAT_PATH, sessionId);
    }

    public State end(GatewayConnection connection, String sessionId, String reason)
            throws IOException {
        String session = requireSessionId(sessionId);
        JSONObject body = new JSONObject();
        put(body, "session_id", session);
        String endReason = reason == null || reason.trim().isEmpty()
                ? DEFAULT_END_REASON : GatewayTransport.requireRequestId(reason);
        put(body, "reason", endReason);
        return request(connection, END_PATH, body, session, false);
    }

    private State sessionRequest(GatewayConnection connection, String path, String sessionId)
            throws IOException {
        String session = requireSessionId(sessionId);
        return request(connection, path, object("session_id", session), session, false);
    }

    private State request(GatewayConnection connection, String path, JSONObject body,
                          String expectedSession, boolean allowSessionlessDeny)
            throws IOException {
        requireConnection(connection);
        try {
            JSONObject response = poster.postJson(connection, path, body, REQUEST_TIMEOUT_MS);
            boolean denied = isDeniedState(response);
            return parseState(response, connection.profileId(), expectedSession,
                    !denied, denied && allowSessionlessDeny);
        } catch (GatewayTransport.GatewayException error) {
            if (error.statusCode() != 403 && error.statusCode() != 409
                    && error.statusCode() != 503) {
                throw error;
            }
            JSONObject response = error.responseBody();
            if (!isDeniedState(response) || !hasActor(response, connection.profileId())) {
                // A generic permission/error response is still an error. Its structured
                // reason remains available through GatewayException.reason()/responseBody().
                throw error;
            }
            return parseState(response, connection.profileId(), expectedSession, false,
                    allowSessionlessDeny);
        }
    }

    private static State parseState(JSONObject response, String expectedActor,
                                    String expectedSession, boolean success,
                                    boolean allowMissingSessionOnDeny) throws IOException {
        if (response == null) throw new IOException("Host gateway returned no child session state.");
        if (!hasActor(response, expectedActor)) {
            throw new IOException("Child session response actor does not match the connection.");
        }

        String responseSession = response.optString("session_id", "").trim();
        if (!responseSession.isEmpty()) {
            try {
                responseSession = normalizeSessionId(responseSession);
            } catch (IllegalArgumentException error) {
                throw new IOException("Child session response contained an invalid session.",
                        error);
            }
            if (expectedSession == null || !expectedSession.equals(responseSession)) {
                throw new IOException("Child session response session does not match the request.");
            }
        } else if (expectedSession != null && (success || !allowMissingSessionOnDeny)) {
            throw new IOException("Child session response omitted the requested session.");
        }

        if (success) validateSuccessfulState(response);
        return new State(response, responseSession);
    }

    private static boolean isDeniedState(JSONObject response) {
        return response != null && response.has("ok") && !response.optBoolean("ok", false);
    }

    private static void validateSuccessfulState(JSONObject response) throws IOException {
        String state = response.optString("state", "").trim();
        String phase = response.optString("phase", "").trim();
        if (!"running".equals(state) && !"launch_pending".equals(state)
                && !"running".equals(phase) && !"launch_pending".equals(phase)) {
            return;
        }
        if (response.optString("execution_profile_id", "").trim().isEmpty()
                || response.optString("game_id", "").trim().isEmpty()) {
            throw new IOException("Running child session state omitted its binding.");
        }
        if (nonNegativeNumber(response, "remaining_daily_seconds") < 0
                || nonNegativeNumber(response, "playable_now_seconds") < 0
                || nonNegativeNumber(response, "deadline_epoch_ms") <= 0) {
            throw new IOException("Running child session state contained invalid timing.");
        }
    }

    private static long nonNegativeNumber(JSONObject response, String key) throws IOException {
        Object value = response.opt(key);
        if (!(value instanceof Number)) {
            throw new IOException("Child session state omitted " + key + ".");
        }
        double asDouble = ((Number) value).doubleValue();
        long asLong = ((Number) value).longValue();
        if (Double.isNaN(asDouble) || Double.isInfinite(asDouble) || asDouble < 0
                || asDouble != asLong) {
            throw new IOException("Child session state contained invalid " + key + ".");
        }
        return asLong;
    }

    private static boolean hasActor(JSONObject response, String expectedActor) {
        if (response == null) return false;
        String actor = response.optString("actor_profile_id", "").trim();
        return !actor.isEmpty() && actor.equals(expectedActor);
    }

    private static JSONObject object(String key, String value) throws IOException {
        JSONObject body = new JSONObject();
        put(body, key, value);
        return body;
    }

    private static void put(JSONObject body, String key, Object value) throws IOException {
        try {
            body.put(key, value);
        } catch (JSONException error) {
            throw new IOException("Could not build child session request.", error);
        }
    }

    private static void requireConnection(GatewayConnection connection) {
        if (connection == null) throw new NullPointerException("connection");
    }

    private static String requireGameId(String gameId) {
        String value = gameId == null ? "" : gameId.trim();
        if (value.isEmpty() || value.length() > 512 || value.indexOf('\n') >= 0
                || value.indexOf('\r') >= 0) {
            throw new IllegalArgumentException("Invalid child session game ID");
        }
        return value;
    }

    private static String requireSessionId(String sessionId) {
        String value = sessionId == null ? "" : sessionId.trim();
        return normalizeSessionId(value);
    }

    private static String normalizeSessionId(String sessionId) {
        if (!sessionId.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-"
                + "[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")) {
            throw new IllegalArgumentException("Invalid child session ID");
        }
        try {
            return UUID.fromString(sessionId).toString().toLowerCase(Locale.US);
        } catch (IllegalArgumentException error) {
            throw new IllegalArgumentException("Invalid child session ID", error);
        }
    }

    public static final class State {
        public final boolean ok;
        public final String state;
        public final String phase;
        public final String reason;
        public final String actorProfileId;
        public final String executionProfileId;
        public final String parentProfileId;
        public final String gameId;
        public final String sessionId;
        public final long remainingDailySeconds;
        public final long playableNowSeconds;
        public final long deadlineEpochMs;
        public final String serverTime;
        public final long policyRevision;
        public final long usageRevision;
        public final boolean cleanupRequired;

        private State(JSONObject body, String responseSession) {
            ok = body.optBoolean("ok", false);
            state = text(body, "state");
            phase = text(body, "phase");
            String bodyReason = text(body, "reason");
            reason = bodyReason.isEmpty() ? text(body, "error") : bodyReason;
            actorProfileId = text(body, "actor_profile_id");
            executionProfileId = text(body, "execution_profile_id");
            parentProfileId = text(body, "parent_profile_id");
            gameId = text(body, "game_id");
            sessionId = responseSession;
            remainingDailySeconds = nonNegativeLong(body, "remaining_daily_seconds");
            playableNowSeconds = nonNegativeLong(body, "playable_now_seconds");
            deadlineEpochMs = nonNegativeLong(body, "deadline_epoch_ms");
            serverTime = text(body, "server_time");
            policyRevision = nonNegativeLong(body, "policy_revision");
            usageRevision = nonNegativeLong(body, "usage_revision");
            cleanupRequired = body.optBoolean("cleanup_required", false);
        }

        private static String text(JSONObject body, String key) {
            return body.optString(key, "");
        }

        private static long nonNegativeLong(JSONObject body, String key) {
            return Math.max(0L, body.optLong(key, 0L));
        }
    }
}
