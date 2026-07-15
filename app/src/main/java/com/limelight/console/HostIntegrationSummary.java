package com.limelight.console;

/** Secret-free presentation model for the local Host Integrations panel. */
final class HostIntegrationSummary {
    final boolean gatewayPaired;
    final String profileId;

    private HostIntegrationSummary(boolean gatewayPaired, String profileId) {
        this.gatewayPaired = gatewayPaired;
        this.profileId = profileId;
    }

    static HostIntegrationSummary from(GatewayConnection connection) {
        return connection == null ?
                new HostIntegrationSummary(false, GatewayConnection.DEFAULT_PROFILE_ID) :
                new HostIntegrationSummary(true, connection.profileId);
    }

    String gatewayLabel() {
        return gatewayPaired ? "GATEWAY · PAIRED" : "GATEWAY · NOT PAIRED";
    }

    String profileLabel() {
        return "INTEGRATION PROFILE · " + profileId.toUpperCase(java.util.Locale.ROOT);
    }

    String servicesLabel() {
        return gatewayPaired ?
                "Discord · Audio · VirtualHere · Vibepollo\nStatus refresh requires the host Gateway" :
                "Pairing/import is required before host services can be used";
    }
}
