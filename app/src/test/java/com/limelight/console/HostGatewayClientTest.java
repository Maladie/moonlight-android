package com.limelight.console;

import org.json.JSONObject;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class HostGatewayClientTest {

    @Test
    public void gameplayPermissionsAllowLaunchAndInputsWithoutServerCommands() {
        int permissions = HostGatewayClient.REQUIRED_GAMEPLAY_PERMISSIONS;
        assertEquals(0x07000000, permissions & 0x07000000);
        assertEquals(0x00001F00, permissions & 0x00001F00);
        assertEquals(0, permissions & 0x00100000);
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

    @Test
    public void vibepolloNumericAppIdIsUsedAsExactLaunchTarget() throws Exception {
        assertEquals(Integer.valueOf(1136869674),
                HostGatewayClient.parseVibepolloAppId(
                        new JSONObject("{\"app_id\":\"1136869674\"}")));
        assertEquals(null, HostGatewayClient.parseVibepolloAppId(
                new JSONObject("{\"app_id\":0}")));
    }

    @Test
    public void vibepolloAppUuidIsNormalized() throws Exception {
        assertEquals("11223344-5566-7788-99aa-bbccddeeff00",
                HostGatewayClient.parseVibepolloAppUuid(new JSONObject()
                        .put("uuid", "11223344-5566-7788-99AA-BBCCDDEEFF00")));
        assertEquals("", HostGatewayClient.parseVibepolloAppUuid(
                new JSONObject().put("uuid", "invalid")));
    }

    @Test
    public void playniteContractUsesSdkSecondsAndPreservesMetadata() throws Exception {
        JSONObject library = new JSONObject("{\"revision\":\"42\",\"api_version\":\"1\"," +
                "\"games\":[{\"id\":\"00000001-0000-0000-0000-000000000000\"," +
                "\"name\":\"Game\",\"isInstalled\":false,\"isInstalling\":true," +
                "\"hidden\":false," +
                "\"playtime\":3600,\"lastActivity\":\"2026-07-20T10:00:00Z\"," +
                "\"source\":\"Steam\",\"genres\":[\"Action\",\"RPG\"]," +
                "\"description\":\"Short overview\",\"playCount\":17," +
                "\"installRequiresAttention\":true," +
                "\"installAttentionReason\":\"launcher_prompt\"," +
                "\"installWindowTitle\":\"Choose install location\"," +
                "\"installLauncher\":\"steam.exe\"," +
                "\"cover\":\"hash\"}]}" );

        HostGatewayClient.PlayniteLibrary parsed =
                HostGatewayClient.parsePlayniteLibrary(library);

        assertEquals(1, parsed.games.size());
        assertEquals(3600L, parsed.games.get(0).playtimeSeconds);
        assertEquals("Steam", parsed.games.get(0).source);
        assertEquals("Action, RPG", parsed.games.get(0).genres);
        assertEquals("Short overview", parsed.games.get(0).description);
        assertEquals(17, parsed.games.get(0).playCount);
        assertTrue(parsed.games.get(0).installing);
        assertTrue(parsed.games.get(0).installRequiresAttention);
        assertEquals("launcher_prompt", parsed.games.get(0).installAttentionReason);
        assertEquals("Choose install location", parsed.games.get(0).installWindowTitle);
        assertEquals("steam.exe", parsed.games.get(0).installLauncher);
        assertEquals("42", parsed.revision);
    }
}
