package com.limelight.console;

import com.limelight.gateway.GatewayConnection;

import org.json.JSONException;
import org.json.JSONObject;
import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class PlayniteTransitionGatewayTest {
    private static final String FINGERPRINT =
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
    private static final String PROCESS_TOKEN =
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";

    @Test public void childSnapshotAndStopUseOnlyPermittedActorRoutes() throws Exception {
        RecordingClient client = new RecordingClient();
        PlayniteTransitionGateway.ActorContext actor =
                PlayniteTransitionGateway.ActorContext.child("kid", "parent");
        GatewayConnection connection = connection("kid");
        PlayniteTransitionGateway gateway =
                new PlayniteTransitionGateway(connection, actor, client);

        PlayniteTransitionGateway.Snapshot snapshot = gateway.snapshot();
        gateway.stopGame("steam:123", PROCESS_TOKEN);

        assertTrue(snapshot.gatewayReady);
        assertTrue(snapshot.connectorReady);
        assertEquals(2, client.requests.size());
        assertTrue(client.requests.get(0).startsWith(
                "GET /api/v1/session/observation?request_ordinal="));
        assertEquals("POST /api/v1/game/stop-verified", client.requests.get(1));
        assertFalse(client.requests.contains("GET /api/v1/game-provider/health"));
        assertEquals("kid", gateway.gatewayConnection().profileId());
        assertEquals("kid", gateway.actorContext().actorProfileId);
        assertEquals("parent", gateway.actorContext().executionProfileId);
        assertEquals("secret", gateway.gatewayConnection().token());
        assertEquals(FINGERPRINT, gateway.gatewayConnection().certificateSha256());
        assertEquals("https://host:8785", gateway.gatewayConnection().endpoint());
        assertEquals(Arrays.asList("kid", "kid"), client.profiles);
    }

    @Test public void parentSnapshotRetainsHealthAndGameplayRoutes() throws Exception {
        RecordingClient client = new RecordingClient();
        PlayniteTransitionGateway gateway = new PlayniteTransitionGateway(
                connection("parent"),
                PlayniteTransitionGateway.ActorContext.parent("parent"), client);

        PlayniteTransitionGateway.Snapshot snapshot = gateway.snapshot();
        gateway.stopGame("steam:123", PROCESS_TOKEN);

        assertTrue(snapshot.connectorReady);
        assertEquals(2, client.requests.size());
        assertTrue(client.requests.get(0).startsWith(
                "GET /api/v1/session/observation?request_ordinal="));
        assertEquals("POST /api/v1/game/stop-verified", client.requests.get(1));
        assertEquals(Arrays.asList("parent", "parent"),
                client.profiles);
    }

    @Test public void rejectedObservationDoesNotLeakRawStateToConsumers() throws Exception {
        SessionStateResolver.resetProjectionOwnersForTests();
        RecordingClient client = new RecordingClient();
        PlayniteTransitionGateway gateway = new PlayniteTransitionGateway(
                connection("parent"),
                PlayniteTransitionGateway.ActorContext.parent("parent"), client);

        client.revision = 2L;
        PlayniteTransitionGateway.Snapshot accepted = gateway.snapshot();
        client.revision = 1L;
        client.gameId = "steam:456";
        PlayniteTransitionGateway.Snapshot rejected = gateway.snapshot();

        assertTrue(accepted.gatewayReady);
        assertFalse(rejected.gatewayReady);
        assertEquals("unknown", rejected.gameState);
        assertEquals("UNAVAILABLE", rejected.gameEvidence);
        assertEquals(2, client.parsedObservations);
        SessionStateResolver.resetProjectionOwnersForTests();
    }

    private static com.limelight.gateway.ControlOperationClient.Permit permit(
            String operationId, String session, long generation, long epoch) throws Exception {
        java.lang.reflect.Constructor<com.limelight.gateway.ControlOperationClient.Permit> create =
                com.limelight.gateway.ControlOperationClient.Permit.class.getDeclaredConstructor(
                        String.class, String.class, int.class, long.class, long.class,
                        String.class, String.class, String.class, String.class, int.class);
        create.setAccessible(true);
        return create.newInstance(operationId, session, 7, generation, epoch, "permitted", "none",
                "steam:123", PROCESS_TOKEN, 42);
    }

    @Test public void controlSnapshotExposesEvidenceOnlyForTheCallersExactPermit()
            throws Exception {
        SessionStateResolver.resetProjectionOwnersForTests();
        RecordingClient client = new RecordingClient();
        client.operationId = "control-full-quit-1";
        client.streamSessionId = "session-1";
        client.controlGeneration = 2L;
        client.actorLeaseEpoch = 3L;
        PlayniteTransitionGateway gateway = new PlayniteTransitionGateway(
                connection("parent"),
                PlayniteTransitionGateway.ActorContext.parent("parent"), client);

        PlayniteTransitionGateway.Snapshot fenced = gateway.snapshotWithinControl(
                permit("control-full-quit-1", "session-1", 2L, 3L));
        assertTrue(fenced.hasVerifiedProcessFor("steam:123"));
        assertEquals(PROCESS_TOKEN, fenced.processToken);
        assertEquals("running", fenced.gameState);

        for (com.limelight.gateway.ControlOperationClient.Permit other : Arrays.asList(
                permit("control-full-quit-other", "session-1", 2L, 3L),
                permit("control-full-quit-1", "session-1", 1L, 3L),
                permit("control-full-quit-1", "session-2", 2L, 3L),
                permit("control-full-quit-1", "session-1", 2L, 2L))) {
            PlayniteTransitionGateway.Snapshot closed = gateway.snapshotWithinControl(other);
            assertFalse(closed.hasVerifiedProcessFor("steam:123"));
            assertEquals("", closed.processToken);
            assertEquals("unknown", closed.gameState);
        }
        SessionStateResolver.resetProjectionOwnersForTests();
    }

    @Test public void controlSnapshotKeepsPermitEvidenceWhenADashboardPollWinsTheDispatch()
            throws Exception {
        // Lab F-S06-6: "Close game and stream" returns to the dashboard, whose poll takes a
        // newer ordinal while the full-quit read is in flight; the owner then rejects that read.
        SessionStateResolver.resetProjectionOwnersForTests();
        RecordingClient client = new RecordingClient();
        client.operationId = "control-full-quit-1";
        client.streamSessionId = "session-1";
        client.controlGeneration = 2L;
        client.actorLeaseEpoch = 3L;
        PlayniteTransitionGateway termination = new PlayniteTransitionGateway(
                connection("parent"), PlayniteTransitionGateway.ActorContext.parent("parent"), client);
        PlayniteTransitionGateway dashboard = new PlayniteTransitionGateway(
                connection("parent"), PlayniteTransitionGateway.ActorContext.parent("parent"), client);
        client.beforeReturn = () -> {
            try {
                dashboard.snapshot();
            } catch (IOException error) {
                throw new AssertionError(error);
            }
        };

        PlayniteTransitionGateway.Snapshot fenced = termination.snapshotWithinControl(
                permit("control-full-quit-1", "session-1", 2L, 3L));

        assertEquals(2, client.parsedObservations);
        assertEquals(2L, fenced.sessionSnapshot.requestOrdinal);
        assertTrue(fenced.hasVerifiedProcessFor("steam:123"));
        assertEquals(PROCESS_TOKEN, fenced.processToken);
        client.beforeReturn = () -> {
            try {
                dashboard.snapshot();
            } catch (IOException error) {
                throw new AssertionError(error);
            }
        };
        PlayniteTransitionGateway.Snapshot other = termination.snapshotWithinControl(
                permit("control-full-quit-other", "session-1", 2L, 3L));
        assertFalse(other.hasVerifiedProcessFor("steam:123"));
        SessionStateResolver.resetProjectionOwnersForTests();
    }

    @Test public void rejectedIdleControlReadCannotLetFullQuitSkipTheStop() throws Exception {
        // R011 P1: the full-quit read (idle, ordinal N) loses to a newer dashboard read that
        // still shows the permit's game running (N+1). The rejected idle must not read as idle.
        SessionStateResolver.resetProjectionOwnersForTests();
        RecordingClient client = new RecordingClient();
        client.operationId = "control-full-quit-1";
        client.streamSessionId = "session-1";
        client.controlGeneration = 2L;
        client.actorLeaseEpoch = 3L;
        client.idle = true;
        PlayniteTransitionGateway termination = new PlayniteTransitionGateway(
                connection("parent"), PlayniteTransitionGateway.ActorContext.parent("parent"), client);
        PlayniteTransitionGateway dashboard = new PlayniteTransitionGateway(
                connection("parent"), PlayniteTransitionGateway.ActorContext.parent("parent"), client);
        client.beforeReturn = () -> {
            client.idle = false;
            try {
                dashboard.snapshot();
            } catch (IOException error) {
                throw new AssertionError(error);
            }
        };

        PlayniteTransitionGateway.Snapshot fenced = termination.snapshotWithinControl(
                permit("control-full-quit-1", "session-1", 2L, 3L));

        assertEquals(2, client.parsedObservations);
        assertFalse("idle".equalsIgnoreCase(fenced.gameState));
        assertFalse(fenced.hasVerifiedProcessFor("steam:123"));
        assertEquals("", fenced.processToken);
        SessionStateResolver.resetProjectionOwnersForTests();
    }

    @Test public void capturedContextSurvivesDeterministicActorRebindOrdering()
            throws Exception {
        RecordingClient trace = new RecordingClient();
        PlayniteTransitionGateway child = new PlayniteTransitionGateway(
                connection("kid"),
                PlayniteTransitionGateway.ActorContext.child("kid", "parent"), trace);
        PlayniteTransitionGateway parent = new PlayniteTransitionGateway(
                connection("parent"),
                PlayniteTransitionGateway.ActorContext.parent("parent"), trace);

        child.snapshot();
        parent.snapshot();
        child.stopGame("steam:123", PROCESS_TOKEN);

        assertEquals(Arrays.asList("kid", "parent", "kid"), trace.profiles);
        assertEquals("kid", child.actorContext().actorProfileId);
        assertEquals("parent", child.actorContext().executionProfileId);
        assertTrue(child.actorContext().child);
        assertFalse(parent.actorContext().child);
    }

    @Test public void connectionCannotBeBoundToAnotherActor() {
        try {
            new PlayniteTransitionGateway(connection("parent"),
                    PlayniteTransitionGateway.ActorContext.child("kid", "parent"),
                    new RecordingClient());
            fail("Expected actor mismatch rejection");
        } catch (IllegalArgumentException expected) {
            assertEquals("Gateway actor profile mismatch", expected.getMessage());
        }
    }

    @Test public void everyProductionCallerUsesExplicitActorContext() throws IOException {
        Path root = sourceRoot();
        String gateway = read(root.resolve("console/PlayniteTransitionGateway.java"));
        String connect = gateway.substring(gateway.indexOf(
                "public static PlayniteTransitionGateway connect("),
                gateway.indexOf("public Snapshot snapshot()"));
        assertEquals(1, occurrences(connect,
                "public static PlayniteTransitionGateway connect("));
        assertTrue(connect.contains("ActorContext actorContext"));
        assertFalse(connect.contains("boolean childSession"));
        assertFalse(connect.contains("DEFAULT_PROFILE_ID"));

        String game = read(root.resolve("Game.java"));
        assertEquals(8, occurrences(game, "PlayniteTransitionGateway.connect("));
        assertTrue(game.contains("currentGatewayActorContext()"));
        assertTrue(game.contains("operation.actorContext"));
        assertTrue(game.contains("ActorContext.child(\n"
                + "                            childActorProfileId, childExecutionProfileId)"));
        String console = read(root.resolve("console/ConsoleActivity.java"));
        assertEquals(1, occurrences(console, "PlayniteTransitionGateway.connect("));
        assertTrue(console.contains("gatewayActorContext(host.uuid, transitionProfileId)"));
    }

    @Test public void missingConnectionIsDiagnosedWithoutAddressOrCredentials()
            throws IOException {
        Path source = Paths.get(
                "src/main/java/com/limelight/console/PlayniteTransitionGateway.java");
        if (!Files.exists(source)) source = Paths.get(
                "app/src/main/java/com/limelight/console/PlayniteTransitionGateway.java");
        String text = new String(Files.readAllBytes(source), StandardCharsets.UTF_8).replace("\r\n", "\n");
        String connect = text.substring(text.indexOf(
                        "public static PlayniteTransitionGateway connect("),
                text.indexOf("public Snapshot snapshot()"));

        assertTrue(connect.contains("gateway.connect_failed"));
        assertTrue(connect.contains("connection_missing"));
        assertTrue(connect.contains("host_id"));
        assertFalse(connect.contains("\"active_host\""));
        assertFalse(connect.contains("authorization"));
        assertFalse(connect.contains("token"));
    }

    @Test public void suspendAcceptanceRequiresEchoedIdAndExactTarget() {
        PlayniteTransitionGateway.SuspendAcceptance acceptance =
                new PlayniteTransitionGateway.SuspendAcceptance(
                        true, "suspend-a", 42, "game");

        assertTrue(acceptance.matches("suspend-a", 42, "GAME"));
        assertFalse(acceptance.matches("suspend-b", 42, "game"));
        assertFalse(acceptance.matches("suspend-a", 7, "game"));
        assertFalse(acceptance.matches("suspend-a", 42, "other"));
    }

    @Test public void rejectedSuspendNeverMatches() {
        PlayniteTransitionGateway.SuspendAcceptance acceptance =
                new PlayniteTransitionGateway.SuspendAcceptance(
                        false, "suspend-a", 42, "game");

        assertFalse(acceptance.matches("suspend-a", 42, "game"));
    }

    @Test public void acceptedLegacySuspendWithoutCorrelationMatches() {
        PlayniteTransitionGateway.SuspendAcceptance acceptance =
                new PlayniteTransitionGateway.SuspendAcceptance(true, "", 0, "");

        assertTrue(acceptance.matches("suspend-a", 42, "game"));
    }

    @Test public void partialCorrelationIsNotTreatedAsLegacy() {
        PlayniteTransitionGateway.SuspendAcceptance acceptance =
                new PlayniteTransitionGateway.SuspendAcceptance(true, "suspend-a", 0, "");

        assertFalse(acceptance.matches("suspend-a", 42, "game"));
    }

    private static GatewayConnection connection(String profileId) {
        return new GatewayConnection(
                "https://host:8785", "secret", FINGERPRINT, profileId);
    }

    private static Path sourceRoot() {
        Path root = Paths.get("src/main/java/com/limelight");
        return Files.exists(root) ? root : Paths.get("app/src/main/java/com/limelight");
    }

    private static String read(Path path) throws IOException {
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8).replace("\r\n", "\n");
    }

    private static int occurrences(String text, String needle) {
        int count = 0;
        for (int index = 0; (index = text.indexOf(needle, index)) >= 0;
             index += needle.length()) count++;
        return count;
    }

    private static final class RecordingClient extends HostGatewayClient {
        final List<String> requests = new ArrayList<>();
        final List<String> profiles = new ArrayList<>();
        long revision;
        String operationId = "";
        String streamSessionId = "";
        long controlGeneration;
        long actorLeaseEpoch;
        Runnable beforeReturn;
        boolean idle;
        String gameState = "running";
        String gameEvidence = "VERIFIED";
        String gameId = "steam:123";
        int parsedObservations;

        @Override SessionObservation getSessionObservation(
                GatewayConnection connection, String executionProfileId,
                long requestOrdinal, String transitionId) throws IOException {
            SessionObservation observation = super.getSessionObservation(connection,
                    executionProfileId, requestOrdinal, transitionId);
            parsedObservations++;
            Runnable interleaved = beforeReturn;
            beforeReturn = null;
            if (interleaved != null) interleaved.run();
            return observation;
        }

        @Override JSONObject request(GatewayConnection connection, String path,
                                     String method, JSONObject body, int readTimeoutMs)
                throws IOException {
            requests.add(method + " " + path);
            profiles.add(connection.profileId());
            try {
                if (path.startsWith("/api/v1/session/observation?")) {
                    long ordinal = Long.parseLong(path.replaceFirst(
                            ".*request_ordinal=([0-9]+).*", "$1"));
                    long now = System.currentTimeMillis();
                    String transition = java.net.URLDecoder.decode(path.replaceFirst(
                            ".*transition_id=([^&]*).*", "$1"), "UTF-8");
                    return new JSONObject().put("ok", true).put("schema_version", 1)
                            .put("producer", new JSONObject().put("boot_id", "boot-a")
                                    .put("revision", revision > 0L ? revision : ordinal))
                            .put("observed_at_unix_ms", now).put("fresh_for_ms", 5_000)
                            .put("state_episode", "boot-a:1")
                            .put("state_since_unix_ms", now)
                            .put("scope", new JSONObject()
                                    .put("actor_profile_id", connection.profileId())
                                    .put("execution_profile_id",
                                            "kid".equals(connection.profileId())
                                                    ? "parent" : connection.profileId()))
                            .put("correlation", new JSONObject()
                                    .put("request_ordinal", ordinal)
                                    .put("caller_transition_id", transition)
                                    .put("operation_id", operationId)
                                    .put("stream_session_id", streamSessionId)
                                    .put("control_generation", controlGeneration)
                                    .put("actor_lease_epoch", actorLeaseEpoch))
                            .put("provider", new JSONObject().put("state", gameState)
                                    .put("connector_evidence", "VERIFIED")
                                    .put("reason", "none"))
                            .put("game", idle
                                    ? new JSONObject().put("state", "idle")
                                            .put("evidence", "ABSENT").put("id", "")
                                    : new JSONObject().put("state", gameState)
                                            .put("evidence", gameEvidence).put("id", gameId))
                            .put("process", idle
                                    ? new JSONObject().put("evidence", "ABSENT")
                                            .put("process_id", 0).put("process_token", "")
                                    : new JSONObject().put("evidence", "VERIFIED")
                                            .put("process_id", 42).put("process_token", PROCESS_TOKEN))
                            .put("window", idle
                                    ? new JSONObject().put("evidence", "ABSENT")
                                            .put("ready", false).put("target_kind", "game")
                                            .put("stable_samples", 0).put("reason", "none")
                                    : new JSONObject().put("evidence", "VERIFIED")
                                            .put("ready", true).put("target_kind", "game")
                                            .put("stable_samples", 3)
                                            .put("reason", "target_window_ready"))
                            .put("capabilities", new JSONObject()
                                    .put("host_guide_allowed", false)
                                    .put("requires_connector", true)
                                    .put("start_before_stream", false));
                }
                if ("/api/v1/game/stop-verified".equals(path)) {
                    assertEquals("steam:123", body.optString("game_id"));
                    assertEquals(PROCESS_TOKEN,
                            body.optString("expected_process_token"));
                    return new JSONObject().put("ok", true)
                            .put("accepted", true)
                            .put("stopped_current", true)
                            .put("stopped_game_id", "steam:123");
                }
                throw new IOException("Unexpected route " + path);
            } catch (JSONException impossible) {
                throw new IOException(impossible);
            }
        }
    }
}
