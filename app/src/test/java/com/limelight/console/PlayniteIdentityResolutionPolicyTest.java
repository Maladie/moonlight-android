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

    @Test public void freshIdleSuppressesOnlyGamePendingReconnect() {
        assertFalse(PlayniteIdentityResolutionPolicy.allowsPendingReconnect(
                "idle", "steam:224760"));
        assertTrue(PlayniteIdentityResolutionPolicy.allowsPendingReconnect(
                "idle", ""));
        assertTrue(PlayniteIdentityResolutionPolicy.allowsPendingReconnect(
                "unknown", "steam:224760"));
    }
}
