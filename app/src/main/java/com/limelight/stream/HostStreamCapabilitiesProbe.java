package com.limelight.stream;

import com.limelight.nvstream.http.NvHTTP;

import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** Reads host stream limits from an already-fetched serverinfo response. */
public final class HostStreamCapabilitiesProbe {
    private static final long H264_CODEC_MODES = 0x3;
    private static final long HEVC_CODEC_MODES = 0xF00;

    private HostStreamCapabilitiesProbe() { }

    public static final class Result {
        public final StreamingAutopilot.Capabilities capabilities;
        public final String serverVersion;
        public final String backendVersion;
        public final String gpu;
        public final long serverCodecModeSupport;
        public final long maxLumaPixelsH264;
        public final long maxLumaPixelsHevc;
        public final boolean supports4K;

        private Result(StreamingAutopilot.Capabilities capabilities, String serverVersion,
                       String backendVersion, String gpu, long serverCodecModeSupport,
                       long maxLumaPixelsH264, long maxLumaPixelsHevc, boolean supports4K) {
            this.capabilities = capabilities;
            this.serverVersion = serverVersion;
            this.backendVersion = backendVersion;
            this.gpu = gpu;
            this.serverCodecModeSupport = serverCodecModeSupport;
            this.maxLumaPixelsH264 = maxLumaPixelsH264;
            this.maxLumaPixelsHevc = maxLumaPixelsHevc;
            this.supports4K = supports4K;
        }
    }

    public static Result probe(NvHTTP http, String serverInfo)
            throws IOException, XmlPullParserException {
        String serverVersion = http.getServerVersion(serverInfo);
        String backendVersion = http.getGfeVersion(serverInfo);
        String gpu = http.getGpuType(serverInfo);
        long codecModes = http.getServerCodecModeSupport(serverInfo);
        long maxLumaH264 = http.getMaxLumaPixelsH264(serverInfo);
        long maxLumaHevc = http.getMaxLumaPixelsHEVC(serverInfo);
        boolean supports4K = http.supports4K(serverInfo);
        return new Result(capabilitiesFor(codecModes, maxLumaH264, maxLumaHevc, supports4K),
                serverVersion, backendVersion, gpu, codecModes, maxLumaH264, maxLumaHevc,
                supports4K);
    }

    static StreamingAutopilot.Capabilities capabilitiesFor(long codecModes,
                                                            long maxLumaH264,
                                                            long maxLumaHevc,
                                                            boolean supports4K) {
        boolean h264Supported = codecModes == 0 || (codecModes & H264_CODEC_MODES) != 0;
        boolean hevcSupported = (codecModes & HEVC_CODEC_MODES) != 0;
        List<StreamingAutopilot.Mode> h264Modes = new ArrayList<>();
        List<StreamingAutopilot.Mode> hevcModes = new ArrayList<>();
        for (StreamingAutopilot.Mode mode : StreamingAutopilot.standardModes()) {
            long lumaPixelsPerSecond = (long) mode.width * mode.height * mode.fps;
            if ((mode.width >= 3840 || mode.height >= 2160) && !supports4K) {
                continue;
            }
            if (h264Supported && (maxLumaH264 <= 0 ||
                    lumaPixelsPerSecond <= maxLumaH264)) h264Modes.add(mode);
            if (hevcSupported && (maxLumaHevc <= 0 ||
                    lumaPixelsPerSecond <= maxLumaHevc)) hevcModes.add(mode);
        }
        return StreamingAutopilot.forCodecModes(h264Modes, hevcModes);
    }
}
