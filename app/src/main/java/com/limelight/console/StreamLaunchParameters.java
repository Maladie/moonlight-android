package com.limelight.console;

import com.limelight.nvstream.StreamConfiguration;
import com.limelight.nvstream.http.ComputerDetails;
import com.limelight.nvstream.http.NvApp;

import java.security.cert.X509Certificate;

/** Validated host and application identity required to initialize a stream session. */
public final class StreamLaunchParameters {
    public final String host;
    public final int port;
    public final int httpsPort;
    public final String uniqueId;
    public final String computerUuid;
    public final String computerName;
    public final String appName;
    public final int appId;
    public final boolean appSupportsHdr;
    public final X509Certificate serverCertificate;
    public final String quickLaunchAppKey;
    public final boolean applyPreferenceOverrides;
    public final int runtimeBitrateKbps;

    private StreamLaunchParameters(ComputerDetails computer,
                                   NvApp app,
                                   String uniqueId,
                                   String quickLaunchAppKey,
                                   boolean applyPreferenceOverrides,
                                   int runtimeBitrateKbps) {
        if (computer == null || computer.activeAddress == null) {
            throw new IllegalArgumentException("Active host address is required");
        }
        if (app == null || app.getAppId() == StreamConfiguration.INVALID_APP_ID) {
            throw new IllegalArgumentException("Valid application is required");
        }
        if (uniqueId == null || uniqueId.isBlank()) {
            throw new IllegalArgumentException("Client unique ID is required");
        }

        host = computer.activeAddress.address;
        port = computer.activeAddress.port;
        httpsPort = computer.httpsPort;
        this.uniqueId = uniqueId;
        computerUuid = computer.uuid;
        computerName = computer.name;
        appName = app.getAppName();
        appId = app.getAppId();
        appSupportsHdr = app.isHdrSupported();
        serverCertificate = computer.serverCert;
        this.quickLaunchAppKey = quickLaunchAppKey;
        this.applyPreferenceOverrides = applyPreferenceOverrides;
        this.runtimeBitrateKbps = runtimeBitrateKbps > 0 ?
                StreamBitratePolicy.clamp(runtimeBitrateKbps) : 0;
    }

    public static StreamLaunchParameters create(ComputerDetails computer,
                                                NvApp app,
                                                String uniqueId,
                                                String quickLaunchAppKey,
                                                boolean applyPreferenceOverrides) {
        return new StreamLaunchParameters(computer, app, uniqueId, quickLaunchAppKey,
                applyPreferenceOverrides, 0);
    }

    static StreamLaunchParameters create(ComputerDetails computer,
                                         NvApp app,
                                         String uniqueId,
                                         String quickLaunchAppKey,
                                         boolean applyPreferenceOverrides,
                                         int runtimeBitrateKbps) {
        return new StreamLaunchParameters(computer, app, uniqueId, quickLaunchAppKey,
                applyPreferenceOverrides, runtimeBitrateKbps);
    }
}
