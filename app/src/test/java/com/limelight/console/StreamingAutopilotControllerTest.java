package com.limelight.console;

import com.limelight.preferences.PreferenceConfiguration;
import com.limelight.stream.StreamingAutopilot;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class StreamingAutopilotControllerTest {
    private static final StreamingAutopilot.Capabilities CAPABILITIES =
            StreamingAutopilot.Capabilities.upTo(3840, 2160, 120);

    @Test public void measuredSampleProducesBudgetAndRecommendation() {
        StreamingAutopilotController.NetworkEvaluation result = combine(
                StreamingAutopilotController.NetworkStatus.MEASURED,
                80_000_000L, 8_000_000_000L);

        assertEquals(StreamingAutopilotController.NetworkStatus.MEASURED, result.status);
        assertEquals(80_000, result.goodputKbps.getAsLong());
        assertEquals(59_000, result.safeBitrateKbps.getAsInt());
        assertTrue(result.sample.isPresent());
        assertTrue(result.recommendation.isPresent());
    }

    @Test public void failedMeasurementUsesCapabilityOnlyFallback() {
        StreamingAutopilotController.NetworkEvaluation result = combine(
                StreamingAutopilotController.NetworkStatus.FAILED, 0, 0);

        assertEquals(StreamingAutopilotController.NetworkStatus.FAILED, result.status);
        assertFalse(result.sample.isPresent());
        assertFalse(result.safeBitrateKbps.isPresent());
        assertEquals(StreamingAutopilot.Confidence.LOW,
                result.recommendation.get().confidence);
    }

    @Test public void unavailableMeasurementUsesExplicitCapabilityOnlyFallback() {
        StreamingAutopilotController.NetworkEvaluation result = combine(
                StreamingAutopilotController.NetworkStatus.UNAVAILABLE, 0, 0);

        assertEquals(StreamingAutopilotController.NetworkStatus.UNAVAILABLE, result.status);
        assertFalse(result.sample.isPresent());
        assertTrue(result.recommendation.isPresent());
        assertEquals(StreamingAutopilot.Confidence.LOW,
                result.recommendation.get().confidence);
    }

    @Test public void unusableMeasuredBudgetHasNoRecommendation() {
        StreamingAutopilotController.NetworkEvaluation result = combine(
                StreamingAutopilotController.NetworkStatus.MEASURED,
                1_000_000L, 8_000_000_000L);

        assertEquals(StreamingAutopilotController.NetworkStatus.TOO_SLOW, result.status);
        assertEquals(1_000, result.goodputKbps.getAsLong());
        assertEquals(0, result.safeBitrateKbps.getAsInt());
        assertFalse(result.recommendation.isPresent());
    }

    private static StreamingAutopilotController.NetworkEvaluation combine(
            StreamingAutopilotController.NetworkStatus status, long bytes, long elapsedNanos) {
        return StreamingAutopilotController.combine(
                CAPABILITIES, CAPABILITIES, PreferenceConfiguration.FormatOption.AUTO,
                status, bytes, elapsedNanos);
    }
}
