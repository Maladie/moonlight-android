package com.limelight.console;

import com.limelight.SourceText;
import org.junit.Test;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class ConsoleChildProfileUiContractTest {
    @Test public void childGameSwitchEntersCommonOrchestratorBeforeAllowanceAcquisition() throws Exception {
        String activity = source("src/main/java/com/limelight/console/ConsoleActivity.java");
        String play = between(activity, "private void playProviderGame(PlayIntent intent)",
                "private ChildLaunchBinding childBinding(");
        assertTrue(play.contains("sessionOrchestrator.play(intent)"));
        assertFalse(play.contains("isChildIntent"));
        assertFalse(play.contains("eligibility("));
        String start = between(activity, "private ChildLaunchBinding startChildSession(",
                "private void validateChildState(");
        assertTrue(start.contains("childSessionClient.eligibility("));
        assertTrue(start.contains("childSessionClient.start("));
    }

    @Test public void childHostActionsAndRetryUseAuthorizedSession() throws Exception {
        String activity = source("src/main/java/com/limelight/console/ConsoleActivity.java");
        String menu = between(activity, "private void showHostSelectionOptions(",
                "static HostGatewayClient.IntegrationProfile activeAuthorizedProfile(");
        String panel = source("src/main/java/com/limelight/console/ConsoleHostPanel.java");
        for (String action : new String[]{"WAKE", "DESKTOP", "CLOSE_SESSION", "HARD_TERMINATE",
                "UNPAIR", "TEST_CONNECTION", "SLEEP"}) {
            assertTrue(menu.contains("case " + action + ":"));
        }
        assertFalse(menu.contains("childProfile"));
        assertFalse(panel.contains("childProfile"));
        assertTrue(panel.contains("facts.online && facts.paired && facts.gatewayAvailable"));
        String desktop = between(activity, "private void launchDesktopSession(",
                "private void renderApps(");
        assertFalse(desktop.contains("selectedProfileIsChild"));
        assertTrue(desktop.contains("playSunshineApp(current, desktop, \"\")"));
        String sunshine = between(activity, "private void playSunshineApp(",
                "private void playPlayniteGame(");
        assertFalse(sunshine.contains("selectedProfileIsChild"));
        assertTrue(sunshine.contains("sessionOrchestrator.play("));
        String game = source("src/main/java/com/limelight/Game.java");
        String presentation = between(game, "private void configureActorPresentation()",
                "private void setupOverlayMenu()");
        assertTrue(presentation.contains("setSuspendSessionAvailable(true)"));
        assertFalse(presentation.contains("setSuspendSessionAvailable(!isChildSession())"));
        String start = between(activity, "private ChildLaunchBinding startChildSession(",
                "private void validateChildState(");
        assertFalse(start.contains("endChildBindingAndAwait"));
        assertTrue(start.contains("childLaunchBindings.remove(pending.key(), pending)"));
        assertFalse(start.contains("new ChildLaunchFailure(\"child_session_ending\")"));
        String close = between(activity, "private void requestTerminateSession(",
                "private void finishTerminateSession(");
        assertFalse(close.contains("selectedProfileIsChild"));
        assertTrue(close.contains("retained.executionProfileId.equals("));
    }

    @Test public void childStartsOnlyAfterOrchestratorHasResolvedPreviousSession() throws Exception {
        String activity = source("src/main/java/com/limelight/console/ConsoleActivity.java");
        String preflight = between(activity, "@Override public HostLaunchPreflight.Result preflight(",
                "@Override public SessionOrchestrator.CloseResult switchRetained(");
        assertFalse(preflight.contains("startChildSession("));
        String launch = between(activity, "@Override public void launch(PlayIntent intent, NvApp app,",
                "private void launchStream(PlayIntent intent, NvApp app,");
        assertTrue(launch.contains("startChildSession(intent, cancelled)"));
        assertTrue(launch.contains("launchToken != childEligibilityLaunchToken"));
        assertTrue(launch.contains("endChildBinding(childBinding(intent), \"cancelled\")"));
    }

    @Test public void childConsoleCloseUsesCommonTerminationAndBadgeAcceptsCurrentGame() throws Exception {
        String activity = source("src/main/java/com/limelight/console/ConsoleActivity.java");
        int start = activity.indexOf("private void requestTerminateSession(ComputerDetails host, String expectedProviderGameId)");
        int retained = activity.indexOf("new SessionEvent.TerminateRequested(", start);
        int stop = activity.indexOf("terminateKnownControlPlane(host, profileKey.profileId",
                retained);
        assertTrue(start >= 0 && retained > start && stop > retained);
        String close = activity.substring(start,
                activity.indexOf("private void finishTerminateSession(", start));
        assertFalse(close.contains("endChildBinding"));
        assertFalse(close.contains("childProfile"));
        assertFalse(activity.contains("getPlayniteCurrentGame("));
    }

    /**
     * Owner 2026-10-14: a child's Play over its parent's running game switches on the live
     * stream without a confirmation, like any actor's; the host gates the child's own start.
     */
    @Test public void childKeepsInstallAndUninstallButNeverGetsMicrophone() throws Exception {
        String activity = source("src/main/java/com/limelight/console/ConsoleActivity.java");
        String install = between(activity, "private void startPlayniteInstallation(",
                "private void confirmPlayniteUninstall(");
        String uninstall = between(activity, "private void uninstallPlayniteGame(",
                "private void continuePlayniteInstallation(");
        assertFalse(install.contains("selectedProfileIsChild"));
        assertFalse(install.contains("isChild"));
        assertFalse(uninstall.contains("selectedProfileIsChild"));
        assertFalse(uninstall.contains("isChild"));
        String microphone = between(activity,
                "private boolean microphoneAllowedForSelectedProfile()",
                "private boolean discordPanelVisible()");
        assertTrue(microphone.contains("return discordAllowedForSelectedProfile();"));
        String indicator = between(activity, "private void refreshUsbMicrophoneIndicator()",
                "static int microphoneStateTextResource(");
        assertTrue(indicator.contains("boolean allowed = microphoneAllowedForSelectedProfile();"));
        assertTrue(indicator.contains("ConsoleTopBar.microphoneVisible(allowed, enabled)"));
    }

    @Test public void streamHandoffPreservesOnlyTransferredChildOnPause() throws Exception {
        String activity = source("src/main/java/com/limelight/console/ConsoleActivity.java");
        String pause = between(activity, "protected void onPause()", "public void onBackPressed()");
        assertTrue(pause.contains("cancelCurrentPreparation(childStreamHandoff)"));
        String cancellation = between(activity,
                "private void cancelCurrentPreparation()", "private void pairHost(");
        assertTrue(cancellation.contains("cancelCurrentPreparation(null)"));
        assertTrue(cancellation.contains("preservedBinding);"));
        String bindings = between(activity, "private void cancelChildLaunches(",
                "private void refreshChildBindingStates()");
        assertTrue(bindings.contains("cancelChildLaunches(hostId, profileId, null)"));
        assertTrue(bindings.contains("binding != preservedBinding"));
        assertTrue(bindings.contains("endChildBinding(binding, \"cancelled\")"));
        int launch = activity.indexOf("ServerHelper.doStart(this, app, host, managerBinder, quickLaunchKey, presentation)");
        int handoff = activity.indexOf("childStreamHandoff = presentation.containsKey", launch);
        assertTrue(launch >= 0 && handoff > launch);
        String resume = between(activity, "protected void onResume()", "protected void onPause()");
        assertTrue(resume.contains("childStreamHandoff = null"));
    }

    @Test public void sharingPanelCarriesHostRevisionAfterSave() throws Exception {
        String panel = source("src/main/java/com/limelight/console/"
                + "ConsoleChildGameSharingPanel.java");
        String activity = source("src/main/java/com/limelight/console/ConsoleActivity.java");

        assertTrue(panel.contains("void onSaved(int revision)"));
        assertTrue(panel.contains("expectedRevision = Math.max(0, revision)"));
        assertTrue(panel.contains("AUTOSAVE_DELAY_MS = 5000L"));
        assertTrue(panel.contains("if (saveInFlight)"));
        assertTrue(panel.contains("completed.selectedChildIds.contains"));
        assertTrue(panel.contains("void prepareForDismiss()"));
        assertTrue(panel.contains("void resumeAfterRestore()"));
        assertTrue(panel.contains("String requestIdFor(String signature)"));
        assertTrue(panel.contains("setRefreshCallback(Runnable refresh)"));
        assertTrue(panel.contains("if (conflict)"));
        assertTrue(activity.contains("private void prepareChildSharingForDeparture()"));
        assertTrue(activity.contains("panel.requestIdFor(signature.toString())"));
        assertTrue(activity.contains("if (alreadyShowing && !samePanel) prepareChildSharingForDeparture();"));
        assertTrue(activity.contains("isAttachedChildSharingPanel(panel)"));
        assertFalse(panel.contains("saveButton"));
        assertTrue(activity.contains("completion.onSaved(response.revision)"));
        assertTrue(activity.contains("prepareChildSharingForDeparture();"));
        assertTrue(activity.contains("((ConsoleChildGameSharingPanel) child).resumeAfterRestore();"));
    }

    @Test public void profileToolbarUsesAvatarAndChildDiscordHasNoSlot() throws Exception {
        String activity = source("src/main/java/com/limelight/console/ConsoleActivity.java");
        assertTrue(activity.contains("topBar.showProfile(new ProfileToolbarDrawable("));

        String discord = between(activity, "private void refreshDiscordIndicator()",
                "private void applyDiscordIndicator(");
        assertTrue(discord.contains("ConsoleTopBar.discordVisible("));
        assertTrue(discord.contains("discordAllowedForSelectedProfile(), configured)"));
        assertTrue(discord.contains("topBar.showDiscord(visible)"));
        String access = between(activity, "private boolean discordAllowedForSelectedProfile()",
                "private boolean discordPanelVisible()");
        assertTrue(access.contains("selection.selected.isChild()"));
        assertTrue(activity.contains("private boolean discordAllowedForSelectedProfile()"));
        assertTrue(activity.contains("if (!discordAllowedForSelectedProfile()) return;"));
        assertTrue(activity.contains("discordDmNotifications.deactivateHost(discordDmHostToken)"));

        String status = between(activity, "private String hostStatus(",
                "private ConsoleHostPresentation.State consoleHostState(");
        assertFalse(status.contains("console_profile_status_"));
    }

    @Test public void fullLibraryTileRequiresGamesOutsideCarousel() {
        PlayniteDashboardItem first = item("1");
        PlayniteDashboardItem second = item("2");
        assertFalse(ConsoleActivity.hasGamesOutsideCarousel(
                Collections.singletonList(first), Collections.singletonList(first)));
        assertFalse(ConsoleActivity.hasGamesOutsideCarousel(
                Collections.emptyList(), Collections.singletonList(first)));
        assertTrue(ConsoleActivity.hasGamesOutsideCarousel(
                Arrays.asList(first, second), Collections.singletonList(first)));
        assertTrue(ConsoleActivity.hasGamesOutsideCarousel(
                Collections.singletonList(first), Collections.emptyList()));
    }

    @Test public void childScheduleRejectsImpossibleWindowsAndLimits() {
        assertTrue(ConsoleChildProfileEditor.validSchedule(780, 840, 60));
        assertFalse(ConsoleChildProfileEditor.validSchedule(780, 840, 120));
        assertFalse(ConsoleChildProfileEditor.validSchedule(900, 480, 0));
        assertFalse(ConsoleChildProfileEditor.validSchedule(0, 1680, 60));
        assertFalse(ConsoleChildProfileEditor.validSchedule(840, 840, 0));
        assertFalse(ConsoleChildProfileEditor.validSchedule(0, 60, -1));
        assertTrue(ConsoleChildProfileEditor.validSchedule(1439, 1440, 1));
        assertTrue(ConsoleChildProfileEditor.validSchedule(0, 1440, 1440));
    }

    @Test public void childDayKeepsItsLimitInsideTheWindow() {
        ConsoleChildProfileEditor.Day hour = new ConsoleChildProfileEditor.Day(
                true, 960, 1140, 60 * 60);
        assertEquals(40, hour.withWindow(960, 1000).limitMinutes());
        assertEquals(60, hour.withWindow(900, 1140).limitMinutes());
        ConsoleChildProfileEditor.Day whole = new ConsoleChildProfileEditor.Day(
                true, 960, 1140, 180 * 60);
        assertTrue(whole.unlimited());
        assertEquals(240, whole.withWindow(900, 1140).limitMinutes());
        ConsoleChildProfileEditor.Day opened =
                ConsoleChildProfileEditor.Day.disabled().withEnabled(true);
        assertTrue(opened.enabled && opened.unlimited());
        assertTrue(ConsoleChildProfileEditor.validSchedule(opened.startMinute,
                opened.endMinute, opened.limitMinutes()));
        assertEquals("19:05", ConsoleChildProfileEditor.clock(1145));
        assertEquals("24:00", ConsoleChildProfileEditor.clock(1440));
    }

    @Test public void parentPinUsesThePinPage() throws Exception {
        String activity = source("src/main/java/com/limelight/console/ConsoleActivity.java");
        String authorize = between(activity, "private void reauthorizeChildManagementFor(",
                "private void beginChildManagement(");
        assertTrue(authorize.contains("showPinPage(R.string.console_child_management_pin_title"));
        assertTrue(authorize.contains("pinEntryView.showInvalid()"));
        assertFalse(authorize.contains("consoleFeedback.showInput("));
    }

    @Test public void onlyChildAuthorizationFailuresDropReusableLease() {
        assertTrue(ConsoleActivity.isChildManagementAuthorizationFailure(
                new HostGatewayClient.GatewayException("authorization_required", 403)));
        assertTrue(ConsoleActivity.isChildManagementAuthorizationFailure(
                new HostGatewayClient.GatewayException(
                        "A fresh child management session is required.", 403)));
        assertFalse(ConsoleActivity.isChildManagementAuthorizationFailure(
                new HostGatewayClient.GatewayException("revision_conflict", 409)));
        assertFalse(ConsoleActivity.isChildManagementAuthorizationFailure(
                new IOException("timeout")));
    }

    @Test public void childEligibilityUsesNoneAndExpiresAtWindowBoundary() {
        HostGatewayClient.IntegrationProfile allowed = childProfile(
                7_200L, 1_800L, "none", "2026-09-06T12:00:00.123456Z",
                "2026-09-06T13:00:00Z");
        assertEquals("", ConsoleActivity.childEligibilityBlockReason(
                allowed, ConsoleActivity.parseChildPolicyTime(
                        "2026-09-06T12:30:00Z"), 0L));
        assertEquals("outside_schedule", ConsoleActivity.childEligibilityBlockReason(
                allowed, ConsoleActivity.parseChildPolicyTime(
                        "2026-09-06T13:00:01Z"), 0L));
        HostGatewayClient.IntegrationProfile exhausted = childProfile(
                0L, 0L, "none", "2026-09-06T12:00:00Z", "");
        assertEquals("daily_limit_reached", ConsoleActivity.childEligibilityBlockReason(
                exhausted, ConsoleActivity.parseChildPolicyTime(
                        "2026-09-06T12:30:00Z"), 0L));
        assertEquals(ConsoleActivity.parseChildPolicyTime(
                        "2026-09-06T12:00:00.123Z"),
                ConsoleActivity.parseChildPolicyTime(
                        "2026-09-06T12:00:00.123456Z"));
        assertEquals(-1L, ConsoleActivity.parseChildPolicyTime("not-a-date"));
        assertEquals(ConsoleActivity.parseChildPolicyTime(
                        "2026-09-06T10:00:00Z"),
                ConsoleActivity.parseChildPolicyTime(
                        "2026-09-06T12:00:00+02:00"));
    }

    @Test public void childPolicyTimeAcceptsOnlyTheServerTimestampShapes() {
        long noon = 1_788_696_000_000L; // 2026-09-06T12:00:00Z
        assertEquals(noon, ConsoleActivity.parseChildPolicyTime("2026-09-06T12:00:00Z"));
        assertEquals(noon, ConsoleActivity.parseChildPolicyTime("  2026-09-06T12:00:00Z\n"));
        assertEquals(noon, ConsoleActivity.parseChildPolicyTime("2026-09-06 12:00:00Z"));
        assertEquals(noon, ConsoleActivity.parseChildPolicyTime("2026-09-06T14:00:00+02:00"));
        assertEquals(noon, ConsoleActivity.parseChildPolicyTime("2026-09-06T14:00:00+0200"));
        assertEquals(noon, ConsoleActivity.parseChildPolicyTime("2026-09-06 07:00:00-05:00"));
        assertEquals(noon + 500L, ConsoleActivity.parseChildPolicyTime(
                "2026-09-06T12:00:00.5Z"));
        assertEquals(noon + 123L, ConsoleActivity.parseChildPolicyTime(
                "2026-09-06T12:00:00.123456789Z"));
        assertEquals(noon + 250L, ConsoleActivity.parseChildPolicyTime(
                "2026-09-06T07:00:00.250-05:00"));
        // Repeated and interleaved shapes parse the same: no state leaks between calls.
        for (int round = 0; round < 3; round++) {
            assertEquals(noon, ConsoleActivity.parseChildPolicyTime("2026-09-06 12:00:00Z"));
            assertEquals(noon + 500L, ConsoleActivity.parseChildPolicyTime(
                    "2026-09-06T12:00:00.5Z"));
            assertEquals(-1L, ConsoleActivity.parseChildPolicyTime("2026-09-06T12:00:00"));
        }

        for (String rejected : new String[] {null, "", "   ", "not-a-date",
                "2026-09-06T12:00:00", "2026-09-06", "2026-09-06T12:00Z",
                "2026-13-06T12:00:00Z", "2026-09-31T12:00:00Z", "2026-09-06T25:00:00Z",
                "2026-09-06T12:00:00Zjunk", "2026-09-06 12:00:00.5Z",
                "2026-09-06T12:00:00+02", "1788696000"}) {
            assertEquals(String.valueOf(rejected), -1L,
                    ConsoleActivity.parseChildPolicyTime(rejected));
        }
    }

    @Test public void childEligibilityGateRejectsEveryNonReadyOrDeniedState() {
        assertTrue(ConsoleActivity.childEligibilityCanLaunch(
                true, "ready", "none", 1L));
        assertFalse(ConsoleActivity.childEligibilityCanLaunch(
                true, "ready", "session_in_use", 1L));
        assertFalse(ConsoleActivity.childEligibilityCanLaunch(
                true, "blocked", "none", 1L));
        assertFalse(ConsoleActivity.childEligibilityCanLaunch(
                true, "ready", "none", 0L));
        assertFalse(ConsoleActivity.childEligibilityCanLaunch(
                false, "ready", "none", 1L));
        assertFalse(ConsoleActivity.childEligibilityCanLaunch(
                true, "", "none", 1L));
        assertTrue(ConsoleActivity.childSessionCanContinue(
                true, "running", "none", 1L));
        assertTrue(ConsoleActivity.childSessionCanContinue(
                true, "launch_pending", "none", 1L));
        assertFalse(ConsoleActivity.childSessionCanContinue(
                true, "blocked", "none", 1L));
        assertFalse(ConsoleActivity.childSessionCanContinue(
                true, "running", "pairing_required", 1L));
        assertFalse(ConsoleActivity.childSessionCanContinue(
                true, "running", "none", 0L));
    }

    /** A child's switch refused by its time or schedule shows that reason, not a close failure. */
    @Test public void childSwitchRefusalKeepsTheChildsOwnMessage() {
        assertEquals(com.limelight.R.string.console_child_launch_outside_schedule,
                ConsoleActivity.childRefusalMessage("outside_schedule"));
        assertEquals(com.limelight.R.string.console_child_launch_daily_limit,
                ConsoleActivity.childRefusalMessage("daily_limit_reached"));
        assertEquals(com.limelight.R.string.console_child_launch_unavailable,
                ConsoleActivity.childRefusalMessage("child_not_playable"));
        assertEquals(0, ConsoleActivity.childRefusalMessage("retained_switch_process_unconfirmed"));
    }

    private static HostGatewayClient.IntegrationProfile childProfile(
            long remaining, long playable, String reason, String server, String windowEnd) {
        return new HostGatewayClient.IntegrationProfile("child", "Child",
                false, false, false, false, false, false, true, false,
                "active", "available", false, false, 0,
                "child", "parent", "child", "", true, 1L, 1L,
                remaining, playable, "", server, reason, windowEnd);
    }

    private static PlayniteDashboardItem item(String suffix) {
        String id = ("00000000" + suffix);
        id = id.substring(id.length() - 8) + "-0000-0000-0000-000000000000";
        return new PlayniteDashboardItem(new PlayniteLibraryGame(id, "Game " + suffix,
                true, false, 0, "", "", "", "Test"), null,
                PlayniteDashboardItem.MappingState.MAPPED);
    }

    private static String source(String path) throws Exception {
        return SourceText.module(path);
    }

    private static String between(String source, String start, String end) {
        int from = source.indexOf(start);
        int to = source.indexOf(end, from + start.length());
        assertTrue(from >= 0 && to > from);
        return source.substring(from, to);
    }
}
