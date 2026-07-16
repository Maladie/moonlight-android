package com.limelight.console;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class ConsoleLaunchContractTest {
    @Test public void requestCarriesStableProtectedLaunchIdentity() {
        ConsoleLaunchContract.Request request = ConsoleLaunchContract.create(
                new ConsoleDataRepository.Host("host-a", "Living room", null),
                new ConsoleDataRepository.App(17, "Baba Is You", null, true),
                "com.limelight.unofficial");

        assertEquals("host-a", request.hostUuid);
        assertEquals(17, request.appId);
        assertEquals("Baba Is You", request.appName);
        assertTrue(request.appSupportsHdr);
        assertEquals("com.limelight.unofficial", request.frontendPackage);
        assertEquals("Preparing Baba Is You…", request.privacyMessage);
        assertTrue(request.readinessRequired);
    }

    @Test public void nonHdrApplicationRemainsExplicitlyDisabled() {
        ConsoleLaunchContract.Request request = ConsoleLaunchContract.create(
                new ConsoleDataRepository.Host("host-a", "Living room", null),
                new ConsoleDataRepository.App(17, "Game", null),
                "com.limelight.unofficial");

        assertFalse(request.appSupportsHdr);
    }

    @Test public void reconnectKeepsIdentityAndMarksPersistedGamepads() {
        ConsoleLaunchContract.Request request = ConsoleLaunchContract.create(
                new ConsoleDataRepository.Host("host-a", "Living room", null),
                new ConsoleDataRepository.App(17, "Game", null),
                "com.limelight.unofficial");

        ConsoleLaunchContract.Request reconnect = request.withRuntimeBitrate(24_000)
                .asReconnect();

        assertTrue(request.matches(reconnect));
        assertFalse(request.resumePersistedGamepads);
        assertTrue(reconnect.resumePersistedGamepads);
        assertEquals(24_000, reconnect.runtimeBitrateKbps);
    }

    @Test public void incompleteRequestIsRejectedBeforeAnyActivityLaunch() {
        assertInvalid(null, new ConsoleDataRepository.App(1, "Game", null), "package");
        assertInvalid(new ConsoleDataRepository.Host("host", "Host", null),
                null, "package");
        assertInvalid(new ConsoleDataRepository.Host("host", "Host", null),
                new ConsoleDataRepository.App(1, "Game", null), "");
    }

    private static void assertInvalid(ConsoleDataRepository.Host host,
                                      ConsoleDataRepository.App app,
                                      String packageName) {
        try {
            ConsoleLaunchContract.create(host, app, packageName);
            fail("Expected invalid launch request");
        }
        catch (IllegalArgumentException expected) {
            // Expected.
        }
    }
}
