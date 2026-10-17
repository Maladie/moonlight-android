package com.limelight.stream;

import org.junit.Test;

import java.util.Arrays;
import java.util.OptionalInt;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class StreamingAutopilotTest {
    @Test public void safeBudgetKeepsHeadroomAndAudioAtEightyMegabits() {
        assertEquals(59_000, StreamingAutopilot.safeBitrateBudgetKbps(
                80_000_000L, 8_000_000_000L));
    }

    @Test public void safeBudgetKeepsHeadroomAndAudioAtFiveHundredMegabits() {
        assertEquals(374_000, StreamingAutopilot.safeBitrateBudgetKbps(
                500_000_000L, 8_000_000_000L));
    }

    @Test public void safeBudgetReturnsZeroBelowUsefulMinimum() {
        assertEquals(0, StreamingAutopilot.safeBitrateBudgetKbps(
                1_000_000L, 8_000_000_000L));
    }

    @Test public void safeBudgetClampsBeforeIntegerOverflow() {
        assertEquals(2_147_483_500, StreamingAutopilot.safeBitrateBudgetKbps(
                Long.MAX_VALUE, 1));
    }

    @Test public void skipsModeWhoseClientAndHostCodecPathsAreDisjoint() {
        StreamingAutopilot.Capabilities client = codecCapabilities(
                new StreamingAutopilot.Mode[]{mode(3840, 2160, 60)},
                new StreamingAutopilot.Mode[]{mode(1920, 1080, 60)});
        StreamingAutopilot.Capabilities host = codecCapabilities(
                new StreamingAutopilot.Mode[0],
                new StreamingAutopilot.Mode[]{mode(3840, 2160, 60), mode(1920, 1080, 60)});

        StreamingAutopilot.Recommendation result = StreamingAutopilot.recommend(
                client, host, OptionalInt.of(100_000));

        assertMode(result, 1920, 1080, 60, 20_000);
    }

    @Test public void forceH264NeverUsesAnHevcOnlyMode() {
        StreamingAutopilot.Capabilities client = codecCapabilities(
                new StreamingAutopilot.Mode[]{mode(1280, 720, 60)},
                new StreamingAutopilot.Mode[]{mode(1920, 1080, 60)});
        StreamingAutopilot.Capabilities host = codecCapabilities(
                new StreamingAutopilot.Mode[]{mode(1280, 720, 60)},
                new StreamingAutopilot.Mode[]{mode(1920, 1080, 60)});

        StreamingAutopilot.Recommendation result = StreamingAutopilot.recommend(
                client, host, OptionalInt.of(100_000),
                com.limelight.preferences.PreferenceConfiguration.FormatOption.FORCE_H264);

        assertMode(result, 1280, 720, 60, 10_000);
    }

    @Test public void explicitCapabilitiesDoNotCombineUnsupportedResolutionAndFps() {
        StreamingAutopilot.Capabilities client = new StreamingAutopilot.Capabilities(
                new StreamingAutopilot.Mode(3840, 2160, 60),
                new StreamingAutopilot.Mode(1920, 1080, 120));

        StreamingAutopilot.Recommendation result = StreamingAutopilot.recommend(
                client, StreamingAutopilot.Capabilities.upTo(3840, 2160, 120),
                OptionalInt.of(150_000));

        assertMode(result, 3840, 2160, 60, 80_000);
    }

    @Test public void selectsTheBestCandidateWithinEveryMeasuredLimit() {
        StreamingAutopilot.Recommendation result = StreamingAutopilot.recommend(
                capabilities(3840, 2160, 120),
                capabilities(2560, 1440, 60),
                OptionalInt.of(40_000));

        assertMode(result, 2560, 1440, 60, 40_000);
        assertEquals(StreamingAutopilot.Confidence.HIGH, result.confidence);
    }

    @Test public void safeBitrateBudgetSelectsALowerDiscreteMode() {
        StreamingAutopilot.Recommendation result = StreamingAutopilot.recommend(
                capabilities(3840, 2160, 120),
                capabilities(3840, 2160, 120),
                OptionalInt.of(20_000));

        assertMode(result, 1920, 1080, 60, 20_000);
        assertTrue(result.bitrateKbps <= 20_000);
    }

    @Test public void missingNetworkMeasurementUsesAConservativeFallback() {
        StreamingAutopilot.Recommendation result = StreamingAutopilot.recommend(
                capabilities(3840, 2160, 120),
                capabilities(3840, 2160, 120),
                OptionalInt.empty());

        assertMode(result, 1280, 720, 60, 10_000);
        assertEquals(StreamingAutopilot.Confidence.LOW, result.confidence);
        assertEquals(StreamingAutopilot.Reason.NETWORK_MEASUREMENT_UNAVAILABLE,
                result.reasons.get(0));
    }

    @Test public void fallbackAndVeryLowBudgetNeverExceedLimits() {
        StreamingAutopilot.Capabilities client = capabilities(854, 480, 30);
        StreamingAutopilot.Capabilities host = capabilities(1920, 1080, 120);

        StreamingAutopilot.Recommendation fallback = StreamingAutopilot.recommend(
                client, host, OptionalInt.empty());
        assertMode(fallback, 854, 480, 30, 2_000);

        StreamingAutopilot.Recommendation constrained = StreamingAutopilot.recommend(
                client, host, OptionalInt.of(750));
        assertMode(constrained, 640, 360, 30, 500);
        assertEquals(StreamingAutopilot.Confidence.LOW, constrained.confidence);
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsABudgetBelowTheSupportedStreamMinimum() {
        StreamingAutopilot.recommend(
                capabilities(1920, 1080, 60),
                capabilities(1920, 1080, 60),
                OptionalInt.of(499));
    }

    private static StreamingAutopilot.Capabilities capabilities(int width, int height, int fps) {
        return new StreamingAutopilot.Capabilities(width, height, fps);
    }

    private static StreamingAutopilot.Capabilities codecCapabilities(
            StreamingAutopilot.Mode[] h264, StreamingAutopilot.Mode[] hevc) {
        return StreamingAutopilot.forCodecModes(Arrays.asList(h264), Arrays.asList(hevc));
    }

    private static StreamingAutopilot.Mode mode(int width, int height, int fps) {
        return new StreamingAutopilot.Mode(width, height, fps);
    }

    private static void assertMode(StreamingAutopilot.Recommendation result,
                                   int width, int height, int fps, int bitrateKbps) {
        assertEquals(width, result.width);
        assertEquals(height, result.height);
        assertEquals(fps, result.fps);
        assertEquals(bitrateKbps, result.bitrateKbps);
    }
}
