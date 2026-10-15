package com.limelight.console;

import com.limelight.SourceText;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Test;

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
        String source = SourceText.main("console/ConsoleActivity.java");
        int from = source.indexOf("protected void onDestroy()");
        int to = source.indexOf("public void onBackPressed()", from);
        assertTrue(from >= 0 && to > from);
        assertFalse(source.substring(from, to).contains("ProfileAuthorization"));
    }

    /**
     * A Console recreated in the same process resolves its entry automatically (host picker, gate
     * or PIN screen). That must not drop the process-wide host grant; only the user's own return
     * to the gate or host selection does.
     */
    @Test public void onlyUserInitiatedReturnsRevokeTheGrant() throws Exception {
        String source = SourceText.main("console/ConsoleActivity.java");
        for (String automatic : new String[] {
                "private void resolveInitialHostSelection()", "private void showHostSelection(",
                "private void resolveProfileGate(", "private void showProfileGate(",
                "private void showPinEntry("}) {
            assertFalse(automatic, body(source, automatic).contains("ProfileAuthorization.revoke()"));
        }
        for (String user : new String[] {"private void leaveHost()", "private void closeProfileGate()"}) {
            assertTrue(user, body(source, user).contains("ProfileAuthorization.revoke()"));
        }
    }

    private static String body(String source, String signature) {
        int from = source.indexOf(signature);
        assertTrue(signature, from >= 0);
        return source.substring(from, source.indexOf("\n    private ", from + signature.length()));
    }
}
