package com.limelight.console;

import android.content.Context;
import android.view.Display;

import com.limelight.gateway.GatewayConnection;
import com.limelight.gateway.GatewayTransport;
import com.limelight.nvstream.http.NvHTTP;
import com.limelight.preferences.AppPreferences;
import com.limelight.preferences.PreferenceConfiguration;
import com.limelight.stream.ClientStreamCapabilitiesProbe;
import com.limelight.stream.HostStreamCapabilitiesProbe;
import com.limelight.stream.StreamingAutopilot;

import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;

/** Synchronous orchestration for streaming capability and network analysis. */
public final class StreamingAutopilotController {
    private StreamingAutopilotController() { }

    public enum NetworkStatus {
        MEASURED,
        UNAVAILABLE,
        FAILED,
        TOO_SLOW
    }

    public static final class NetworkSample {
        public final long bytes;
        public final long elapsedNanos;

        private NetworkSample(long bytes, long elapsedNanos) {
            this.bytes = bytes;
            this.elapsedNanos = elapsedNanos;
        }
    }

    public static final class Analysis {
        public final StreamingAutopilot.Capabilities clientCapabilities;
        public final HostStreamCapabilitiesProbe.Result host;
        public final NetworkStatus networkStatus;
        public final Optional<NetworkSample> networkSample;
        public final OptionalLong goodputKbps;
        public final OptionalInt safeBitrateKbps;
        public final Optional<StreamingAutopilot.Recommendation> recommendation;

        private Analysis(StreamingAutopilot.Capabilities clientCapabilities,
                         HostStreamCapabilitiesProbe.Result host,
                         NetworkEvaluation network) {
            this.clientCapabilities = clientCapabilities;
            this.host = host;
            networkStatus = network.status;
            networkSample = network.sample;
            goodputKbps = network.goodputKbps;
            safeBitrateKbps = network.safeBitrateKbps;
            recommendation = network.recommendation;
        }
    }

    public static Analysis analyze(Context context, Display display, String glRenderer,
                                   NvHTTP http, String serverInfo,
                                   GatewayConnection gatewayConnection)
            throws IOException, XmlPullParserException {
        StreamingAutopilot.Capabilities client =
                ClientStreamCapabilitiesProbe.probe(context, display, glRenderer);
        HostStreamCapabilitiesProbe.Result host =
                HostStreamCapabilitiesProbe.probe(http, serverInfo);
        PreferenceConfiguration.FormatOption format =
                PreferenceConfiguration.readPreferences(context).videoFormat;

        NetworkEvaluation network;
        if (gatewayConnection == null) {
            network = combine(client, host.capabilities, format,
                    NetworkStatus.UNAVAILABLE, 0, 0);
        } else {
            try {
                GatewayTransport.NetworkDownloadSample sample =
                        new HostGatewayClient().measureAdaptiveNetworkDownload(gatewayConnection);
                network = combine(client, host.capabilities, format, NetworkStatus.MEASURED,
                        sample.bytes, sample.elapsedNanos);
            } catch (IOException e) {
                network = combine(client, host.capabilities, format,
                        NetworkStatus.FAILED, 0, 0);
            }
        }
        return new Analysis(client, host, network);
    }

    public static void applyGlobal(Context context, Analysis analysis) {
        StreamingAutopilot.Recommendation recommendation = requireRecommendation(analysis);
        PreferenceConfiguration.applyStreamSettings(context, recommendation.width,
                recommendation.height, recommendation.fps, recommendation.bitrateKbps);
    }

    public static void applyForApp(Context context, String appKey, Analysis analysis) {
        StreamingAutopilot.Recommendation recommendation = requireRecommendation(analysis);
        AppPreferences.applyStreamSettings(context, appKey, recommendation.width,
                recommendation.height, recommendation.fps, recommendation.bitrateKbps);
    }

    private static StreamingAutopilot.Recommendation requireRecommendation(Analysis analysis) {
        return analysis.recommendation.orElseThrow(
                () -> new IllegalStateException("Analysis has no applicable recommendation"));
    }

    static NetworkEvaluation combine(StreamingAutopilot.Capabilities client,
                                     StreamingAutopilot.Capabilities host,
                                     PreferenceConfiguration.FormatOption format,
                                     NetworkStatus status, long bytes, long elapsedNanos) {
        if (status == NetworkStatus.MEASURED) {
            int safeBudget = StreamingAutopilot.safeBitrateBudgetKbps(bytes, elapsedNanos);
            NetworkSample sample = new NetworkSample(bytes, elapsedNanos);
            long goodputKbps = (long) (bytes * 8_000_000d / elapsedNanos);
            if (safeBudget == 0) {
                return new NetworkEvaluation(NetworkStatus.TOO_SLOW, Optional.of(sample),
                        OptionalLong.of(goodputKbps), OptionalInt.of(0), Optional.empty());
            }
            return new NetworkEvaluation(NetworkStatus.MEASURED, Optional.of(sample),
                    OptionalLong.of(goodputKbps), OptionalInt.of(safeBudget),
                    Optional.of(StreamingAutopilot.recommend(
                            client, host, OptionalInt.of(safeBudget), format)));
        }
        if (status == NetworkStatus.TOO_SLOW) {
            throw new IllegalArgumentException("TOO_SLOW is derived from a measured sample");
        }
        return new NetworkEvaluation(status, Optional.empty(), OptionalLong.empty(),
                OptionalInt.empty(), Optional.of(
                        StreamingAutopilot.recommend(client, host, OptionalInt.empty(), format)));
    }

    static final class NetworkEvaluation {
        final NetworkStatus status;
        final Optional<NetworkSample> sample;
        final OptionalLong goodputKbps;
        final OptionalInt safeBitrateKbps;
        final Optional<StreamingAutopilot.Recommendation> recommendation;

        private NetworkEvaluation(NetworkStatus status, Optional<NetworkSample> sample,
                                  OptionalLong goodputKbps, OptionalInt safeBitrateKbps,
                                  Optional<StreamingAutopilot.Recommendation> recommendation) {
            this.status = status;
            this.sample = sample;
            this.goodputKbps = goodputKbps;
            this.safeBitrateKbps = safeBitrateKbps;
            this.recommendation = recommendation;
        }
    }
}
