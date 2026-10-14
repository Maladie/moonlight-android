package com.limelight.console;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Test;

public class MoonWakerExitTest {
    @Before public void reset() {
        MoonWakerExit.withdraw();
    }

    @Test public void aCloseCompletedWhileAwayEndsMoonWakerOnce() {
        long exit = MoonWakerExit.request();

        assertTrue(MoonWakerExit.take(exit));
        assertFalse(MoonWakerExit.take(exit));
    }

    /** Back in MoonWaker (any screen, any console instance): the close no longer ends it. */
    @Test public void returningWithdrawsTheRequest() {
        long exit = MoonWakerExit.request();
        MoonWakerExit.withdraw();

        assertFalse(MoonWakerExit.take(exit));
    }

    /** Back, then away again with a new close: only the newer request may end MoonWaker. */
    @Test public void anOlderRequestNeverEndsANewerVisit() {
        long older = MoonWakerExit.request();
        MoonWakerExit.withdraw();
        long newer = MoonWakerExit.request();

        assertFalse(MoonWakerExit.take(older));
        assertTrue(MoonWakerExit.take(newer));
        assertFalse(MoonWakerExit.take(0L));
    }
}
