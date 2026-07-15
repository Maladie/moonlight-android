package com.limelight.console;

import com.limelight.nvstream.StreamConfiguration;
import com.limelight.nvstream.http.NvApp;
import com.limelight.preferences.PreferenceConfiguration;

/** Combines decoder, display, and controller negotiation into a session plan. */
public final class StreamSessionConfigurationPlanner {
    public static final class DecoderCapabilities {
        public final boolean hevc;
        public final boolean hevcMain10Hdr10;
        public final boolean av1;
        public final boolean av1Main10;
        public final int colorSpace;
        public final int colorRange;

        public DecoderCapabilities(boolean hevc,
                                   boolean hevcMain10Hdr10,
                                   boolean av1,
                                   boolean av1Main10,
                                   int colorSpace,
                                   int colorRange) {
            this.hevc = hevc;
            this.hevcMain10Hdr10 = hevcMain10Hdr10;
            this.av1 = av1;
            this.av1Main10 = av1Main10;
            this.colorSpace = colorSpace;
            this.colorRange = colorRange;
        }
    }

    public static final class Plan {
        public final StreamConfiguration configuration;
        public final boolean hdrEnabled;
        public final boolean hdrDecoderUnavailable;
        public final boolean forcedHevcUnavailable;
        public final boolean forcedAv1Unavailable;
        public final int effectiveFramePacing;
        public final StreamFrameRatePolicy.Adjustment frameRateAdjustment;

        private Plan(StreamConfiguration configuration,
                     boolean hdrEnabled,
                     boolean hdrDecoderUnavailable,
                     boolean forcedHevcUnavailable,
                     boolean forcedAv1Unavailable,
                     int effectiveFramePacing,
                     StreamFrameRatePolicy.Adjustment frameRateAdjustment) {
            this.configuration = configuration;
            this.hdrEnabled = hdrEnabled;
            this.hdrDecoderUnavailable = hdrDecoderUnavailable;
            this.forcedHevcUnavailable = forcedHevcUnavailable;
            this.forcedAv1Unavailable = forcedAv1Unavailable;
            this.effectiveFramePacing = effectiveFramePacing;
            this.frameRateAdjustment = frameRateAdjustment;
        }
    }

    private StreamSessionConfigurationPlanner() { }

    public static Plan plan(PreferenceConfiguration preferences,
                            NvApp app,
                            boolean requestedHdr,
                            float displayRefreshRate,
                            int attachedGamepadMask,
                            DecoderCapabilities decoder) {
        return plan(preferences, app, requestedHdr, displayRefreshRate,
                attachedGamepadMask, decoder, !preferences.multiController);
    }

    public static Plan plan(PreferenceConfiguration preferences,
                            NvApp app,
                            boolean requestedHdr,
                            float displayRefreshRate,
                            int attachedGamepadMask,
                            DecoderCapabilities decoder,
                            boolean persistGamepadsAfterDisconnect) {
        StreamVideoFormatPolicy.Result videoFormats = StreamVideoFormatPolicy.evaluate(
                requestedHdr, decoder.hevc, decoder.hevcMain10Hdr10,
                decoder.av1, decoder.av1Main10);
        StreamFrameRatePolicy.Result frameRate = StreamFrameRatePolicy.evaluate(
                preferences.fps, preferences.framePacing, displayRefreshRate);
        int gamepadMask = StreamGamepadMaskPolicy.evaluate(
                attachedGamepadMask, preferences.multiController,
                preferences.onscreenController);
        StreamConfiguration configuration = StreamConfigurationFactory.build(
                preferences, app, frameRate.frameRate, videoFormats.supportedFormats,
                gamepadMask, decoder.colorSpace, decoder.colorRange,
                persistGamepadsAfterDisconnect);

        return new Plan(
                configuration,
                videoFormats.hdrEnabled,
                requestedHdr && !videoFormats.hdrEnabled,
                preferences.videoFormat == PreferenceConfiguration.FormatOption.FORCE_HEVC &&
                        !decoder.hevc,
                preferences.videoFormat == PreferenceConfiguration.FormatOption.FORCE_AV1 &&
                        !decoder.av1,
                frameRate.framePacing,
                frameRate.adjustment);
    }
}
