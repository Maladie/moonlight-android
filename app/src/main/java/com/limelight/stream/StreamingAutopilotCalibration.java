package com.limelight.stream;

import com.limelight.preferences.PreferenceConfiguration;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Pure evaluation of one-second video-stat windows for a candidate stream mode. */
public final class StreamingAutopilotCalibration {
    public static final long WARMUP_MS = 30_000L;
    public static final long MEASUREMENT_MS = 4 * 60_000L;
    public static final double MIN_FPS_RATIO = 0.90;
    public static final double MAX_FRAME_LOSS_PERCENT = 1.0;
    public static final double MAX_RTT_AND_VARIANCE_MS = 100.0;
    public static final double MAX_RTT_VARIANCE_MS = 1.0;
    public static final double MIN_HOST_PROCESSING_COVERAGE_RATIO = 0.90;

    public enum Phase { WAITING, WARMING_UP, MEASURING, COMPLETE }

    public enum Reason {
        RECEIVED_FPS_LOW,
        RENDERED_FPS_LOW,
        FRAME_LOSS_HIGH,
        RTT_HIGH,
        RTT_VARIANCE_HIGH,
        DECODER_LATENCY_HIGH,
        HOST_PROCESSING_LATENCY_HIGH,
        HOST_PROCESSING_COVERAGE_LOW
    }

    public static final class Sample {
        public final long windowStartMs;
        public final long windowEndMs;
        public final double receivedFps;
        public final double renderedFps;
        public final double frameLossPercent;
        public final double rttMs;
        public final double rttVarianceMs;
        public final double decoderLatencyMs;
        public final double hostProcessingLatencyMs;
        public final double hostProcessingReportedRatio;

        public Sample(long windowStartMs, long windowEndMs, double receivedFps,
                      double renderedFps, double frameLossPercent, double rttMs,
                      double rttVarianceMs, double decoderLatencyMs,
                      double hostProcessingLatencyMs,
                      double hostProcessingReportedRatio) {
            if (windowStartMs < 0 || windowEndMs <= windowStartMs
                    || !nonNegative(receivedFps, renderedFps, frameLossPercent, rttMs,
                    rttVarianceMs, decoderLatencyMs, hostProcessingLatencyMs,
                    hostProcessingReportedRatio) || hostProcessingReportedRatio > 1) {
                throw new IllegalArgumentException("Invalid calibration sample");
            }
            this.windowStartMs = windowStartMs;
            this.windowEndMs = windowEndMs;
            this.receivedFps = receivedFps;
            this.renderedFps = renderedFps;
            this.frameLossPercent = frameLossPercent;
            this.rttMs = rttMs;
            this.rttVarianceMs = rttVarianceMs;
            this.decoderLatencyMs = decoderLatencyMs;
            this.hostProcessingLatencyMs = hostProcessingLatencyMs;
            this.hostProcessingReportedRatio = hostProcessingReportedRatio;
        }

        long durationMs() {
            return windowEndMs - windowStartMs;
        }

        private static boolean nonNegative(double... values) {
            for (double value : values) {
                if (Double.isNaN(value) || Double.isInfinite(value) || value < 0) {
                    return false;
                }
            }
            return true;
        }
    }

    public static final class Result {
        public final boolean passed;
        public final boolean lowerBitrateSuggested;
        public final boolean lowerModeSuggested;
        public final int sampleCount;
        public final double receivedFps;
        public final double renderedFps;
        public final double frameLossPercent;
        public final double rttMs;
        public final double rttVarianceMs;
        public final double decoderLatencyMs;
        public final double hostProcessingLatencyMs;
        public final double hostProcessingReportedRatio;
        public final List<Reason> reasons;

        private Result(boolean passed, boolean lowerBitrateSuggested,
                       boolean lowerModeSuggested, int sampleCount, Totals totals,
                       List<Reason> reasons) {
            this.passed = passed;
            this.lowerBitrateSuggested = lowerBitrateSuggested;
            this.lowerModeSuggested = lowerModeSuggested;
            this.sampleCount = sampleCount;
            receivedFps = totals.average(totals.receivedFps);
            renderedFps = totals.average(totals.renderedFps);
            frameLossPercent = totals.average(totals.frameLossPercent);
            rttMs = totals.average(totals.rttMs);
            rttVarianceMs = totals.average(totals.rttVarianceMs);
            decoderLatencyMs = totals.average(totals.decoderLatencyMs);
            hostProcessingLatencyMs = totals.average(totals.hostProcessingLatencyMs);
            hostProcessingReportedRatio = totals.average(
                    totals.hostProcessingReportedRatio);
            this.reasons = Collections.unmodifiableList(reasons);
        }
    }

