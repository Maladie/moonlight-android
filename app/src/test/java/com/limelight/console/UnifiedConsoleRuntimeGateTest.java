package com.limelight.console;

import org.junit.Test;

import com.limelight.BuildConfig;

import static org.junit.Assert.assertEquals;

public class UnifiedConsoleRuntimeGateTest {
    @Test public void unifiedRuntimeMatchesTheSelectedBuildVariant() {
        assertEquals(BuildConfig.UNIFIED_CONSOLE_RUNTIME,
                UnifiedConsoleRuntimeGate.isEnabled());
    }
}
