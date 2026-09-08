package com.limelight.console;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ChildSessionCountdownTest {
    @Test public void remainingUsesHostDeadlineAndCeils() {
        ChildSessionCountdown countdown = new ChildSessionCountdown("session-a");

        assertTrue(countdown.update("session-a", 2, 1_000));
        assertEquals(2, countdown.remainingSeconds(1_001));
        assertEquals(1, countdown.remainingSeconds(2_000));
        assertFalse(countdown.isExpired(2_999));
        assertTrue(countdown.isExpired(3_000));
    }

    @Test public void firstUpdateIsBaselineThenCrossesSixtyMinutes() {
        ChildSessionCountdown countdown = new ChildSessionCountdown("session-a");
        long seventyFiveMinutes = 75 * 60L;

        assertTrue(countdown.update("session-a", seventyFiveMinutes, 0));
        assertEquals(-1, countdown.crossedWarningMinutes(0));
        assertEquals(60, countdown.crossedWarningMinutes(15 * 60_000L));
        assertEquals(-1, countdown.crossedWarningMinutes(15 * 60_000L));
    }

    @Test public void baselineSkipsPassedWarningUntilHostExtendsIt() {
        ChildSessionCountdown countdown = new ChildSessionCountdown("session-a");

        countdown.update("session-a", 45 * 60L, 0);
        assertEquals(-1, countdown.crossedWarningMinutes(0));
        assertEquals(30, countdown.crossedWarningMinutes(15 * 60_000L));

        countdown.update("session-a", 75 * 60L, 15 * 60_000L);
        assertEquals(60, countdown.crossedWarningMinutes(30 * 60_000L));
    }

    @Test public void largeJumpReturnsOnlyLatestCrossedWarning() {
        ChildSessionCountdown countdown = new ChildSessionCountdown("session-a");

        countdown.update("session-a", 75 * 60L, 0);
        long fourMinutesRemaining = (75 - 4) * 60_000L;

        assertEquals(5, countdown.crossedWarningMinutes(fourMinutesRemaining));
        assertEquals(4 * 60L, countdown.remainingSeconds(fourMinutesRemaining));
        assertEquals(-1, countdown.crossedWarningMinutes(fourMinutesRemaining));
    }

    @Test public void updateOnlyRefreshDoesNotLoseUnobservedWarning() {
        ChildSessionCountdown countdown = new ChildSessionCountdown("session-a");

        countdown.update("session-a", 75 * 60L, 0);
        assertTrue(countdown.update("session-a", 59 * 60L, 16 * 60_000L));

        assertEquals(60, countdown.crossedWarningMinutes(16 * 60_000L));
    }

    @Test public void delayedRefreshJumpReturnsLatestWarning() {
        ChildSessionCountdown countdown = new ChildSessionCountdown("session-a");

        countdown.update("session-a", 75 * 60L, 0);
        countdown.update("session-a", 59 * 60L, 16 * 60_000L);
        assertTrue(countdown.update("session-a", 4 * 60L, 71 * 60_000L));

        assertEquals(5, countdown.crossedWarningMinutes(71 * 60_000L));
        assertEquals(4 * 60L, countdown.remainingSeconds(71 * 60_000L));
    }

    @Test public void extensionUsesNewHostDeadlineWithoutResettingWarnings() {
        ChildSessionCountdown countdown = new ChildSessionCountdown("session-a");
        long fifteenMinutes = 15 * 60_000L;

        countdown.update("session-a", 75 * 60L, 0);
        assertEquals(60, countdown.crossedWarningMinutes(fifteenMinutes));
        assertTrue(countdown.update("session-a", 80 * 60L, fifteenMinutes));
        assertEquals(80 * 60L, countdown.remainingSeconds(fifteenMinutes));
        assertEquals(-1, countdown.crossedWarningMinutes(fifteenMinutes));
        assertEquals(30, countdown.crossedWarningMinutes(65 * 60_000L));
    }

    @Test public void foreignAndOlderUpdatesAreIgnored() {
        ChildSessionCountdown countdown = new ChildSessionCountdown("session-a");

        assertTrue(countdown.update("session-a", 120, 1_000));
        assertFalse(countdown.update("session-b", 999, 2_000));
        assertFalse(countdown.update("session-a", 999, 999));
        assertEquals(120, countdown.remainingSeconds(1_000));
    }

    @Test public void aNewSessionStartsWithFreshWarningBaseline() {
        ChildSessionCountdown first = new ChildSessionCountdown("session-a");
        ChildSessionCountdown second = new ChildSessionCountdown("session-b");

        first.update("session-a", 75 * 60L, 0);
        assertEquals(60, first.crossedWarningMinutes(15 * 60_000L));
        second.update("session-b", 75 * 60L, 0);

        assertEquals(60, second.crossedWarningMinutes(15 * 60_000L));
    }
}
