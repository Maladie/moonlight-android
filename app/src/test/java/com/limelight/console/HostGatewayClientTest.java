package com.limelight.console;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class HostGatewayClientTest {

    @Test public void playniteLibraryRejectsInvalidIdsAndKeepsMetadata() throws Exception {
        JSONArray games = new JSONArray()
                .put(new JSONObject().put("id", "840317c9-b9a4-4f72-be8e-807414e36a9b")
                        .put("name", "Baba Is You").put("installed", true)
                        .put("coverImage", "cover.jpg"))
                .put(new JSONObject().put("id", "../../desktop").put("name", "Bad"));
        HostGatewayClient.PlayniteLibrary library = HostGatewayClient.parsePlayniteLibrary(
                new JSONObject().put("games", games).put("next_cursor", "100").put("total", 3));
        assertEquals(1, library.games.size());
        assertEquals("Baba Is You", library.games.get(0).name);
        assertEquals("cover.jpg", library.games.get(0).cover);
        assertEquals("100", library.nextCursor);
        assertEquals(3, library.total);
    }

    @Test public void playniteReadinessDefaultsClosed() {
        HostGatewayClient.PlayniteReadiness readiness =
                HostGatewayClient.parsePlayniteReadiness(new JSONObject());
        assertFalse(readiness.ready);
        assertEquals("window_probe_pending", readiness.reason);
    }
    @Test
    public void endpointForIpv4HostUsesDefaultGatewayPort() {
        assertEquals("https://192.0.2.10:8785",
                HostGatewayClient.endpointForHost("192.0.2.10"));
    }

    @Test
    public void endpointForIpv6HostAddsBrackets() {
        assertEquals("https://[2001:db8::10]:8785",
                HostGatewayClient.endpointForHost("2001:db8::10"));
    }

    @Test
    public void certificateFingerprintIsNormalized() {
        assertEquals("aabbcc", HostGatewayClient.normalizeFingerprint("AA:BB:CC"));
    }

    @Test
    public void discordIdsAcceptOnlySnowflakeDigits() {
        assertTrue(HostGatewayClient.isDiscordId("123456789012345678"));
        assertFalse(HostGatewayClient.isDiscordId("../../shutdown"));
        assertFalse(HostGatewayClient.isDiscordId("1234"));
    }
}
