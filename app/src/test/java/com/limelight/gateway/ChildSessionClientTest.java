package com.limelight.gateway;

import org.json.JSONObject;
import org.json.JSONException;
import org.junit.Test;

import java.io.IOException;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class ChildSessionClientTest {
    private static final String FINGERPRINT =
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
    private static final String SESSION = "123e4567-e89b-12d3-a456-426614174000";
    private static final GatewayConnection CONNECTION = new GatewayConnection(
            "https://host:8785", "secret", FINGERPRINT, "profile-1");

    @Test
    public void startBuildsBodyAndParsesRunningState() throws Exception {
        RecordingPoster poster = new RecordingPoster();
        poster.response = state(true, "running", "running", "profile-1", SESSION);

        ChildSessionClient.State result = client(poster).start(
                CONNECTION, "steam:123", SESSION, "request-1");

        assertEquals("/api/v1/child/session/start", poster.path);
        assertEquals("steam:123", poster.body.getString("game_id"));
        assertEquals(SESSION, poster.body.getString("session_id"));
        assertEquals("request-1", poster.body.getString("request_id"));
        assertEquals("running", result.state);
        assertEquals("running", result.phase);
        assertEquals(3600L, result.playableNowSeconds);
        assertEquals(7200L, result.deadlineEpochMs);
        assertEquals(4L, result.policyRevision);
    }

    @Test
    public void eligibilityHasNoSessionAndParsesBlockedState() throws Exception {
        RecordingPoster poster = new RecordingPoster();
        poster.response = state(false, "blocked", "blocked", "profile-1", null)
                .put("reason", "game_not_shared");

        ChildSessionClient.State result = client(poster).eligibility(CONNECTION, "playnite:abc");

        assertEquals("/api/v1/child/session/eligibility", poster.path);
        assertEquals("playnite:abc", poster.body.getString("game_id"));
        assertFalse(poster.body.has("session_id"));
        assertEquals("game_not_shared", result.reason);
        assertEquals("profile-1", result.actorProfileId);
        assertEquals("", result.sessionId);
    }

    @Test
    public void sessionRoutesUseSessionOnlyBodies() throws Exception {
        RecordingPoster poster = new RecordingPoster();
        poster.response = state(true, "running", "running", "profile-1", SESSION);
        ChildSessionClient client = client(poster);

        client.status(CONNECTION, SESSION);
        assertEquals("/api/v1/child/session/status", poster.path);
        assertEquals(SESSION, poster.body.getString("session_id"));
        assertEquals(1, poster.body.length());

        client.resume(CONNECTION, SESSION);
        assertEquals("/api/v1/child/session/resume", poster.path);
        client.heartbeat(CONNECTION, SESSION);
        assertEquals("/api/v1/child/session/heartbeat", poster.path);

        client.end(CONNECTION, SESSION, "client_closed");
        assertEquals("/api/v1/child/session/end", poster.path);
        assertEquals("client_closed", poster.body.getString("reason"));
        assertEquals(2, poster.body.length());
    }

    @Test
    public void structuredBusinessDenyReturnsCorrelatedState() throws Exception {
        RecordingPoster poster = new RecordingPoster();
        poster.failure = new GatewayTransport.GatewayException("blocked", 409, 0,
                state(false, "blocked", "blocked", "profile-1", SESSION)
                        .put("reason", "outside_schedule")
                        .put("remaining_daily_seconds", 0)
                        .put("cleanup_required", false));

        ChildSessionClient.State result = client(poster).status(CONNECTION, SESSION);

        assertFalse(result.ok);
        assertEquals("outside_schedule", result.reason);
        assertEquals(SESSION, result.sessionId);
        assertFalse(result.cleanupRequired);
    }

    @Test
    public void cleanupStateAndEndReasonArePreserved() throws Exception {
        RecordingPoster poster = new RecordingPoster();
        poster.failure = new GatewayTransport.GatewayException("cleanup", 503, 0,
                state(false, "cleanup_required", "cleanup", "profile-1", SESSION)
                        .put("reason", "cleanup_required")
                        .put("cleanup_required", true));

        ChildSessionClient.State result = client(poster).end(CONNECTION, SESSION, null);

        assertEquals("/api/v1/child/session/end", poster.path);
        assertEquals("client_closed", poster.body.getString("reason"));
        assertEquals("cleanup_required", result.reason);
        assertTrue(result.cleanupRequired);
    }

    @Test
    public void foreignActorOrSessionIsRejected() throws Exception {
        RecordingPoster poster = new RecordingPoster();
        poster.response = state(true, "running", "running", "other-profile", SESSION);
        assertRejected(() -> client(poster).status(CONNECTION, SESSION));

        poster.response = state(true, "running", "running", "profile-1",
                "123e4567-e89b-12d3-a456-426614174001");
        assertRejected(() -> client(poster).status(CONNECTION, SESSION));
    }

    @Test
    public void successSessionResponseMustEchoRequestedSession() throws Exception {
        RecordingPoster poster = new RecordingPoster();
        poster.response = state(true, "running", "running", "profile-1", null);
        assertRejected(() -> client(poster).heartbeat(CONNECTION, SESSION));
    }

    @Test
    public void sparseEndedReplayOnlyNeedsActorAndSessionBinding() throws Exception {
        RecordingPoster poster = new RecordingPoster();
        poster.response = new JSONObject()
                .put("ok", true)
                .put("state", "ended")
                .put("reason", "stale_session")
                .put("actor_profile_id", "profile-1")
                .put("session_id", SESSION);

        ChildSessionClient.State result = client(poster).end(CONNECTION, SESSION, "client_closed");

        assertTrue(result.ok);
        assertEquals("ended", result.state);
        assertEquals("stale_session", result.reason);
        assertEquals(SESSION, result.sessionId);
        assertEquals("", result.gameId);
        assertEquals("", result.executionProfileId);
    }

    @Test
    public void startDenyMayOmitSessionBeforeOneExists() throws Exception {
        RecordingPoster poster = new RecordingPoster();
        poster.response = new JSONObject()
                .put("ok", false)
                .put("state", "blocked")
                .put("reason", "game_not_shared")
                .put("actor_profile_id", "profile-1");

        ChildSessionClient.State result = client(poster).start(
                CONNECTION, "playnite:abc", SESSION, "request-1");

        assertFalse(result.ok);
        assertEquals("game_not_shared", result.reason);
        assertEquals("", result.sessionId);
    }

    @Test
    public void runningStateRequiresBindingAndTimingFields() throws Exception {
        RecordingPoster poster = new RecordingPoster();
        JSONObject missingBinding = state(true, "running", "running", "profile-1", SESSION);
        missingBinding.remove("execution_profile_id");
        assertRejected(() -> {
            poster.response = missingBinding;
            client(poster).status(CONNECTION, SESSION);
        });

        JSONObject invalidTiming = state(true, "running", "running", "profile-1", SESSION);
        invalidTiming.remove("deadline_epoch_ms");
        assertRejected(() -> {
            poster.response = invalidTiming;
            client(poster).status(CONNECTION, SESSION);
        });
    }

    @Test
    public void genericPermissionErrorWithoutActorRemainsGatewayException() throws Exception {
        RecordingPoster poster = new RecordingPoster();
        poster.failure = new GatewayTransport.GatewayException("permission denied", 403, 0,
                new JSONObject().put("error", "permission_denied")
                        .put("reason", "child_profiles_disabled"));

        try {
            client(poster).eligibility(CONNECTION, "steam:123");
            fail("expected GatewayException");
        } catch (GatewayTransport.GatewayException error) {
            assertEquals(403, error.statusCode());
            assertEquals("child_profiles_disabled", error.reason());
            assertNotNull(error.responseBody());
        }
    }

    @Test
    public void offlineAndUnstructuredErrorsRemainErrors() throws Exception {
        RecordingPoster poster = new RecordingPoster();
        poster.failure = new IOException("offline");
        try {
            client(poster).heartbeat(CONNECTION, SESSION);
            fail("expected IOException");
        } catch (IOException error) {
            assertEquals("offline", error.getMessage());
        }

        poster.failure = new GatewayTransport.GatewayException("invalid response", 503);
        try {
            client(poster).heartbeat(CONNECTION, SESSION);
            fail("expected GatewayException");
        } catch (GatewayTransport.GatewayException error) {
            assertEquals(503, error.statusCode());
            assertEquals(null, error.responseBody());
        }
    }

    private static ChildSessionClient client(RecordingPoster poster) {
        return new ChildSessionClient(poster);
    }

    private static JSONObject state(boolean ok, String state, String phase, String actor,
                                   String session) throws Exception {
        JSONObject body = new JSONObject()
                .put("ok", ok)
                .put("state", state)
                .put("phase", phase)
                .put("actor_profile_id", actor)
                .put("execution_profile_id", "parent-1")
                .put("parent_profile_id", "parent-1")
                .put("game_id", "steam:123")
                .put("remaining_daily_seconds", 3600)
                .put("playable_now_seconds", 3600)
                .put("deadline_epoch_ms", 7200)
                .put("server_time", "2026-09-05T10:00:00Z")
                .put("policy_revision", 4)
                .put("usage_revision", 5)
                .put("cleanup_required", false);
        if (session != null) body.put("session_id", session);
        return body;
    }

    private static void assertRejected(ThrowingCall call) throws Exception {
        try {
            call.run();
            fail("expected response binding failure");
        } catch (IOException expected) {
            // Expected: a response from another actor/session is never accepted as state.
        }
    }

    private interface ThrowingCall {
        void run() throws Exception;
    }

    private static final class RecordingPoster implements ChildSessionClient.Poster {
        private JSONObject response;
        private IOException failure;
        private String path;
        private JSONObject body;

        @Override
        public JSONObject postJson(GatewayConnection connection, String path, JSONObject body,
                                   int readTimeoutMs) throws IOException {
            this.path = path;
            try {
                this.body = new JSONObject(body.toString());
            } catch (JSONException error) {
                throw new IOException(error);
            }
            if (failure != null) throw failure;
            return response;
        }
    }
}
