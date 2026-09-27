package com.limelight.gateway;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** Transport for the Gateway-owned MoonWaker control-operation lease. */
public final class ControlOperationClient {
    private static final int TIMEOUT_MS = 30_000;

    @FunctionalInterface
    interface Poster {
        JSONObject post(GatewayConnection connection, String path, JSONObject body,
                        String requestId, int timeoutMs) throws IOException;
    }

    private final Poster poster;

    public ControlOperationClient(GatewayTransport transport) {
        if (transport == null) throw new NullPointerException("transport");
        poster = transport::postJson;
    }

    ControlOperationClient(Poster poster) {
        if (poster == null) throw new NullPointerException("poster");
        this.poster = poster;
    }

    public Permit acquire(GatewayConnection connection, String operationId, String intent,
                          String streamSessionId, int sunshineAppId,
                          long expectedGeneration, int observedSunshineAppId,
                          String childSessionId) throws IOException {
        JSONObject body = new JSONObject();
        put(body, "operation_id", GatewayTransport.requireRequestId(operationId));
        put(body, "intent", requireIntent(intent));
        put(body, "stream_session_id", GatewayTransport.requireRequestId(streamSessionId));
        if (sunshineAppId <= 0 || expectedGeneration < 0 || observedSunshineAppId < 0) {
            throw new IllegalArgumentException("Invalid control operation binding");
        }
        put(body, "sunshine_app_id", sunshineAppId);
        put(body, "expected_generation", expectedGeneration);
        put(body, "observed_sunshine_app_id", observedSunshineAppId);
        put(body, "child_session_id", childSessionId == null ? "" : childSessionId.trim());
        return parse(poster.post(connection, "/api/v1/control/operation/acquire",
                body, operationId, "full_quit".equals(intent) || "hard_reset".equals(intent)
                        ? 40_000 : TIMEOUT_MS), operationId, streamSessionId, sunshineAppId,
                true);
    }

    /** A completed operation is a ledger replay, not a connection bootstrap. */
    public Permit acquireBootstrapAfterReplay(GatewayConnection connection,
                                              String bootstrapOperationId,
                                              Permit replay, int observedSunshineAppId,
                                              String childSessionId) throws IOException {
        if (replay == null || !replay.succeeded()) {
            throw new IllegalArgumentException("A completed replay is required");
        }
        return acquire(connection, bootstrapOperationId, "reconnect",
                replay.streamSessionId, replay.sunshineAppId, replay.generation,
                observedSunshineAppId, childSessionId);
    }

    /** Read the server-owned binding when an explicit quit has no local resume record. */
    public Permit ownedSession(GatewayConnection connection) throws IOException {
        String requestId = "control-status-" + UUID.randomUUID();
        JSONObject body = poster.post(connection, "/api/v1/control/operation/status",
                new JSONObject(), requestId, TIMEOUT_MS);
        if (body == null || !body.optBoolean("ok") || !"owned".equals(body.optString("state"))) {
            throw new IOException("Gateway could not identify an owned stream.");
        }
        String session = body.optString("stream_session_id");
        int app = body.optInt("sunshine_app_id");
        long generation = body.optLong("generation");
        long epoch = body.optLong("actor_lease_epoch");
        if (session.isEmpty() || app <= 0 || generation <= 0 || epoch <= 0) {
            throw new IOException("Gateway returned an incomplete owned stream.");
        }
        return new Permit(requestId, session, app, generation, epoch, "owned", "none",
                body.optString("provider_game_id"), body.optString("process_token"),
                body.optInt("process_id"));
    }

    public static String operationId(String intent, String streamSessionId,
                                     int sunshineAppId, long expectedGeneration,
                                     String childSessionId) {
        String value = requireIntent(intent) + "\n"
                + GatewayTransport.requireRequestId(streamSessionId) + "\n"
                + sunshineAppId + "\n" + expectedGeneration + "\n"
                + (childSessionId == null ? "" : childSessionId.trim());
        return "control-" + UUID.nameUUIDFromBytes(
                value.getBytes(StandardCharsets.UTF_8));
    }

    public Result complete(GatewayConnection connection, Permit permit, boolean succeeded)
            throws IOException {
        return complete(connection, permit, succeeded, false);
    }

