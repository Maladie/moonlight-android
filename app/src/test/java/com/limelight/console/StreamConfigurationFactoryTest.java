package com.limelight.console;

import com.limelight.nvstream.StreamConfiguration;
import com.limelight.nvstream.http.NvApp;
import com.limelight.nvstream.jni.MoonBridge;
import com.limelight.preferences.PreferenceConfiguration;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class StreamConfigurationFactoryTest {
    @Test public void mapsNegotiatedAndPreferenceValuesToTransportConfiguration() {
        PreferenceConfiguration preferences = preferences();
        NvApp app = new NvApp("Desktop", 7, true);

        StreamConfiguration config = StreamConfigurationFactory.build(
                preferences, app, 59, 0x35, 0x0b, 2, 1);

        assertEquals(2560, config.getWidth());
        assertEquals(1440, config.getHeight());
        assertEquals(60, config.getLaunchRefreshRate());
        assertEquals(59, config.getRefreshRate());
        assertSame(app, config.getApp());
        assertEquals(42000, config.getBitrate());
        assertEquals(1392, config.getMaxPacketSize());
        assertEquals(StreamConfiguration.STREAM_CFG_AUTO, config.getRemote());
        assertEquals(0x35, config.getSupportedVideoFormats());
        assertEquals(0x0b, config.getAttachedGamepadMask());
        assertEquals(2, config.getAudioConfiguration().channelCount);
        assertEquals(0x3, config.getAudioConfiguration().channelMask);
        assertEquals(2, config.getColorSpace());
        assertEquals(1, config.getColorRange());
        assertEquals(5994, config.getClientRefreshRateX100());
        assertTrue(config.getSops());
        assertTrue(config.getPlayLocalAudio());
        assertTrue(config.getEnableUltraLowLatency());
        assertFalse(config.getPersistGamepadsAfterDisconnect());
    }

    @Test public void persistsPrimaryGamepadWhenMultipleControllersAreDisabled() {
        PreferenceConfiguration preferences = preferences();
        preferences.multiController = false;
        preferences.actualDisplayRefreshRate = "invalid";

        StreamConfiguration config = StreamConfigurationFactory.build(
                preferences, new NvApp("Desktop"), 60, 1, 1, 0, 0);

        assertTrue(config.getPersistGamepadsAfterDisconnect());
        assertEquals(0, config.getClientRefreshRateX100());
    }

    private static PreferenceConfiguration preferences() {
        PreferenceConfiguration preferences = new PreferenceConfiguration();
        preferences.width = 2560;
        preferences.height = 1440;
        preferences.fps = 60;
        preferences.bitrate = 42000;
        preferences.enableUltraLowLatency = true;
        preferences.enableSops = true;
        preferences.playHostAudio = true;
        preferences.multiController = true;
        preferences.audioConfiguration = new MoonBridge.AudioConfiguration(2, 0x3);
        preferences.actualDisplayRefreshRate = "59.94";
        return preferences;
    }
}
