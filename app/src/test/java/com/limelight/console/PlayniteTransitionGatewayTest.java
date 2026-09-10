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

    @Test public void childSnapshotAndStopUseOnlyPermittedActorRoutes() throws Exception {
        RecordingClient client = new RecordingClient();
        PlayniteTransitionGateway.ActorContext actor =
                PlayniteTransitionGateway.ActorContext.child("kid", "parent");
        GatewayConnection connection = connection("kid");
        PlayniteTransitionGateway gateway =
                new PlayniteTransitionGateway(connection, actor, client);

        PlayniteTransitionGateway.Snapshot snapshot = gateway.snapshot();
        gateway.stopGame("steam:123");

        assertTrue(snapshot.gatewayReady);
        assertTrue(snapshot.connectorReady);
        assertEquals(Arrays.asList("GET /api/v1/game/current",
                "GET /api/v1/window/readiness", "POST /api/v1/game/stop"),
                client.requests);
        assertFalse(client.requests.contains("GET /api/v1/game-provider/health"));
        assertEquals("kid", gateway.gatewayConnection().profileId());
        assertEquals("kid", gateway.actorContext().actorProfileId);
        assertEquals("parent", gateway.actorContext().executionProfileId);
        assertEquals("secret", gateway.gatewayConnection().token());
        assertEquals(FINGERPRINT, gateway.gatewayConnection().certificateSha256());
        assertEquals("https://host:8785", gateway.gatewayConnection().endpoint());
        assertEquals(Arrays.asList("kid", "kid", "kid"), client.profiles);
    }

    @Test public void parentSnapshotRetainsHealthAndGameplayRoutes() throws Exception {
        RecordingClient client = new RecordingClient();
        PlayniteTransitionGateway gateway = new PlayniteTransitionGateway(
                connection("parent"),
                PlayniteTransitionGateway.ActorContext.parent("parent"), client);

        PlayniteTransitionGateway.Snapshot snapshot = gateway.snapshot();
        gateway.stopGame("steam:123");

        assertTrue(snapshot.connectorReady);
        assertEquals(Arrays.asList("GET /api/v1/game-provider/health",
                "GET /api/v1/game/current", "GET /api/v1/window/readiness",
                "POST /api/v1/game/stop"), client.requests);
        assertEquals(Arrays.asList("parent", "parent", "parent", "parent"),
                client.profiles);
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
        child.stopGame("steam:123");

        assertEquals(Arrays.asList("kid", "kid", "parent", "parent", "parent", "kid"),
                trace.profiles);
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
        assertEquals(9, occurrences(game, "PlayniteTransitionGateway.connect("));
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
        String text = new String(Files.readAllBytes(source), StandardCharsets.UTF_8);
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
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
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

        @Override JSONObject request(GatewayConnection connection, String path,
                                     String method, JSONObject body, int readTimeoutMs)
                throws IOException {
            requests.add(method + " " + path);
            profiles.add(connection.profileId());
            try {
                if ("/api/v1/game-provider/health".equals(path)) {
                    return new JSONObject().put("bridge", new JSONObject()
                            .put("connector_connected", true));
                }
                if ("/api/v1/game/current".equals(path)) {
                    return new JSONObject().put("current", new JSONObject()
                            .put("state", "running")
                            .put("id", "steam:123")
                            .put("process_id", 42)
                            .put("requires_connector", true));
                }
                if ("/api/v1/window/readiness".equals(path)) {
                    return new JSONObject().put("readiness", new JSONObject()
                            .put("ready", true)
                            .put("target_kind", "game")
                            .put("stable_samples", 2));
                }
                if ("/api/v1/game/stop".equals(path)) {
                    assertEquals("steam:123", body.optString("game_id"));
                    return new JSONObject().put("ok", true);
                }
                throw new IOException("Unexpected route " + path);
            } catch (JSONException impossible) {
                throw new IOException(impossible);
            }
        }
    }
}
