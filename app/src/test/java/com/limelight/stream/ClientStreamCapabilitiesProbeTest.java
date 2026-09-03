package com.limelight.stream;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ClientStreamCapabilitiesProbeTest {
    @Test public void resolutionAndFpsMustFitTheSameDisplayMode() {
        StreamingAutopilot.Mode fourK120 = new StreamingAutopilot.Mode(3840, 2160, 120);

        assertFalse(ClientStreamCapabilitiesProbe.displayModeSupports(
                fourK120, 3840, 2160, 60));
        assertFalse(ClientStreamCapabilitiesProbe.displayModeSupports(
                fourK120, 1920, 1080, 120));
        assertTrue(ClientStreamCapabilitiesProbe.displayModeSupports(
                new StreamingAutopilot.Mode(3840, 2160, 60), 3840, 2160, 60));
        assertTrue(ClientStreamCapabilitiesProbe.displayModeSupports(
                new StreamingAutopilot.Mode(1920, 1080, 120), 1920, 1080, 120));
    }

    @Test public void acceptsTheSameSmallRefreshRateShortfallAsStreamSettings() {
        assertTrue(ClientStreamCapabilitiesProbe.displayModeSupports(
                new StreamingAutopilot.Mode(1920, 1080, 120), 1920, 1080, 118));
        assertTrue(ClientStreamCapabilitiesProbe.displayModeSupports(
                new StreamingAutopilot.Mode(1920, 1080, 90), 1920, 1080, 88));
        assertFalse(ClientStreamCapabilitiesProbe.displayModeSupports(
                new StreamingAutopilot.Mode(1920, 1080, 120), 1920, 1080, 117.9f));
    }
}
