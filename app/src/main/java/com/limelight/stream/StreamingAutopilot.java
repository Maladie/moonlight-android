package com.limelight.stream;

import com.limelight.preferences.PreferenceConfiguration;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.OptionalInt;

/** Pure, deterministic recommendation logic for supported Moonlight stream settings. */
public final class StreamingAutopilot {
    private static final int MIN_BITRATE_KBPS = 500;
    private static final int BITRATE_STEP_KBPS = 500;
    private static final int MAX_BITRATE_KBPS =
            Integer.MAX_VALUE / BITRATE_STEP_KBPS * BITRATE_STEP_KBPS;
    private static final int CODEC_H264 = 1;
    private static final int CODEC_HEVC = 1 << 1;
    private static final int[][] RESOLUTIONS = {
            {640, 360},
            {854, 480},
            {1280, 720},
            {1920, 1080},
            {2560, 1440},
            {3840, 2160}
    };
    private static final int[] FRAME_RATES = {30, 60, 90, 120};

    private StreamingAutopilot() { }

    public enum Confidence {
        HIGH,
        LOW
    }

    public enum Reason {
        NETWORK_MEASUREMENT_UNAVAILABLE,
        CONSERVATIVE_FALLBACK,
        WITHIN_KNOWN_LIMITS,
        HIGHEST_RANKED_CANDIDATE,
        BUDGET_BELOW_MINIMUM_MODE,
        BITRATE_CAPPED_TO_SAFE_BUDGET
    }

    public static final class Mode {
        public final int width;
        public final int height;
        public final int fps;

        public Mode(int width, int height, int fps) {
            this.width = width;
            this.height = height;
            this.fps = fps;
        }
    }

    public static final class Capabilities {
        public final int maxWidth;
        public final int maxHeight;
        public final int maxFps;
        public final List<Mode> modes;
        private final List<Mode> h264Modes;
        private final List<Mode> hevcModes;

        public Capabilities(int maxWidth, int maxHeight, int maxFps) {
            this(modesUpTo(maxWidth, maxHeight, maxFps));
        }

        public Capabilities(Mode... modes) {
            this(modes, modes);
        }

        private Capabilities(Mode[] h264Modes, Mode[] hevcModes) {
            if (h264Modes == null || hevcModes == null ||
                    h264Modes.length + hevcModes.length == 0) {
                throw new IllegalArgumentException("At least one stream mode is required");
            }
            List<Mode> supportedH264 = validatedModes(h264Modes);
            List<Mode> supportedHevc = validatedModes(hevcModes);
            List<Mode> supported = new ArrayList<>(supportedH264);
            for (Mode mode : supportedHevc) {
                if (!containsMode(supported, mode.width, mode.height, mode.fps)) {
                    supported.add(mode);
                }
            }
            int width = 0;
            int height = 0;
            int fps = 0;
            for (Mode mode : supported) {
                width = Math.max(width, mode.width);
                height = Math.max(height, mode.height);
                fps = Math.max(fps, mode.fps);
            }
            this.maxWidth = width;
            this.maxHeight = height;
            this.maxFps = fps;
            this.modes = Collections.unmodifiableList(supported);
            this.h264Modes = Collections.unmodifiableList(supportedH264);
            this.hevcModes = Collections.unmodifiableList(supportedHevc);
        }

        public static Capabilities upTo(int maxWidth, int maxHeight, int maxFps) {
            return new Capabilities(maxWidth, maxHeight, maxFps);
        }

        boolean supports(int width, int height, int fps) {
            return containsMode(modes, width, height, fps);
        }

        private int codecsFor(int width, int height, int fps) {
            int codecs = 0;
            if (containsMode(h264Modes, width, height, fps)) codecs |= CODEC_H264;
            if (containsMode(hevcModes, width, height, fps)) codecs |= CODEC_HEVC;
            return codecs;
        }
    }

    static Capabilities forCodecModes(List<Mode> h264Modes, List<Mode> hevcModes) {
        return new Capabilities(h264Modes.toArray(new Mode[0]), hevcModes.toArray(new Mode[0]));
    }

