package com.limelight.console;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class LegacyGameLifecyclePolicyTest {
    @Test public void consoleTargetKeepsActiveSessionEvenWhenGameIsFinishing() {
        assertTrue(LegacyGameLifecyclePolicy.shouldKeepSessionOnStop(
                true, true, true, true, true));
    }

    @Test public void normalExternalCoverKeepsActiveSession() {
        assertTrue(LegacyGameLifecyclePolicy.shouldKeepSessionOnStop(
                true, false, true, true, false));
    }

    @Test public void inactiveSessionIsNeverKept() {
        assertFalse(LegacyGameLifecyclePolicy.shouldKeepSessionOnStop(
                true, false, true, false, true));
    }

    @Test public void consoleTargetSurvivesWindowSurfaceLoss() {
        assertTrue(LegacyGameLifecyclePolicy.hasAlternateRenderTarget(
                true, true, false));
    }

    @Test public void unboundWindowSurfaceLossHasNoFallback() {
        assertFalse(LegacyGameLifecyclePolicy.hasAlternateRenderTarget(
                true, false, false));
    }
}
