package com.limelight.ui.overlay;

import android.view.KeyEvent;

import com.limelight.R;
import com.limelight.ui.ControllerGlyphs;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class OverlayMenuViewTest {
    @Test
    public void endGameIsASeparateConditionalActionFromQuitSession() throws Exception {
        Path source = Paths.get("src/main/java/com/limelight/ui/overlay/OverlayMenuView.java");
        if (!Files.exists(source)) source = Paths.get(
                "app/src/main/java/com/limelight/ui/overlay/OverlayMenuView.java");
        String text = new String(Files.readAllBytes(source), StandardCharsets.UTF_8);
        String menu = text.substring(text.indexOf("public void buildMenu()"),
                text.indexOf("private void addVerticalButton("));
        String activate = text.substring(text.indexOf("private void activateSelected()"),
                text.indexOf("private void activateDiscordMute()"));

        assertTrue(menu.contains("if (endGameAvailable)"));
        assertTrue(menu.contains("R.string.overlay_menu_end_game"));
        assertTrue(menu.contains("ACTION_END_GAME"));
        assertTrue(menu.contains("R.string.overlay_menu_quit_session"));
        assertTrue(activate.contains("actionListener.onEndGame()"));
        assertTrue(activate.contains("actionListener.onQuitSession()"));
    }
    @Test
    public void socialVisibilityAndActionAreIndependentOfHostVoice() {
        assertTrue(OverlayMenuView.shouldShowDiscordCard(false, true));
        assertTrue(OverlayMenuView.shouldShowDiscordCard(true, false));
        assertFalse(OverlayMenuView.shouldShowDiscordCard(false, false));
        assertEquals(R.string.overlay_discord_social_connect_in_menu,
                OverlayMenuView.socialActionLabel(false, false));
        assertEquals(R.string.overlay_discord_social_friends,
                OverlayMenuView.socialActionLabel(true, false));
        assertEquals(R.string.overlay_discord_social_hide_friends,
                OverlayMenuView.socialActionLabel(true, true));
    }

    @Test
    public void communityPeopleUsePresenceDotsActivityAndBackwardCompatibleAvatarFallback() {
        assertTrue(OverlayMenuView.communityFriendShowsPresenceDot(
                OverlayMenuView.FriendPresence.PLAYING));
        assertTrue(OverlayMenuView.communityFriendShowsPresenceDot(
                OverlayMenuView.FriendPresence.ONLINE));
        assertFalse(OverlayMenuView.communityFriendShowsPresenceDot(
                OverlayMenuView.FriendPresence.OFFLINE));

        OverlayMenuView.CommunityFriend online = new OverlayMenuView.CommunityFriend(
                "1", "Friend", "Playing MoonWaker", "https://cdn.discordapp.com/a.png",
                OverlayMenuView.FriendPresence.ONLINE, true);
        OverlayMenuView.CommunityFriend idle = new OverlayMenuView.CommunityFriend(
                "2", "Friend", "", "", OverlayMenuView.FriendPresence.ONLINE);
        assertEquals("Playing MoonWaker", OverlayMenuView.communityFriendDetails(online));
        assertEquals("", OverlayMenuView.communityFriendDetails(idle));

        OverlayMenuView.VoiceParticipant legacy = new OverlayMenuView.VoiceParticipant(
                "3", "Participant", 100, false, false, false);
        OverlayMenuView.VoiceParticipant projected = new OverlayMenuView.VoiceParticipant(
                "3", "Participant", 100, false, true, false,
                "https://cdn.discordapp.com/avatar.png");
        assertEquals("", legacy.avatarUrl);
        assertEquals("https://cdn.discordapp.com/avatar.png", projected.avatarUrl);
        assertTrue(projected.speaking);
    }

    @Test
    public void menuAndCommunityAreMutuallyExclusiveAndBackReturnsToMenu() {
        assertFalse(OverlayMenuView.communityVisible(OverlayMenuView.OverlayMode.MENU, true));
        assertTrue(OverlayMenuView.communityVisible(OverlayMenuView.OverlayMode.COMMUNITY, true));
        assertFalse(OverlayMenuView.communityVisible(OverlayMenuView.OverlayMode.COMMUNITY, false));
        assertEquals(OverlayMenuView.OverlayMode.MENU,
                OverlayMenuView.backMode(OverlayMenuView.OverlayMode.COMMUNITY));
    }

    @Test
    public void openingCommunityNotifiesOnceForTheModeTransitionNotForModelRenders() {
        assertTrue(OverlayMenuView.communityOpenNotifies(OverlayMenuView.OverlayMode.MENU));
        assertFalse(OverlayMenuView.communityOpenNotifies(OverlayMenuView.OverlayMode.COMMUNITY));
    }

    @Test
    public void shoulderNavigationIsClampedToCommunityRail() {
        assertEquals(0, OverlayMenuView.adjacentCommunitySection(0,
                KeyEvent.KEYCODE_BUTTON_L1, 3));
        assertEquals(1, OverlayMenuView.adjacentCommunitySection(0,
                KeyEvent.KEYCODE_BUTTON_R1, 3));
        assertEquals(2, OverlayMenuView.adjacentCommunitySection(2,
                KeyEvent.KEYCODE_BUTTON_R1, 3));
    }

    @Test
    public void quickCommunityPanelUsesCompactSafeDimensionsAndThreeRailDestinations() {
        assertEquals(520, OverlayMenuView.communityQuickWidthDp());
        assertEquals(360, OverlayMenuView.communityQuickHeightDp());
        assertEquals(600, OverlayMenuView.communityChatWidthDp());
        assertEquals(460, OverlayMenuView.communityChatHeightDp());
        assertEquals(3, OverlayMenuView.communityRailIconCount());
        assertEquals(2, OverlayMenuView.communityActionMaxRows());
        assertFalse(OverlayMenuView.communityContentUsesSingleScrollOwner());
        assertTrue(OverlayMenuView.communityContentUsesFocusableRows());
    }

    @Test
    public void communityHatUsesRailDirectionsAndNeutralCanRearm() {
        assertEquals(KeyEvent.KEYCODE_DPAD_DOWN,
                OverlayMenuView.communityMotionDirection(0f, 1f, .45f));
        assertEquals(KeyEvent.KEYCODE_DPAD_UP,
                OverlayMenuView.communityMotionDirection(0f, -1f, .45f));
        assertEquals(KeyEvent.KEYCODE_DPAD_RIGHT,
                OverlayMenuView.communityMotionDirection(1f, 0f, .45f));
        assertEquals(KeyEvent.KEYCODE_UNKNOWN,
                OverlayMenuView.communityMotionDirection(.1f, 0f, .45f));
        assertEquals(1, OverlayMenuView.nextCommunityRailIndex(0, 1, 3));
        assertEquals(2, OverlayMenuView.nextCommunityRailIndex(1, 1, 3));
    }

    @Test
    public void onlyTogetherShowsVoiceActionsAndConnectedVoiceHidesRejoin() {
        assertTrue(OverlayMenuView.communityVoiceActionsVisible(0, true));
        assertFalse(OverlayMenuView.communityVoiceActionsVisible(1, true));
        assertFalse(OverlayMenuView.communityVoiceActionsVisible(2, true));
        assertFalse(OverlayMenuView.communityVoiceActionsVisible(0, false));
        assertFalse(OverlayMenuView.communityRejoinVisible(true, true));
        assertTrue(OverlayMenuView.communityRejoinVisible(false, true));
        assertFalse(OverlayMenuView.communityRejoinVisible(false, false));
    }

    @Test
    public void communityActionStateOverridesStaleLegacyVoiceOnlyInCommunity() {
        OverlayMenuView.CommunityModel disconnected = communityModel(null);
        OverlayMenuView.DiscordActionVoiceState community = OverlayMenuView.discordActionVoiceState(
                OverlayMenuView.OverlayMode.COMMUNITY, disconnected, true, true, true);
        assertFalse(community.connected);
        assertFalse(community.muted);
        assertFalse(community.deafened);

        OverlayMenuView.VoiceSummary projectedVoice = new OverlayMenuView.VoiceSummary(
                true, "Guild", "Voice", true, true, Collections.emptyList());
        OverlayMenuView.DiscordActionVoiceState projected = OverlayMenuView.discordActionVoiceState(
                OverlayMenuView.OverlayMode.COMMUNITY, communityModel(projectedVoice), false, false, false);
        assertTrue(projected.connected);
        assertTrue(projected.muted);
        assertTrue(projected.deafened);

        OverlayMenuView.DiscordActionVoiceState legacy = OverlayMenuView.discordActionVoiceState(
                OverlayMenuView.OverlayMode.MENU, communityModel(projectedVoice), false, false, false);
        assertFalse(legacy.connected);
        assertFalse(legacy.muted);
        assertFalse(legacy.deafened);
    }

    @Test
    public void discordDockGlassKeepsOpacityOnRootAndTranslucencyInBackground() throws Exception {
        String layout = readProjectFile("src/main/res/layout/activity_game.xml",
                "app/src/main/res/layout/activity_game.xml");
        String drawable = readProjectFile("src/main/res/drawable/discord_dock_glass.xml",
                "app/src/main/res/drawable/discord_dock_glass.xml");

        assertTrue(layout.contains("android:background=\"@drawable/discord_dock_glass\""));
        assertTrue(layout.contains("android:alpha=\"1\""));
        assertTrue(drawable.contains("android:startColor=\"#C0181C27\""));
        assertTrue(drawable.contains("android:endColor=\"#B812151D\""));
        assertTrue(drawable.contains("android:radius=\"14dp\""));
        assertTrue(drawable.contains("android:width=\"1dp\""));
        assertTrue(drawable.contains("android:color=\"#38FFFFFF\""));
    }

    @Test
    public void communityActionNavigationMatchesTwoColumnRows() {
        assertEquals(1, OverlayMenuView.communityActionTarget(0,
                KeyEvent.KEYCODE_DPAD_RIGHT, 3));
        assertEquals(-1, OverlayMenuView.communityActionTarget(0,
                KeyEvent.KEYCODE_DPAD_LEFT, 3));
        assertEquals(0, OverlayMenuView.communityActionTarget(1,
                KeyEvent.KEYCODE_DPAD_LEFT, 3));
        assertEquals(2, OverlayMenuView.communityActionTarget(0,
                KeyEvent.KEYCODE_DPAD_DOWN, 3));
        assertEquals(2, OverlayMenuView.communityActionTarget(1,
                KeyEvent.KEYCODE_DPAD_DOWN, 3));
        assertEquals(1, OverlayMenuView.communityActionTarget(1,
                KeyEvent.KEYCODE_DPAD_UP, 3));
        assertEquals(0, OverlayMenuView.communityActionTarget(2,
                KeyEvent.KEYCODE_DPAD_UP, 3));
    }

    @Test
    public void communityContentSitsBetweenRailAndActionsAndScrollsWithinBounds() {
        assertEquals(OverlayMenuView.COMMUNITY_REGION_CONTENT,
                OverlayMenuView.communityRegionTarget(OverlayMenuView.COMMUNITY_REGION_RAIL,
                        KeyEvent.KEYCODE_DPAD_RIGHT, false));
        assertEquals(OverlayMenuView.COMMUNITY_REGION_RAIL,
                OverlayMenuView.communityRegionTarget(OverlayMenuView.COMMUNITY_REGION_CONTENT,
                        KeyEvent.KEYCODE_DPAD_LEFT, false));
        assertEquals(OverlayMenuView.COMMUNITY_REGION_CONTENT,
                OverlayMenuView.communityRegionTarget(OverlayMenuView.COMMUNITY_REGION_ACTION,
                        KeyEvent.KEYCODE_DPAD_LEFT, true));
        assertEquals(OverlayMenuView.COMMUNITY_REGION_CONTENT,
                OverlayMenuView.communityRegionTarget(OverlayMenuView.COMMUNITY_REGION_CONTENT,
                        KeyEvent.KEYCODE_DPAD_RIGHT, false));
        assertEquals(OverlayMenuView.COMMUNITY_REGION_ACTION,
                OverlayMenuView.communityRegionTarget(OverlayMenuView.COMMUNITY_REGION_CONTENT,
                        KeyEvent.KEYCODE_DPAD_RIGHT, true));
        assertEquals(0, OverlayMenuView.communityContentScrollTarget(20, 100, -84));
        assertEquals(100, OverlayMenuView.communityContentScrollTarget(70, 100, 84));
        assertEquals(40, OverlayMenuView.communityContentScrollTarget(0, 40, 40));
    }

    @Test
    public void railRightSkipsEmptyListToAvailableActions() {
        assertEquals(OverlayMenuView.COMMUNITY_REGION_CONTENT,
                OverlayMenuView.communityRailRightRegion(true, true));
        assertEquals(OverlayMenuView.COMMUNITY_REGION_ACTION,
                OverlayMenuView.communityRailRightRegion(false, true));
        assertEquals(OverlayMenuView.COMMUNITY_REGION_RAIL,
                OverlayMenuView.communityRailRightRegion(false, false));
    }

    @Test
    public void chatMediaCtaMustIntersectViewportAndPrefersLowerVisibleItem() {
        assertEquals(1, OverlayMenuView.nearestVisibleCommunityMediaIndex(100, 100,
                new int[]{0, 140, 210}, new int[]{40, 170, 240}, 3));
        assertEquals(-1, OverlayMenuView.nearestVisibleCommunityMediaIndex(100, 80,
                new int[]{0, 200}, new int[]{40, 240}, 2));
    }

    @Test
    public void guildBackNotifiesOnlyForGuildChannelSubmode() {
        assertTrue(OverlayMenuView.communityBackNotifiesChannels(
                OverlayMenuView.CommunitySubmode.GUILD_CHANNELS));
        assertFalse(OverlayMenuView.communityBackNotifiesChannels(
                OverlayMenuView.CommunitySubmode.ROOT));
        assertFalse(OverlayMenuView.communityBackNotifiesChannels(
                OverlayMenuView.CommunitySubmode.FRIEND_CHAT));
    }

    @Test
    public void lifecycleExitOnlyAppliesToAnOpenCommunityChat() {
        assertTrue(OverlayMenuView.shouldExitCommunityChatForPause(
                OverlayMenuView.OverlayMode.COMMUNITY,
                OverlayMenuView.CommunitySubmode.FRIEND_CHAT));
        assertFalse(OverlayMenuView.shouldExitCommunityChatForPause(
                OverlayMenuView.OverlayMode.COMMUNITY,
                OverlayMenuView.CommunitySubmode.ROOT));
        assertFalse(OverlayMenuView.shouldExitCommunityChatForPause(
                OverlayMenuView.OverlayMode.MENU,
                OverlayMenuView.CommunitySubmode.FRIEND_CHAT));
    }

    @Test
    public void communityRowsKeepStableSelectionAndChatPanelClampsToScreen() {
        assertEquals(1, OverlayMenuView.communityStableIndex(
                java.util.Arrays.asList("a", "b", "c"), "b"));
        assertEquals(0, OverlayMenuView.communityStableIndex(
                java.util.Arrays.asList("a", "b"), "gone"));
        assertEquals(-1, OverlayMenuView.communityStableIndex(java.util.Collections.<String>emptyList(), "a"));
        assertEquals(600, OverlayMenuView.communityPanelDimension(600, 0));
        assertEquals(560, OverlayMenuView.communityPanelDimension(600, 560));
        assertEquals(460, OverlayMenuView.communityPanelDimension(460, 900));
    }

    @Test
    public void communityRightStickUsesModernAxisThenDualSenseFallback() {
        assertEquals(android.view.MotionEvent.AXIS_RY,
                OverlayMenuView.communityRightStickVerticalAxis(true, true, true, true));
        assertEquals(android.view.MotionEvent.AXIS_RZ,
                OverlayMenuView.communityRightStickVerticalAxis(false, false, true, true));
        assertEquals(-1, OverlayMenuView.communityRightStickVerticalAxis(false, false, true, false));
    }

    @Test
    public void communityFriendCarriesUnreadStateWithoutChangingLegacyConstruction() {
        assertFalse(new OverlayMenuView.CommunityFriend("1", "One", "", "",
                OverlayMenuView.FriendPresence.ONLINE).unread);
        assertTrue(new OverlayMenuView.CommunityFriend("2", "Two", "", "",
                OverlayMenuView.FriendPresence.PLAYING, true).unread);
    }

    @Test
    public void postedCommunityFocusRestoreRejectsStaleModelNavigationAndScope() {
        assertTrue(OverlayMenuView.communityRestoreStillCurrent(4, 4, 20, 20,
                OverlayMenuView.CommunitySection.FRIENDS, OverlayMenuView.CommunitySection.FRIENDS,
                OverlayMenuView.CommunitySubmode.ROOT, OverlayMenuView.CommunitySubmode.ROOT, 8, 8));
        assertFalse(OverlayMenuView.communityRestoreStillCurrent(4, 5, 20, 20,
                OverlayMenuView.CommunitySection.FRIENDS, OverlayMenuView.CommunitySection.FRIENDS,
                OverlayMenuView.CommunitySubmode.ROOT, OverlayMenuView.CommunitySubmode.ROOT, 8, 8));
        assertFalse(OverlayMenuView.communityRestoreStillCurrent(4, 4, 20, 21,
                OverlayMenuView.CommunitySection.FRIENDS, OverlayMenuView.CommunitySection.FRIENDS,
                OverlayMenuView.CommunitySubmode.ROOT, OverlayMenuView.CommunitySubmode.ROOT, 8, 8));
        assertFalse(OverlayMenuView.communityRestoreStillCurrent(4, 4, 20, 20,
                OverlayMenuView.CommunitySection.FRIENDS, OverlayMenuView.CommunitySection.FRIENDS,
                OverlayMenuView.CommunitySubmode.ROOT, OverlayMenuView.CommunitySubmode.ROOT, 8, 9));
        assertFalse(OverlayMenuView.communityRestoreStillCurrent(4, 4, 20, 20,
                OverlayMenuView.CommunitySection.CHANNELS, OverlayMenuView.CommunitySection.FRIENDS,
                OverlayMenuView.CommunitySubmode.GUILD_CHANNELS,
                OverlayMenuView.CommunitySubmode.ROOT, 8, 9));
    }

    @Test
    public void stableIdsCoverEveryCommunityRowScope() {
        assertEquals(1, OverlayMenuView.communityStableIndex(java.util.Arrays.asList(
                "voice:1", "voice:2"), "voice:2"));
        assertEquals(1, OverlayMenuView.communityStableIndex(java.util.Arrays.asList(
                "friend:1", "friend:2"), "friend:2"));
        assertEquals(2, OverlayMenuView.communityStableIndex(java.util.Arrays.asList(
                "favorite-channel:7", "recent-channel:7", "guild:3"), "guild:3"));
        assertEquals(1, OverlayMenuView.communityStableIndex(java.util.Arrays.asList(
                "guild-channel:10", "guild-channel:11"), "guild-channel:11"));
    }

    @Test
    public void communityConsumesNavigationButNotUnrelatedSystemKeys() {
        assertTrue(OverlayMenuView.isCommunityNavigationKey(KeyEvent.KEYCODE_DPAD_DOWN));
        assertTrue(OverlayMenuView.isCommunityNavigationKey(KeyEvent.KEYCODE_ENTER));
        assertTrue(OverlayMenuView.isCommunityNavigationKey(KeyEvent.KEYCODE_BUTTON_R1));
        assertFalse(OverlayMenuView.isCommunityNavigationKey(KeyEvent.KEYCODE_VOLUME_UP));
        assertFalse(OverlayMenuView.isCommunityNavigationKey(KeyEvent.KEYCODE_HOME));
    }

    @Test
    public void chatAutoscrollOnlyFollowsRecipientBottomOrNewOutgoingMessage() {
        assertTrue(OverlayMenuView.shouldAutoScrollCommunityChat(false, false, false));
        assertTrue(OverlayMenuView.shouldAutoScrollCommunityChat(true, true, false));
        assertTrue(OverlayMenuView.shouldAutoScrollCommunityChat(true, false, true));
        assertFalse(OverlayMenuView.shouldAutoScrollCommunityChat(true, false, false));
    }

    @Test
    public void communityProjectionOnlyRebuildsForRevisionSectionOrSubmodeChanges() {
        assertFalse(OverlayMenuView.shouldRenderCommunityProjection(7, 7,
                OverlayMenuView.CommunitySection.FRIENDS, OverlayMenuView.CommunitySection.FRIENDS,
                OverlayMenuView.CommunitySubmode.ROOT, OverlayMenuView.CommunitySubmode.ROOT));
        assertTrue(OverlayMenuView.shouldRenderCommunityProjection(7, 8,
                OverlayMenuView.CommunitySection.FRIENDS, OverlayMenuView.CommunitySection.FRIENDS,
                OverlayMenuView.CommunitySubmode.ROOT, OverlayMenuView.CommunitySubmode.ROOT));
        assertTrue(OverlayMenuView.shouldRenderCommunityProjection(7, 7,
                OverlayMenuView.CommunitySection.FRIENDS, OverlayMenuView.CommunitySection.CHANNELS,
                OverlayMenuView.CommunitySubmode.ROOT, OverlayMenuView.CommunitySubmode.ROOT));
        assertTrue(OverlayMenuView.shouldRenderCommunityProjection(7, 7,
                OverlayMenuView.CommunitySection.CHANNELS, OverlayMenuView.CommunitySection.CHANNELS,
                OverlayMenuView.CommunitySubmode.ROOT,
                OverlayMenuView.CommunitySubmode.GUILD_CHANNELS));
    }

    @Test
    public void communityReducerTraversesRowsAndActionsWithoutEmptyListDeadEnd() {
        assertEquals(OverlayMenuView.COMMUNITY_REGION_CONTENT,
                OverlayMenuView.communityRailRightRegion(true, true));
        assertEquals(OverlayMenuView.COMMUNITY_REGION_ACTION,
                OverlayMenuView.communityRegionTarget(OverlayMenuView.COMMUNITY_REGION_CONTENT,
                        KeyEvent.KEYCODE_DPAD_RIGHT, true));
        assertEquals(OverlayMenuView.COMMUNITY_REGION_CONTENT,
                OverlayMenuView.communityActionLeftRegion(0, true));
        assertEquals(OverlayMenuView.COMMUNITY_REGION_RAIL,
                OverlayMenuView.communityRegionTarget(OverlayMenuView.COMMUNITY_REGION_CONTENT,
                        KeyEvent.KEYCODE_DPAD_LEFT, true));

        assertEquals(OverlayMenuView.COMMUNITY_REGION_ACTION,
                OverlayMenuView.communityRailRightRegion(false, true));
        assertEquals(OverlayMenuView.COMMUNITY_REGION_ACTION,
                OverlayMenuView.communityActionLeftRegion(1, false));
        assertEquals(OverlayMenuView.COMMUNITY_REGION_RAIL,
                OverlayMenuView.communityActionLeftRegion(0, false));
    }

    @Test
    public void cancelLatchAllowsOneEffectPerPhysicalPress() {
        assertTrue(OverlayMenuView.communityCancelDownHasEffect(false, 0));
        assertFalse(OverlayMenuView.communityCancelDownHasEffect(true, 0));
        assertFalse(OverlayMenuView.communityCancelDownHasEffect(true, 2));
        assertFalse(OverlayMenuView.communityCancelDownHasEffect(false, 2));
    }

    @Test
    public void verticalCommunityBoundariesConnectParticipantsAndActions() {
        assertFalse(OverlayMenuView.communityListDownEntersActions(0, 2, true));
        assertTrue(OverlayMenuView.communityListDownEntersActions(1, 2, true));
        assertFalse(OverlayMenuView.communityListDownEntersActions(1, 2, false));
        assertTrue(OverlayMenuView.communityActionUpEntersList(0, true));
        assertTrue(OverlayMenuView.communityActionUpEntersList(1, true));
        assertFalse(OverlayMenuView.communityActionUpEntersList(2, true));
        assertFalse(OverlayMenuView.communityActionUpEntersList(0, false));
    }

    @Test
    public void chatStructureIgnoresDraftAndStatusButTracksHistory() {
        OverlayMenuView.ChatMessage first = new OverlayMenuView.ChatMessage(
                "10", "hello", "", "", 0, false, false);
        OverlayMenuView.ChatModel base = new OverlayMenuView.ChatModel("1", "Friend", "avatar",
                "a", java.util.Collections.singletonList(first), false,
                false, "", false, "");
        String rendered = OverlayMenuView.communityChatStructureSignature(base);

        OverlayMenuView.ChatModel draftAndStatusOnly = new OverlayMenuView.ChatModel(
                "1", "Friend", "avatar", "ab", java.util.Collections.singletonList(first), false,
                true, "safe error", true, "retry");
        assertFalse(OverlayMenuView.shouldRebuildCommunityChat(rendered, draftAndStatusOnly));

        OverlayMenuView.ChatMessage second = new OverlayMenuView.ChatMessage(
                "11", "new", "Sticker", "wave", 1, true, false);
        OverlayMenuView.ChatModel newMessage = new OverlayMenuView.ChatModel(
                "1", "Friend", "avatar", "ab", java.util.Arrays.asList(first, second), false,
                false, "", false, "");
        assertTrue(OverlayMenuView.shouldRebuildCommunityChat(rendered, newMessage));
        assertTrue(OverlayMenuView.shouldRebuildCommunityChat(rendered,
                new OverlayMenuView.ChatModel("2", "Other", "avatar", "",
                        java.util.Collections.singletonList(first), false,
                        false, "", false, "")));
        assertTrue(OverlayMenuView.shouldRebuildCommunityChat(rendered,
                new OverlayMenuView.ChatModel("1", "Friend", "avatar", "",
                        java.util.Collections.singletonList(first), true,
                        false, "", false, "")));
    }

    @Test
    public void friendChatRoutesBothKeyEdgesAndContinuousHatOnlyToVisibleKeyboard() {
        assertTrue(OverlayMenuView.shouldRouteCommunityChatKeyboard(
                OverlayMenuView.OverlayMode.COMMUNITY,
                OverlayMenuView.CommunitySubmode.FRIEND_CHAT, true));
        assertFalse(OverlayMenuView.shouldRouteCommunityChatKeyboard(
                OverlayMenuView.OverlayMode.COMMUNITY,
                OverlayMenuView.CommunitySubmode.ROOT, true));
        assertFalse(OverlayMenuView.shouldRouteCommunityChatKeyboard(
                OverlayMenuView.OverlayMode.COMMUNITY,
                OverlayMenuView.CommunitySubmode.FRIEND_CHAT, false));
        assertTrue(OverlayMenuView.shouldForwardCommunityChatKeyAction(KeyEvent.ACTION_DOWN));
        assertTrue(OverlayMenuView.shouldForwardCommunityChatKeyAction(KeyEvent.ACTION_UP));
        assertFalse(OverlayMenuView.shouldForwardCommunityChatKeyAction(KeyEvent.ACTION_MULTIPLE));
        assertEquals(KeyEvent.KEYCODE_UNKNOWN,
                OverlayMenuView.communityMotionDirection(0f, 0f, .45f));
    }

    @Test
    public void communityFooterGlyphsFollowLogicalConfirmCancelAndFaceFlip() {
        assertEquals(ControllerGlyphs.Button.CONFIRM,
                OverlayMenuView.communityFooterButton(true, false));
        assertEquals(ControllerGlyphs.Button.CANCEL,
                OverlayMenuView.communityFooterButton(false, false));
        assertEquals(ControllerGlyphs.Button.CANCEL,
                OverlayMenuView.communityFooterButton(true, true));
        assertEquals(ControllerGlyphs.Button.CONFIRM,
                OverlayMenuView.communityFooterButton(false, true));
        assertFalse(ControllerGlyphs.text(true, ControllerGlyphs.Button.CONFIRM).isEmpty());
        assertFalse(ControllerGlyphs.text(false, ControllerGlyphs.Button.CONFIRM).isEmpty());
    }

    private static OverlayMenuView.CommunityModel communityModel(
            OverlayMenuView.VoiceSummary voice) {
        return new OverlayMenuView.CommunityModel(1,
                OverlayMenuView.CommunityStatus.READY, "",
                OverlayMenuView.CommunityStatus.READY, "", voice,
                Collections.emptyList(), Collections.emptyList(), Collections.emptyList(),
                Collections.emptyList(), Collections.emptyList(), "", false, null);
    }

    private static String readProjectFile(String modulePath, String rootPath) throws Exception {
        Path path = Paths.get(modulePath);
        if (!Files.exists(path)) path = Paths.get(rootPath);
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }
}
