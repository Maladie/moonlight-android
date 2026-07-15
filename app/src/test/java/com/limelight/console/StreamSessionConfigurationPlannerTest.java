package com.limelight.console;

import com.limelight.nvstream.http.NvApp;
import com.limelight.nvstream.jni.MoonBridge;
import com.limelight.preferences.PreferenceConfiguration;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class StreamSessionConfigurationPlannerTest {
    @Test public void combinesNegotiatedCodecFrameRateAndControllerMask() {
        PreferenceConfiguration preferences = preferences();
        StreamSessionConfigurationPlanner.DecoderCapabilities decoder =
                new StreamSessionConfigurationPlanner.DecoderCapabilities(
                        true, true, true, false, 2, 1);

        StreamSessionConfigurationPlanner.Plan plan =
                StreamSessionConfigurationPlanner.plan(preferences,
                        new NvApp("Desktop", 7, true), true, 59.94f, 0b1010, decoder);

        assertTrue(plan.hdrEnabled);
        assertFalse(plan.hdrDecoderUnavailable);
        assertEquals(59, plan.configuration.getRefreshRate());
        assertEquals(0b1011, plan.configuration.getAttachedGamepadMask());
        assertEquals(MoonBridge.VIDEO_FORMAT_H264 |
                        MoonBridge.VIDEO_FORMAT_H265 |
                        MoonBridge.VIDEO_FORMAT_H265_MAIN10 |
                        MoonBridge.VIDEO_FORMAT_AV1_MAIN8,
                plan.configuration.getSupportedVideoFormats());
        assertEquals(StreamFrameRatePolicy.Adjustment.CAP_TO_DISPLAY,
                plan.frameRateAdjustment);
    }

    @Test public void reportsUnavailableRequestedAndForcedDecoders() {
        PreferenceConfiguration preferences = preferences();
        preferences.videoFormat = PreferenceConfiguration.FormatOption.FORCE_AV1;
        StreamSessionConfigurationPlanner.DecoderCapabilities decoder =
                new StreamSessionConfigurationPlanner.DecoderCapabilities(
                        true, false, false, false, 0, 0);

        StreamSessionConfigurationPlanner.Plan plan =
                StreamSessionConfigurationPlanner.plan(preferences,
                        new NvApp("Desktop", 7, true), true, 60f, 0, decoder);

        assertFalse(plan.hdrEnabled);
        assertTrue(plan.hdrDecoderUnavailable);
        assertTrue(plan.forcedAv1Unavailable);
        assertFalse(plan.forcedHevcUnavailable);
    }

    private static PreferenceConfiguration preferences() {
        PreferenceConfiguration preferences = new PreferenceConfiguration();
        preferences.width = 1920;
        preferences.height = 1080;
        preferences.fps = 60;
        preferences.framePacing = PreferenceConfiguration.FRAME_PACING_CAP_FPS;
        preferences.bitrate = 20000;
        preferences.enableSops = true;
        preferences.multiController = true;
        preferences.onscreenController = true;
        preferences.videoFormat = PreferenceConfiguration.FormatOption.AUTO;
        preferences.audioConfiguration = new MoonBridge.AudioConfiguration(2, 0x3);
        return preferences;
    }
}
