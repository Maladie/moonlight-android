package com.limelight;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class PendingRetainedSwitchTest {
    @Test public void latestUndispatchedTargetWinsAndIsSubmittedOnce() {
        PendingRetainedSwitch.Slot slot = new PendingRetainedSwitch.Slot();
        PendingRetainedSwitch first = target("a");
        PendingRetainedSwitch second = target("b");

        assertTrue(slot.offer(first));
        assertTrue("a newer selection before submit replaces the older one",
                slot.offer(second));
        assertSame(second, slot.take());
        assertNull("submitted exactly once", slot.take());
    }

    @Test public void selectionDuringASubmittedSwitchIsRejectedNotQueued() {
        PendingRetainedSwitch.Slot slot = new PendingRetainedSwitch.Slot();
        slot.offer(target("a"));
        slot.take();

        assertFalse(slot.offer(target("b")));
        assertNull(slot.take());

        slot.completed();
        assertTrue(slot.offer(target("c")));
        assertFalse(slot.submitted());
    }

    @Test public void absentTargetIsNotOffered() {
        PendingRetainedSwitch.Slot slot = new PendingRetainedSwitch.Slot();
        assertFalse(slot.offer(null));
        assertNull(slot.take());
    }

    private static PendingRetainedSwitch target(String game) {
        return new PendingRetainedSwitch("host", "p", "p", false, 7, game, game, "Desktop", "");
    }
}
