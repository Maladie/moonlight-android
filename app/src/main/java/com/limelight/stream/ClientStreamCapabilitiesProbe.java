package com.limelight.stream;

import android.content.Context;
import android.graphics.Point;
import android.media.MediaCodecInfo;
import android.os.Build;
import android.view.Display;

import com.limelight.binding.video.MediaCodecHelper;

import java.util.ArrayList;
import java.util.List;

/** Probes the stream modes that both the display and a safe decoder can handle. */
public final class ClientStreamCapabilitiesProbe {
    private static final String AVC_MIME_TYPE = "video/avc";
    private static final String HEVC_MIME_TYPE = "video/hevc";

    private ClientStreamCapabilitiesProbe() { }

    public static StreamingAutopilot.Capabilities probe(Context context, Display display,
                                                         String glRenderer) {
        MediaCodecHelper.initialize(context, glRenderer);

        MediaCodecInfo avcDecoder = MediaCodecHelper.findProbableSafeDecoder(
                AVC_MIME_TYPE, MediaCodecInfo.CodecProfileLevel.AVCProfileHigh);
        if (avcDecoder == null) {
            avcDecoder = MediaCodecHelper.findFirstDecoder(AVC_MIME_TYPE);
        }

        MediaCodecInfo hevcDecoder = MediaCodecHelper.findProbableSafeDecoder(HEVC_MIME_TYPE, -1);
        if (hevcDecoder != null && !MediaCodecHelper.decoderIsWhitelistedForHevc(hevcDecoder)) {
            hevcDecoder = null;
        }

        List<StreamingAutopilot.Mode> h264Modes = new ArrayList<>();
        List<StreamingAutopilot.Mode> hevcModes = new ArrayList<>();
        for (StreamingAutopilot.Mode candidate : StreamingAutopilot.standardModes()) {
            if (!displaySupports(display, candidate)) continue;
            if (decoderSupports(avcDecoder, AVC_MIME_TYPE, candidate)) h264Modes.add(candidate);
            if (decoderSupports(hevcDecoder, HEVC_MIME_TYPE, candidate)) hevcModes.add(candidate);
        }
        return StreamingAutopilot.forCodecModes(h264Modes, hevcModes);
    }

    private static boolean displaySupports(Display display, StreamingAutopilot.Mode streamMode) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            for (Display.Mode displayMode : display.getSupportedModes()) {
                if (displayModeSupports(streamMode, displayMode.getPhysicalWidth(),
                        displayMode.getPhysicalHeight(), displayMode.getRefreshRate())) {
                    return true;
                }
            }
            return false;
        }

        Point size = new Point();
        display.getRealSize(size);
        return displayModeSupports(streamMode, size.x, size.y, display.getRefreshRate());
    }

    static boolean displayModeSupports(StreamingAutopilot.Mode streamMode,
                                       int displayWidth, int displayHeight, float refreshRate) {
        int normalizedWidth = Math.max(displayWidth, displayHeight);
        int normalizedHeight = Math.min(displayWidth, displayHeight);
        return streamMode.width <= normalizedWidth && streamMode.height <= normalizedHeight &&
                streamMode.fps <= refreshRate + 2f;
    }

    private static boolean decoderSupports(MediaCodecInfo decoder, String mimeType,
                                            StreamingAutopilot.Mode streamMode) {
        if (decoder == null) {
            return false;
        }
        try {
            MediaCodecInfo.VideoCapabilities caps =
                    decoder.getCapabilitiesForType(mimeType).getVideoCapabilities();
            return MediaCodecHelper.decoderCanMeetPerformancePoint(
                    caps, streamMode.width, streamMode.height, streamMode.fps);
        } catch (RuntimeException e) {
            return false;
        }
    }
}
