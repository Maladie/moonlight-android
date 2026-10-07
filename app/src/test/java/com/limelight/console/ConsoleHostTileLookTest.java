package com.limelight.console;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.limelight.R;
import com.limelight.console.ConsoleHostPresentation.ProfileState;
import com.limelight.console.ConsoleHostPresentation.State;
import com.limelight.console.ConsoleHostTileLook.Badge;

import org.junit.Test;

public class ConsoleHostTileLookTest {
    private static ConsoleHostTileLook look(State state) {
        return ConsoleHostTileLook.of(state, ProfileState.NONE, false);
    }

    @Test public void onlineIsAGreenDotWithTheHostStatus() {
        ConsoleHostTileLook online = look(State.ONLINE);
        assertEquals(Badge.NONE, online.badge);
        assertEquals(ConsoleHostTileLook.ONLINE, online.dot);
        assertEquals(0, online.label);
        assertFalse(online.dimmed);
        assertFalse(online.wakingRing);
    }

    @Test public void activeSessionIsLiveWithARedDotAndTheSessionLine() {
        ConsoleHostTileLook live = look(State.ACTIVE_SESSION);
        assertEquals(Badge.LIVE, live.badge);
        assertEquals(ConsoleHostTileLook.LIVE_RED, live.dot);
        assertEquals(0, live.label);
    }

    @Test public void wakingShowsTheRingAndCountsSecondsOnlyWhenTimed() {
        ConsoleHostTileLook timed = ConsoleHostTileLook.of(State.WAKING, ProfileState.NONE, true);
        assertTrue(timed.wakingRing);
        assertEquals(R.string.console_host_tile_waking, timed.label);
        ConsoleHostTileLook untimed = look(State.WAKING);
        assertTrue(untimed.wakingRing);
        assertEquals(0, untimed.label);
    }

    @Test public void asleepCarriesTheMoonBadge() {
        ConsoleHostTileLook asleep = look(State.ASLEEP);
        assertEquals(Badge.ASLEEP, asleep.badge);
        assertEquals(ConsoleHostTileLook.ASLEEP, asleep.dot);
        assertFalse(asleep.dimmed);
    }

    @Test public void offlineAndUnreachableAreDimmedAndUnavailable() {
        for (State state : new State[]{State.OFFLINE, State.UNREACHABLE}) {
            ConsoleHostTileLook gone = look(state);
            assertTrue(gone.dimmed);
            assertEquals(R.string.console_host_tile_unavailable, gone.label);
            assertEquals(Badge.NONE, gone.badge);
            assertEquals(ConsoleHostTileLook.OFFLINE_RED, gone.dot);
        }
    }

    @Test public void unpairedAsksToPairInAmber() {
        ConsoleHostTileLook unpaired = look(State.UNPAIRED);
        assertEquals(Badge.PAIR, unpaired.badge);
        assertEquals(R.string.console_host_tile_pair, unpaired.label);
        assertEquals(ConsoleHostTileLook.AMBER, unpaired.dot);
    }

    @Test public void checkingSpinsANeutralDotInsteadOfAskingToPair() {
        ConsoleHostTileLook checking = look(State.CHECKING);
        assertTrue(checking.spinner);
        assertEquals(R.string.console_status_checking, checking.label);
        assertEquals(Badge.NONE, checking.badge);
        assertFalse(checking.dimmed);
        assertFalse(look(State.UNPAIRED).spinner);
    }

    @Test public void profileAttentionNamesWindowsSignInOnlyWhenThatIsTheReason() {
        ConsoleHostTileLook signIn = ConsoleHostTileLook.of(State.PROFILE_ATTENTION,
                ProfileState.SIGN_IN_REQUIRED, false);
        assertEquals(Badge.ATTENTION, signIn.badge);
        assertEquals(R.string.console_host_tile_sign_in, signIn.label);
        ConsoleHostTileLook other = ConsoleHostTileLook.of(State.PROFILE_ATTENTION,
                ProfileState.OTHER_ACTIVE, false);
        assertEquals(Badge.ATTENTION, other.badge);
        assertEquals(0, other.label);
    }
}
