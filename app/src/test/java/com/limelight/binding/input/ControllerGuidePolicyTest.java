package com.limelight.binding.input;

import com.limelight.nvstream.input.ControllerPacket;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class ControllerGuidePolicyTest {
    private static final int GUIDE = ControllerPacket.SPECIAL_BUTTON_FLAG;
    private static final int GAME_BUTTONS = ControllerPacket.A_FLAG | ControllerPacket.UP_FLAG
            | ControllerPacket.PLAY_FLAG | ControllerPacket.LB_FLAG;

    @Test public void managedNonSteamFiltersEveryGuidePacketWithoutEatingGameButtons() {
        ControllerGuidePolicy policy = new ControllerGuidePolicy();
        policy.begin("epic-launch", "epic:Celeste", true);
        // Pending provider, tap, held/repeated press, chord, and release all use this filter.
        assertEquals(0, policy.filter(GUIDE));
        policy.observe("epic-launch", "epic:celeste", false);
        for (int buttons : new int[] {GUIDE, GUIDE, GUIDE | GAME_BUTTONS, GAME_BUTTONS, 0}) {
            assertEquals(buttons & ~GUIDE, policy.filter(buttons));
        }
    }

    @Test public void retainedSwitchAndResumeCannotReuseAnOldSteamDecision() {
        ControllerGuidePolicy policy = new ControllerGuidePolicy();
        policy.begin("steam-launch", "steam:1", true);
        policy.observe("steam-launch", "steam:1", true);
        assertEquals(GUIDE | GAME_BUTTONS, policy.filter(GUIDE | GAME_BUTTONS));
        policy.begin("epic-switch", "epic:Celeste", true);
        policy.observe("steam-launch", "steam:1", true);
        policy.observe("epic-switch", "steam:1", true);
        assertEquals(GAME_BUTTONS, policy.filter(GUIDE | GAME_BUTTONS));
        policy.observe("epic-switch", "epic:Celeste", false);
        policy.begin("steam-resume", "steam:1", true);
        assertEquals(0, policy.filter(GUIDE));
        policy.observe("steam-resume", "steam:1", true);
        policy.begin("steam-resume", "steam:1", true);
        assertEquals(GUIDE, policy.filter(GUIDE));
    }

    @Test public void observedPlayniteGamesFollowHostWhileUnmanagedStreamsKeepGuide() {
        ControllerGuidePolicy policy = new ControllerGuidePolicy();
        assertEquals(GUIDE, policy.filter(GUIDE));
        policy.begin("playnite", "", true);
        policy.observe("playnite", "legacy-steam-guid", true);
        assertEquals(GUIDE, policy.filter(GUIDE));
        policy.observe("playnite", "legacy-epic-guid", false);
        assertEquals(0, policy.filter(GUIDE));
        policy.begin("desktop", "", false);
        policy.observe("playnite", "legacy-epic-guid", false);
        assertEquals(GUIDE, policy.filter(GUIDE));
    }
}
