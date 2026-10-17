package com.limelight.console;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.limelight.nvstream.http.ComputerDetails;
import com.limelight.session.HostSleepStateStore;
import com.limelight.session.SessionEvent;
import com.limelight.session.SessionModelDriver;
import com.limelight.session.SuspendedSessionStore;

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
    @Test public void acceptedSleepSettlesFromTheHostsRealStateWithoutAConsole() {
        SessionModelDriver m = new SessionModelDriver();
        m.post(new SessionEvent.HostSleepRequested("asleep", 10L));
        m.post(new SessionEvent.HostSleepRequested("refused", 20L));

        HostReadiness.sleepAnswered(m.model, "asleep", 10L, null, true, 15L);
        HostReadiness.settleSleep(m.model, "asleep", 15L, null, true);
        HostReadiness.sleepAnswered(m.model, "refused", 20L, null, false, 25L);
        m.drain();

        assertEquals(15L, m.model.records().hostSleep("asleep").requestedAt);
        assertTrue(m.model.records().hostSleep("asleep").sleepObservedAt > 0L);
        assertNull(m.model.records().hostSleep("refused"));
    }

    /** A sleep slower than the follow-up's bound is still recorded by a later poll. */
    @Test public void noEvidenceOfDownKeepsTheRecordForALaterObservation() {
        SessionModelDriver m = new SessionModelDriver();
        m.post(new SessionEvent.HostSleepRequested("host", 10L));
        HostReadiness.sleepAnswered(m.model, "host", 10L, null, true, 15L);
        HostReadiness.settleSleep(m.model, "host", 15L, null, false);
        m.drain();

        HostSleepStateStore.State kept = m.model.records().hostSleep("host");
        assertEquals(0L, kept.sleepObservedAt);
        m.post(new SessionEvent.HostSleepObserved("host", kept.requestedAt));
        assertTrue(m.model.records().hostSleep("host").sleepObservedAt > 0L);
    }

    /** The bound counts from the host's acceptance, not from before the request's round trip. */
    @Test public void sleepRecordExpiresFromTheGatewaysAcceptance() {
        SessionModelDriver m = new SessionModelDriver();
        m.post(new SessionEvent.HostSleepRequested("host", 1_000L));
        HostReadiness.sleepAnswered(m.model, "host", 1_000L, null, true, 40_000L);
        m.drain();

        long timedFrom = m.model.records().hostSleep("host").requestedAt;
        assertFalse(com.limelight.session.HostSleepStateStore.requestExpired(timedFrom, 70_000L));
        assertTrue(com.limelight.session.HostSleepStateStore.requestExpired(timedFrom, 40_000L + 61_000L));
        assertTrue(com.limelight.session.HostSleepStateStore.requestExpired(timedFrom, 1_000L));
    }

    /** A real wake cleared the record and a new request followed: the old settle is stale. */
    @Test public void wakeThenNewRequestIgnoresTheOldRequestsSettle() {
        SessionModelDriver m = new SessionModelDriver();
        m.post(new SessionEvent.HostSleepRequested("host", 10L));
        HostReadiness.sleepAnswered(m.model, "host", 10L, null, true, 15L);
        m.drain();
        m.post(new SessionEvent.HostSleepCleared("host"));
        m.post(new SessionEvent.HostSleepRequested("host", 40L));

        // The old request's late answer and settle reach the new record.
        HostReadiness.sleepAnswered(m.model, "host", 10L, null, true, 41L);
        HostReadiness.settleSleep(m.model, "host", 15L, null, true);
        m.drain();

        HostSleepStateStore.State current = m.model.records().hostSleep("host");
        assertEquals(40L, current.requestedAt);
        assertEquals(0L, current.sleepObservedAt);
    }

    @Test public void suspendedSleepIsSavedOnAcceptanceAndObservedOnlyUntilResumed() {
        SessionModelDriver m = new SessionModelDriver();
        SuspendedSessionStore.Session asleep = suspended("asleep");
        HostReadiness.sleepAnswered(m.model, "host", 0L, asleep, true, 15L);
        HostReadiness.settleSleep(m.model, "host", 15L, asleep, true);
        m.drain();
        assertTrue(m.model.records().suspendedForExecution("host", "parent")
                .sleepObservedAt > 0L);

        SuspendedSessionStore.Session resumed = suspended("resumed");
        HostReadiness.sleepAnswered(m.model, "host", 0L, resumed, true, 20L);
        m.drain();
        assertTrue(m.call(new SessionEvent.SuspendedResumed("resumed", "host", "parent", 42,
                "game", "stream")));
        // The follow-up is late: the game already resumed, its host is awake.
        HostReadiness.settleSleep(m.model, "host", 20L, resumed, true);
        m.drain();
        SuspendedSessionStore.Session after =
                m.model.records().suspendedForExecution("host", "parent");
        assertTrue(after.resumedAt > 0L);
        assertEquals(0L, after.sleepObservedAt);

        SuspendedSessionStore.Session refused = suspended("refused");
        HostReadiness.sleepAnswered(m.model, "host", 0L, refused, false, 30L);
        m.drain();
        assertEquals("resumed",
                m.model.records().suspendedForExecution("host", "parent").suspendId);
    }

    private static SuspendedSessionStore.Session suspended(String suspendId) {
        return new SuspendedSessionStore.Session(suspendId, "host", "parent", 42, "game",
                "Game", "", 1L, "parent");
    }

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

        // Two answers, then DOWN_PROBES failures in a row.
        assertEquals(2 + HostReadiness.DOWN_PROBES, probes.get());
    }

    /** One failed probe between answers is a blip, not a host that went to sleep. */
    @Test public void awaitDownNeedsConsecutiveFailedProbes() {
        AtomicInteger probes = new AtomicInteger();
        AtomicLong clock = new AtomicLong();

        assertFalse(HostReadiness.awaitDown(HostReadinessTest::onlineHost, () -> false,
                ignored -> probes.incrementAndGet() % 2 == 0, clock::get,
                () -> clock.addAndGet(1_200L) > 0L));

        assertEquals(30_000L, clock.get());
    }

    /** No host or no address is no evidence: a dropped TV network is not a sleeping host. */
    @Test public void awaitDownWithoutAnAddressIsNoEvidence() {
        ComputerDetails unaddressed = onlineHost();
        unaddressed.activeAddress = null;
        AtomicLong clock = new AtomicLong();

        assertFalse(HostReadiness.awaitDown(() -> unaddressed, () -> false,
                ignored -> false, clock::get, () -> clock.addAndGet(1_200L) > 0L));
        clock.set(0L);
        assertFalse(HostReadiness.awaitDown(() -> null, () -> false,
                ignored -> false, clock::get, () -> clock.addAndGet(1_200L) > 0L));
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
