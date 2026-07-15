package com.limelight.console;

import com.limelight.ui.overlay.DiscordGatewayClient;

import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class GatewayProfileAdapterTest {
    private static final String PIN =
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

    @Test public void pinnedResponseBecomesSecretFreeConsoleCatalog() throws Exception {
        GatewayProfileAdapter adapter = new GatewayProfileAdapter(connection ->
                new DiscordGatewayClient.IntegrationProfiles(Arrays.asList(
                        new DiscordGatewayClient.IntegrationProfile("desk", "Desktop",
                                true, true, true, true, false)), "desk"));

        IntegrationProfileCatalog catalog = adapter.load(
                new GatewayConnection("https://host:47990", "secret", PIN, "desk"));

        IntegrationProfileStatus profile = catalog.find("desk");
        assertNotNull(profile);
        assertTrue(catalog.isSuggested(profile));
        assertEquals(IntegrationProfileStatus.ServiceState.ONLINE, profile.discordState());
        assertEquals(IntegrationProfileStatus.ServiceState.ONLINE, profile.vibepolloState());
    }

    @Test public void malformedRemoteProfilesAndSuggestionAreIgnored() throws Exception {
        GatewayProfileAdapter adapter = new GatewayProfileAdapter(connection ->
                new DiscordGatewayClient.IntegrationProfiles(Arrays.asList(
                        new DiscordGatewayClient.IntegrationProfile("bad profile", "Bad",
                                false, false, false, false, false),
                        new DiscordGatewayClient.IntegrationProfile("valid", "Valid",
                                true, false, false, false, true)), "also bad"));

        IntegrationProfileCatalog catalog = adapter.load(
                new GatewayConnection("https://host", "secret", PIN, "default"));

        assertEquals(1, catalog.profiles.size());
        assertNotNull(catalog.find("valid"));
        assertNull(catalog.suggestedProfileId);
    }
}
