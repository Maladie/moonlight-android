package com.limelight.console;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class ConsoleReturnRequestsTest {
    @Test public void freshLaunchTakesTheOfferAndTheSuppressionOnce() {
        ConsoleReturnRequests r = new ConsoleReturnRequests();
        r.created(false, false, true, "HOST");
        assertEquals("HOST", r.takeHardTerminateOffer());
        assertEquals("", r.takeHardTerminateOffer());
        assertTrue(r.takeSuppressAutoWarmUp());
        assertFalse(r.takeSuppressAutoWarmUp());
    }

    /** The error screen's offer reaching the running console also suppresses its warm-up. */
    @Test public void deliveredIntentToARunningConsoleSuppressesItsWarmUp() {
        ConsoleReturnRequests r = new ConsoleReturnRequests();
        r.created(false, false, false, null);
        assertFalse(r.suppressesAutoWarmUp());
        r.delivered(true, "HOST");
        assertTrue(r.takeSuppressAutoWarmUp());
        assertEquals("HOST", r.takeHardTerminateOffer());
        // An Intent without requests leaves nothing to take.
        r.delivered(false, null);
        assertFalse(r.takeSuppressAutoWarmUp());
        assertEquals("", r.takeHardTerminateOffer());
    }

    /** Process death re-delivers the Intent's extras: a recreation never replays them. */
    @Test public void recreationFromSavedStateNeverReplaysTheIntent() {
        ConsoleReturnRequests r = new ConsoleReturnRequests();
        r.created(true, false, true, "HOST");
        assertEquals("", r.takeHardTerminateOffer());
        assertFalse(r.takeSuppressAutoWarmUp());
        // A suppression still pending when the state was saved stays pending.
        ConsoleReturnRequests pending = new ConsoleReturnRequests();
        pending.created(true, true, false, null);
        assertTrue(pending.takeSuppressAutoWarmUp());
    }
}
