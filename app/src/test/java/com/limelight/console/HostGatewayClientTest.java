package com.limelight.console;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class HostGatewayClientTest {

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
}
