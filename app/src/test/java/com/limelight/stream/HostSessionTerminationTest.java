package com.limelight.stream;

import com.limelight.gateway.GatewayTransport;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class HostSessionTerminationTest {
    private static final String GAME = "8d3cd9e1-6c9e-4bb2-9b5e-0d6f5b1e7a10";

    private static final String TOKEN =
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";

    @Test public void eachCloseRefusalReasonHasItsOwnOutcome() {
        for (boolean abandoned : new boolean[] {false, true}) {
            // No record: a running application may be the stream left behind; none is nothing.
            assertEquals(HostSessionTermination.Outcome.FAILED,
                    HostSessionTermination.failure("managed_stream_unavailable", 7, abandoned));
            assertEquals(HostSessionTermination.Outcome.NOTHING_TO_CLOSE,
                    HostSessionTermination.failure("managed_stream_unavailable", 0, abandoned));
            assertEquals(HostSessionTermination.Outcome.OTHER_STREAM,
                    HostSessionTermination.failure("unmanaged_stream", 9, abandoned));
            assertEquals(HostSessionTermination.Outcome.FAILED,
                    HostSessionTermination.failure(null, 0, abandoned));
        }
        // A newer generation of this owner's stream: the session and its game still run under
        // it. Only of a start this client gave up is nothing left there.
        assertEquals(HostSessionTermination.Outcome.SUPERSEDED,
                HostSessionTermination.failure("stream_generation_superseded", 7, false));
        assertEquals(HostSessionTermination.Outcome.NOTHING_TO_CLOSE,
                HostSessionTermination.failure("stream_generation_superseded", 7, true));
        // Another owner took the host: nothing of an abandoned start, a refusal for a user.
        assertEquals(HostSessionTermination.Outcome.OTHER_STREAM,
                HostSessionTermination.failure("stream_owner_mismatch", 7, false));
        assertEquals(HostSessionTermination.Outcome.NOTHING_TO_CLOSE,
                HostSessionTermination.failure("stream_owner_mismatch", 7, true));
    }

    @Test public void usersCloseOfASupersededBindingIsRepeatedOnceWithTheHostsBinding()
            throws Exception {
        HostSessionTermination.Outcome superseded = HostSessionTermination.Outcome.SUPERSEDED;
        assertFalse(superseded.closed());
        // Never grounds for clearing the local records: the newer session still runs.
        assertFalse(superseded.streamClosed());
        int[] retries = {0};
        for (HostSessionTermination.Outcome first : HostSessionTermination.Outcome.values()) {
            if (first == superseded) continue;
            assertEquals(first, HostSessionTermination.retrySuperseded(first, () -> {
                retries[0]++;
                return HostSessionTermination.Outcome.CLOSED;
            }));
        }
        assertEquals(0, retries[0]);
        // The second attempt's result is the close's result.
        for (HostSessionTermination.Outcome second : new HostSessionTermination.Outcome[] {
                HostSessionTermination.Outcome.CLOSED,
                HostSessionTermination.Outcome.CLOSED_GAME_UNVERIFIED,
                HostSessionTermination.Outcome.GAME_UNVERIFIED,
                HostSessionTermination.Outcome.OTHER_STREAM}) {
            assertEquals(second, HostSessionTermination.retrySuperseded(superseded, () -> {
                retries[0]++;
                return second;
            }));
        }
        assertEquals(4, retries[0]);
        // Once: a second supersession, or no binding the host reports, is a failed close.
        assertEquals(HostSessionTermination.Outcome.FAILED,
                HostSessionTermination.retrySuperseded(superseded, () -> superseded));
        assertEquals(HostSessionTermination.Outcome.FAILED,
                HostSessionTermination.retrySuperseded(superseded, () -> {
                    throw new java.io.IOException("Gateway could not identify an owned stream.");
                }));
        String host = source("src/main/java/com/limelight/stream/HostSessionTermination.java");
        String close = host.substring(host.indexOf("private static Outcome close("),
                host.indexOf("static Outcome retrySuperseded("));
        // The retry names the host's own binding and is never the abandoned-start mapping.
        assertTrue(close.contains("control.ownedSession(gateway);"));
        assertTrue(close.contains("return attempt(false, context,"));
        assertTrue(close.contains("owned.streamSessionId, owned.sunshineAppId, gameId,"));
        assertTrue(host.contains("return close(true, context,"));
        assertTrue(host.contains("return close(false, context,"));
        // The user's closes keep the retrying entry; the two exact-generation ones do not.
        String console = source("src/main/java/com/limelight/console/ConsoleActivity.java");
        String known = console.substring(console.indexOf(
                "private HostSessionTermination.Outcome terminateKnownControlPlane("),
                console.indexOf("private long knownControlGeneration("));
        assertTrue(known.contains("HostSessionTermination.run("));
        assertTrue(known.indexOf("if (stopped.streamClosed()) {")
                < known.indexOf("new SessionEvent.Cleared(sessionId)"));
        assertTrue(source("src/main/java/com/limelight/Game.java").contains(
                "resolved = HostSessionTermination.closeAbandonedStart(application,"));
    }

    @Test public void startFoundOnTheHostAfterALostPermitIsClosedLikeACancelledOne()
            throws Exception {
        String connection = source("src/main/java/com/limelight/nvstream/NvConnection.java");
        String lost = connection.substring(connection.indexOf("catch (IOException unavailable)"),
                connection.indexOf("boolean replayed = permit.succeeded();"));
        int abandon = lost.indexOf("abandonControlStart(operationId, app.getAppId(), 0L);");
        int handed = lost.indexOf(
                "context.connListener.startLeftOnHost(abandoned.generation);");
        assertTrue(abandon > 0 && handed > abandon && lost.indexOf("throw abandoned", handed) > 0);
        String runtime = source("src/main/java/com/limelight/stream/StreamRuntime.java");
        assertTrue(runtime.contains("@Override public void startLeftOnHost(long controlGeneration) {\n"
                + "        closeAbandonedLaunch(controlGeneration);"));
    }

    @Test public void onlyNothingToCloseEndsTheSessionSilently() throws Exception {
        HostSessionTermination.Outcome nothing = HostSessionTermination.Outcome.NOTHING_TO_CLOSE;
        assertTrue(nothing.closed());
        assertTrue(nothing.streamClosed());
        assertFalse(HostSessionTermination.Outcome.OTHER_STREAM.closed());
        assertFalse(HostSessionTermination.Outcome.OTHER_STREAM.streamClosed());
        // The abandoned launch's close reports every outcome through the one notice owner:
        // no blanket hiding of a foreign stream.
        String runtime = new String(Files.readAllBytes(Paths.get(
                "src/main/java/com/limelight/stream/AndroidStreamRuntime.java")),
                StandardCharsets.UTF_8);
        assertFalse(runtime.contains("reportsAbandonedLaunchClose"));
        assertTrue(runtime.replace("\r\n", "\n").contains(
                "new Thread(() -> Game.reportGameLeftOnHost(app,\n"
                        + "                    HostSessionTermination.closeAbandonedStart("));
        String console = new String(Files.readAllBytes(Paths.get(
                "src/main/java/com/limelight/console/ConsoleActivity.java")),
                StandardCharsets.UTF_8);
        String finish = console.substring(console.indexOf("private void finishTerminateSession("));
        assertTrue(finish.indexOf("outcome == HostSessionTermination.Outcome.NOTHING_TO_CLOSE) return;")
                < finish.indexOf("R.string.console_terminate_session_success"));
    }

    @Test public void knownRunningGameIsTheStopTarget() throws Exception {
        assertEquals(GAME, HostSessionTermination.stopTarget(GAME, "running", GAME));
        // run() stops exactly that verified target inside the permit before quitting Sunshine.
        String host = source("src/main/java/com/limelight/stream/HostSessionTermination.java");
        int target = host.indexOf("String exact = verifiedStopGame(");
        int stop = host.indexOf("provider.stopGameWithinControl(exact, permit)");
        assertTrue(target > 0 && stop > target && host.indexOf("http.quitApp()") > stop);
    }

    @Test public void idleProviderNeedsNoGame() {
        assertEquals("", HostSessionTermination.stopTarget("", "idle", ""));
        assertEquals("", HostSessionTermination.verifiedStopGame(
                GAME, "idle", "", false, "", GAME, TOKEN));
    }

    @Test public void phaseTwoOrAdoptedGameIsStoppedOnlyWithThePermitsExactProcess() {
        assertEquals(GAME, HostSessionTermination.verifiedStopGame(
                GAME, "starting", GAME, true, TOKEN, GAME, TOKEN));
        assertEquals(GAME, HostSessionTermination.verifiedStopGame(
                GAME, "running", GAME, true, TOKEN, GAME, TOKEN));
        assertNull(HostSessionTermination.verifiedStopGame(
                GAME, "starting", GAME, true, TOKEN.replace('a', 'b'), GAME, TOKEN));
        assertNull(HostSessionTermination.verifiedStopGame(
                GAME, "starting", GAME, false, "", GAME, TOKEN));
    }

    @Test public void failedStartCannotBeStoppedButStillClosesTheStream() throws Exception {
        // A failed start keeps the request's game id with no verified process.
        assertNull(HostSessionTermination.verifiedStopGame(
                GAME, "failed", GAME, false, "", GAME, TOKEN));
        assertTrue(HostSessionTermination.Outcome.CLOSED_GAME_UNVERIFIED.streamClosed());
        String host = source("src/main/java/com/limelight/stream/HostSessionTermination.java");
        String decision = host.substring(host.indexOf("String exact = verifiedStopGame("),
                host.indexOf("http.quitApp()"));
        // No unverified game throws before /cancel: the stream, its display and permit end.
        assertTrue(decision.contains("gameLeftUnverified = exact == null;"));
        assertTrue(!decision.contains("throw new IllegalStateException(\"provider_"));
        assertTrue(host.contains(
                "gameLeftUnverified ? Outcome.CLOSED_GAME_UNVERIFIED : Outcome.CLOSED"));
    }

    @Test public void gameLeftBehindAClosedStreamIsNotAClosedSession() throws Exception {
        assertTrue(HostSessionTermination.Outcome.CLOSED.closed());
        assertTrue(HostSessionTermination.Outcome.CLOSED.streamClosed());
        assertFalse(HostSessionTermination.Outcome.CLOSED_GAME_UNVERIFIED.closed());
        assertFalse(HostSessionTermination.Outcome.GAME_UNVERIFIED.streamClosed());
        assertFalse(HostSessionTermination.Outcome.FAILED.streamClosed());
        // Closing a previous session before a new launch needs the game gone too, so a game
        // left on the host blocks the launch with its visible failure.
        String console = source("src/main/java/com/limelight/console/ConsoleActivity.java");
        assertTrue(console.contains(
                "if (!outcome.closed()) throw SessionFailure.CLOSE_GAME_LEFT.raise();"));
        assertTrue(console.contains("knownProviderGameId(ready)).closed()) {"));
        assertTrue(console.contains("host, intent.profileId, pending.playniteGameId).closed();"));
        assertTrue(console.contains("boolean success = close.get().closed();"));
        // Only the user's explicit close treats a closed stream as done.
        String finish = console.substring(console.indexOf("private void finishTerminateSession("),
                console.indexOf("private boolean isPairingContextCurrent("));
        assertTrue(finish.contains("if (outcome.streamClosed())"));
        // A retained close ends with the stream; a requesting console gets gameLeftOnHost.
        String game = source("src/main/java/com/limelight/Game.java");
        assertTrue(game.contains("host.complete(outcome.streamClosed());"));
        String headless = source("src/main/java/com/limelight/stream/AndroidStreamRuntime.java");
        assertTrue(headless.contains("host.complete(outcome.streamClosed());"));
        assertTrue(headless.indexOf("host.complete(outcome.streamClosed());")
                < headless.indexOf("completion.gameLeftOnHost();"));
    }

    @Test public void missingOrDifferentGameIsRejectedAsUnverified() {
        assertNull(HostSessionTermination.stopTarget("", "running", GAME));
        assertNull(HostSessionTermination.stopTarget(
                "1d3cd9e1-6c9e-4bb2-9b5e-0d6f5b1e7a10", "running", GAME));
        assertNull(HostSessionTermination.verifiedStopGame(
                "", "running", GAME, true, TOKEN, GAME, TOKEN));
        assertEquals(HostSessionTermination.Outcome.OTHER_STREAM,
                HostSessionTermination.failure("unmanaged_stream", 0, false));
        assertEquals(HostSessionTermination.Outcome.FAILED,
                HostSessionTermination.failure("gateway_unavailable", 0, false));
    }

    @Test public void acquireRejectionKeepsItsReason() {
        assertEquals(HostSessionTermination.Outcome.OTHER_STREAM, HostSessionTermination
                .rejection(new GatewayTransport.GatewayException("unmanaged_stream", 409), 7,
                        false));
        assertEquals(HostSessionTermination.Outcome.SUPERSEDED, HostSessionTermination
                .rejection(new GatewayTransport.GatewayException(
                        "stream_generation_superseded", 409), 7, false));
        assertEquals(HostSessionTermination.Outcome.NOTHING_TO_CLOSE, HostSessionTermination
                .rejection(new GatewayTransport.GatewayException(
                        "stream_generation_superseded", 409), 7, true));
        assertEquals(HostSessionTermination.Outcome.GAME_UNVERIFIED, HostSessionTermination
                .rejection(new GatewayTransport.GatewayException(
                        "retained_process_unconfirmed", 409), 7, false));
        // A lost transport stays FAILED, so run() reconciles the possibly granted permit.
        assertEquals(HostSessionTermination.Outcome.FAILED, HostSessionTermination
                .rejection(new java.io.IOException("unmanaged_stream"), 7, false));
    }

    @Test public void hostPanelCloseSessionPassesTheKnownGame() throws Exception {
        String console = source("src/main/java/com/limelight/console/ConsoleActivity.java");
        assertTrue(console.contains("case CLOSE_SESSION: confirmTerminateSession(host); break;"));
        String confirm = console.substring(console.indexOf(
                        "private void confirmTerminateSession(ComputerDetails host, String"),
                console.indexOf("private void finishTerminateSession("));
        // The dialog resolves the shown game once; the close stops exactly that game.
        assertTrue(confirm.contains("HostGatewayClient.isPlayniteId(session.playniteGameId)"));
        assertTrue(confirm.contains("requestTerminateSession(host, gameId)"));
        assertTrue(confirm.contains("profileKey.profileId, providerGameId)"));
    }

    private static String source(String relative) throws Exception {
        Path path = Paths.get(relative);
        if (!Files.exists(path)) path = Paths.get("app").resolve(relative);
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8).replace("\r\n", "\n");
    }
}
