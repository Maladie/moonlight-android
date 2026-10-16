package com.limelight.console;

import com.limelight.SourceText;
import com.limelight.session.MemoryPreferences;
import com.limelight.session.SessionModel;

import com.limelight.gateway.GatewayConnection;

import org.json.JSONException;
import org.json.JSONObject;
import org.junit.Test;

import java.io.IOException;
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
    /** A model on its own thread: the gateway awaits it like a worker in production. */
    private final java.util.concurrent.ExecutorService modelThread =
            java.util.concurrent.Executors.newSingleThreadExecutor();
    private final java.util.concurrent.atomic.AtomicInteger published =
            new java.util.concurrent.atomic.AtomicInteger();
    private final SessionModel model = new SessionModel(modelThread::execute, Runnable::run,
            () -> false, () -> 0L, (level, component, event, fields) -> {
                if ("projection.published".equals(event)) published.incrementAndGet();
                return true;
            }, new MemoryPreferences(), new MemoryPreferences(), new MemoryPreferences(), 0L);

    @org.junit.After public void stopModelThread() {
        modelThread.shutdownNow();
    }
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
                new PlayniteTransitionGateway(model, connection, actor, client);

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
        PlayniteTransitionGateway gateway = new PlayniteTransitionGateway(model, connection("parent"),
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
        RecordingClient client = new RecordingClient();
        PlayniteTransitionGateway gateway = new PlayniteTransitionGateway(model, connection("parent"),
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
    }

    @Test public void snapshotReadsAgainWhenADashboardPollWinsTheDispatch() throws Exception {
        // Lab formal133: the retained switch's read lost the owner's ordering to the dashboard
        // poll and the switch decided on the owner's evidence-free projection.
        RecordingClient client = new RecordingClient();
        PlayniteTransitionGateway switching = new PlayniteTransitionGateway(model, connection("parent"), PlayniteTransitionGateway.ActorContext.parent("parent"), client);
        PlayniteTransitionGateway dashboard = new PlayniteTransitionGateway(model, connection("parent"), PlayniteTransitionGateway.ActorContext.parent("parent"), client);
        client.beforeReturn = () -> {
            try {
                dashboard.snapshot();
            } catch (IOException error) {
                throw new AssertionError(error);
            }
        };

        PlayniteTransitionGateway.Snapshot snapshot = switching.snapshot();

        assertEquals(3, client.parsedObservations);
        assertEquals(3L, snapshot.sessionSnapshot.requestOrdinal);
        assertTrue(snapshot.hasVerifiedProcessFor("steam:123"));
        assertEquals(PROCESS_TOKEN, snapshot.processToken);
    }

    @Test public void cancelledReadPropagatesWithoutPublishingUnavailability() throws Exception {
        RecordingClient client = new RecordingClient();
        PlayniteTransitionGateway gateway = new PlayniteTransitionGateway(model, "host",
                connection("parent"), PlayniteTransitionGateway.ActorContext.parent("parent"),
                client, () -> 0L);
        gateway.snapshot();
        int before = published.get();
        client.failure = new java.io.InterruptedIOException("interrupted");
        try {
            for (int read = 0; read < 2; read++) {
                try {
                    if (read == 0) gateway.snapshot();
                    else gateway.snapshotWithinControl(permit("op", "session-1", 2L, 3L));
                    fail("cancelled read must propagate");
                } catch (java.io.InterruptedIOException expected) {
                    assertTrue(Thread.interrupted());
                }
                assertEquals(before, published.get());
            }
        } finally {
            Thread.interrupted();
        }
    }

    @Test public void failedOrInterruptedModelWaitIsAnUnavailableRead() throws Exception {
        SessionModel failing = new SessionModel(modelThread::execute, Runnable::run,
                () -> false, () -> 0L, (level, component, event, fields) -> {
                    throw new IllegalStateException("transition failed");
                }, new MemoryPreferences(), new MemoryPreferences(), new MemoryPreferences(), 0L);
        // The read fails without a cancellation: the model is told it is unavailable.
        HostGatewayClient down = new HostGatewayClient() {
            @Override SessionObservation getSessionObservation(GatewayConnection connection,
                    String executionProfileId, long requestOrdinal)
                    throws IOException {
                throw new IOException("down");
            }
        };
        PlayniteTransitionGateway gateway = new PlayniteTransitionGateway(failing, "host",
                connection("parent"), PlayniteTransitionGateway.ActorContext.parent("parent"),
                down, () -> 0L);
        try {
            gateway.snapshot();
            fail("a failed model transition must not be read as a projection");
        } catch (IOException expected) {
            assertEquals("session_model_failed", expected.getMessage());
        }

        Thread.currentThread().interrupt();
        try {
            new PlayniteTransitionGateway(model, "host", connection("parent"),
                    PlayniteTransitionGateway.ActorContext.parent("parent"),
                    new RecordingClient(), () -> 0L).snapshot();
            fail("an interrupted wait must propagate");
        } catch (java.io.InterruptedIOException expected) {
            assertTrue(Thread.interrupted());
        } finally {
            Thread.interrupted();
        }
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

    /**
     * Relaunching Cuphead met control.operation_pending: the provider refused the close's stop
     * (a received answer), the close reported it as uncertain, and the host held that until a
     * hard close. Only an answer that may be lost is uncertain.
     */
    @Test public void answeredStopRefusalIsNotAnUncertainEffect() throws Exception {
        RecordingClient client = new RecordingClient();
        PlayniteTransitionGateway gateway = new PlayniteTransitionGateway(model, connection("parent"),
                PlayniteTransitionGateway.ActorContext.parent("parent"), client);
        com.limelight.gateway.ControlOperationClient.Permit permit =
                permit("control-full-quit-1", "session-1", 2L, 3L);

        assertTrue(gateway.stopGameWithinControl("steam:123", permit));
        // The Gateway's 200 for a provider refusal, and its 409 fence before any effect.
        client.stopAnswer = () -> new JSONObject().put("ok", false)
                .put("error", "running_game_identity_changed")
                .put("result", new JSONObject().put("accepted", false));
        assertFalse(gateway.stopGameWithinControl("steam:123", permit));
        client.stopAnswer = () -> {
            throw new HostGatewayClient.GatewayException("control_operation_required", 409);
        };
        assertFalse(gateway.stopGameWithinControl("steam:123", permit));
        // The provider's answer never reached the Gateway, or none reached the TV: uncertain.
        for (IOException lost : new IOException[] {
                new HostGatewayClient.GatewayException("provider_response_unavailable", 502),
                new java.net.SocketTimeoutException("timeout")}) {
            client.stopAnswer = () -> {
                throw lost;
            };
            try {
                gateway.stopGameWithinControl("steam:123", permit);
                fail("A possibly lost stop answer must stay uncertain");
            } catch (IOException expected) {
                assertEquals(lost, expected);
            }
        }
        String host = SourceText.main("stream/HostSessionTermination.java");
        assertTrue(host.contains("if (!provider.stopGameWithinControl(exact, permit)) {\n"
                + "                            gameLeftUnverified = true;"));
    }

    @Test public void controlSnapshotExposesEvidenceOnlyForTheCallersExactPermit()
            throws Exception {
        RecordingClient client = new RecordingClient();
        client.operationId = "control-full-quit-1";
        client.streamSessionId = "session-1";
        client.controlGeneration = 2L;
        client.actorLeaseEpoch = 3L;
        PlayniteTransitionGateway gateway = new PlayniteTransitionGateway(model, connection("parent"),
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
    }

    @Test public void controlSnapshotKeepsPermitEvidenceWhenADashboardPollWinsTheDispatch()
            throws Exception {
        // Lab F-S06-6: "Close game and stream" returns to the dashboard, whose poll takes a
        // newer ordinal while the full-quit read is in flight; the owner then rejects that read.
        RecordingClient client = new RecordingClient();
        client.operationId = "control-full-quit-1";
        client.streamSessionId = "session-1";
        client.controlGeneration = 2L;
        client.actorLeaseEpoch = 3L;
        PlayniteTransitionGateway termination = new PlayniteTransitionGateway(model, connection("parent"), PlayniteTransitionGateway.ActorContext.parent("parent"), client);
        PlayniteTransitionGateway dashboard = new PlayniteTransitionGateway(model, connection("parent"), PlayniteTransitionGateway.ActorContext.parent("parent"), client);
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
    }

    @Test public void stopEvidenceWaitsOutAStaleRunningScanUnderTheSamePermit() throws Exception {
        // Lab diag146b: the dashboard's library refresh made the provider's running scan stale
        // while the full-quit read was in flight; the game was verified, its process pending,
        // so the close completed as failed (effect_rejected) and the game kept running.
        RecordingClient client = new RecordingClient();
        client.operationId = "control-full-quit-1";
        client.streamSessionId = "session-1";
        client.controlGeneration = 2L;
        client.actorLeaseEpoch = 3L;
        client.processPending = true;
        PlayniteTransitionGateway termination = new PlayniteTransitionGateway(model, connection("parent"), PlayniteTransitionGateway.ActorContext.parent("parent"), client);
        client.beforeReturn = () -> client.processPending = false;

        PlayniteTransitionGateway.Snapshot settled = termination.stopEvidenceWithinControl(
                permit("control-full-quit-1", "session-1", 2L, 3L), "steam:123", 5_000L);

        assertEquals(2, client.parsedObservations);
        assertTrue(settled.hasVerifiedProcessFor("steam:123"));
        assertEquals(PROCESS_TOKEN, settled.processToken);

        client.parsedObservations = 0;
        client.idle = true;
        PlayniteTransitionGateway.Snapshot idle = termination.stopEvidenceWithinControl(
                permit("control-full-quit-1", "session-1", 2L, 3L), "steam:123", 5_000L);
        assertEquals(1, client.parsedObservations);
        assertEquals("idle", idle.gameState);

        client.idle = false;
        client.processPending = true;
        PlayniteTransitionGateway.Snapshot pending = termination.stopEvidenceWithinControl(
                permit("control-full-quit-1", "session-1", 2L, 3L), "steam:123", 300L);
        assertFalse(pending.hasVerifiedProcessFor("steam:123"));
    }

    @Test public void rejectedIdleControlReadCannotLetFullQuitSkipTheStop() throws Exception {
        // R011 P1: the full-quit read (idle, ordinal N) loses to a newer dashboard read that
        // still shows the permit's game running (N+1). The rejected idle must not read as idle.
        RecordingClient client = new RecordingClient();
        client.operationId = "control-full-quit-1";
        client.streamSessionId = "session-1";
        client.controlGeneration = 2L;
        client.actorLeaseEpoch = 3L;
        client.idle = true;
        PlayniteTransitionGateway termination = new PlayniteTransitionGateway(model, connection("parent"), PlayniteTransitionGateway.ActorContext.parent("parent"), client);
        PlayniteTransitionGateway dashboard = new PlayniteTransitionGateway(model, connection("parent"), PlayniteTransitionGateway.ActorContext.parent("parent"), client);
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
    }

    @Test public void capturedContextSurvivesDeterministicActorRebindOrdering()
            throws Exception {
        RecordingClient trace = new RecordingClient();
        PlayniteTransitionGateway child = new PlayniteTransitionGateway(model, connection("kid"),
                PlayniteTransitionGateway.ActorContext.child("kid", "parent"), trace);
        PlayniteTransitionGateway parent = new PlayniteTransitionGateway(model, connection("parent"),
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
            new PlayniteTransitionGateway(model, connection("parent"),
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
        assertTrue(game.contains("ActorContext.child(operation.request.targetProfileId,\n"
                + "                        operation.request.executionProfileId)"));
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
        String text = SourceText.read(source);
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

    private static final String ATTEMPT = "0123456789abcdef0123456789abcdef";

    @Test public void hostUnlockPollsItsAttemptUntilReady() throws Exception {
        UnlockClient client = new UnlockClient();
        client.statuses.add("ready");

        assertEquals(PlayniteTransitionGateway.HostUnlock.UNLOCKED,
                unlockGateway(client, () -> 0L).unlockHostSession(() -> false));
        assertEquals(1, client.polls);
        assertEquals(0, client.cancels.size());
    }

    @Test public void readyWithoutAnAttemptIsNotAnUnlock() throws Exception {
        UnlockClient client = new UnlockClient();
        client.readyWithoutAttempt = true;

        assertEquals(PlayniteTransitionGateway.HostUnlock.READY_WITHOUT_ATTEMPT,
                unlockGateway(client, () -> 0L).unlockHostSession(() -> false));
        assertEquals(0, client.polls);
        assertEquals(0, client.cancels.size());
    }

    @Test public void foreignUnlockAnswerCancelsTheAttempt() throws Exception {
        UnlockClient client = new UnlockClient();
        client.statuses.add("ready");
        client.foreignStatus = true;

        assertEquals(PlayniteTransitionGateway.HostUnlock.NOT_READY,
                unlockGateway(client, () -> 0L).unlockHostSession(() -> false));
        assertEquals(Arrays.asList(client.requestId + "/" + ATTEMPT), client.cancels);
    }

    @Test public void unlockBoundCancelsTheAttempt() throws Exception {
        UnlockClient client = new UnlockClient();
        long[] now = {0L};
        client.onStatus = () -> now[0] += PlayniteTransitionGateway.HOST_UNLOCK_TIMEOUT_MILLIS;
        client.statuses.add("pending");

        assertEquals(PlayniteTransitionGateway.HostUnlock.NOT_READY,
                unlockGateway(client, () -> now[0]).unlockHostSession(() -> false));
        assertEquals(1, client.polls);
        assertEquals(Arrays.asList(client.requestId + "/" + ATTEMPT), client.cancels);
    }

    @Test public void cancelledUnlockStopsPollingAndCancelsTheAttempt() throws Exception {
        UnlockClient client = new UnlockClient();
        client.statuses.add("ready");

        assertEquals(PlayniteTransitionGateway.HostUnlock.NOT_READY,
                unlockGateway(client, () -> 0L).unlockHostSession(() -> true));
        assertEquals(0, client.polls);
        assertEquals(Arrays.asList(client.requestId + "/" + ATTEMPT), client.cancels);
    }

    private PlayniteTransitionGateway unlockGateway(UnlockClient client,
                                                    java.util.function.LongSupplier clock) {
        return new PlayniteTransitionGateway(model, "host", connection("kid"),
                PlayniteTransitionGateway.ActorContext.child("kid", "parent"), client, clock);
    }

    /** Ensure answers with a pending attempt; each status poll returns the next state. */
    private static final class UnlockClient extends HostGatewayClient {
        final List<String> statuses = new ArrayList<>();
        final List<String> cancels = new ArrayList<>();
        String requestId = "";
        boolean foreignStatus;
        boolean readyWithoutAttempt;
        Runnable onStatus;
        int polls;

        @Override WindowsSession ensureWindowsSession(GatewayConnection connection,
                                                      String requestId) {
            this.requestId = requestId;
            if (readyWithoutAttempt) {
                return new WindowsSession("ready", "none", "", 250, requestId, "kid", "parent");
            }
            return new WindowsSession("pending", "none", ATTEMPT, 250, requestId,
                    "kid", "parent");
        }

        @Override WindowsSession getWindowsSessionStatus(GatewayConnection connection,
                                                         String requestId, String attemptId) {
            polls++;
            if (onStatus != null) onStatus.run();
            return new WindowsSession(statuses.remove(0), "none", attemptId, 250,
                    foreignStatus ? "android-other" : requestId, "kid", "parent");
        }

        @Override void cancelWindowsSession(GatewayConnection connection, String requestId,
                                            String attemptId) {
            cancels.add(requestId + "/" + attemptId);
        }
    }

    private static GatewayConnection connection(String profileId) {
        return new GatewayConnection(
                "https://host:8785", "secret", FINGERPRINT, profileId);
    }

    private static Path sourceRoot() {
        return SourceText.MAIN_JAVA;
    }

    private static String read(Path path) throws IOException {
        return SourceText.read(path);
    }

    private static int occurrences(String text, String needle) {
        int count = 0;
        for (int index = 0; (index = text.indexOf(needle, index)) >= 0;
             index += needle.length()) count++;
        return count;
    }

    private interface StopAnswer {
        JSONObject answer() throws IOException, JSONException;
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
        boolean processPending;
        String gameState = "running";
        String gameEvidence = "VERIFIED";
        String gameId = "steam:123";
        int parsedObservations;
        IOException failure;
        /** The stop-verified answer, or its failure; null answers a verified stop. */
        StopAnswer stopAnswer;

        @Override SessionObservation getSessionObservation(
                GatewayConnection connection, String executionProfileId,
                long requestOrdinal) throws IOException {
            SessionObservation observation = super.getSessionObservation(connection,
                    executionProfileId, requestOrdinal);
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
            if (failure != null) {
                // The transport restores the interrupt that okhttp consumed.
                Thread.currentThread().interrupt();
                throw failure;
            }
            try {
                if (path.startsWith("/api/v1/session/observation?")) {
                    long ordinal = Long.parseLong(path.replaceFirst(
                            ".*request_ordinal=([0-9]+).*", "$1"));
                    long now = System.currentTimeMillis();
                    return new JSONObject().put("ok", true).put("schema_version", 2)
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
                                    .put("operation_id", operationId)
                                    .put("stream_session_id", streamSessionId)
                                    .put("control_generation", controlGeneration)
                                    .put("actor_lease_epoch", actorLeaseEpoch)
                                    .put("stream_started_unix_ms", 0))
                            .put("provider", new JSONObject().put("state", gameState)
                                    .put("connector_evidence", "VERIFIED")
                                    .put("reason", "none"))
                            .put("game", idle
                                    ? new JSONObject().put("state", "idle")
                                            .put("evidence", "ABSENT").put("id", "")
                                    : new JSONObject().put("state", gameState)
                                            .put("evidence", gameEvidence).put("id", gameId))
                            .put("process", idle || processPending
                                    ? new JSONObject().put("evidence", idle ? "ABSENT" : "PENDING")
                                            .put("process_id", 0).put("process_token", "")
                                            .put("started_unix_ms", 0)
                                    : new JSONObject().put("evidence", "VERIFIED")
                                            .put("process_id", 42).put("process_token", PROCESS_TOKEN)
                                            .put("started_unix_ms", 0))
                            .put("window", idle || processPending
                                    ? new JSONObject().put("evidence", idle ? "ABSENT" : "PENDING")
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
                    if (stopAnswer != null) return stopAnswer.answer();
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
