package com.limelight.console;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class StreamRefreshRateOverridePolicyTest {
    @Test public void convertsPositiveRefreshRateToX100() {
        assertEquals(5994, StreamRefreshRateOverridePolicy.parseX100("59.94"));
        assertEquals(12000, StreamRefreshRateOverridePolicy.parseX100(" 120 "));
    }

    @Test public void missingAndNonPositiveValuesDisableOverride() {
        assertEquals(0, StreamRefreshRateOverridePolicy.parseX100(null));
        assertEquals(0, StreamRefreshRateOverridePolicy.parseX100("  "));
        assertEquals(0, StreamRefreshRateOverridePolicy.parseX100("0"));
        assertEquals(0, StreamRefreshRateOverridePolicy.parseX100("-60"));
    }

    @Test public void malformedAndNonFiniteValuesDisableOverride() {
        assertEquals(0, StreamRefreshRateOverridePolicy.parseX100("auto"));
        assertEquals(0, StreamRefreshRateOverridePolicy.parseX100("NaN"));
        assertEquals(0, StreamRefreshRateOverridePolicy.parseX100("Infinity"));
        assertEquals(0, StreamRefreshRateOverridePolicy.parseX100("1e30"));
    }
}
