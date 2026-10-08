package com.limelight.nvstream;

import org.junit.Test;

import java.util.concurrent.Semaphore;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class NativeStartGateTest {
    private final Semaphore permits = new Semaphore(1);
    private final NativeStartGate gate = new NativeStartGate(permits);

    @Test public void stopBeforeAcquireNeverStartsAndReturnsThePermit() throws Exception {
        gate.stopped();
        assertEquals(1, permits.availablePermits());
        gate.acquire();
        assertFalse(gate.tryBegin());
        assertEquals(1, permits.availablePermits());
    }

    @Test public void stopDuringStartReleasesTheHeldPermitOnce() throws Exception {
        gate.acquire();
        assertTrue(gate.tryBegin());
        assertEquals(0, permits.availablePermits());
        gate.stopped();
        assertEquals(1, permits.availablePermits());
    }

    @Test public void repeatedStopNeverInflatesThePermit() throws Exception {
        gate.stopped();
        gate.stopped();
        assertEquals(1, permits.availablePermits());
        NativeStartGate started = new NativeStartGate(permits);
        started.acquire();
        assertTrue(started.tryBegin());
        started.stopped();
        started.stopped();
        assertEquals(1, permits.availablePermits());
    }

    @Test public void failedStartReturnsThePermitAndALaterStopDoesNotAgain() throws Exception {
        gate.acquire();
        assertTrue(gate.tryBegin());
        gate.startFailed();
        assertEquals(1, permits.availablePermits());
        gate.stopped();
        assertEquals(1, permits.availablePermits());
    }
}