    public static final class Progress {
        public final Phase phase;
        public final long completedMs;
        public final long totalMs;
        public final Result result;

        private Progress(Phase phase, long completedMs, long totalMs, Result result) {
            this.phase = phase;
            this.completedMs = completedMs;
            this.totalMs = totalMs;
            this.result = result;
        }
    }

    public static final class Adjustment {
        public final int width;
        public final int height;
        public final int fps;
        public final int bitrateKbps;
        public final boolean conservative;

        private Adjustment(int width, int height, int fps, int bitrateKbps,
                           boolean conservative) {
            this.width = width;
            this.height = height;
            this.fps = fps;
            this.bitrateKbps = bitrateKbps;
            this.conservative = conservative;
        }
    }

    private final int targetFps;
    private final Totals totals = new Totals();
    private long armedAtMs = -1L;
    private long warmupMs;
    private int sampleCount;
    private Result result;

    public StreamingAutopilotCalibration(int targetFps) {
        if (targetFps <= 0) throw new IllegalArgumentException("Target FPS must be positive");
        this.targetFps = targetFps;
    }

    public void arm(long nowMs) {
        if (nowMs < 0 || armedAtMs >= 0) throw new IllegalStateException("Calibration already armed");
        armedAtMs = nowMs;
    }

    public Progress add(Sample sample) {
        if (result != null) return progress(Phase.COMPLETE, MEASUREMENT_MS, result);
        if (armedAtMs < 0) return progress(Phase.WAITING, 0, null);
        if (sample.windowStartMs < armedAtMs) {
            return progress(Phase.WARMING_UP, warmupMs, null);
        }
        if (warmupMs < WARMUP_MS) {
            warmupMs += sample.durationMs();
            return progress(Phase.WARMING_UP, Math.min(warmupMs, WARMUP_MS), null);
        }
        totals.add(sample);
        sampleCount++;
        if (totals.durationMs < MEASUREMENT_MS) {
            return progress(Phase.MEASURING, totals.durationMs, null);
        }
        result = evaluate();
        return progress(Phase.COMPLETE, MEASUREMENT_MS, result);
    }

    public static Adjustment adjustedSettings(int width, int height, int fps,
                                               int bitrateKbps, Result result) {
        if (bitrateKbps < 500 || result == null) {
            throw new IllegalArgumentException("Invalid calibration candidate");
        }
        StreamingAutopilot.Mode current = null;
        for (StreamingAutopilot.Mode mode : StreamingAutopilot.standardModes()) {
            if (mode.width == width && mode.height == height && mode.fps == fps) {
                current = mode;
                break;
            }
        }
        if (current == null) throw new IllegalArgumentException("Unsupported stream mode");
        if (result.passed) {
            return new Adjustment(width, height, fps, bitrateKbps, false);
        }

        StreamingAutopilot.Mode adjustedMode = current;
        if (result.lowerModeSuggested) {
            StreamingAutopilot.Mode lower = null;
            for (StreamingAutopilot.Mode mode : StreamingAutopilot.standardModes()) {
                if (mode.width == width && mode.height == height && mode.fps < fps
                        && (lower == null || mode.fps > lower.fps)) {
                    lower = mode;
                }
            }
            if (lower == null) {
                int currentPixels = width * height;
                for (StreamingAutopilot.Mode mode : StreamingAutopilot.standardModes()) {
                    int pixels = mode.width * mode.height;
                    if (mode.fps == 30 && pixels < currentPixels
                            && (lower == null
                            || pixels > lower.width * lower.height)) {
                        lower = mode;
                    }
                }
            }
            if (lower != null) adjustedMode = lower;
        }

        int adjustedBitrate = bitrateKbps;
        if (result.lowerBitrateSuggested || adjustedMode == current) {
            long reduced = (long) bitrateKbps * 3 / 4;
            adjustedBitrate = (int) Math.max(500L, reduced / 500 * 500);
        }
        if (adjustedMode != current) {
            int defaultBitrate = PreferenceConfiguration.getDefaultBitrate(
                    adjustedMode.width + "x" + adjustedMode.height,
                    Integer.toString(adjustedMode.fps));
            adjustedBitrate = Math.min(adjustedBitrate, defaultBitrate);
        }
        return new Adjustment(adjustedMode.width, adjustedMode.height,
                adjustedMode.fps, adjustedBitrate, true);
    }

