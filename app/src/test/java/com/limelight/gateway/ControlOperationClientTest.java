package com.limelight.gateway;

import org.json.JSONObject;
import org.json.JSONException;
import org.junit.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ControlOperationClientTest {
    private final GatewayConnection connection = new GatewayConnection(
            "https://host:8785", "token", "a".repeat(64), "parent");

    @Test
    public void acquireAndCompleteReuseExactOperationIdentity() throws Exception {
        List<String> paths = new ArrayList<>();
        ControlOperationClient client = new ControlOperationClient((connection, path, body,
                                                                     requestId, timeout) -> {
            paths.add(path + ":" + requestId);
            if (path.endsWith("acquire")) {
                assertEquals(0, body.optInt("observed_sunshine_app_id", -1));
            }
            return response(body.optString("operation_id"), "stream-1", 7,
                    body.optLong("generation", 4),
                    body.optLong("actor_lease_epoch", 3),
                    path.endsWith("acquire") ? "permitted" : "succeeded");
        });

        ControlOperationClient.Permit permit = client.acquire(connection, "op-1", "launch",
                "stream-1", 7, 0, 0, "");
        ControlOperationClient.Result result = client.complete(connection, permit, true);

        assertEquals(4, permit.generation);
        assertTrue(result.succeeded());
        assertEquals(List.of("/api/v1/control/operation/acquire:op-1",
                "/api/v1/control/operation/complete:op-1"), paths);
    }

    @Test
    public void definitiveFailureIsDistinctFromUnknownEffectOutcome() throws Exception {
        List<JSONObject> completions = new ArrayList<>();
        ControlOperationClient client = new ControlOperationClient((connection, path, body,
                                                                     requestId, timeout) -> {
            if (path.endsWith("complete")) completions.add(body);
            return response(requestId, "stream-1", 7, 4, 3,
                    path.endsWith("acquire") ? "permitted" : "failed");
        });
        ControlOperationClient.Permit permit = client.acquire(connection, "op-1", "launch",
                "stream-1", 7, 0, 0, "");

        client.complete(connection, permit, false, true);
        client.complete(connection, permit, false);

        assertEquals("definitive_failure", completions.get(0)
                .getJSONObject("effect_result").getString("outcome"));
        assertFalse(completions.get(1).has("effect_result"));
    }

    @Test(expected = IOException.class)
    public void mismatchedBindingFailsClosed() throws Exception {
        ControlOperationClient client = new ControlOperationClient((connection, path, body,
                                                                     requestId, timeout) ->
                response(requestId, "other", 7, 1, 1, "permitted"));
        client.acquire(connection, "op-1", "launch", "stream-1", 7, 0, 0, "");
    }

    @Test public void recoveredProviderStopDoesNotCompleteTheWholeQuit() throws Exception {
        ControlOperationClient client = new ControlOperationClient((connection, path, body,
                                                                     requestId, timeout) ->
                response(requestId, "stream-1", 7, 4, 3,
                        path.endsWith("reconcile") ? "provider_stopped" : "permitted"));
        ControlOperationClient.Permit permit = client.acquire(connection, "close", "full_quit",
                "stream-1", 7, 3, 7, "");
        ControlOperationClient.Result result = client.reconcile(connection, permit, 7);
        assertTrue(result.providerStopped());
        assertFalse(result.succeeded());
    }

    @Test public void parentTerminalAllowsTheBoundedHostChildCleanupToFinish() throws Exception {
        ControlOperationClient client = new ControlOperationClient((connection, path, body,
                                                                     requestId, timeout) -> {
            assertEquals(40_000, timeout);
            return response(requestId, "stream-1", 7, 2, 2, "succeeded");
        });
        assertTrue(client.acquire(connection, "parent-close", "full_quit", "stream-1",
                7, 1, 7, "").succeeded());
    }

    @Test public void completedReplayRequiresANewConnectionBootstrapPermit() throws Exception {
        String first = ControlOperationClient.operationId(
                "launch", "stream-1", 7, 0, "");
        assertEquals(first, ControlOperationClient.operationId(
                "launch", "stream-1", 7, 0, ""));
        assertFalse(first.equals(ControlOperationClient.operationId(
                "launch", "stream-1", 7, 1, "")));
        List<String> intents = new ArrayList<>();
        ControlOperationClient client = new ControlOperationClient((connection, path, body,
                                                                     requestId, timeout) -> {
            intents.add(body.optString("intent") + ":" + requestId);
            return response(requestId, "stream-1", 7,
                    "reconnect".equals(body.optString("intent")) ? 5 : 4,
                    "reconnect".equals(body.optString("intent")) ? 4 : 3,
                    "reconnect".equals(body.optString("intent"))
                            ? "permitted" : "succeeded");
        });

        ControlOperationClient.Permit replay = client.acquire(connection, first, "launch",
                "stream-1", 7, 0, 0, "");
        ControlOperationClient.Permit bootstrap = client.acquireBootstrapAfterReplay(
                connection, "control-bootstrap", replay, 7, "");
        assertFalse(bootstrap.succeeded());
        assertEquals(List.of("launch:" + first,
                "reconnect:control-bootstrap"), intents);
    }

    @Test public void retainedReauthorizationCarriesNewAllowanceAndCurrentGeneration() throws Exception {
        ControlOperationClient client = new ControlOperationClient((connection, path, body,
                                                                     requestId, timeout) -> {
            assertEquals("reauthorize", body.optString("intent"));
            assertEquals(4, body.optLong("expected_generation"));
            assertEquals("child-new", body.optString("child_session_id"));
            return response(requestId, "stream-1", 7, 5, 5, "succeeded");
        });
        assertTrue(client.acquire(connection, "owner-switch", "reauthorize", "stream-1",
                7, 4, 7, "child-new").succeeded());
    }

    @Test public void missingLocalResumeCanReadExactServerOwnedBinding() throws Exception {
        ControlOperationClient client = new ControlOperationClient((connection, path, body,
                                                                     requestId, timeout) -> {
            assertEquals("/api/v1/control/operation/status", path);
            assertEquals(0, body.length());
            JSONObject result = response(requestId, "owned-stream", 7, 9, 8, "owned");
            try { return result.put("ok", true); }
            catch (JSONException impossible) { throw new IOException(impossible); }
        });
        ControlOperationClient.Permit owned = client.ownedSession(connection);
        assertEquals("owned-stream", owned.streamSessionId);
        assertEquals(9, owned.generation);
        assertFalse(owned.succeeded());
    }

    @Test(expected = IOException.class)
    public void ownershipLookupCannotTurnMissingStateIntoBlindClose() throws Exception {
        ControlOperationClient client = new ControlOperationClient((connection, path, body,
                                                                     requestId, timeout) -> new JSONObject());
        client.ownedSession(connection);
    }

    @Test
    public void abandonNamesTheOperationAndKnownGenerationAndReadsReleaseOrTheStarted()
            throws Exception {
        List<String> requests = new ArrayList<>();
        String[] state = {"failed"};
        ControlOperationClient client = new ControlOperationClient((connection, path, body,
                                                                     requestId, timeout) -> {
            requests.add(path + ":" + requestId + ":" + body);
            return response(body.optString("operation_id"), "stream-1", 7, 4, 3, state[0]);
        });

        ControlOperationClient.Result released = client.abandon(
                connection, "op-1", "stream-1", 7, " child-1 ", 4L);
        state[0] = "succeeded";
        // 0: the permit never arrived, so its generation is unknown.
        ControlOperationClient.Result started = client.abandon(
                connection, "op-1", "stream-1", 7, null, 0L);

        assertFalse(released.succeeded());
        assertTrue(started.succeeded());
        assertEquals(4, started.generation);
        assertEquals(List.of(
                "/api/v1/control/operation/abandon:op-1:"
                        + new JSONObject().put("operation_id", "op-1")
                        .put("child_session_id", "child-1").put("generation", 4L),
                "/api/v1/control/operation/abandon:op-1:"
                        + new JSONObject().put("operation_id", "op-1")
                        .put("child_session_id", "").put("generation", 0L)), requests);
    }

    @Test(expected = IOException.class)
    public void abandonResultForAnotherOperationIsRejected() throws Exception {
        new ControlOperationClient((connection, path, body, requestId, timeout) ->
                response("another-op", "stream-1", 7, 4, 3, "failed"))
                .abandon(connection, "op-1", "stream-1", 7, "", 0L);
    }

    private static JSONObject response(String operation, String session, int app,
                                       long generation, long epoch, String state)
            throws IOException {
        try {
            return new JSONObject().put("operation_id", operation)
                    .put("stream_session_id", session).put("sunshine_app_id", app)
                    .put("generation", generation).put("actor_lease_epoch", epoch)
                    .put("state", state).put("reason", "none");
        } catch (JSONException impossible) {
            throw new IOException(impossible);
        }
    }
}
