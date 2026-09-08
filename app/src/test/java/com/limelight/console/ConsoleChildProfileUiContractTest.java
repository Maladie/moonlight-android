package com.limelight.console;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
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
        assertTrue(menu.contains("actions.add(wake)"));
        assertTrue(menu.contains("if (childProfile) {\n            actions.add(terminate);\n            actions.add(sleep);"));
        assertFalse(menu.contains("canSleep && !childProfile"));
        String start = between(activity, "private ChildLaunchBinding startChildSession(",
                "private void validateChildState(");
        assertTrue(start.contains("endChildBindingAndAwait(pending, cancelled)"));
        assertFalse(start.contains("new ChildLaunchFailure(\"child_session_ending\")"));
        String close = between(activity, "private void requestCloseHostStream(",
                "private void finishCloseHostStream(");
        assertTrue(close.contains("if (selectedProfileIsChild(host.uuid))"));
        assertTrue(close.contains("requestTerminateSession(host)"));
    }

    @Test public void childStartsOnlyAfterOrchestratorHasResolvedPreviousSession() throws Exception {
        String activity = source("src/main/java/com/limelight/console/ConsoleActivity.java");
        String preflight = between(activity, "@Override public HostLaunchPreflight.Result preflight(",
                "@Override public SessionOrchestrator.CloseResult closePreviousSession(");
        assertFalse(preflight.contains("startChildSession("));
        String launch = between(activity, "@Override public void launch(PlayIntent intent, NvApp app,",
                "private void launchStream(PlayIntent intent, NvApp app,");
        assertTrue(launch.contains("startChildSession(intent, cancelled)"));
        assertTrue(launch.contains("launchToken != childEligibilityLaunchToken"));
        assertTrue(launch.contains("endChildBinding(childBinding(intent), \"cancelled\")"));
    }

    @Test public void childConsoleCloseUsesBoundSessionAndBadgeAcceptsCurrentGame() throws Exception {
        String activity = source("src/main/java/com/limelight/console/ConsoleActivity.java");
        int start = activity.indexOf("private void requestTerminateSession(ComputerDetails host, String expectedProviderGameId)");
        int child = activity.indexOf("if (childProfile)", start);
        int end = activity.indexOf("endChildBindingAndAwait(childSession", child);
        int adult = activity.indexOf("} else {", child);
        int stop = activity.indexOf("stopActiveProviderGame(host, providerGameId", child);
        assertTrue(start >= 0 && child > start && end > child && adult > end && stop > adult);
        assertTrue(activity.contains("boolean runningSession = isFreshExactRunningManagedGame(host, item)\n"
                + "                || isFreshExactBridgeRunning(selectedHostUuid, host.uuid,"));
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
        assertTrue(activity.contains("profileSelector = profileQuickAction()"));
        assertTrue(activity.contains("new ProfileToolbarDrawable("));
        assertTrue(activity.contains("profileSelector.setTooltipText(name)"));

        String discord = between(activity, "private void refreshDiscordIndicator()",
                "private void applyDiscordIndicator(");
        assertTrue(discord.contains("!discordAllowedForSelectedProfile()"));
        assertTrue(discord.contains("button.setVisibility(View.GONE)"));
        assertTrue(discord.contains("button.setVisibility(View.VISIBLE)"));
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

    @Test public void childEditorShowsIdentityAndScheduleTogether() throws Exception {
        String editor = source("src/main/java/com/limelight/console/"
                + "ConsoleChildProfileEditor.java");
        String activity = source("src/main/java/com/limelight/console/ConsoleActivity.java");
        assertTrue(editor.contains("identityStep.setVisibility(VISIBLE)"));
        assertTrue(editor.contains("scheduleStep.setVisibility(VISIBLE)"));
        assertTrue(editor.contains("console_child_profile_step_schedule"));
        assertTrue(editor.contains("if (!editor.enabled.isChecked())"));
        assertTrue(editor.contains("input.setSelectAllOnFocus(true)"));
        assertTrue(editor.contains("hours == 24 && minutes != 0"));
        assertTrue(editor.contains("setAfterEditorFocus"));
        assertTrue(activity.contains("outerActions.add(editor)"));
        assertTrue(activity.contains("editor.setAfterEditorFocus(delete)"));
        assertFalse(activity.contains("sidePanel.addView(editor,"));
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

    private static HostGatewayClient.IntegrationProfile childProfile(
            long remaining, long playable, String reason, String server, String windowEnd) {
        return new HostGatewayClient.IntegrationProfile("child", "Child",
                false, false, false, false, false, false, false, true, false,
                "active", "available", false, false, 0,
                "child", "parent", "child", "", true, 1L, 1L,
                remaining, playable, "", server, reason, windowEnd);
    }

    private static PlayniteDashboardItem item(String suffix) {
        String id = ("00000000" + suffix);
        id = id.substring(id.length() - 8) + "-0000-0000-0000-000000000000";
        return new PlayniteDashboardItem(new PlayniteLibraryGame(id, "Game " + suffix,
                true, false, 0, "", "", "", "Test"), null, "",
                PlayniteDashboardItem.MappingState.MAPPED);
    }

    private static String source(String path) throws Exception {
        return new String(Files.readAllBytes(Paths.get(path)), StandardCharsets.UTF_8);
    }

    private static String between(String source, String start, String end) {
        int from = source.indexOf(start);
        int to = source.indexOf(end, from + start.length());
        assertTrue(from >= 0 && to > from);
        return source.substring(from, to);
    }
}
