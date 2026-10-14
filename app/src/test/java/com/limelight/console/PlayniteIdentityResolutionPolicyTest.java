package com.limelight.console;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class PlayniteIdentityResolutionPolicyTest {
    @Test public void rawLiveHostRequestsCorrelationEvenWhenSnapshotIsSuppressed() {
        assertEquals(PlayniteIdentityResolutionPolicy.Action.REQUEST,
                PlayniteIdentityResolutionPolicy.decide(
                        true, true, SessionSnapshot.State.NONE));
    }

    @Test public void onlineZeroStillRequestsBridgeGameCorrelation() {
        assertEquals(PlayniteIdentityResolutionPolicy.Action.REQUEST,
                PlayniteIdentityResolutionPolicy.decide(
                        true, true, SessionSnapshot.State.NONE));
        assertEquals(PlayniteIdentityResolutionPolicy.Action.REQUEST,
                PlayniteIdentityResolutionPolicy.decide(
                        true, true, SessionSnapshot.State.ACTIVE));
    }

    @Test public void terminatingNeverRequestsCorrelationOrResume() {
        assertEquals(PlayniteIdentityResolutionPolicy.Action.CLEAR,
                PlayniteIdentityResolutionPolicy.decide(
                        true, true, SessionSnapshot.State.TERMINATING));
    }

    @Test public void onlineSuspendedAndReconnectStillRefreshBridgeIdentity() {
        assertEquals(PlayniteIdentityResolutionPolicy.Action.REQUEST,
                PlayniteIdentityResolutionPolicy.decide(
                        true, true, SessionSnapshot.State.SUSPENDED));
        assertEquals(PlayniteIdentityResolutionPolicy.Action.REQUEST,
                PlayniteIdentityResolutionPolicy.decide(
                        true, true, SessionSnapshot.State.RECONNECT_REQUIRED));
    }

    @Test public void responseRequiresExactHostGenerationAndSunshineApp() {
        assertTrue(PlayniteIdentityResolutionPolicy.acceptsResponse(
                "host", 7, 0, "HOST", 7, 0, true, true));
        assertFalse(PlayniteIdentityResolutionPolicy.acceptsResponse(
                "host", 7, 0, "other", 7, 0, true, true));
        assertFalse(PlayniteIdentityResolutionPolicy.acceptsResponse(
                "host", 7, 0, "host", 8, 0, true, true));
        assertFalse(PlayniteIdentityResolutionPolicy.acceptsResponse(
                "host", 7, 0, "host", 7, 42, true, true));
    }

    @Test public void observationRequiresMatchingAppAndBoundedAge() {
        assertTrue(PlayniteIdentityResolutionPolicy.isFreshObservation(
                0, 0, 100L, 110L, 15L));
        assertFalse(PlayniteIdentityResolutionPolicy.isFreshObservation(
                0, 0, 100L, 116L, 15L));
        assertFalse(PlayniteIdentityResolutionPolicy.isFreshObservation(
                0, 42, 100L, 110L, 15L));
        assertFalse(PlayniteIdentityResolutionPolicy.isFreshObservation(
                0, 0, 0L, 0L, 15L));
    }

    @Test public void providerStopUsesOnlyFreshExactCurrentIdentity() {
        String game = "steam:224760";
        assertEquals("", PlayniteIdentityResolutionPolicy.verifiedStopTarget(
                game, "idle", ""));
        assertEquals(game, PlayniteIdentityResolutionPolicy.verifiedStopTarget(
                game, "running", game));
        assertEquals(game, PlayniteIdentityResolutionPolicy.verifiedStopTarget(
                "", "starting", game));
        assertEquals(game, PlayniteIdentityResolutionPolicy.verifiedStopTarget(
                game, "stopping", game));
        assertEquals(null, PlayniteIdentityResolutionPolicy.verifiedStopTarget(
                game, "running", "steam:other"));
        assertEquals(null, PlayniteIdentityResolutionPolicy.verifiedStopTarget(
                game, "ambiguous", game));
        assertEquals(null, PlayniteIdentityResolutionPolicy.verifiedStopTarget(
                game, "reconciling", game));
        assertEquals("epic:CelesteApp",
                PlayniteIdentityResolutionPolicy.verifiedStopTarget(
                        "epic:celesteapp", "running", "epic:CelesteApp"));
    }

    /** TV 2026-10-14 13:54: Play RE3 over the neutral warm-up while the host runs RE3. */
    @Test public void neutralStreamPlayOfTheGameTheHostRunsAdoptsItInsteadOfFailing() {
        String re3 = "steam:952060";
        assertEquals(PlayniteIdentityResolutionPolicy.GamelessSwitch.ADOPT_RUNNING,
                PlayniteIdentityResolutionPolicy.gamelessSwitch(
                        re3, true, "running", re3, true));
        // An idle host: the chosen game is started, as before.
        assertEquals(PlayniteIdentityResolutionPolicy.GamelessSwitch.START,
                PlayniteIdentityResolutionPolicy.gamelessSwitch(re3, true, "idle", "", false));
    }

    @Test public void neutralStreamNeverStopsOrAdoptsAnotherOrUnprovenGame() {
        String re3 = "steam:952060";
        for (boolean parent : new boolean[] {true, false}) {
            assertEquals(PlayniteIdentityResolutionPolicy.GamelessSwitch.MISMATCH,
                    PlayniteIdentityResolutionPolicy.gamelessSwitch(
                            re3, parent, "running", "steam:224760", true));
            assertEquals(PlayniteIdentityResolutionPolicy.GamelessSwitch.MISMATCH,
                    PlayniteIdentityResolutionPolicy.gamelessSwitch(
                            re3, parent, "running", re3, false));
            assertEquals(PlayniteIdentityResolutionPolicy.GamelessSwitch.MISMATCH,
                    PlayniteIdentityResolutionPolicy.gamelessSwitch(
                            re3, parent, "stopping", re3, true));
        }
    }

    /**
     * Review K5 round 2 (2): the host never reauthorizes a gameless stream over a running game
     * for another actor, so a child's start there could only fail after its allowance began.
     * Refused before it (fail-closed); over an idle host the child starts as before.
     */
    @Test public void childOrOtherActorIsRefusedOverTheRunningGameAndStartsOnlyOverIdle() {
        String re3 = "steam:952060";
        assertEquals(PlayniteIdentityResolutionPolicy.GamelessSwitch.MISMATCH,
                PlayniteIdentityResolutionPolicy.gamelessSwitch(
                        re3, false, "running", re3, true));
        assertEquals(PlayniteIdentityResolutionPolicy.GamelessSwitch.START,
                PlayniteIdentityResolutionPolicy.gamelessSwitch(re3, false, "idle", "", false));
        // The stream shows the refusal as the session being another actor's, not a retry.
        assertEquals(SessionFailure.SESSION_HELD_BY_OTHER_ACTOR,
                SessionFailure.switchFailed(SessionFailure.SWITCH_OTHER_ACTOR_REASON).reason);
    }
}
