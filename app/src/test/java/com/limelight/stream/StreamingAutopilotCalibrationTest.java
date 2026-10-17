package com.limelight.stream;

import com.limelight.preferences.PreferenceConfiguration;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class StreamingAutopilotCalibrationTest {
    @Test public void usesThirtySecondWarmupAndFourMinuteMeasurement() {
        assertEquals(30_000L, StreamingAutopilotCalibration.WARMUP_MS);
        assertEquals(4 * 60_000L, StreamingAutopilotCalibration.MEASUREMENT_MS);
    }

    @Test public void ignoresPreArmThenUsesConfiguredWarmupAndMeasurement() {
        StreamingAutopilotCalibration calibration = new StreamingAutopilotCalibration(60);
        int warmupSeconds = (int) (StreamingAutopilotCalibration.WARMUP_MS / 1_000L);
        int measurementSeconds =
                (int) (StreamingAutopilotCalibration.MEASUREMENT_MS / 1_000L);
        calibration.arm(1_000L);
        assertEquals(StreamingAutopilotCalibration.Phase.WARMING_UP,
                calibration.add(good(0)).phase);
        for (int second = 1; second <= warmupSeconds; second++) {
            assertEquals(StreamingAutopilotCalibration.Phase.WARMING_UP,
                    calibration.add(good(second)).phase);
        }
        int measurementStart = warmupSeconds + 1;
        for (int second = measurementStart;
             second < measurementStart + measurementSeconds - 1; second++) {
            assertEquals(StreamingAutopilotCalibration.Phase.MEASURING,
                    calibration.add(good(second)).phase);
        }

        StreamingAutopilotCalibration.Progress complete = calibration.add(
                good(measurementStart + measurementSeconds - 1));

        assertEquals(StreamingAutopilotCalibration.Phase.COMPLETE, complete.phase);
        assertTrue(complete.result.passed);
        assertEquals(measurementSeconds, complete.result.sampleCount);
        assertEquals(60d, complete.result.receivedFps, .01);
        assertEquals(59d, complete.result.renderedFps, .01);
    }

    @Test public void lossAndJitterRecommendLowerBitrate() {
        StreamingAutopilotCalibration.Result result = complete(new SampleValues(
                60, 59, 2, 20, 2, 5, 5, 1));

        assertFalse(result.passed);
        assertTrue(result.lowerBitrateSuggested);
        assertFalse(result.lowerModeSuggested);
        assertTrue(result.reasons.contains(
                StreamingAutopilotCalibration.Reason.FRAME_LOSS_HIGH));
        assertTrue(result.reasons.contains(
                StreamingAutopilotCalibration.Reason.RTT_VARIANCE_HIGH));
    }

    @Test public void fpsAndPipelineLatencyRecommendLowerModeUsingFrameInterval() {
        StreamingAutopilotCalibration.Result result = complete(new SampleValues(
                50, 49, 0, 20, .5, 17, 18, .5));

        assertFalse(result.passed);
        assertFalse(result.lowerBitrateSuggested);
        assertTrue(result.lowerModeSuggested);
        assertTrue(result.reasons.contains(
                StreamingAutopilotCalibration.Reason.RECEIVED_FPS_LOW));
        assertTrue(result.reasons.contains(
                StreamingAutopilotCalibration.Reason.DECODER_LATENCY_HIGH));
        assertTrue(result.reasons.contains(
                StreamingAutopilotCalibration.Reason.HOST_PROCESSING_LATENCY_HIGH));
        assertTrue(result.reasons.contains(
                StreamingAutopilotCalibration.Reason.HOST_PROCESSING_COVERAGE_LOW));
    }

    @Test public void networkFailureReducesBitrateByQuarterAndRoundsDown() {
        StreamingAutopilotCalibration.Result result = complete(new SampleValues(
                60, 59, 2, 20, .5, 5, 5, 1));

        StreamingAutopilotCalibration.Adjustment adjusted =
                StreamingAutopilotCalibration.adjustedSettings(
                        1920, 1080, 60, 20_400, result);

        assertEquals(1920, adjusted.width);
        assertEquals(1080, adjusted.height);
        assertEquals(60, adjusted.fps);
        assertEquals(15_000, adjusted.bitrateKbps);
        assertTrue(adjusted.conservative);
    }

    @Test public void pipelineFailureUsesNextLowerFpsAndCapsItsBitrate() {
        StreamingAutopilotCalibration.Result result = complete(new SampleValues(
                80, 79, 0, 20, .5, 5, 5, 1), 120);

        StreamingAutopilotCalibration.Adjustment adjusted =
                StreamingAutopilotCalibration.adjustedSettings(
                        1920, 1080, 120, 100_000, result);

        assertEquals(1920, adjusted.width);
        assertEquals(1080, adjusted.height);
        assertEquals(90, adjusted.fps);
        assertEquals(PreferenceConfiguration.getDefaultBitrate(
                "1920x1080", "90"), adjusted.bitrateKbps);
    }

    @Test public void pipelineFailureAtThirtyFpsUsesNextLowerResolution() {
        StreamingAutopilotCalibration.Result result = complete(new SampleValues(
                20, 20, 0, 20, .5, 40, 40, 1), 30);

        StreamingAutopilotCalibration.Adjustment adjusted =
                StreamingAutopilotCalibration.adjustedSettings(
                        1920, 1080, 30, 100_000, result);

        assertEquals(1280, adjusted.width);
        assertEquals(720, adjusted.height);
        assertEquals(30, adjusted.fps);
        assertEquals(PreferenceConfiguration.getDefaultBitrate(
                "1280x720", "30"), adjusted.bitrateKbps);
    }

    private static StreamingAutopilotCalibration.Result complete(SampleValues values) {
        return complete(values, 60);
    }

    private static StreamingAutopilotCalibration.Result complete(
            SampleValues values, int targetFps) {
        StreamingAutopilotCalibration calibration =
                new StreamingAutopilotCalibration(targetFps);
        int warmupSeconds = (int) (StreamingAutopilotCalibration.WARMUP_MS / 1_000L);
        int measurementSeconds =
                (int) (StreamingAutopilotCalibration.MEASUREMENT_MS / 1_000L);
        calibration.arm(0);
        for (int second = 0; second < warmupSeconds; second++) {
            calibration.add(values.at(second));
        }
        StreamingAutopilotCalibration.Progress progress = null;
        for (int second = warmupSeconds;
             second < warmupSeconds + measurementSeconds; second++) {
            progress = calibration.add(values.at(second));
        }
        return progress.result;
    }

    private static StreamingAutopilotCalibration.Sample good(int second) {
        return new SampleValues(60, 59, 0, 20, .5, 5, 0, 0).at(second);
    }

    private static final class SampleValues {
        final double received;
        final double rendered;
        final double loss;
        final double rtt;
        final double variance;
        final double decoder;
        final double host;
        final double hostCoverage;

        SampleValues(double received, double rendered, double loss, double rtt,
                     double variance, double decoder, double host) {
            this(received, rendered, loss, rtt, variance, decoder, host, 1);
        }

        SampleValues(double received, double rendered, double loss, double rtt,
                     double variance, double decoder, double host,
                     double hostCoverage) {
            this.received = received;
            this.rendered = rendered;
            this.loss = loss;
            this.rtt = rtt;
            this.variance = variance;
            this.decoder = decoder;
            this.host = host;
            this.hostCoverage = hostCoverage;
        }

        StreamingAutopilotCalibration.Sample at(int second) {
            return new StreamingAutopilotCalibration.Sample(second * 1_000L,
                    (second + 1) * 1_000L, received, rendered, loss, rtt,
                    variance, decoder, host, hostCoverage);
        }
    }
}
