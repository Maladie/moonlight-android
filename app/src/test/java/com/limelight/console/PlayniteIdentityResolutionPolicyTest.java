package com.limelight.console;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class PlayniteIdentityResolutionPolicyTest {
    @Test public void rawLiveHostRequestsCorrelationEvenWhenSnapshotIsSuppressed() {
        assertEquals(PlayniteIdentityResolutionPolicy.Action.REQUEST,
                PlayniteIdentityResolutionPolicy.decide(
                        true, true, 42, SessionSnapshot.State.NONE));
    }

    @Test public void rawZeroClearsOnlyWhenThereIsNoSessionPresentation() {
        assertEquals(PlayniteIdentityResolutionPolicy.Action.CLEAR,
                PlayniteIdentityResolutionPolicy.decide(
                        true, true, 0, SessionSnapshot.State.NONE));
        assertEquals(PlayniteIdentityResolutionPolicy.Action.PRESERVE,
                PlayniteIdentityResolutionPolicy.decide(
                        true, true, 0, SessionSnapshot.State.ACTIVE));
    }

    @Test public void terminatingNeverRequestsCorrelationOrResume() {
        assertEquals(PlayniteIdentityResolutionPolicy.Action.CLEAR,
                PlayniteIdentityResolutionPolicy.decide(
                        true, true, 42, SessionSnapshot.State.TERMINATING));
    }

    @Test public void suspendedAndReconnectDoNotTriggerRawLookup() {
        assertEquals(PlayniteIdentityResolutionPolicy.Action.PRESERVE,
                PlayniteIdentityResolutionPolicy.decide(
                        true, true, 0, SessionSnapshot.State.SUSPENDED));
        assertEquals(PlayniteIdentityResolutionPolicy.Action.PRESERVE,
                PlayniteIdentityResolutionPolicy.decide(
                        true, true, 0, SessionSnapshot.State.RECONNECT_REQUIRED));
    }
}
