package com.limelight.console;

import com.limelight.nvstream.jni.MoonBridge;

/** Pure decoder capability negotiation shared by Game and the future in-Activity runtime. */
public final class StreamVideoFormatPolicy {
    public static final class Result {
        public final boolean hdrEnabled;
        public final int supportedFormats;

        private Result(boolean hdrEnabled, int supportedFormats) {
            this.hdrEnabled = hdrEnabled;
            this.supportedFormats = supportedFormats;
        }
    }

    private StreamVideoFormatPolicy() { }

    public static Result evaluate(boolean requestedHdr,
                                  boolean hevcSupported,
                                  boolean hevcMain10Hdr10Supported,
                                  boolean av1Supported,
                                  boolean av1Main10Supported) {
        boolean hdr = requestedHdr &&
                (hevcMain10Hdr10Supported || av1Main10Supported);
        int formats = MoonBridge.VIDEO_FORMAT_H264;
        if (hevcSupported) {
            formats |= MoonBridge.VIDEO_FORMAT_H265;
            if (hdr && hevcMain10Hdr10Supported) {
                formats |= MoonBridge.VIDEO_FORMAT_H265_MAIN10;
            }
        }
        if (av1Supported) {
            formats |= MoonBridge.VIDEO_FORMAT_AV1_MAIN8;
            if (hdr && av1Main10Supported) {
                formats |= MoonBridge.VIDEO_FORMAT_AV1_MAIN10;
            }
        }
        return new Result(hdr, formats);
    }
}
