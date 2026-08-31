package com.limelight.console;

import com.limelight.gateway.GatewayConnection;
import com.limelight.nvstream.http.ComputerDetails;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import static org.junit.Assert.*;

public class RunningGameInventoryTest {
    private static final String TOKEN = new String(new char[64]).replace('\0', 'a');

    private static JSONObject entry(String id, String token) throws Exception {
        return new JSONObject().put("game_id", id).put("process_id", 123)
                .put("process_token", token);
    }

    private static HostGatewayClient.RunningGames inventory(String status, JSONObject... entries)
            throws Exception {
        JSONArray array = new JSONArray();
        for (JSONObject entry : entries) array.put(entry);
        return HostGatewayClient.parseRunningGames(new JSONObject().put("running_games", array)
                .put("running_scan_status", status).put("running_scan_revision", "revision"));
    }

    @Test public void twoRunningGamesAreIndependentOfCurrentAndPreserveEpicRequestCase()
            throws Exception {
        HostGatewayClient.RunningGames games = inventory("partial",
                entry("steam:367520", TOKEN), entry("epic:Cowbird", TOKEN));
        assertNotNull(games.find("steam:367520"));
        assertNotNull(games.find("epic:cowbird"));
        assertEquals("epic:Cowbird", HostGatewayClient.gameStopBody(
                games.find("epic:cowbird").gameId, TOKEN).getString("game_id"));
        assertEquals(TOKEN, HostGatewayClient.gameStopBody("epic:Cowbird", TOKEN)
                .getString("expected_process_token"));
        assertNull(games.find("steam:1"));
    }

    @Test public void unsupportedUnavailableInvalidAndAmbiguousAreNotPositive() throws Exception {
        assertNull(HostGatewayClient.parseRunningGames(new JSONObject()));
        assertNull(inventory("unavailable", entry("steam:1", TOKEN)).find("steam:1"));
        assertNull(inventory("complete", entry("steam:1", "INVALID")).find("steam:1"));
        assertNull(inventory("complete", entry("steam:1", TOKEN),
                entry("steam:1", TOKEN.replace('a', 'b'))).find("steam:1"));
    }

    @Test public void observationExpiryAndChangedInventoryChangeCardRebindSignature()
            throws Exception {
        ConsoleActivity.RunningGameObservation first = new ConsoleActivity.RunningGameObservation(
                inventory("complete", entry("steam:1", TOKEN)), null, 1000);
        ConsoleActivity.RunningGameObservation second = new ConsoleActivity.RunningGameObservation(
                inventory("complete", entry("steam:1", TOKEN), entry("epic:Cowbird", TOKEN)),
                null, 1000);
        assertNotNull(first.find("steam:1", 1001));
        assertNull(first.find("steam:1", 11_000));
        assertNull(first.find("steam:1", 999));
        assertNotEquals(first.signature(1001), second.signature(1001));
        assertEquals(first.signature(1001), first.signature(1002));
        assertNotEquals(first.signature(1001), first.signature(11_000));
        ConsoleActivity.RunningGameObservation afterStop = second.without(
                second.find("steam:1", 1001));
        assertNull(afterStop.find("steam:1", 1001));
        assertNotNull(afterStop.find("epic:Cowbird", 1001));
        assertEquals(second.observedAt, afterStop.observedAt);
    }

    @Test public void verifiedNoncurrentStopDoesNotAuthorizeCurrentMutationAndLegacyStillWorks()
            throws Exception {
        JSONObject response = new JSONObject().put("ok", true).put("accepted", true)
                .put("stopped_game_id", "epic:Cowbird").put("stopped_current", false);
        assertFalse(HostGatewayClient.stoppedCurrent(response, "epic:Cowbird", true));
        response.put("stopped_current", true);
        assertTrue(HostGatewayClient.stoppedCurrent(response, "epic:Cowbird", true));
        assertTrue(HostGatewayClient.stoppedCurrent(new JSONObject().put("ok", true),
                "steam:1", false));
        for (JSONObject invalid : new JSONObject[] {
                new JSONObject().put("ok", true),
                new JSONObject().put("ok", true).put("stopped_game_id", "steam:2")
                        .put("stopped_current", false)}) {
            try {
                HostGatewayClient.stoppedCurrent(invalid, "steam:1", true);
                fail("Unconfirmed result accepted");
            } catch (IOException expected) { }
        }
    }