    public static final class Recommendation {
        public final int width;
        public final int height;
        public final int fps;
        public final int bitrateKbps;
        public final Confidence confidence;
        public final List<Reason> reasons;

        private Recommendation(Candidate candidate, int bitrateKbps,
                               Confidence confidence, Reason... reasons) {
            width = candidate.width;
            height = candidate.height;
            fps = candidate.fps;
            this.bitrateKbps = bitrateKbps;
            this.confidence = confidence;
            this.reasons = Collections.unmodifiableList(Arrays.asList(reasons));
        }
    }

    /** An empty bitrate budget means that no network measurement is available. */
    public static Recommendation recommend(Capabilities client, Capabilities host,
                                           OptionalInt safeBitrateKbps) {
        return recommend(client, host, safeBitrateKbps,
                PreferenceConfiguration.FormatOption.AUTO);
    }

    public static Recommendation recommend(Capabilities client, Capabilities host,
                                           OptionalInt safeBitrateKbps,
                                           PreferenceConfiguration.FormatOption format) {
        Objects.requireNonNull(client, "client");
        Objects.requireNonNull(host, "host");
        Objects.requireNonNull(safeBitrateKbps, "safeBitrateKbps");
        Objects.requireNonNull(format, "format");
        if (safeBitrateKbps.isPresent()
                && safeBitrateKbps.getAsInt() < MIN_BITRATE_KBPS) {
            throw new IllegalArgumentException("Safe bitrate budget is below the supported minimum");
        }
        int allowedCodecs = format == PreferenceConfiguration.FormatOption.FORCE_H264
                ? CODEC_H264 : format == PreferenceConfiguration.FormatOption.FORCE_HEVC
                ? CODEC_HEVC : CODEC_H264 | CODEC_HEVC;

        if (!safeBitrateKbps.isPresent()) {
            Candidate fallback = bestCandidate(client, host, 1280, 720, 60,
                    OptionalInt.empty(), allowedCodecs);
            return new Recommendation(fallback, fallback.defaultBitrateKbps, Confidence.LOW,
                    Reason.NETWORK_MEASUREMENT_UNAVAILABLE,
                    Reason.CONSERVATIVE_FALLBACK);
        }

        int budget = safeBitrateKbps.getAsInt() / BITRATE_STEP_KBPS * BITRATE_STEP_KBPS;
        Candidate candidate = bestCandidate(client, host, Integer.MAX_VALUE,
                Integer.MAX_VALUE, Integer.MAX_VALUE, OptionalInt.of(budget), allowedCodecs);
        if (candidate != null) {
            return new Recommendation(candidate, candidate.defaultBitrateKbps, Confidence.HIGH,
                    Reason.WITHIN_KNOWN_LIMITS,
                    Reason.HIGHEST_RANKED_CANDIDATE);
        }

        Candidate minimum = lowestCandidate(client, host, allowedCodecs);
        return new Recommendation(minimum, budget, Confidence.LOW,
                Reason.BUDGET_BELOW_MINIMUM_MODE,
                Reason.BITRATE_CAPPED_TO_SAFE_BUDGET);
    }

    public static int safeBitrateBudgetKbps(long bytes, long elapsedNanos) {
        if (bytes <= 0 || elapsedNanos <= 0) {
            throw new IllegalArgumentException("Network sample must be positive");
        }
        double budget = bytes * 8_000_000d / elapsedNanos * 0.75 - 1_000;
        if (budget <= 0) return 0;
        long bounded = (long) Math.min(budget, MAX_BITRATE_KBPS);
        return (int) (bounded / BITRATE_STEP_KBPS * BITRATE_STEP_KBPS);
    }

