package com.limelight.console;

/** Secret-free, profile-scoped service state matching the Wake Gateway contract. */
final class IntegrationProfileStatus {
    enum ServiceState { ONLINE, READY, OFFLINE }

    final String id;
    final String name;
    final boolean discordBridgeOnline;
    final boolean discordRpcConnected;
    final boolean discordAuthenticated;
    final boolean vibepolloBridgeOnline;
    final boolean playniteBridgeOnline;
    final boolean virtualHereAvailable;

    IntegrationProfileStatus(String id, String name,
                             boolean discordBridgeOnline,
                             boolean discordRpcConnected,
                             boolean discordAuthenticated,
                             boolean vibepolloBridgeOnline,
                             boolean virtualHereAvailable) {
        this(id, name, discordBridgeOnline, discordRpcConnected,
                discordAuthenticated, vibepolloBridgeOnline, false,
                virtualHereAvailable);
    }

    IntegrationProfileStatus(String id, String name,
                             boolean discordBridgeOnline,
                             boolean discordRpcConnected,
                             boolean discordAuthenticated,
                             boolean vibepolloBridgeOnline,
                             boolean playniteBridgeOnline,
                             boolean virtualHereAvailable) {
        this.id = GatewayConnection.normalizeProfileId(id);
        this.name = name == null || name.trim().isEmpty() ? this.id : name.trim();
        this.discordBridgeOnline = discordBridgeOnline;
        this.discordRpcConnected = discordRpcConnected;
        this.discordAuthenticated = discordAuthenticated;
        this.vibepolloBridgeOnline = vibepolloBridgeOnline;
        this.playniteBridgeOnline = playniteBridgeOnline;
        this.virtualHereAvailable = virtualHereAvailable;
    }

    ServiceState discordState() {
        if (discordRpcConnected && discordAuthenticated) return ServiceState.ONLINE;
        if (discordBridgeOnline) return ServiceState.READY;
        return ServiceState.OFFLINE;
    }

    ServiceState vibepolloState() {
        return vibepolloBridgeOnline ? ServiceState.ONLINE : ServiceState.OFFLINE;
    }

    ServiceState virtualHereState() {
        return virtualHereAvailable ? ServiceState.READY : ServiceState.OFFLINE;
    }

    ServiceState playniteState() {
        return playniteBridgeOnline ? ServiceState.ONLINE : ServiceState.OFFLINE;
    }
}
