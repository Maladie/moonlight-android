package com.limelight.console;

import com.limelight.nvstream.jni.MoonBridge;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class StreamVideoFormatPolicyTest {
    @Test public void h264IsAlwaysAdvertised() {
        StreamVideoFormatPolicy.Result result = StreamVideoFormatPolicy.evaluate(
                false, false, false, false, false);

        assertEquals(MoonBridge.VIDEO_FORMAT_H264, result.supportedFormats);
        assertFalse(result.hdrEnabled);
    }

    @Test public void hdrRequiresAtLeastOneMain10Decoder() {
        assertFalse(StreamVideoFormatPolicy.evaluate(
                true, true, false, true, false).hdrEnabled);
        assertTrue(StreamVideoFormatPolicy.evaluate(
                true, true, true, false, false).hdrEnabled);
    }

    @Test public void supportedMain8AndMain10FormatsAreIndependent() {
        StreamVideoFormatPolicy.Result result = StreamVideoFormatPolicy.evaluate(
                true, true, true, true, false);

        int expected = MoonBridge.VIDEO_FORMAT_H264 |
                MoonBridge.VIDEO_FORMAT_H265 |
                MoonBridge.VIDEO_FORMAT_H265_MAIN10 |
                MoonBridge.VIDEO_FORMAT_AV1_MAIN8;
        assertEquals(expected, result.supportedFormats);
        assertTrue(result.hdrEnabled);
    }

    @Test public void main10IsNotAdvertisedWhenHdrWasNotRequested() {
        StreamVideoFormatPolicy.Result result = StreamVideoFormatPolicy.evaluate(
                false, true, true, true, true);

        assertEquals(MoonBridge.VIDEO_FORMAT_H264 |
                        MoonBridge.VIDEO_FORMAT_H265 | MoonBridge.VIDEO_FORMAT_AV1_MAIN8,
                result.supportedFormats);
        assertFalse(result.hdrEnabled);
    }
}
