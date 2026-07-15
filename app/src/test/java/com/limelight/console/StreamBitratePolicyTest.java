package com.limelight.console;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class StreamBitratePolicyTest {
    @Test public void preservesBitrateInsideSupportedRange() {
        assertEquals(20_000, StreamBitratePolicy.clamp(20_000));
    }

    @Test public void clampsBothSupportedBoundaries() {
        assertEquals(StreamBitratePolicy.MIN_KBPS, StreamBitratePolicy.clamp(0));
        assertEquals(StreamBitratePolicy.MIN_KBPS, StreamBitratePolicy.clamp(999));
        assertEquals(StreamBitratePolicy.MAX_KBPS, StreamBitratePolicy.clamp(150_001));
        assertEquals(StreamBitratePolicy.MAX_KBPS, StreamBitratePolicy.clamp(Integer.MAX_VALUE));
    }
}
