package com.limelight.console;

import com.limelight.nvstream.StreamConfiguration;
import com.limelight.nvstream.http.ComputerDetails;
import com.limelight.nvstream.http.LimelightCryptoProvider;
import com.limelight.nvstream.http.NvApp;

import org.junit.Test;

import java.security.PrivateKey;
import java.security.cert.X509Certificate;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class StreamTransportConfigurationTest {
    @Test public void mapsResolvedLaunchIntoTransportInputs() {
        StreamLaunchParameters parameters = parameters();
        StreamConfiguration streamConfiguration = new StreamConfiguration.Builder().build();
        LimelightCryptoProvider cryptoProvider = new FakeCryptoProvider();

        StreamTransportConfiguration configuration = StreamTransportConfiguration.from(
                parameters, streamConfiguration, cryptoProvider, true);

        assertEquals(new ComputerDetails.AddressTuple("host", 47989), configuration.host);
        assertEquals(47984, configuration.httpsPort);
        assertEquals("client", configuration.uniqueId);
        assertSame(streamConfiguration, configuration.streamConfiguration);
        assertSame(cryptoProvider, configuration.cryptoProvider);
        assertNull(configuration.serverCertificate);
        assertTrue(configuration.enableAudioFx);
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsNegativeHttpsPort() {
        new StreamTransportConfiguration(
                new ComputerDetails.AddressTuple("host", 47989), -1, "client",
                new StreamConfiguration.Builder().build(), new FakeCryptoProvider(),
                null, false);
    }

    private static StreamLaunchParameters parameters() {
        ComputerDetails computer = new ComputerDetails();
        computer.uuid = "host-a";
        computer.name = "Host";
        computer.activeAddress = new ComputerDetails.AddressTuple("host", 47989);
        computer.httpsPort = 47984;
        return StreamLaunchParameters.create(
                computer, new NvApp("Game", 7, false), "client", null, true);
    }

    private static final class FakeCryptoProvider implements LimelightCryptoProvider {
        @Override public X509Certificate getClientCertificate() { return null; }
        @Override public PrivateKey getClientPrivateKey() { return null; }
        @Override public byte[] getPemEncodedClientCertificate() { return new byte[0]; }
        @Override public String encodeBase64String(byte[] data) { return ""; }
    }
}