    @Test public void profileCorrelationRejectsChangedHostOrProfile() {
        GatewayConnection initial = new GatewayConnection("https://host:8785", "secret", TOKEN, "default");
        assertTrue(ConsoleActivity.sameGatewayProfile(initial,
                new GatewayConnection("https://host:8785", "secret", TOKEN, "default")));
        assertFalse(ConsoleActivity.sameGatewayProfile(initial,
                new GatewayConnection("https://other:8785", "secret", TOKEN, "default")));
        assertFalse(ConsoleActivity.sameGatewayProfile(initial,
                new GatewayConnection("https://host:8785", "secret", TOKEN, "other")));
    }

    @Test public void inventoryIsReboundInBothViewsAndNeverProjectedIntoResume() throws Exception {
        String source = new String(Files.readAllBytes(Paths.get(
                "src/main/java/com/limelight/console/ConsoleActivity.java")), "UTF-8");
        assertTrue(source.contains("+ runningGameSignature(host)"));
        assertTrue(source.contains("sessionSignature += runningGameSignature(host)"));
        String stop = source.substring(source.indexOf("private void requestEndPlayniteGame("),
                source.indexOf("private void markExactPlayniteGameIdle("));
        assertTrue(stop.contains("if (stoppedSessionGame) markExactPlayniteGameIdle"));
        assertTrue(stop.contains("if (stoppedSessionGame) refreshSessionState(host.uuid)"));
        assertTrue(stop.contains("getPlayniteCurrentGame(connection)"));
        assertTrue(stop.contains("confirmedGame.processToken.equals(exact.processToken)"));
        assertFalse(stop.contains("quitApp"));
        assertFalse(stop.contains("stopConnection"));
        String resolver = source.substring(source.indexOf("private SessionSnapshot resolveSessionSnapshot("),
                source.indexOf("private String hostStatus("));
        assertFalse(resolver.contains("runningGameObservations"));
    }

    @Test public void unchangedHostWithoutStreamStillRefreshesAndFailuresStayThrottled()
            throws Exception {
        ComputerDetails host = new ComputerDetails();
        host.uuid = "host";
        host.state = ComputerDetails.State.ONLINE;
        host.runningGameId = 0;
        assertEquals(ConsoleUpdateChannels.NONE,
                ConsoleUpdateChannels.diff(host, new ComputerDetails(host), "host"));
        long requestedAt = 1000L;
        ConsoleActivity.RunningGameObservation observed = new ConsoleActivity.RunningGameObservation(
                inventory("complete", entry("steam:1", TOKEN)), null, requestedAt);
        String rendered = observed.signature(requestedAt);
        int requests = 0;
        for (long now : new long[] {2000, 6000, 7000, 11_000, 12_000}) {
            if (!ConsoleActivity.activeGameRefreshDue(now, requestedAt)) continue;
            requestedAt = now; // Updated even if the first request fails.
            requests++;
            if (requests == 2) observed = new ConsoleActivity.RunningGameObservation(
                    inventory("complete", entry("steam:1", TOKEN)), null, now);
        }
        assertEquals(2, requests);
        assertNotNull(observed.find("steam:1", 12_000));
        assertEquals(rendered, observed.signature(12_000)); // No unchanged-card rebuild.
    }

    @Test public void expiredNoResponseClearsRenderedBadgeNotJustCurrentMenuEligibility()
            throws Exception {
        ConsoleActivity.RunningGameObservation observed = new ConsoleActivity.RunningGameObservation(
                inventory("complete", entry("steam:1", TOKEN)), null, 1000L);
        String rendered = observed.signature(1000L);
        assertNull(observed.find("steam:1", 11_000L)); // No stop action.
        String expired = observed.signature(11_000L);
        assertNotEquals(rendered, expired); // The LAST rendered positive needs rebinding.
        rendered = expired;
        assertEquals(rendered, observed.signature(12_000L)); // No repeated rebuild.

        String source = new String(Files.readAllBytes(Paths.get(
                "src/main/java/com/limelight/console/ConsoleActivity.java")), "UTF-8");
        String listener = source.substring(source.indexOf("private final ComputerManagerListener"),
                source.indexOf("private void reconcileFreshSessionFacts("));
        assertTrue(listener.contains("fresh && active && copy.uuid.equals(selectedHostUuid)"));
        assertTrue(listener.indexOf("resolveActivePlayniteGame(copy,")
                < listener.indexOf("ConsoleUpdateChannels.diff("));
        assertTrue(listener.contains("refreshRunningGamePresentation(copy)"));
        assertTrue(source.contains("if (acceptsInventory) refreshRunningGamePresentation(latestHost)"));
        assertTrue(source.contains("!signature.equals(renderedCarouselSessionSignature)"));
        assertTrue(source.contains("!signature.equals(renderedExpandedSessionSignature)"));
        assertFalse(source.contains("previousInventorySignature"));
    }
}
