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
        assertTrue(console.contains("providerGameId).closed();"));
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
                HostSessionTermination.failure("unmanaged_stream"));
        assertEquals(HostSessionTermination.Outcome.FAILED,
                HostSessionTermination.failure("gateway_unavailable"));
    }

    @Test public void acquireRejectionKeepsItsReason() {
        assertEquals(HostSessionTermination.Outcome.OTHER_STREAM, HostSessionTermination
                .rejection(new GatewayTransport.GatewayException("unmanaged_stream", 409)));
        assertEquals(HostSessionTermination.Outcome.GAME_UNVERIFIED, HostSessionTermination
                .rejection(new GatewayTransport.GatewayException(
                        "retained_process_unconfirmed", 409)));
        // A lost transport stays FAILED, so run() reconciles the possibly granted permit.
        assertEquals(HostSessionTermination.Outcome.FAILED, HostSessionTermination
                .rejection(new java.io.IOException("unmanaged_stream")));
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
