package com.limelight.console;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class DiscordCommunityStateTest {
    @Test
    public void focusSelectionDoesNotNavigateAwayFromFeed() {
        DiscordCommunityState state = DiscordCommunityState.initial().select("discord.social.friend:42");
        assertEquals(DiscordCommunityState.Detail.FEED, state.detail);
        assertEquals("discord.social.friend:42", state.selectedId);
    }

    @Test
    public void destinationsOpenInsideTheSameCommunityStateAndBackUnwinds() {
        DiscordCommunityState state = DiscordCommunityState.initial().select("friend")
                .enter(DiscordCommunityState.Detail.FRIEND);
        assertEquals(DiscordCommunityState.Detail.FRIEND, state.detail);
        assertEquals(DiscordCommunityState.Detail.FEED, state.back().detail);
        assertEquals("friend", state.back().selectedId);
    }

    @Test
    public void directMessageReturnsToTheSameFriendDetail() {
        DiscordCommunityState friend = DiscordCommunityState.initial().select("discord.social.friend:42")
                .enter(DiscordCommunityState.Detail.FRIEND);
        DiscordCommunityState chat = friend.openDirectMessage(42, 3);
        assertEquals(DiscordCommunityState.Detail.DIRECT_MESSAGE, chat.detail);
        assertEquals(42, chat.directMessageRecipientId);
        assertEquals(3, chat.directMessageGeneration);
        assertEquals(DiscordCommunityState.Detail.FRIEND, chat.back().detail);
        assertEquals("discord.social.friend:42", chat.back().selectedId);
    }

    @Test
    public void directMessageBackHelperRecognizesSystemBackAndPlayStationCircle() {
        assertEquals(true, DiscordCommunityView.isDirectMessageBackKey(
                android.view.KeyEvent.KEYCODE_BACK));
        assertEquals(true, DiscordCommunityView.isDirectMessageBackKey(
                android.view.KeyEvent.KEYCODE_BUTTON_B));
        assertEquals(false, DiscordCommunityView.isDirectMessageBackKey(
                android.view.KeyEvent.KEYCODE_BUTTON_A));
    }

    @Test
    public void sameDirectMessageRecipientPreservesComposerStateAcrossRebinds() {
        assertEquals(true, DiscordCommunityView.shouldPreserveDirectComposer(42, 42));
        assertEquals(false, DiscordCommunityView.shouldPreserveDirectComposer(42, 43));
        assertEquals(false, DiscordCommunityView.shouldPreserveDirectComposer(0, 42));
    }

    @Test
    public void directComposerRebindUsesTheControllerDraftAsTheOnlyTextSource() {
        assertEquals("", DiscordCommunityView.directComposerDraftForRebind(""));
        assertEquals("draft", DiscordCommunityView.directComposerDraftForRebind("draft"));
    }

    @Test
    public void ownMessagesScrollHistoryButIncomingMessagesRespectReadingPosition() {
        assertTrue(DiscordCommunityView.shouldScrollDirectHistoryToBottom(false, true, false));
        assertFalse(DiscordCommunityView.shouldScrollDirectHistoryToBottom(false, true, true));
        assertTrue(DiscordCommunityView.shouldShowNewMessagePill(false, true, true));
        assertFalse(DiscordCommunityView.shouldShowNewMessagePill(false, true, false));
    }

    @Test
    public void triggerShortcutsFireOnlyOnThePressEdge() {
        assertTrue(DiscordCommunityView.shouldDispatchTriggerEdge(false, true));
        assertFalse(DiscordCommunityView.shouldDispatchTriggerEdge(true, true));
        assertFalse(DiscordCommunityView.shouldDispatchTriggerEdge(false, false));
    }

    @Test
    public void triangleIsAReservedEmbeddedKeyboardSpaceShortcut() {
        assertTrue(EmbeddedTvKeyboardView.isTriangleSpaceKey(
                android.view.KeyEvent.KEYCODE_BUTTON_Y));
        assertFalse(EmbeddedTvKeyboardView.isTriangleSpaceKey(
                android.view.KeyEvent.KEYCODE_BUTTON_X));
        assertTrue(EmbeddedTvKeyboardView.isEmbeddedKeyboardHandledKey(
                android.view.KeyEvent.KEYCODE_BUTTON_Y));
        assertTrue(EmbeddedTvKeyboardView.isEmbeddedKeyboardHandledKey(
                android.view.KeyEvent.KEYCODE_BUTTON_THUMBR));
        assertFalse(EmbeddedTvKeyboardView.isEmbeddedKeyboardHandledKey(
                android.view.KeyEvent.KEYCODE_BUTTON_B));
    }

    @Test
    public void changingTabKeepsTheStableSelectedIdentity() {
        DiscordCommunityState state = DiscordCommunityState.initial().select("discord.community.channel:1")
                .tab(DiscordCommunityState.Tab.SERVERS);
        assertEquals(DiscordCommunityState.Tab.SERVERS, state.tab);
        assertEquals("discord.community.channel:1", state.selectedId);
        assertEquals(DiscordCommunityState.Detail.FEED, state.detail);
    }

    @Test
    public void channelBackReturnsToItsServerThenTheOriginalFeedDestination() {
        DiscordCommunityState server = DiscordCommunityState.initial().select("server:S")
                .openServer("server:S");
        DiscordCommunityState channel = server.select("channel:C").openChannel("channel:C");
        DiscordCommunityState channelsAgain = channel.back();
        assertEquals(DiscordCommunityState.Detail.SERVER_CHANNELS, channelsAgain.detail);
        assertEquals("channel:C", channelsAgain.selectedId);
        assertEquals("server:S", channelsAgain.parentDestinationId);
        DiscordCommunityState feed = channelsAgain.back();
        assertEquals(DiscordCommunityState.Detail.FEED, feed.detail);
        assertEquals("server:S", feed.selectedId);
    }

    @Test
    public void tabNavigationClampsDpadAndShoulderButtons() {
        assertEquals(0, DiscordCommunityState.adjacentTabIndex(0,
                android.view.KeyEvent.KEYCODE_BUTTON_L1, 3));
        assertEquals(1, DiscordCommunityState.adjacentTabIndex(0,
                android.view.KeyEvent.KEYCODE_DPAD_RIGHT, 3));
        assertEquals(1, DiscordCommunityState.adjacentTabIndex(0,
                android.view.KeyEvent.KEYCODE_BUTTON_R1, 3));
        assertEquals(2, DiscordCommunityState.adjacentTabIndex(2,
                android.view.KeyEvent.KEYCODE_BUTTON_R1, 3));
    }

    @Test
    public void optionsReturnsToTheInlineDetailThatOpenedIt() {
        DiscordCommunityState channel = DiscordCommunityState.initial().select("channel:C")
                .openServer("server:S").select("channel:C").openChannel("channel:C");
        DiscordCommunityState options = channel.openOptions();
        assertEquals(DiscordCommunityState.Detail.OPTIONS, options.detail);
        assertEquals(DiscordCommunityState.Detail.CHANNEL, options.back().detail);
        assertEquals("channel:C", options.back().selectedId);
    }

    @Test
    public void audioAndSocialOptionsReturnToInlineOptionsInsteadOfLegacyPanels() {
        DiscordCommunityState options = DiscordCommunityState.initial().select("server:S").openOptions();
        assertEquals(DiscordCommunityState.Detail.OPTIONS, options.openAudio().back().detail);
        assertEquals(DiscordCommunityState.Detail.OPTIONS, options.openSocial().back().detail);
        assertEquals("server:S", options.openAudio().back().selectedId);
    }

    @Test
    public void accountDownEntersAvailableInlineOptionDetailOnly() {
        assertEquals(true, DiscordCommunityView.shouldEnterDetailFromAccount(
                DiscordCommunityState.Detail.OPTIONS, 1));
        assertEquals(true, DiscordCommunityView.shouldEnterDetailFromAccount(
                DiscordCommunityState.Detail.AUDIO, 1));
        assertEquals(false, DiscordCommunityView.shouldEnterDetailFromAccount(
                DiscordCommunityState.Detail.FEED, 1));
        assertEquals(false, DiscordCommunityView.shouldEnterDetailFromAccount(
                DiscordCommunityState.Detail.SOCIAL, 0));
    }

    @Test
    public void dualSenseHatDirectionsAreEdgeSafeAndUseVerticalTieBreak() {
        assertEquals(android.view.KeyEvent.KEYCODE_DPAD_RIGHT,
                DiscordCommunityView.motionDirection(1f, 0f, .45f,
                        android.view.KeyEvent.KEYCODE_UNKNOWN));
        assertEquals(android.view.KeyEvent.KEYCODE_DPAD_DOWN,
                DiscordCommunityView.motionDirection(.8f, .8f, .45f,
                        android.view.KeyEvent.KEYCODE_DPAD_RIGHT));
        assertEquals(android.view.KeyEvent.KEYCODE_UNKNOWN,
                DiscordCommunityView.motionDirection(.2f, .1f, .45f,
                        android.view.KeyEvent.KEYCODE_DPAD_RIGHT));
    }

    @Test
    public void communityMotionOnlyAcceptsJoystickOrGamepadSources() {
        assertEquals(true, DiscordCommunityView.isCommunityGamepadSource(
                android.view.InputDevice.SOURCE_JOYSTICK));
        assertEquals(true, DiscordCommunityView.isCommunityGamepadSource(
                android.view.InputDevice.SOURCE_GAMEPAD));
        assertEquals(false, DiscordCommunityView.isCommunityGamepadSource(
                android.view.InputDevice.SOURCE_TOUCHSCREEN));
        assertEquals(false, DiscordCommunityView.shouldUseFallbackAxes(true));
        assertEquals(true, DiscordCommunityView.shouldUseFallbackAxes(false));
    }

    @Test
    public void heldHatDirectionDoesNotRepeatAndNeutralRearmsTheEdge() {
        int right = android.view.KeyEvent.KEYCODE_DPAD_RIGHT;
        assertEquals(true, DiscordCommunityView.shouldDispatchMotionEdge(
                android.view.KeyEvent.KEYCODE_UNKNOWN, right));
        assertEquals(false, DiscordCommunityView.shouldDispatchMotionEdge(right, right));
        assertEquals(true, DiscordCommunityView.shouldDispatchMotionEdge(
                android.view.KeyEvent.KEYCODE_UNKNOWN,
                android.view.KeyEvent.KEYCODE_DPAD_LEFT));
    }

    @Test
    public void rightStickHistoryScrollIsThrottledWithoutTouchOrHatRouting() {
        assertTrue(DiscordCommunityView.shouldDispatchHistoryScroll(Long.MIN_VALUE, 100L));
        assertFalse(DiscordCommunityView.shouldDispatchHistoryScroll(100L, 209L));
        assertTrue(DiscordCommunityView.shouldDispatchHistoryScroll(100L, 210L));
    }

    @Test
    public void r3ChoosesTheMediaRowNearestTheVisibleChatCenter() {
        assertEquals(1, DiscordCommunityView.nearestVisibleMediaRow(100, 300,
                new int[]{20, 170, 340}, new int[]{80, 220, 390}));
        assertEquals(-1, DiscordCommunityView.nearestVisibleMediaRow(100, 300,
                new int[]{20, 340}, new int[]{80, 390}));
    }
}