    public Result complete(GatewayConnection connection, Permit permit, boolean succeeded,
                           boolean definitiveFailure) throws IOException {
        if (succeeded && definitiveFailure) {
            throw new IllegalArgumentException("A successful effect cannot be a failure");
        }
        JSONObject body = new JSONObject();
        put(body, "operation_id", permit.operationId);
        put(body, "generation", permit.generation);
        put(body, "actor_lease_epoch", permit.actorLeaseEpoch);
        put(body, "succeeded", succeeded);
        if (definitiveFailure) {
            JSONObject effectResult = new JSONObject();
            put(effectResult, "outcome", "definitive_failure");
            put(body, "effect_result", effectResult);
        }
        Permit result = parse(poster.post(connection,
                "/api/v1/control/operation/complete", body, permit.operationId, TIMEOUT_MS),
                permit.operationId, permit.streamSessionId, permit.sunshineAppId, false);
        return new Result(result.state, result.reason, result.generation);
    }

    public Result reconcile(GatewayConnection connection, Permit permit, int observedAppId)
            throws IOException {
        return reconcile(connection, permit.operationId, permit.streamSessionId,
                permit.sunshineAppId, observedAppId);
    }

    public Result reconcile(GatewayConnection connection, String operationId,
                            String streamSessionId, int sunshineAppId, int observedAppId)
            throws IOException {
        if (observedAppId < 0) throw new IllegalArgumentException("Invalid observed app ID");
        JSONObject body = new JSONObject();
        put(body, "operation_id", operationId);
        put(body, "observed_sunshine_app_id", observedAppId);
        Permit result = parse(poster.post(connection,
                "/api/v1/control/operation/reconcile", body, operationId, TIMEOUT_MS),
                operationId, streamSessionId, sunshineAppId, false);
        return new Result(result.state, result.reason, result.generation);
    }

    private static Permit parse(JSONObject body, String operationId, String streamSessionId,
                                int sunshineAppId, boolean requirePermit) throws IOException {
        if (body == null || !operationId.equals(body.optString("operation_id"))
                || !streamSessionId.equals(body.optString("stream_session_id"))
                || sunshineAppId != body.optInt("sunshine_app_id", 0)) {
            throw new IOException("Gateway returned an unbound control operation.");
        }
        String state = body.optString("state", "");
        long generation = body.optLong("generation", 0L);
        long epoch = body.optLong("actor_lease_epoch", 0L);
        if (generation <= 0 || epoch <= 0 || requirePermit
                && !("permitted".equals(state) || "succeeded".equals(state))) {
            throw new IOException("Gateway did not grant the requested control operation.");
        }
        String processToken = body.optString("process_token", "");
        if (!processToken.isEmpty() && !processToken.matches("[0-9a-f]{64}")) {
            throw new IOException("Gateway returned an invalid process identity.");
        }
        return new Permit(operationId, streamSessionId, sunshineAppId, generation, epoch,
                state, body.optString("reason", ""),
                body.optString("provider_game_id", ""), processToken,
                body.optInt("process_id", 0));
    }

    private static String requireIntent(String intent) {
        String value = intent == null ? "" : intent.trim();
        if (!value.matches("launch|resume|reconnect|replacement|warm_up|full_quit|child_terminal|hard_reset|reauthorize")) {
            throw new IllegalArgumentException("Invalid control operation intent");
        }
        return value;
    }

    private static void put(JSONObject body, String key, Object value) throws IOException {
        try {
            body.put(key, value);
        } catch (JSONException impossible) {
            throw new IOException(impossible);
        }
    }

    public static final class Permit {
        public final String operationId;
        public final String streamSessionId;
        public final int sunshineAppId;
        public final long generation;
        public final long actorLeaseEpoch;
        public final String state;
        public final String reason;
        public final String providerGameId;
        public final String processToken;
        public final int processId;

        Permit(String operationId, String streamSessionId, int sunshineAppId,
               long generation, long actorLeaseEpoch, String state, String reason,
               String providerGameId, String processToken, int processId) {
            this.operationId = operationId;
            this.streamSessionId = streamSessionId;
            this.sunshineAppId = sunshineAppId;
            this.generation = generation;
            this.actorLeaseEpoch = actorLeaseEpoch;
            this.state = state;
            this.reason = reason;
            this.providerGameId = providerGameId == null ? "" : providerGameId.trim();
            this.processToken = processToken == null ? "" : processToken.trim();
            this.processId = Math.max(0, processId);
        }

        public boolean succeeded() { return "succeeded".equals(state); }
    }

    public static final class Result {
        public final String state;
        public final String reason;
        public final long generation;

        Result(String state, String reason, long generation) {
            this.state = state;
            this.reason = reason;
            this.generation = generation;
        }

        public boolean succeeded() {
            return "succeeded".equals(state);
        }

        public boolean providerStopped() {
            return "provider_stopped".equals(state);
        }
    }
}
