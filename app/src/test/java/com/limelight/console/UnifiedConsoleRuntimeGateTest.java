package com.limelight.console;

import org.junit.Test;

import static org.junit.Assert.assertFalse;

public class UnifiedConsoleRuntimeGateTest {
    @Test public void unifiedRuntimeRemainsDisabledBeforeTvP0Evidence() {
        assertFalse(UnifiedConsoleRuntimeGate.isEnabled());
    }
}
