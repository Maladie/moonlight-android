package com.limelight.console;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.limelight.nvstream.http.ComputerDetails;

import org.junit.Test;

import java.security.KeyStore;
import java.security.cert.X509Certificate;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
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

    @Test public void awaitDownReturnsOnceThePortStopsAnswering() {
        ComputerDetails host = onlineHost();
        AtomicInteger probes = new AtomicInteger();
        AtomicLong clock = new AtomicLong();

        assertTrue(HostReadiness.awaitDown(() -> host, () -> false,
                ignored -> probes.incrementAndGet() < 3, clock::get,
                () -> clock.addAndGet(1_200L) > 0L));

        assertEquals(3, probes.get());
        assertEquals(2_400L, clock.get());
    }

    @Test public void awaitDownReturnsOnceThePollLeavesOnline() {
        ComputerDetails host = onlineHost();
        host.state = ComputerDetails.State.OFFLINE;

        assertTrue(HostReadiness.awaitDown(() -> host, () -> false,
                ignored -> true, () -> 0L, () -> true));
    }

    @Test public void awaitDownStopsWhenCancelled() {
        AtomicInteger probes = new AtomicInteger();

        assertFalse(HostReadiness.awaitDown(HostReadinessTest::onlineHost, cancelAfterEntry(),
                ignored -> probes.incrementAndGet() > 0, () -> 0L, () -> true));

        assertEquals(1, probes.get());
    }

    @Test public void awaitDownGivesUpAtItsLimitWhenTheHostStaysUp() {
        AtomicLong clock = new AtomicLong();

        assertFalse(HostReadiness.awaitDown(HostReadinessTest::onlineHost, () -> false,
                ignored -> true, clock::get, () -> clock.addAndGet(1_200L) > 0L));

        assertEquals(30_000L, clock.get());
    }

    private static ComputerDetails onlineHost() {
        ComputerDetails host = new ComputerDetails();
        host.state = ComputerDetails.State.ONLINE;
        host.activeAddress = new ComputerDetails.AddressTuple("192.168.1.14", 47989);
        return host;
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
