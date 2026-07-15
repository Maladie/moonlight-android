package com.limelight.console;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class StreamPreferenceContextTest {
    @Test public void appKeyMatchesExistingPerAppPreferenceContract() {
        assertEquals("host-uuid:17", StreamPreferenceContext.appKey("host-uuid", 17));
    }

    @Test public void nullComputerIdentityPreservesLegacyKeyShape() {
        assertEquals("null:17", StreamPreferenceContext.appKey(null, 17));
    }
}
