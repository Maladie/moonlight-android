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
                "\"games\":[{\"id\":\"steam:289070\"," +
                "\"name\":\"Game\",\"isInstalled\":false,\"isInstalling\":true," +
                "\"hidden\":false," +
                "\"playtime\":3600,\"lastActivity\":\"2026-07-20T10:00:00Z\"," +
                "\"source\":\"Steam\",\"provider\":\"steam\"," +
                "\"providerGameId\":\"289070\",\"playniteGameId\":" +
                "\"11223344-5566-7788-99aa-bbccddeeff00\"," +
                "\"libraryKey\":\"steam\",\"libraryName\":\"Steam\"," +
                "\"capabilities\":{\"launch\":true,\"install\":false," +
                "\"uninstall\":true},\"genres\":[\"Action\",\"RPG\"]," +
                "\"description\":\"Short overview\",\"playCount\":17," +
                "\"installRequiresAttention\":true," +
                "\"installAttentionReason\":\"launcher_prompt\"," +
                "\"installWindowTitle\":\"Choose install location\"," +
                "\"installLauncher\":\"steam.exe\"," +
                "\"vibepollo_state\":\"preparing\"," +
                "\"cover\":\"hash\"}]}" );

        HostGatewayClient.PlayniteLibrary parsed =
                HostGatewayClient.parsePlayniteLibrary(library);

        assertEquals(1, parsed.games.size());
        assertEquals(3600L, parsed.games.get(0).playtimeSeconds);
        assertEquals("Steam", parsed.games.get(0).source);
        assertEquals("steam", parsed.games.get(0).provider);
        assertEquals("steam:289070", parsed.games.get(0).id);
        assertEquals("289070", parsed.games.get(0).providerGameId);
        assertEquals("steam", parsed.games.get(0).libraryKey);
        assertFalse(parsed.games.get(0).canInstall);
        assertEquals("Action, RPG", parsed.games.get(0).genres);
        assertEquals("Short overview", parsed.games.get(0).description);
        assertEquals(17, parsed.games.get(0).playCount);
        assertTrue(parsed.games.get(0).installing);
        assertTrue(parsed.games.get(0).installRequiresAttention);
        assertEquals("launcher_prompt", parsed.games.get(0).installAttentionReason);
        assertEquals("Choose install location", parsed.games.get(0).installWindowTitle);
        assertEquals("steam.exe", parsed.games.get(0).installLauncher);
        assertEquals("preparing", parsed.games.get(0).vibepolloState);
        assertEquals("42", parsed.revision);
    }

    @Test public void epicRecordPreservesExactLegendaryAppName() throws Exception {
        HostGatewayClient.PlayniteLibrary parsed = HostGatewayClient.parsePlayniteLibrary(
                new JSONObject("{\"games\":[{\"id\":\"epic:CelesteApp\"," +
                        "\"name\":\"Celeste\",\"source\":\"Epic\"," +
                        "\"provider\":\"epic\",\"providerGameId\":\"CelesteApp\"," +
                        "\"capabilities\":{\"launch\":true,\"install\":true," +
                        "\"uninstall\":true}}]}"));

        assertEquals("epic:CelesteApp", parsed.games.get(0).id);
        assertEquals("CelesteApp", parsed.games.get(0).providerGameId);
        assertTrue(parsed.games.get(0).canLaunch);
    }

    @Test public void legacyExternalGuidHasNoExecutableCapabilities() throws Exception {
        HostGatewayClient.PlayniteGame game = HostGatewayClient.parsePlayniteLibrary(
                new JSONObject("{\"games\":[{\"id\":" +
                        "\"00000001-0000-0000-0000-000000000000\"," +
                        "\"name\":\"Old Steam Game\",\"source\":\"Steam\"}]}"))
                .games.get(0);

        assertEquals("steam", game.provider);
        assertFalse(game.canLaunch);
        assertFalse(game.canInstall);
        assertFalse(game.canUninstall);
    }

    @Test public void profileProjectionPreservesPlayniteConnectorState() throws Exception {
        HostGatewayClient.IntegrationProfiles profiles =
                HostGatewayClient.parseIntegrationProfiles(new JSONObject()
                        .put("profiles", new org.json.JSONArray().put(new JSONObject()
                                .put("id", "default")
                                .put("playnite_bridge_online", true)
                                .put("playnite_connector_connected", true))));

        assertTrue(profiles.find("default").playniteConnectorConnected);
    }

    @Test public void gameStartRejectionPreservesTopLevelAndNestedReasons() throws Exception {
        assertEquals("launcher_interaction_required",
                HostGatewayClient.gameStartRejectionReason(new JSONObject()
                        .put("accepted", false)
                        .put("reason", "launcher_interaction_required")));
        assertEquals("host_session_locked",
                HostGatewayClient.gameStartRejectionReason(new JSONObject()
                        .put("result", new JSONObject()
                                .put("accepted", false)
                                .put("reason", "host_session_locked"))));
    }

    @Test public void acceptedGameStartHasNoRejectionReason() throws Exception {
        assertEquals("", HostGatewayClient.gameStartRejectionReason(new JSONObject()
                .put("accepted", true)
                .put("result", new JSONObject().put("accepted", true))));
    }
}
