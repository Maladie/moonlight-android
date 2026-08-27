package com.limelight.ui.overlay;

import android.view.KeyEvent;

import com.limelight.R;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class OverlayMenuViewTest {
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
    public void menuAndCommunityAreMutuallyExclusiveAndBackReturnsToMenu() {
        assertFalse(OverlayMenuView.communityVisible(OverlayMenuView.OverlayMode.MENU, true));
        assertTrue(OverlayMenuView.communityVisible(OverlayMenuView.OverlayMode.COMMUNITY, true));
        assertFalse(OverlayMenuView.communityVisible(OverlayMenuView.OverlayMode.COMMUNITY, false));
        assertEquals(OverlayMenuView.OverlayMode.MENU,
                OverlayMenuView.backMode(OverlayMenuView.OverlayMode.COMMUNITY));
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
        assertEquals(3, OverlayMenuView.communityRailIconCount());
        assertEquals(2, OverlayMenuView.communityActionMaxRows());
        assertTrue(OverlayMenuView.communityContentUsesSingleScrollOwner());
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
}
