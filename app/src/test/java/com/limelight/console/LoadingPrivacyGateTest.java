package com.limelight.console;

import org.junit.Test;
import static org.junit.Assert.*;

public class LoadingPrivacyGateTest {
    @Test public void firstFrameIsEnoughOnlyForUnmanagedLaunch() {
        LoadingPrivacyGate gate = new LoadingPrivacyGate(3);
        gate.reset(false);
        gate.onFirstDecodedFrame();
        assertTrue(gate.mayReveal());
    }

    @Test public void managedLaunchRequiresStableWindowAndFrame() {
        LoadingPrivacyGate gate = new LoadingPrivacyGate(3);
        gate.reset(true);
        gate.onReadiness(new LaunchOrchestrator.ReadinessSample(true, true, true, 3));
        assertFalse(gate.mayReveal());
        gate.onFirstDecodedFrame();
        assertTrue(gate.mayReveal());
    }

    @Test public void processLikeIncompleteSampleCannotReveal() {
        LoadingPrivacyGate gate = new LoadingPrivacyGate(3);
        gate.reset(true);
        gate.onFirstDecodedFrame();
        gate.onReadiness(new LaunchOrchestrator.ReadinessSample(true, false, false, 1));
        assertFalse(gate.mayReveal());
    }
}
