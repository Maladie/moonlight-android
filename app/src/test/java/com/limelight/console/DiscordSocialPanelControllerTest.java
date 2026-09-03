package com.limelight.console;

import org.junit.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.limelight.discord.DiscordSocialClient;

public class DiscordSocialPanelControllerTest {
    @Test
    public void onlyNewSnapshotRevisionsRequireARender() {
        assertTrue(DiscordSocialPanelController.shouldRender(4L, 5L));
        assertFalse(DiscordSocialPanelController.shouldRender(5L, 5L));
    }

    @Test
    public void friendFocusUsesStableDiscordUserIdentity() {
        assertEquals("discord.social.friend:1234",
                DiscordSocialPanelController.friendFocusTag("1234"));
    }

    @Test
    public void openingAFriendImmediatelyEntersItsFirstDetailAction() {
        assertTrue(DiscordSocialPanelController.shouldFocusDetailAfterOpen(
                DiscordCommunityPresentation.Kind.FRIEND));
        assertFalse(DiscordSocialPanelController.shouldFocusDetailAfterOpen(
                DiscordCommunityPresentation.Kind.CHANNEL));
    }

    @Test
    public void tabsClampLeftAndRightFocusTargets() {
        assertEquals(0, DiscordSocialPanelController.adjacentTabIndex(0,
                android.view.KeyEvent.KEYCODE_DPAD_LEFT, 3));
        assertEquals(1, DiscordSocialPanelController.adjacentTabIndex(0,
                android.view.KeyEvent.KEYCODE_DPAD_RIGHT, 3));
        assertEquals(2, DiscordSocialPanelController.adjacentTabIndex(2,
                android.view.KeyEvent.KEYCODE_DPAD_RIGHT, 3));
    }

    @Test
    public void modernGridGroupsTilesIntoTwoColumnRows() {
        assertEquals(0, DiscordPanelViews.rowCount(0));
        assertEquals(1, DiscordPanelViews.rowCount(1));
        assertEquals(1, DiscordPanelViews.rowCount(2));
        assertEquals(2, DiscordPanelViews.rowCount(3));
    }

    @Test
    public void gridKeepsVerticalFocusInsideItsOwnRows() {
        assertEquals(-1, DiscordPanelViews.previousRowIndex(0));
        assertEquals(-1, DiscordPanelViews.previousRowIndex(1));
        assertEquals(0, DiscordPanelViews.previousRowIndex(2));
        assertEquals(3, DiscordPanelViews.nextRowIndex(1, 4));
        assertEquals(-1, DiscordPanelViews.nextRowIndex(2, 3));
    }

    @Test
    public void offlineFriendsStayCollapsedUntilRequested() {
        assertFalse(DiscordPanelViews.offlineVisible(false));
        assertTrue(DiscordPanelViews.offlineVisible(true));
    }

    @Test
    public void selectedGuildIsFirstInTheCollapsedFourAndToggleIsLocal() {
        assertEquals(Arrays.asList("d", "a", "b", "c"), DiscordPanelController.visibleGuildIds(
                Arrays.asList("a", "b", "c", "d", "e"), "d", 4));
        assertEquals(Arrays.asList("a", "b", "c", "d"), DiscordPanelController.visibleGuildIds(
                Arrays.asList("a", "b", "c", "d", "e"), "missing", 4));
        assertTrue(DiscordPanelController.toggleGuildVisibility(false));
        assertFalse(DiscordPanelController.toggleGuildVisibility(true));
    }

    @Test
    public void summaryProjectionPrioritizesConnectionAndAuthorizationFlags() {
        assertEquals(DiscordSocialPanelController.SummaryState.CONNECTED,
                DiscordSocialPanelController.summaryState(true, true, "anything"));
        assertEquals(DiscordSocialPanelController.SummaryState.AUTHORIZATION_REQUIRED,
                DiscordSocialPanelController.summaryState(false, true, "Not connected"));
        assertEquals(DiscordSocialPanelController.SummaryState.UNAVAILABLE,
                DiscordSocialPanelController.summaryState(false, false, "Unable to restore"));
        assertEquals(DiscordSocialPanelController.SummaryState.CONNECTING,
                DiscordSocialPanelController.summaryState(false, false, "Authorizing"));
    }

    @Test
    public void discordGlyphColorReflectsBridgeRpcAndVoiceState() {
        assertEquals(0xFFFFB74D, DiscordPanelController.discordIndicatorColor(
                false, false, false, false));
        assertEquals(0xFFFF6B6B, DiscordPanelController.discordIndicatorColor(
                true, false, false, false));
        assertEquals(0xFFFF6B6B, DiscordPanelController.discordIndicatorColor(
                true, true, false, false));
        assertEquals(0xFF4DA3FF, DiscordPanelController.discordIndicatorColor(
                true, true, true, false));
        assertEquals(0xFF36B96C, DiscordPanelController.discordIndicatorColor(
                true, true, true, true));
    }

    @Test
    public void joinedVoiceMustMatchTheRequestedChannel() {
        HostGatewayClient.DiscordVoice matching = new HostGatewayClient.DiscordVoice(
                true, "12345", "Lobby", "67890", false, false, 1);
        assertTrue(DiscordPanelController.matchesJoinedVoice("12345", matching));
        assertFalse(DiscordPanelController.matchesJoinedVoice("99999", matching));
        assertFalse(DiscordPanelController.matchesJoinedVoice("12345",
                new HostGatewayClient.DiscordVoice(false, "12345", "Lobby", "67890",
                        false, false, 0)));
    }

