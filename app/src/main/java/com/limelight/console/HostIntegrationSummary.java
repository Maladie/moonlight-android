package com.limelight.console;

/** Secret-free presentation model for the local Host Integrations panel. */
final class HostIntegrationSummary {
    final boolean gatewayPaired;
    final String profileId;
    final IntegrationProfileStatus profileStatus;

    private HostIntegrationSummary(boolean gatewayPaired, String profileId,
                                   IntegrationProfileStatus profileStatus) {
        this.gatewayPaired = gatewayPaired;
        this.profileId = profileId;
        this.profileStatus = profileStatus;
    }

    static HostIntegrationSummary from(GatewayConnection connection) {
        return connection == null ?
                new HostIntegrationSummary(false, GatewayConnection.DEFAULT_PROFILE_ID, null) :
                new HostIntegrationSummary(true, connection.profileId, null);
    }

    static HostIntegrationSummary from(GatewayConnection connection,
                                       IntegrationProfileStatus profileStatus) {
        if (connection == null) return from(null);
        IntegrationProfileStatus matching = profileStatus != null &&
                connection.profileId.equals(profileStatus.id) ? profileStatus : null;
        return new HostIntegrationSummary(true, connection.profileId, matching);
    }

    String gatewayLabel() {
        return gatewayPaired ? "GATEWAY · PAIRED" : "GATEWAY · NOT PAIRED";
    }

    String profileLabel() {
        return "INTEGRATION PROFILE · " + profileId.toUpperCase(java.util.Locale.ROOT);
    }

    String servicesLabel() {
        if (!gatewayPaired) {
            return "Pairing/import is required before host services can be used";
        }
        if (profileStatus == null) {
            return "Discord · Audio · VirtualHere · Vibepollo\n" +
                    "Status refresh requires the host Gateway";
        }
        return "DISCORD " + profileStatus.discordState() +
                " · VIBEPOLLO " + profileStatus.vibepolloState() +
                " · USB " + profileStatus.virtualHereState();
    }
}
