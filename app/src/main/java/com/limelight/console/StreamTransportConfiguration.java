package com.limelight.console;

import com.limelight.nvstream.StreamConfiguration;
import com.limelight.nvstream.http.ComputerDetails;
import com.limelight.nvstream.http.LimelightCryptoProvider;

import java.security.cert.X509Certificate;
import java.util.Objects;

/** Immutable inputs required to construct the Moonlight transport and audio renderer. */
public final class StreamTransportConfiguration {
    public final ComputerDetails.AddressTuple host;
    public final int httpsPort;
    public final String uniqueId;
    public final StreamConfiguration streamConfiguration;
    public final LimelightCryptoProvider cryptoProvider;
    public final X509Certificate serverCertificate;
    public final boolean enableAudioFx;

    public StreamTransportConfiguration(ComputerDetails.AddressTuple host,
                                        int httpsPort,
                                        String uniqueId,
                                        StreamConfiguration streamConfiguration,
                                        LimelightCryptoProvider cryptoProvider,
                                        X509Certificate serverCertificate,
                                        boolean enableAudioFx) {
        this.host = Objects.requireNonNull(host, "host");
        if (httpsPort < 0) {
            throw new IllegalArgumentException("HTTPS port cannot be negative");
        }
        this.httpsPort = httpsPort;
        if (uniqueId == null || uniqueId.isBlank()) {
            throw new IllegalArgumentException("Client unique ID is required");
        }
        this.uniqueId = uniqueId;
        this.streamConfiguration = Objects.requireNonNull(
                streamConfiguration, "streamConfiguration");
        this.cryptoProvider = Objects.requireNonNull(cryptoProvider, "cryptoProvider");
        this.serverCertificate = serverCertificate;
        this.enableAudioFx = enableAudioFx;
    }

    public static StreamTransportConfiguration from(
            StreamLaunchParameters parameters,
            StreamConfiguration streamConfiguration,
            LimelightCryptoProvider cryptoProvider,
            boolean enableAudioFx) {
        Objects.requireNonNull(parameters, "parameters");
        return new StreamTransportConfiguration(
                new ComputerDetails.AddressTuple(parameters.host, parameters.port),
                parameters.httpsPort,
                parameters.uniqueId,
                streamConfiguration,
                cryptoProvider,
                parameters.serverCertificate,
                enableAudioFx);
    }
}
