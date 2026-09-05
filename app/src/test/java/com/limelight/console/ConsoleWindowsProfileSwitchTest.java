package com.limelight.console;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Arrays;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class ConsoleWindowsProfileSwitchTest {
    @Test public void activeAuthorizedProfileIsAnAlignOnlyAction() throws Exception {
        HostGatewayClient.IntegrationProfile basia = profile(
                "basia", "other_user_active", true);
        HostGatewayClient.IntegrationProfile gry = profile("gry", "active", false);
        HostGatewayStore.ProfileSelection selection = HostGatewayStore.projectProfiles(
                Arrays.asList(basia, gry), "basia", "");

        assertSame(gry, ConsoleActivity.activeAuthorizedProfile(selection));

        String menu = between(source(),
                "private void showHostSelectionOptions(ComputerDetails host)",
                "static HostGatewayClient.IntegrationProfile activeAuthorizedProfile(");
        String align = between(menu, "if (alignProfile != null) {", "boolean switchVisible");
        assertTrue(align.contains("selectProfile(host, activeProfile.id)"));
        assertFalse(align.contains("switchWindowsSession("));
    }

    @Test public void explicitSwitchRequiresConfirmationBeforeEndpoint() throws Exception {
        String source = source();
        String menu = between(source,
                "private void showHostSelectionOptions(ComputerDetails host)",
                "static HostGatewayClient.IntegrationProfile activeAuthorizedProfile(");
        String confirmation = between(source,
                "private void confirmWindowsProfileSwitch(",
                "private void requestWindowsProfileSwitch(");
        String request = between(source,
                "private void requestWindowsProfileSwitch(",
                "private void runWindowsProfileSwitch(");
        String run = between(source,
                "private void runWindowsProfileSwitch(",
                "static boolean canPollWindowsProfileSwitch(");

        assertTrue(menu.contains("confirmWindowsProfileSwitch(host, selectedProfile)"));
        assertTrue(confirmation.contains("requestWindowsProfileSwitch(host, profile)"));
        assertFalse(request.contains("switchWindowsSession("));
        assertTrue(run.contains("hostGatewayClient.switchWindowsSession("));
    }

    @Test public void staleAndCancelledAttemptsLoseCorrelation() {
        ConsoleActivity.WindowsProfileSwitchAttempt attempt =
                new ConsoleActivity.WindowsProfileSwitchAttempt(
                        "host-a", "basia", "request-a", null);

        assertTrue(attempt.matches("host-a", "basia", "request-a"));
        assertFalse(attempt.matches("host-b", "basia", "request-a"));
        assertFalse(attempt.matches("host-a", "gry", "request-a"));
        assertFalse(attempt.matches("host-a", "basia", "request-b"));
        attempt.cancelled = true;
        assertFalse(attempt.matches("host-a", "basia", "request-a"));
    }

    @Test public void missingCredentialMapsToLoginScreenAttention() {
        assertTrue(ConsoleActivity.windowsProfileSwitchNeedsAttention(
                new HostGatewayClient.WindowsSession(
                        "attention_required", "credential_missing", "", 1_000)));
        assertTrue(ConsoleActivity.windowsProfileSwitchNeedsAttention(
                new HostGatewayClient.WindowsSession(
                        "action_required", "credential_missing", "", 1_000)));
    }

    @Test public void pollingStopsOnlyForTerminalSwitchStates() {
        String attemptId = "0123456789abcdef0123456789abcdef";
        assertTrue(ConsoleActivity.canPollWindowsProfileSwitch(
                new HostGatewayClient.WindowsSession("pending", "none", attemptId, 1_000)));
        assertTrue(ConsoleActivity.canPollWindowsProfileSwitch(
                new HostGatewayClient.WindowsSession(
                        "credential_issued", "none", attemptId, 1_000)));
        assertTrue(ConsoleActivity.canPollWindowsProfileSwitch(
                new HostGatewayClient.WindowsSession(
                        "session_starting", "none", attemptId, 1_000)));
        assertFalse(ConsoleActivity.canPollWindowsProfileSwitch(
                new HostGatewayClient.WindowsSession("ready", "none", attemptId, 1_000)));
        assertFalse(ConsoleActivity.canPollWindowsProfileSwitch(
                new HostGatewayClient.WindowsSession(
                        "attention_required", "credential_missing", attemptId, 1_000)));
        assertFalse(ConsoleActivity.canPollWindowsProfileSwitch(
                new HostGatewayClient.WindowsSession("expired", "attempt_expired", attemptId,
                        1_000)));
        assertFalse(ConsoleActivity.canPollWindowsProfileSwitch(
                new HostGatewayClient.WindowsSession("pending", "none", "", 1_000)));
    }

    @Test public void ordinaryRefreshNeverStartsWindowsSwitch() throws Exception {
        String source = source();
        String refresh = between(source, "private void refreshDashboard()",
                "private void showQuickLaunchPanel()");
        String profileRefresh = between(source,
                "private void refreshWindowsProfileStatus(",
                "private TextView hostSelectionMenuAction(");

        assertFalse(refresh.contains("switchWindowsSession("));
        assertFalse(profileRefresh.contains("switchWindowsSession("));
    }

    @Test public void everyTerminalPathRefreshesAndStaleResultsCannotTouchUi()
            throws Exception {
        String source = source();
        String finish = between(source,
                "private void finishWindowsProfileSwitch(",
                "private void cancelWindowsProfileSwitch(");
        String cancel = between(source,
                "private void cancelWindowsProfileSwitch(",
                "private void cancelWindowsProfileSwitchAttempt(");
        String stale = between(source,
                "private boolean isCurrentWindowsProfileSwitch(",
                "private void finishWindowsProfileSwitch(");

        assertTrue(finish.indexOf("if (!isCurrentWindowsProfileSwitch(attempt)) return")
                < finish.indexOf("refreshWindowsProfileStatus(attempt.hostId)"));
        assertTrue(finish.contains("console_switch_windows_profile_success"));
        assertTrue(finish.contains("console_switch_windows_profile_attention"));
        assertTrue(finish.contains("console_switch_windows_profile_cancelled"));
        assertTrue(finish.contains("console_switch_windows_profile_failed"));
        assertTrue(cancel.contains("refreshWindowsProfileStatus(attempt.hostId)"));
        assertTrue(cancel.contains("attempt.cancelled = true"));
        assertTrue(stale.contains("windowsProfileSwitchAttempt == attempt"));
        assertTrue(stale.contains("selectedProfileId(attempt.hostId)"));
        assertTrue(stale.contains("attempt.requestId"));
    }

    private static HostGatewayClient.IntegrationProfile profile(
            String id, String sessionState, boolean remoteSignIn) {
        return new HostGatewayClient.IntegrationProfile(id, id,
                false, false, false, false, false, false, false,
                true, remoteSignIn, sessionState, "available");
    }

    private static String source() throws Exception {
        return new String(Files.readAllBytes(Paths.get(
                "src/main/java/com/limelight/console/ConsoleActivity.java")),
                StandardCharsets.UTF_8);
    }

    private static String between(String source, String start, String end) {
        int from = source.indexOf(start);
        int to = source.indexOf(end, from + start.length());
        assertTrue(from >= 0);
        assertTrue(to > from);
        return source.substring(from, to);
    }
}
