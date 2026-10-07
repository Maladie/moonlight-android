package com.limelight.stream;

import com.limelight.gateway.GatewayTransport;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class HostSessionTerminationTest {
    private static final String GAME = "8d3cd9e1-6c9e-4bb2-9b5e-0d6f5b1e7a10";

    @Test public void knownRunningGameIsTheStopTarget() throws Exception {
        assertEquals(GAME, HostSessionTermination.stopTarget(GAME, "running", GAME));
        // run() stops exactly that verified target inside the permit before quitting Sunshine.
        String host = source("src/main/java/com/limelight/stream/HostSessionTermination.java");
        int target = host.indexOf("String exact = stopTarget(");
        int stop = host.indexOf("provider.stopGameWithinControl(exact, permit)");
        assertTrue(target > 0 && stop > target && host.indexOf("http.quitApp()") > stop);
    }

    @Test public void idleProviderNeedsNoGame() {
        assertEquals("", HostSessionTermination.stopTarget("", "idle", ""));
    }

    @Test public void missingOrDifferentGameIsRejectedAsUnverified() {
        assertNull(HostSessionTermination.stopTarget("", "running", GAME));
        assertNull(HostSessionTermination.stopTarget(
                "1d3cd9e1-6c9e-4bb2-9b5e-0d6f5b1e7a10", "running", GAME));
        assertEquals(HostSessionTermination.Outcome.GAME_UNVERIFIED,
                HostSessionTermination.failure("provider_game_mismatch"));
        assertEquals(HostSessionTermination.Outcome.GAME_UNVERIFIED,
                HostSessionTermination.failure("provider_process_mismatch"));
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