    private Result evaluate() {
        List<Reason> reasons = new ArrayList<>();
        double minimumFps = targetFps * MIN_FPS_RATIO;
        double frameIntervalMs = 1_000d / targetFps;
        double receivedFps = totals.average(totals.receivedFps);
        double renderedFps = totals.average(totals.renderedFps);
        double loss = totals.average(totals.frameLossPercent);
        double rtt = totals.average(totals.rttMs);
        double variance = totals.average(totals.rttVarianceMs);
        double decoder = totals.average(totals.decoderLatencyMs);
        double host = totals.average(totals.hostProcessingLatencyMs);
        double hostCoverage = totals.average(totals.hostProcessingReportedRatio);
        if (receivedFps < minimumFps) reasons.add(Reason.RECEIVED_FPS_LOW);
        if (renderedFps < minimumFps) reasons.add(Reason.RENDERED_FPS_LOW);
        if (loss > MAX_FRAME_LOSS_PERCENT) reasons.add(Reason.FRAME_LOSS_HIGH);
        if (rtt + variance > MAX_RTT_AND_VARIANCE_MS) reasons.add(Reason.RTT_HIGH);
        if (variance > MAX_RTT_VARIANCE_MS) reasons.add(Reason.RTT_VARIANCE_HIGH);
        if (decoder > frameIntervalMs) reasons.add(Reason.DECODER_LATENCY_HIGH);
        if (host > frameIntervalMs) reasons.add(Reason.HOST_PROCESSING_LATENCY_HIGH);
        if (hostCoverage > 0 && hostCoverage < MIN_HOST_PROCESSING_COVERAGE_RATIO) {
            reasons.add(Reason.HOST_PROCESSING_COVERAGE_LOW);
        }
        boolean lowerBitrate = reasons.contains(Reason.FRAME_LOSS_HIGH)
                || reasons.contains(Reason.RTT_HIGH)
                || reasons.contains(Reason.RTT_VARIANCE_HIGH);
        boolean lowerMode = reasons.contains(Reason.RECEIVED_FPS_LOW)
                || reasons.contains(Reason.RENDERED_FPS_LOW)
                || reasons.contains(Reason.DECODER_LATENCY_HIGH)
                || reasons.contains(Reason.HOST_PROCESSING_LATENCY_HIGH)
                || reasons.contains(Reason.HOST_PROCESSING_COVERAGE_LOW);
        return new Result(reasons.isEmpty(), lowerBitrate, lowerMode,
                sampleCount, totals, reasons);
    }

    private Progress progress(Phase phase, long completed, Result completedResult) {
        long total = phase == Phase.WARMING_UP ? WARMUP_MS
                : phase == Phase.MEASURING || phase == Phase.COMPLETE
                ? MEASUREMENT_MS : 0;
        return new Progress(phase, completed, total, completedResult);
    }

    private static final class Totals {
        long durationMs;
        double receivedFps;
        double renderedFps;
        double frameLossPercent;
        double rttMs;
        double rttVarianceMs;
        double decoderLatencyMs;
        double hostProcessingLatencyMs;
        double hostProcessingReportedRatio;

        void add(Sample sample) {
            long duration = sample.durationMs();
            durationMs += duration;
            receivedFps += sample.receivedFps * duration;
            renderedFps += sample.renderedFps * duration;
            frameLossPercent += sample.frameLossPercent * duration;
            rttMs += sample.rttMs * duration;
            rttVarianceMs += sample.rttVarianceMs * duration;
            decoderLatencyMs += sample.decoderLatencyMs * duration;
            hostProcessingLatencyMs += sample.hostProcessingLatencyMs * duration;
            hostProcessingReportedRatio += sample.hostProcessingReportedRatio * duration;
        }

        double average(double value) {
            return durationMs == 0 ? 0 : value / durationMs;
        }
    }
}
