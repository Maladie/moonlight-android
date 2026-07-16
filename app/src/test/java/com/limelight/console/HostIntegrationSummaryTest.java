package com.limelight.console;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class HostIntegrationSummaryTest {
    private static final String FINGERPRINT =
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

    @Test public void pairedSummaryContainsProfileButNoSecrets() {
        GatewayConnection connection = new GatewayConnection(
                "https://private-host:8785", "private-token", FINGERPRINT, "living-room");
        HostIntegrationSummary summary = HostIntegrationSummary.from(connection);
        String rendered = summary.gatewayLabel() + summary.profileLabel() + summary.servicesLabel();
        assertTrue(rendered.contains("LIVING-ROOM"));
        assertFalse(rendered.contains("private-host"));
        assertFalse(rendered.contains("private-token"));
        assertFalse(rendered.contains(FINGERPRINT));
    }

    @Test public void unpairedSummaryFailsClosed() {
        HostIntegrationSummary summary = HostIntegrationSummary.from(null);
        assertFalse(summary.gatewayPaired);
        assertTrue(summary.gatewayLabel().contains("NOT PAIRED"));
        assertTrue(summary.servicesLabel().contains("required"));
    }

    @Test public void matchingProfileAddsIndependentServiceStates() {
        GatewayConnection connection = new GatewayConnection(
                "https://private-host:8785", "private-token", FINGERPRINT, "living-room");
        IntegrationProfileStatus status = new IntegrationProfileStatus(
                "living-room", "Living room", true, true, true, false, true, true);
        HostIntegrationSummary summary = HostIntegrationSummary.from(connection, status);
        String rendered = summary.servicesLabel() + summary.playniteLabel();
        assertTrue(rendered.contains("DISCORD ONLINE"));
        assertTrue(rendered.contains("VIBEPOLLO OFFLINE"));
        assertTrue(rendered.contains("PLAYNITE ONLINE"));
        assertTrue(rendered.contains("USB READY"));
    }
}
