package com.limelight.console;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class ConsoleLaunchContractTest {
    @Test public void requestCarriesStableProtectedLaunchIdentity() {
        ConsoleLaunchContract.Request request = ConsoleLaunchContract.create(
                new ConsoleDataRepository.Host("host-a", "Living room", null),
                new ConsoleDataRepository.App(17, "Baba Is You", null),
                "com.limelight.unofficial");

        assertEquals("host-a", request.hostUuid);
        assertEquals(17, request.appId);
        assertEquals("Baba Is You", request.appName);
        assertEquals("com.limelight.unofficial", request.frontendPackage);
        assertEquals("Preparing Baba Is You…", request.privacyMessage);
        assertTrue(request.readinessRequired);
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
