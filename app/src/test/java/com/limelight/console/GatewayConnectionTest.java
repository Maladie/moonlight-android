package com.limelight.console;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class GatewayConnectionTest {
    private static final String FINGERPRINT =
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

    @Test public void normalizesWakeCompatibleConnectionData() {
        GatewayConnection connection = new GatewayConnection(
                " https://192.0.2.10:8785/ ", "secret", colonized(FINGERPRINT), " profile-1 ");
        assertEquals("https://192.0.2.10:8785", connection.endpoint);
        assertEquals(FINGERPRINT, connection.certificateSha256);
        assertEquals("profile-1", connection.profileId);
    }

    @Test public void emptyProfileUsesDefault() {
        GatewayConnection connection = new GatewayConnection(
                "https://host:8785", "secret", FINGERPRINT, "");
        assertEquals("default", connection.profileId);
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsUnpinnedConnection() {
        new GatewayConnection("https://host:8785", "secret", "", "default");
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsPlainHttpEndpoint() {
        new GatewayConnection("http://host:8785", "secret", FINGERPRINT, "default");
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsUnsafeProfileId() {
        new GatewayConnection("https://host:8785", "secret", FINGERPRINT, "bad/profile");
    }

    @Test public void storageKeyMatchesWakeSchema() {
        assertEquals("host-uuid.integration_profile",
                HostGatewayStore.key("host-uuid", "integration_profile"));
    }

    private static String colonized(String value) {
        return value.replaceAll("(..)(?!$)", "$1:").toUpperCase(java.util.Locale.US);
    }
}
