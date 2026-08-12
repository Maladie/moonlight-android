package com.limelight.console;

import org.junit.Test;

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
}
