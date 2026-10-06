package com.limelight.console;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.concurrent.atomic.AtomicInteger;

public class ProfileAuthorizationTest {
    @After public void reset() {
        ProfileAuthorization.revoke();
        ProfileAuthorization.onChange(() -> { });
    }

    @Test public void nothingIsAllowedUntilTheHostIsGrantedAndRevokeStopsIt() {
        ProfileAuthorization.revoke();
        assertFalse(ProfileAuthorization.allows("host-a"));
        assertFalse(ProfileAuthorization.allows(null));
        assertFalse(ProfileAuthorization.allows(""));
        ProfileAuthorization.grant("host-a");
        assertTrue(ProfileAuthorization.allows("host-a"));
        assertFalse(ProfileAuthorization.allows("host-b"));
        ProfileAuthorization.revoke();
        assertFalse(ProfileAuthorization.allows("host-a"));
    }

    @Test public void listenerRunsOnlyWhenTheGrantChanges() {
        ProfileAuthorization.revoke();
        AtomicInteger changes = new AtomicInteger();
        ProfileAuthorization.onChange(changes::incrementAndGet);
        ProfileAuthorization.revoke();
        assertEquals(0, changes.get());
        ProfileAuthorization.grant("host-a");
        ProfileAuthorization.grant("host-a");
        assertEquals(1, changes.get());
        ProfileAuthorization.grant("host-b");
        ProfileAuthorization.revoke();
        assertEquals(3, changes.get());
    }

    /** StreamHomeActivity is a second console over the main one; its destruction must not revoke. */
    @Test public void destroyingAConsoleDoesNotRevokeTheProcessWideGrant() throws Exception {
        String source = new String(Files.readAllBytes(Paths.get(
                "src/main/java/com/limelight/console/ConsoleActivity.java")),
                StandardCharsets.UTF_8);
        int from = source.indexOf("protected void onDestroy()");
        int to = source.indexOf("public void onBackPressed()", from);
        assertTrue(from >= 0 && to > from);
        assertFalse(source.substring(from, to).contains("ProfileAuthorization"));
    }
}
