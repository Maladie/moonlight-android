package com.limelight.console;

import com.limelight.nvstream.StreamConfiguration;
import com.limelight.nvstream.http.NvApp;
import com.limelight.preferences.PreferenceConfiguration;

/** Builds the transport configuration after display and decoder negotiation. */
public final class StreamConfigurationFactory {
    private static final int MAX_PACKET_SIZE = 1392;

    private StreamConfigurationFactory() { }

    public static StreamConfiguration build(PreferenceConfiguration preferences,
                                            NvApp app,
                                            int effectiveFrameRate,
                                            int supportedVideoFormats,
                                            int gamepadMask,
                                            int colorSpace,
                                            int colorRange) {
        StreamConfiguration.Builder builder = new StreamConfiguration.Builder()
                .setResolution(preferences.width, preferences.height)
                .setLaunchRefreshRate(preferences.fps)
                .setRefreshRate(effectiveFrameRate)
                .setApp(app)
                .setEnableUltraLowLatency(preferences.enableUltraLowLatency)
                .setBitrate(preferences.bitrate)
                .setEnableSops(preferences.enableSops)
                .enableLocalAudioPlayback(preferences.playHostAudio)
                .setMaxPacketSize(MAX_PACKET_SIZE)
                .setRemoteConfiguration(StreamConfiguration.STREAM_CFG_AUTO)
                .setSupportedVideoFormats(supportedVideoFormats)
                .setAttachedGamepadMask(gamepadMask)
                .setAudioConfiguration(preferences.audioConfiguration)
                .setColorSpace(colorSpace)
                .setColorRange(colorRange)
                .setPersistGamepadsAfterDisconnect(!preferences.multiController);

        int refreshRateX100 = StreamRefreshRateOverridePolicy.parseX100(
                preferences.actualDisplayRefreshRate);
        if (refreshRateX100 > 0) {
            builder.setClientRefreshRateX100(refreshRateX100);
        }
        return builder.build();
    }
}
