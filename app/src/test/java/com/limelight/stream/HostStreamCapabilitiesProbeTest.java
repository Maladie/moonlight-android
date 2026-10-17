package com.limelight.stream;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class HostStreamCapabilitiesProbeTest {
    @Test public void missingModernFieldsFallsBackToNon4KH264() {
        StreamingAutopilot.Capabilities capabilities =
                HostStreamCapabilitiesProbe.capabilitiesFor(0, 0, 0, false);

        assertTrue(capabilities.supports(2560, 1440, 120));
        assertFalse(capabilities.supports(3840, 2160, 30));
    }

    @Test public void eachCodecUsesItsOwnLumaLimit() {
        StreamingAutopilot.Capabilities h264Only =
                HostStreamCapabilitiesProbe.capabilitiesFor(
                        0x2, 1920L * 1080 * 60, 0, true);
        assertTrue(h264Only.supports(1920, 1080, 60));
        assertFalse(h264Only.supports(1920, 1080, 90));

        StreamingAutopilot.Capabilities withHevc =
                HostStreamCapabilitiesProbe.capabilitiesFor(
                        0x102, 1920L * 1080 * 60, 3840L * 2160 * 60, true);
        assertTrue(withHevc.supports(3840, 2160, 60));
        assertFalse(withHevc.supports(3840, 2160, 90));
    }
}
