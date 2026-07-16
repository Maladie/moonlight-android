package com.limelight.console;

import com.limelight.ui.overlay.DiscordGatewayClient;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** Converts the existing pinned Gateway transport into console-owned profile models. */
final class GatewayProfileAdapter {
    interface Source {
        DiscordGatewayClient.IntegrationProfiles load(
                DiscordGatewayClient.Connection connection) throws IOException;
    }

    private final Source source;

    GatewayProfileAdapter() {
        DiscordGatewayClient client = new DiscordGatewayClient();
        source = client::getIntegrationProfiles;
    }

    GatewayProfileAdapter(Source source) {
        this.source = source;
    }

    IntegrationProfileCatalog load(GatewayConnection connection) throws IOException {
        if (connection == null) throw new IllegalArgumentException("Gateway connection required");
        DiscordGatewayClient.IntegrationProfiles response = source.load(
                new DiscordGatewayClient.Connection(connection.endpoint, connection.token,
                        connection.certificateSha256, connection.profileId));
        List<IntegrationProfileStatus> profiles = new ArrayList<>();
        for (DiscordGatewayClient.IntegrationProfile profile : response.profiles) {
            try {
                profiles.add(new IntegrationProfileStatus(profile.id, profile.name,
                        profile.discordBridgeOnline, profile.discordRpcConnected,
                        profile.discordAuthenticated, profile.vibepolloBridgeOnline,
                        profile.playniteBridgeOnline,
                        profile.virtualHereAvailable));
            }
            catch (IllegalArgumentException invalidProfile) {
                // Ignore malformed remote profile IDs without affecting other profiles.
            }
        }
        String suggested = response.suggestedProfileId;
        try {
            return new IntegrationProfileCatalog(profiles,
                    suggested == null || suggested.trim().isEmpty() ? null : suggested);
        }
        catch (IllegalArgumentException invalidSuggestion) {
            return new IntegrationProfileCatalog(profiles, null);
        }
    }
}
