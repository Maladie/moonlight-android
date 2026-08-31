package com.limelight.console;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

import com.limelight.nvstream.http.ComputerDetails;

import org.junit.Test;

import java.security.KeyStore;
import java.security.cert.X509Certificate;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

public class HostReadinessTest {
    @Test public void freshDetailsKeepConnectionIdentityWithoutMutatingInputs()
            throws Exception {
        ComputerDetails known = new ComputerDetails();
        known.state = ComputerDetails.State.OFFLINE;
        known.activeAddress = new ComputerDetails.AddressTuple("192.168.1.14", 47989);
        known.localAddress = new ComputerDetails.AddressTuple("192.168.1.14", 47989);
        known.manualAddress = new ComputerDetails.AddressTuple("moonwaker.local", 47989);
        known.serverCert = trustedCertificate();
        known.runningGameId = 4;
        known.httpsPort = 47984;

        ComputerDetails fresh = new ComputerDetails();
        fresh.state = ComputerDetails.State.ONLINE;
        fresh.runningGameId = 77;
        fresh.httpsPort = 48000;
        fresh.externalPort = 48010;

        ComputerDetails merged = HostReadiness.mergeFreshDetails(known, fresh);

        assertNotSame(known, merged);
        assertSame(known.activeAddress, merged.activeAddress);
        assertSame(known.localAddress, merged.localAddress);
        assertSame(known.manualAddress, merged.manualAddress);
        assertSame(known.serverCert, merged.serverCert);
        assertEquals(ComputerDetails.State.ONLINE, merged.state);
        assertEquals(77, merged.runningGameId);
        assertEquals(48000, merged.httpsPort);
        assertEquals(48010, merged.externalPort);
        assertEquals(ComputerDetails.State.OFFLINE, known.state);
        assertEquals(4, known.runningGameId);
        assertEquals(47984, known.httpsPort);
        assertNull(fresh.activeAddress);
        assertNull(fresh.serverCert);
    }

    @Test public void priorDecisionCanSkipWake() {
        AtomicInteger sends = new AtomicInteger();

        HostReadiness.awaitAfterWakeDecision(() -> null, new ComputerDetails(), false,
                cancelAfterEntry(), ignored -> { }, "wake", "wait",
                ignored -> sends.incrementAndGet());

        assertEquals(0, sends.get());
    }

    @Test public void priorDecisionSendsWakeExactlyOnce() {
        AtomicInteger sends = new AtomicInteger();

        HostReadiness.awaitAfterWakeDecision(() -> null, new ComputerDetails(), true,
                cancelAfterEntry(), ignored -> { }, "wake", "wait",
                ignored -> sends.incrementAndGet());

        assertEquals(1, sends.get());
    }

    @Test public void cancellationBeforeEntrySkipsWake() {
        AtomicInteger sends = new AtomicInteger();

        HostReadiness.awaitAfterWakeDecision(() -> null, new ComputerDetails(), true,
                () -> true, ignored -> { }, "wake", "wait",
                ignored -> sends.incrementAndGet());

        assertEquals(0, sends.get());
    }

    private static BooleanSupplier cancelAfterEntry() {
        AtomicInteger checks = new AtomicInteger();
        return () -> checks.getAndIncrement() > 0;
    }

    private static X509Certificate trustedCertificate() throws Exception {
        TrustManagerFactory factory = TrustManagerFactory.getInstance(
                TrustManagerFactory.getDefaultAlgorithm());
        factory.init((KeyStore) null);
        for (TrustManager manager : factory.getTrustManagers()) {
            if (manager instanceof X509TrustManager) {
                return ((X509TrustManager) manager).getAcceptedIssuers()[0];
            }
        }
        throw new AssertionError("No JVM trust manager");
    }
}
