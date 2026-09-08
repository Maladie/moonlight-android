package com.limelight.console;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertEquals;

public class ConsoleChildPairingTest {
    @Test public void oldGatewayChildActionDeniedIsAnUnsupportedIdentityEndpoint() {
        assertEquals("identity_binding_unsupported",
                ConsoleActivity.identityBindingFailureReason("child_action_not_allowed", false));
    }

    @Test public void authenticatedRepairUsesPinnedHttpsWhileLegacyUnpairStaysPendingOnly()
            throws IOException {
        String source = new String(Files.readAllBytes(Paths.get(
                "src/main/java/com/limelight/nvstream/http/NvHTTP.java")),
                StandardCharsets.UTF_8);
        String authenticated = between(source, "public boolean unpairAuthenticated()",
                "public InputStream getBoxArt");
        String legacy = between(source, "public void unpair()",
                "public boolean unpairAuthenticated()");

        assertTrue(authenticated.contains("getHttpsUrl(true)"));
        assertTrue(authenticated.contains("\"unpair\""));
        assertTrue(authenticated.contains("serverCert == null"));
        assertTrue(legacy.contains("baseUrlHttp"));
    }

    @Test public void childPairingFailureOffersExplicitHostRepair() throws IOException {
        String source = new String(Files.readAllBytes(Paths.get(
                "src/main/java/com/limelight/console/ConsoleActivity.java")),
                StandardCharsets.UTF_8);
        String failure = between(source, "private String childLaunchFailureMessage(",
                "private static final class WarmUpBridgeProbe");
        String pairHost = between(source, "private void pairHost(ComputerDetails host)",
                "private void pairGatewayAndStream(");
        String automatic = between(source,
                "private void beginAutomaticHostPairing(ComputerDetails host,\n"
                        + "                                           GatewayConnection connection,\n"
                        + "                                           String initialTicket,\n"
                        + "                                           boolean renewExistingPairing)",
                "private void cancelPendingPairing(");

        assertTrue(failure.contains("case \"pairing_required\":"));
        assertTrue(failure.contains("console_child_launch_pairing_required"));
        assertTrue(pairHost.contains("beginAutomaticHostPairing(host, connection, null)"));
        assertTrue(source.contains("beginAutomaticHostPairing(host, connection, null, true)"));
        assertTrue(automatic.contains("http.unpairAuthenticated()"));
        assertTrue(automatic.contains("PairingManager.PairState afterUnpair"));
        assertTrue(automatic.contains("PairingManager.PairState.NOT_PAIRED"));
        assertTrue(automatic.contains("isPairingContextCurrent"));
        assertTrue(automatic.contains("if (pairFuture != null) cancelPendingPairing(http);"));
        assertTrue(automatic.contains("renewExistingPairing"));
        assertTrue(source.contains("beginChildPairingRepair()"));
        assertTrue(source.contains("childConnection(host, profileId)"));
        assertTrue(source.contains("console_child_launch_pairing_action"));
        assertTrue(source.contains("tryBindExistingVibepolloIdentity"));
        assertTrue(source.contains("requestVibepolloIdentityChallenge"));
        assertTrue(source.contains("bindExistingVibepolloIdentity"));
        assertTrue(source.contains("childIdentityBindingLifecycleToken"));
        assertTrue(source.contains("child_action_not_allowed"));
        assertTrue(source.contains("tryBindExistingVibepolloIdentity(hostId, profileId, true)"));
    }

    @Test public void freshSelectedChildProfileRefreshStartsIdentityBinding() throws IOException {
        String source = new String(Files.readAllBytes(Paths.get(
                "src/main/java/com/limelight/console/ConsoleActivity.java")),
                StandardCharsets.UTF_8);
        String refresh = between(source, "private void refreshHostProfiles(",
                "private void refreshVisibleHostProfiles(");

        int save = refresh.indexOf("saveHostProfiles(hostId, profiles)");
        int invalidation = refresh.indexOf("if (childWasInvalidated)");
        int bind = refresh.indexOf(
                "tryBindExistingVibepolloIdentity(hostId, selectedProfileId, false)");
        assertTrue(save >= 0);
        assertTrue(invalidation > save);
        assertTrue(bind > invalidation);
        assertTrue(refresh.contains("selectedProfile.isChild()"));
        assertTrue(refresh.contains("\"pairing_required\".equalsIgnoreCase(selectedProfile.reason)"));
    }

    private static String between(String source, String start, String end) {
        int from = source.indexOf(start);
        int to = source.indexOf(end, from + start.length());
        if (from < 0 || to < 0) {
            throw new AssertionError("Missing source range: " + start);
        }
        return source.substring(from, to);
    }
}
