package com.limelight;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class GameOverlayKeySequenceLatchTest {
    @Test
    public void overlayOwnedSequenceConsumesRepeatsAndOnlyItsMatchingUpReleasesIt() {
        Game.OverlayKeySequenceLatch latch = new Game.OverlayKeySequenceLatch();
        long circleOnFirstPad = Game.overlayKeySequence(7, 97);
        long circleOnSecondPad = Game.overlayKeySequence(8, 97);

        latch.retain(circleOnFirstPad);
        assertTrue(latch.consume(circleOnFirstPad, false));
        assertFalse(latch.consume(circleOnSecondPad, false));
        assertTrue(latch.consume(circleOnFirstPad, true));
        assertFalse(latch.consume(circleOnFirstPad, false));
    }
}
