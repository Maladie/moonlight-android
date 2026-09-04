package com.limelight.console;

import com.limelight.gateway.GatewayTransport;

import org.json.JSONObject;
import org.junit.Test;

import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class HostGatewayClientTest {
    @Test public void windowsSessionResponseKeepsOnlyCoarsePollingState() throws Exception {
        HostGatewayClient.WindowsSession session = HostGatewayClient.parseWindowsSession(
                new JSONObject().put("state", "session_starting")
                        .put("reason", "none")
                        .put("attempt_id", "0123456789abcdef0123456789abcdef")
                        .put("retry_after_ms", 750));

        assertEquals("session_starting", session.state);
        assertEquals("none", session.reason);
        assertEquals("0123456789abcdef0123456789abcdef", session.attemptId);
        assertEquals(750, session.retryAfterMs);
    }

    @Test public void windowsSessionHttpErrorsMapToCoarseAndroidReasons() {
        assertEquals("remote_sign_in_not_granted", HostGatewayClient.sessionFailure(
                new GatewayTransport.GatewayException("denied", 403)).reason);
        assertEquals("broker_unavailable", HostGatewayClient.sessionFailure(
                new GatewayTransport.GatewayException("unavailable", 503)).reason);
        assertEquals("other_user_active", HostGatewayClient.sessionFailure(
                new GatewayTransport.GatewayException("other_user_active", 409)).reason);
        assertEquals("credential_missing", HostGatewayClient.sessionFailure(
                new GatewayTransport.GatewayException("credential_missing", 409)).reason);
    }

    @Test public void windowsSessionCancellationPinsOriginalRequestAndAttempt() throws Exception {
        JSONObject body = HostGatewayClient.sessionCancellationBody(
                "android-42-original", "0123456789abcdef0123456789abcdef");

        assertEquals("android-42-original", body.getString("request_id"));
        assertEquals("0123456789abcdef0123456789abcdef",
                body.getString("attempt_id"));
    }

    @Test public void adaptiveNetworkDownloadTargetsEightSecondsAtEightyMegabits() {
        assertEquals(80_000_000, HostGatewayClient.adaptiveNetworkDownloadSize(
                8L * 1024 * 1024, 838_860_800L));
    }

    @Test public void adaptiveNetworkDownloadTargetsEightSecondsAtFiveHundredMegabits() {
        assertEquals(500_000_000, HostGatewayClient.adaptiveNetworkDownloadSize(
                8L * 1024 * 1024, 134_217_728L));
    }

    @Test public void adaptiveNetworkDownloadClampsFasterLinksToFiveHundredTwelveMebibytes() {
        assertEquals(512 * 1024 * 1024, HostGatewayClient.adaptiveNetworkDownloadSize(
                8L * 1024 * 1024, 67_108_864L));
    }

    @Test public void currentGameGuideDecisionComesFromHostAndDefaultsToBlocked() throws Exception {
        JSONObject current = new JSONObject().put("id", "legacy-steam-guid")
                .put("state", "running").put("host_guide_allowed", true)
                .put("requires_connector", false);
        JSONObject response = new JSONObject().put("current", current);
        assertTrue(HostGatewayClient.parseCurrentGame(response).hostGuideAllowed);
        assertFalse(HostGatewayClient.parseCurrentGame(response).requiresConnector);
        current.put("host_guide_allowed", false);
        assertFalse(HostGatewayClient.parseCurrentGame(response).hostGuideAllowed);
        current.remove("host_guide_allowed");
        current.remove("requires_connector");
        current.put("id", "steam:1");
        assertFalse(HostGatewayClient.parseCurrentGame(response).hostGuideAllowed);
        assertTrue(HostGatewayClient.parseCurrentGame(response).requiresConnector);
    }


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
                "\"uninstall\":true},\"providerCapabilities\":{" +
                "\"requiresConnector\":false,\"streamMode\":\"neutral\"," +
                "\"startBeforeStream\":true}," +
                "\"genres\":[\"Action\",\"RPG\"]," +
                "\"description\":\"Short overview\",\"playCount\":17," +
                "\"installRequiresAttention\":true," +
                "\"installAttentionReason\":\"launcher_prompt\"," +
                "\"installWindowTitle\":\"Choose install location\"," +
                "\"installLauncher\":\"steam.exe\"," +
                "\"vibepollo_state\":\"preparing\"," +
                "\"cover\":\"cover-hash\",\"background\":\"background-hash\"," +
                "\"hero\":\"hero-hash\"}]}" );

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
        assertFalse(parsed.games.get(0).requiresConnector);
        assertEquals("neutral", parsed.games.get(0).streamMode);
        assertTrue(parsed.games.get(0).startBeforeStream);
        assertEquals("Action, RPG", parsed.games.get(0).genres);
        assertEquals("Short overview", parsed.games.get(0).description);
        assertEquals(17, parsed.games.get(0).playCount);
        assertTrue(parsed.games.get(0).installing);
        assertTrue(parsed.games.get(0).installRequiresAttention);
        assertEquals("launcher_prompt", parsed.games.get(0).installAttentionReason);
        assertEquals("Choose install location", parsed.games.get(0).installWindowTitle);
        assertEquals("steam.exe", parsed.games.get(0).installLauncher);
        assertEquals("preparing", parsed.games.get(0).vibepolloState);
        assertEquals("cover-hash", parsed.games.get(0).cover);
        assertEquals("background-hash", parsed.games.get(0).background);
        assertEquals("hero-hash", parsed.games.get(0).hero);
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

    @Test public void futureProviderUsesDeclaredCapabilitiesWithoutClientAllowlist()
            throws Exception {
        HostGatewayClient.PlayniteGame game = HostGatewayClient.parsePlayniteLibrary(
                new JSONObject("{\"games\":[{\"id\":\"gog:Some_Game\"," +
                        "\"name\":\"Some Game\",\"provider\":\"gog\"," +
                        "\"providerGameId\":\"Some_Game\"," +
                        "\"capabilities\":{\"launch\":true}," +
                        "\"providerCapabilities\":{\"requiresConnector\":false," +
                        "\"streamMode\":\"neutral\"," +
                        "\"startBeforeStream\":true}}]}")).games.get(0);

        assertEquals("gog", game.provider);
        assertTrue(game.canLaunch);
        assertFalse(game.requiresConnector);
        assertEquals("neutral", game.streamMode);
        assertTrue(game.startBeforeStream);
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

    @Test public void pairingSelectsTheOnlyGrantedProfile() throws Exception {
        HostGatewayClient.IntegrationProfiles profiles =
                HostGatewayClient.parseIntegrationProfiles(new JSONObject()
                        .put("profiles", new org.json.JSONArray().put(new JSONObject()
                                .put("id", "p-basia")
                                .put("permissions", new JSONObject()
                                        .put("use_profile", true)))));

        assertEquals("p-basia", HostGatewayClient.pairingProfileId(profiles));
    }

    @Test public void pairingUsesARealProfileWhileMultipleProfilesAwaitSelection() throws Exception {
        HostGatewayClient.IntegrationProfiles profiles =
                HostGatewayClient.parseIntegrationProfiles(new JSONObject()
                        .put("profiles", new org.json.JSONArray()
                                .put(new JSONObject().put("id", "p-basia"))
                                .put(new JSONObject().put("id", "p-guest"))));

        assertEquals("p-basia", HostGatewayClient.pairingProfileId(profiles));
    }

    @Test public void selectorProjectsOnlyUsableProfilesAndHidesForOne() throws Exception {
        org.json.JSONArray values = new org.json.JSONArray()
                .put(new JSONObject().put("id", "denied").put("name", "Denied")
                        .put("permissions", new JSONObject().put("use_profile", false)))
                .put(new JSONObject().put("id", "Basia").put("name", "Basia")
                        .put("permissions", new JSONObject()
                                .put("use_profile", true).put("remote_sign_in", true))
                        .put("pin_required", true)
                        .put("session_state", "unlocked")
                        .put("remote_sign_in_state", "available"))
                .put(new JSONObject().put("id", "Gry").put("name", "Gry")
                        .put("permissions", new JSONObject().put("use_profile", true)));
        HostGatewayClient.IntegrationProfiles parsed =
                HostGatewayClient.parseIntegrationProfiles(
                        new JSONObject().put("profiles", values));

        HostGatewayStore.ProfileSelection multiple = HostGatewayStore.projectProfiles(
                parsed.profiles, "Gry", "Basia");
        assertEquals(2, multiple.profiles.size());
        assertEquals("Gry", multiple.selected.id);
        assertTrue(multiple.showSelector);
        assertTrue(parsed.find("Basia").remoteSignIn);
        assertTrue(parsed.find("Basia").pinRequired);
        assertEquals("unlocked", parsed.find("Basia").sessionState);
        assertEquals("available", parsed.find("Basia").remoteSignInState);

        HostGatewayStore.ProfileSelection single = HostGatewayStore.projectProfiles(
                Collections.singletonList(parsed.find("Basia")), "Basia", "");
        assertFalse(single.showSelector);
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
