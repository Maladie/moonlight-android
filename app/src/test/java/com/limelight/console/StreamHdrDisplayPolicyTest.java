package com.limelight.console;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class StreamHdrDisplayPolicyTest {
    private static final int HDR10 = 2;

    @Test public void disabledPreferenceSkipsCapabilityRequirements() {
        StreamHdrDisplayPolicy.Result result =
                StreamHdrDisplayPolicy.evaluate(false, false, null, HDR10);

        assertFalse(result.enabled);
        assertEquals(StreamHdrDisplayPolicy.Reason.DISABLED_BY_USER, result.reason);
    }

    @Test public void requiresPlatformHdrApis() {
        StreamHdrDisplayPolicy.Result result =
                StreamHdrDisplayPolicy.evaluate(true, false, new int[] { HDR10 }, HDR10);

        assertFalse(result.enabled);
        assertEquals(StreamHdrDisplayPolicy.Reason.ANDROID_VERSION, result.reason);
    }

    @Test public void requiresHdr10AmongDisplayTypes() {
        assertFalse(StreamHdrDisplayPolicy.evaluate(
                true, true, null, HDR10).enabled);
        assertFalse(StreamHdrDisplayPolicy.evaluate(
                true, true, new int[] { 1, 3 }, HDR10).enabled);

        StreamHdrDisplayPolicy.Result supported = StreamHdrDisplayPolicy.evaluate(
                true, true, new int[] { 1, HDR10, 3 }, HDR10);
        assertTrue(supported.enabled);
        assertEquals(StreamHdrDisplayPolicy.Reason.ENABLED, supported.reason);
    }
}