    @Test
    public void joinDiagnosticsAreSingleLineBoundedAndDoNotExposeSecrets() {
        String raw = "https://host.invalid/api\nBearer very-secret-token\t"
                + new String(new char[200]).replace('\0', 'x');
        String safe = DiscordPanelController.sanitizeCommunityJoinMessage(raw);
        assertFalse(safe.contains("host.invalid"));
        assertFalse(safe.contains("very-secret-token"));
        assertFalse(safe.contains("\n"));
        assertTrue(safe.length() <= 160);
        assertEquals("HTTP 409: denied", DiscordPanelController.safeCommunityJoinError(
                new HostGatewayClient.GatewayException("denied", 409)));
    }

    @Test
    public void confirmedVoiceDisablesAnotherJoinForThatDestination() {
        HostGatewayClient.DiscordVoice voice = new HostGatewayClient.DiscordVoice(
                true, "12345", "Lobby", "67890", false, false, 1);
        assertTrue(DiscordCommunityView.isConnectedChannel("discord.community.channel:12345", voice));
        assertFalse(DiscordCommunityView.isConnectedChannel("discord.community.channel:99999", voice));
    }

    @Test
    public void successfulLeaveRestoresTheRejoinActionLane() {
        HostGatewayClient.DiscordVoice left = new HostGatewayClient.DiscordVoice(
                false, "", "", "", false, false, 0);
        assertTrue(DiscordSocialPanelController.shouldFocusDetailAfterVoiceAction(
                DiscordPanelController.CommunityVoiceAction.LEAVE, left));
        assertFalse(DiscordSocialPanelController.shouldFocusDetailAfterVoiceAction(
                DiscordPanelController.CommunityVoiceAction.MUTE, left));
    }

    @Test
    public void optionsKeepExistingActionsDuringRefreshButRejectDuplicateRequests() {
        assertTrue(DiscordCommunityView.optionsActionsVisible(true));
        assertFalse(DiscordCommunityView.optionsActionsVisible(false));
        assertTrue(DiscordSocialPanelController.canStartOptionsRequest(false));
        assertFalse(DiscordSocialPanelController.canStartOptionsRequest(true));
    }

    @Test
    public void connectedVoicePromotionOnlyClaimsAnUntouchedFeed() {
        assertTrue(DiscordSocialPanelController.shouldPromoteConnectedVoice(
                DiscordCommunityState.Detail.FEED, "", "discord.community.channel:one"));
        assertTrue(DiscordSocialPanelController.shouldPromoteConnectedVoice(
                DiscordCommunityState.Detail.FEED, "discord.community.channel:one",
                "discord.community.channel:one"));
        assertFalse(DiscordSocialPanelController.shouldPromoteConnectedVoice(
                DiscordCommunityState.Detail.FEED, "discord.community.channel:two",
                "discord.community.channel:one"));
        assertFalse(DiscordSocialPanelController.shouldPromoteConnectedVoice(
                DiscordCommunityState.Detail.OPTIONS, "", "discord.community.channel:one"));
    }

    @Test
    public void actionToneKeepsSemanticColorWhenFocusedOrBusy() {
        assertEquals(0xFF166D9B, DiscordCommunityView.actionBackground(
                DiscordCommunityView.ActionTone.POSITIVE_JOIN, false));
        assertEquals(0xFF1998D0, DiscordCommunityView.actionBackground(
                DiscordCommunityView.ActionTone.POSITIVE_JOIN, true));
        assertEquals(0xFF963B47, DiscordCommunityView.actionBackground(
                DiscordCommunityView.ActionTone.DESTRUCTIVE_LEAVE, false));
        assertEquals(0xFFC34B58, DiscordCommunityView.actionBackground(
                DiscordCommunityView.ActionTone.DESTRUCTIVE_LEAVE, true));
    }

    @Test
    public void onlyRecentRowsOwnTheRecentScrollerAndTabsResetIt() {
        assertFalse(DiscordCommunityView.focusOwnerIsRecent(true));
        assertTrue(DiscordCommunityView.focusOwnerIsRecent(false));
        assertFalse(DiscordCommunityView.shouldResetRecentScroll(null,
                DiscordCommunityState.Tab.TOGETHER));
        assertTrue(DiscordCommunityView.shouldResetRecentScroll(DiscordCommunityState.Tab.TOGETHER,
                DiscordCommunityState.Tab.FRIENDS));
    }

    @Test
    public void releasedCommunityLifecycleDoesNotDrainOnTheNextWatcherTick() throws Exception {
        DiscordSocialPanelController controller = new DiscordSocialPanelController(null, null, null, null);
        DiscordSocialClient.MessageEventLease controllerLease =
                DiscordSocialClient.tryAcquireMessageEventLease();
        assertNotNull(controllerLease);
        Field leaseField = DiscordSocialPanelController.class.getDeclaredField("messageEventLease");
        leaseField.setAccessible(true);
        leaseField.set(controller, controllerLease);
        try {
            controller.onActivityPaused();
            assertNull(leaseField.get(controller));

            Method consume = DiscordSocialPanelController.class.getDeclaredMethod(
                    "consumeDirectMessageEvents", DiscordSocialClient.Snapshot.class);
            consume.setAccessible(true);
            assertFalse((Boolean) consume.invoke(controller, DiscordSocialClient.getSnapshot()));

            DiscordSocialClient.MessageEventLease nextOwner =
                    DiscordSocialClient.tryAcquireMessageEventLease();
            assertNotNull(nextOwner);
            DiscordSocialClient.releaseMessageEventLease(nextOwner);
        } finally {
            DiscordSocialClient.releaseMessageEventLease(controllerLease);
        }
    }

    @Test
    public void pauseCancelsKeyboardHoldOnlyWhenTheCommunityViewExists() {
        assertTrue(DiscordSocialPanelController.shouldCancelDirectKeyboardHoldOnPause(true));
        assertFalse(DiscordSocialPanelController.shouldCancelDirectKeyboardHoldOnPause(false));
    }

}