    private static Candidate bestCandidate(Capabilities client, Capabilities host,
                                           int maxWidth, int maxHeight, int maxFps,
                                           OptionalInt budget, int allowedCodecs) {
        Candidate best = null;
        for (int[] resolution : RESOLUTIONS) {
            for (int fps : FRAME_RATES) {
                Candidate candidate = candidate(resolution, fps);
                if (!candidate.fits(maxWidth, maxHeight, maxFps)
                        || (client.codecsFor(candidate.width, candidate.height, candidate.fps)
                        & host.codecsFor(candidate.width, candidate.height, candidate.fps)
                        & allowedCodecs) == 0
                        || (budget.isPresent()
                        && candidate.defaultBitrateKbps > budget.getAsInt())) {
                    continue;
                }
                if (best == null || candidate.isBetterThan(best)) best = candidate;
            }
        }
        if (best == null && !budget.isPresent()) {
            throw new IllegalArgumentException("No discrete stream mode fits the capabilities");
        }
        return best;
    }

    private static Candidate lowestCandidate(Capabilities client, Capabilities host,
                                             int allowedCodecs) {
        for (int[] resolution : RESOLUTIONS) {
            for (int fps : FRAME_RATES) {
                Candidate candidate = candidate(resolution, fps);
                if ((client.codecsFor(candidate.width, candidate.height, candidate.fps)
                        & host.codecsFor(candidate.width, candidate.height, candidate.fps)
                        & allowedCodecs) != 0) {
                    return candidate;
                }
            }
        }
        throw new IllegalArgumentException("No discrete stream mode fits the capabilities");
    }

    private static Mode[] modesUpTo(int maxWidth, int maxHeight, int maxFps) {
        if (maxWidth <= 0 || maxHeight <= 0 || maxFps <= 0) {
            throw new IllegalArgumentException("Stream capability limits must be positive");
        }
        List<Mode> modes = new ArrayList<>();
        for (int[] resolution : RESOLUTIONS) {
            for (int fps : FRAME_RATES) {
                if (resolution[0] <= maxWidth && resolution[1] <= maxHeight && fps <= maxFps) {
                    modes.add(new Mode(resolution[0], resolution[1], fps));
                }
            }
        }
        return modes.toArray(new Mode[0]);
    }

    static Mode[] standardModes() {
        return modesUpTo(Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE);
    }

    private static boolean isDiscreteMode(int width, int height, int fps) {
        for (int[] resolution : RESOLUTIONS) {
            if (resolution[0] != width || resolution[1] != height) continue;
            for (int candidateFps : FRAME_RATES) {
                if (candidateFps == fps) return true;
            }
        }
        return false;
    }

    private static List<Mode> validatedModes(Mode[] modes) {
        List<Mode> supported = new ArrayList<>(modes.length);
        for (Mode mode : modes) {
            if (mode == null || !isDiscreteMode(mode.width, mode.height, mode.fps)) {
                throw new IllegalArgumentException("Unsupported discrete stream mode");
            }
            supported.add(mode);
        }
        return supported;
    }

    private static boolean containsMode(List<Mode> modes, int width, int height, int fps) {
        for (Mode mode : modes) {
            if (mode.width == width && mode.height == height && mode.fps == fps) return true;
        }
        return false;
    }

    private static Candidate candidate(int[] resolution, int fps) {
        int bitrate = PreferenceConfiguration.getDefaultBitrate(
                resolution[0] + "x" + resolution[1], Integer.toString(fps));
        return new Candidate(resolution[0], resolution[1], fps, bitrate);
    }

    private static final class Candidate {
        final int width;
        final int height;
        final int fps;
        final int defaultBitrateKbps;

        Candidate(int width, int height, int fps, int defaultBitrateKbps) {
            this.width = width;
            this.height = height;
            this.fps = fps;
            this.defaultBitrateKbps = defaultBitrateKbps;
        }

        boolean fits(int maxWidth, int maxHeight, int maxFps) {
            return width <= maxWidth && height <= maxHeight && fps <= maxFps;
        }

        boolean isBetterThan(Candidate other) {
            if (defaultBitrateKbps != other.defaultBitrateKbps) {
                return defaultBitrateKbps > other.defaultBitrateKbps;
            }
            if (fps != other.fps) return fps > other.fps;
            return width * height > other.width * other.height;
        }
    }
}
