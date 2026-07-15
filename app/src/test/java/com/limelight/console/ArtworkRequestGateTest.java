package com.limelight.console;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ArtworkRequestGateTest {
    @Test public void newestArtworkRequestWins() {
        ArtworkRequestGate gate = new ArtworkRequestGate();
        int first = gate.next();
        int second = gate.next();
        assertFalse(gate.isCurrent(first));
        assertTrue(gate.isCurrent(second));
    }

    @Test public void clearInvalidatesPendingArtwork() {
        ArtworkRequestGate gate = new ArtworkRequestGate();
        int request = gate.next();
        gate.invalidate();
        assertFalse(gate.isCurrent(request));
    }
}
