package com.limelight.console;

import com.limelight.stream.RetainedStreamSessionCoordinator;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class AuthoritativeSessionTransitionTest {
    @Test public void cachedOrInitialZeroCannotEndSession() {
        assertFalse(AuthoritativeSessionTransition.ended(null, 0));
        assertFalse(AuthoritativeSessionTransition.ended(0, 0));
    }

    @Test public void onlyObservedActiveToStoppedTransitionEndsSession() {
        assertFalse(AuthoritativeSessionTransition.ended(42, 42));
        assertFalse(AuthoritativeSessionTransition.ended(42, 7));
        assertTrue(AuthoritativeSessionTransition.ended(42, 0));
    }
    @Test public void authoritativeCleanupCarriesOnlyCapturedSessionIdentity() {
        assertEquals("session-a", AuthoritativeSessionTransition.endedSessionId(
                42, 0, "session-a"));
        assertEquals("", AuthoritativeSessionTransition.endedSessionId(
                42, 42, "session-a"));
        assertEquals("", AuthoritativeSessionTransition.endedSessionId(
                42, 0, null));
    }

    @Test public void freshZeroClearsOnlyLostMatchingRetainedTransport() {
        assertTrue(AuthoritativeSessionTransition.lostRetainedTransport(
                true, 0, true, RetainedStreamSessionCoordinator.State.RECONNECT_REQUIRED));
        assertFalse(AuthoritativeSessionTransition.lostRetainedTransport(
                true, 0, true, RetainedStreamSessionCoordinator.State.HOME_LIVE));
        assertFalse(AuthoritativeSessionTransition.lostRetainedTransport(
                true, 0, true, RetainedStreamSessionCoordinator.State.PARKED_LIVE));
        assertFalse(AuthoritativeSessionTransition.lostRetainedTransport(
                true, 0, false, RetainedStreamSessionCoordinator.State.RECONNECT_REQUIRED));
        assertFalse(AuthoritativeSessionTransition.lostRetainedTransport(
                false, 0, true, RetainedStreamSessionCoordinator.State.RECONNECT_REQUIRED));
        assertFalse(AuthoritativeSessionTransition.lostRetainedTransport(
                true, 7, true, RetainedStreamSessionCoordinator.State.RECONNECT_REQUIRED));
    }

    @Test public void freshSunshineStateOverridesStaleCapturedAppId() {
        assertEquals(AuthoritativeSessionTransition.SunshineStopAction.FAILED,
                AuthoritativeSessionTransition.sunshineStopAction(0, null));
        assertEquals(AuthoritativeSessionTransition.SunshineStopAction.FAILED,
                AuthoritativeSessionTransition.sunshineStopAction(0, 7));
        assertEquals(AuthoritativeSessionTransition.SunshineStopAction.COMPLETE,
                AuthoritativeSessionTransition.sunshineStopAction(7, 0));
        assertEquals(AuthoritativeSessionTransition.SunshineStopAction.QUIT,
                AuthoritativeSessionTransition.sunshineStopAction(7, 7));
        assertEquals(AuthoritativeSessionTransition.SunshineStopAction.FAILED,
                AuthoritativeSessionTransition.sunshineStopAction(7, 9));
        assertEquals(AuthoritativeSessionTransition.SunshineStopAction.FAILED,
                AuthoritativeSessionTransition.sunshineStopAction(7, null));
    }
}
