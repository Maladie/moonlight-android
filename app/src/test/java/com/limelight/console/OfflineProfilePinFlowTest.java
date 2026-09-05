package com.limelight.console;

import com.limelight.gateway.GatewayConnection;

import org.junit.Test;

import java.io.IOException;
import java.net.ConnectException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.net.ssl.SSLHandshakeException;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class OfflineProfilePinFlowTest {
    private static final String HOST = "host";
    private static final String PROFILE = "profile";
    private static final String PIN = "1357";
    private static final String CERTIFICATE =
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";

    @Test public void onlineSuccessSeedsOnceWithoutWakeOrProfilePoll() {
        FakeCache cache = new FakeCache(result(OfflineProfilePinStore.Status.MISSING, 0));
        FakeGateway gateway = new FakeGateway();
        FakeWake wake = new FakeWake();

        OfflineProfilePinFlow.Result result = run(cache, gateway, wake);

        assertEquals(OfflineProfilePinFlow.Status.VERIFIED, result.status);
        assertEquals(1, gateway.verifyCalls);
        assertEquals(0, cache.checkCalls);
        assertEquals(1, cache.rememberCalls);
        assertEquals(0, gateway.profileCalls);
        assertEquals(0, wake.calls);
    }

    @Test public void onlineWrongPinNeverUsesCacheOrWakes() {
        FakeCache cache = new FakeCache(result(OfflineProfilePinStore.Status.MATCH, 0));
        FakeGateway gateway = new FakeGateway();
        gateway.verifyResponses.add(new HostGatewayClient.GatewayException(
                "invalid_pin", 401));
        FakeWake wake = new FakeWake();

        OfflineProfilePinFlow.Result result = run(cache, gateway, wake);

        assertEquals(OfflineProfilePinFlow.Status.INVALID, result.status);
        assertEquals(1, gateway.verifyCalls);
        assertEquals(0, cache.checkCalls);
        assertEquals(0, cache.forgetCalls);
        assertEquals(0, wake.calls);
    }

    @Test public void pinVerificationContractErrorDoesNotEraseCachedVerifier() {
        FakeCache cache = new FakeCache(result(OfflineProfilePinStore.Status.MATCH, 0));
        FakeGateway gateway = new FakeGateway();
        gateway.verifyResponses.add(new HostGatewayClient.GatewayException(
                "pin_verification_failed", 502));
        FakeWake wake = new FakeWake();

        OfflineProfilePinFlow.Result result = run(cache, gateway, wake);

        assertEquals(OfflineProfilePinFlow.Status.UNAVAILABLE, result.status);
        assertEquals(0, cache.checkCalls);
        assertEquals(0, cache.forgetCalls);
        assertEquals(0, wake.calls);
    }

    @Test public void offlineMissingDoesNotWake() {
        FakeCache cache = new FakeCache(result(OfflineProfilePinStore.Status.MISSING, 0));
        FakeGateway gateway = offlineFirstGateway();
        FakeWake wake = new FakeWake();

        OfflineProfilePinFlow.Result result = run(cache, gateway, wake);

        assertEquals(OfflineProfilePinFlow.Status.MISSING, result.status);
        assertEquals(1, gateway.verifyCalls);
        assertEquals(1, cache.checkCalls);
        assertEquals(0, wake.calls);
        assertEquals(0, gateway.profileCalls);
    }

    @Test public void offlineWrongPinDoesNotWakeAndHonorsCooldown() {
        FakeCache cache = new FakeCache(result(OfflineProfilePinStore.Status.INVALID, 7));
        FakeGateway gateway = offlineFirstGateway();
        FakeWake wake = new FakeWake();

        OfflineProfilePinFlow.Result result = run(cache, gateway, wake);

        assertEquals(OfflineProfilePinFlow.Status.INVALID, result.status);
        assertEquals(7, result.retryAfterSeconds);
        assertEquals(0, wake.calls);
        assertEquals(0, gateway.profileCalls);
    }

    @Test public void offlineMatchWakesOncePollsProfilesAndReverifiesOnce() {
        FakeCache cache = new FakeCache(result(OfflineProfilePinStore.Status.MATCH, 0));
        FakeGateway gateway = offlineFirstGateway();
        gateway.profileResponses.add(new ConnectException("still asleep"));
        gateway.profileResponses.add(profiles(true, true));
        gateway.verifyResponses.add(null);
        FakeWake wake = new FakeWake();
        gateway.expectedWake = wake;
        cache.afterRemember = () -> assertEquals(2, gateway.verifyCalls);

        OfflineProfilePinFlow.Result result = run(cache, gateway, wake);

        assertEquals(OfflineProfilePinFlow.Status.VERIFIED, result.status);
        assertEquals(2, gateway.verifyCalls);
        assertEquals(2, gateway.profileCalls);
        assertEquals(1, wake.calls);
        assertEquals(1, cache.rememberCalls);
        assertEquals(1, gateway.waits);
    }

    @Test public void offlineMatchStopsPollingAtNinetySeconds() {
        FakeCache cache = new FakeCache(result(OfflineProfilePinStore.Status.MATCH, 0));
        FakeGateway gateway = offlineFirstGateway();
        gateway.defaultProfileResponse = new ConnectException("still asleep");
        FakeWake wake = new FakeWake();
        FakeClock clock = new FakeClock();

        OfflineProfilePinFlow.Result result = run(cache, gateway, wake,
                new AtomicBoolean(), clock);

        assertEquals(OfflineProfilePinFlow.Status.TIMEOUT, result.status);
        assertEquals(90_000L, clock.now);
        assertEquals(90, gateway.profileCalls);
        assertEquals(1, wake.calls);
        assertEquals(1, gateway.verifyCalls);
        assertEquals(0, cache.rememberCalls);
    }

    @Test public void serverWrongPinAfterWakeForgetsCachedVerifier() {
        FakeCache cache = new FakeCache(result(OfflineProfilePinStore.Status.MATCH, 0));
        FakeGateway gateway = offlineFirstGateway();
        gateway.profileResponses.add(profiles(true, true));
        gateway.verifyResponses.add(new HostGatewayClient.GatewayException(
                "invalid_pin", 401));
        FakeWake wake = new FakeWake();

        OfflineProfilePinFlow.Result result = run(cache, gateway, wake);

        assertEquals(OfflineProfilePinFlow.Status.INVALID, result.status);
        assertEquals(2, gateway.verifyCalls);
        assertEquals(1, wake.calls);
        assertEquals(1, cache.forgetCalls);
        assertEquals(0, cache.rememberCalls);
    }

    @Test public void tlsFailureNeverFallsBackToLocalVerifier() {
        FakeCache cache = new FakeCache(result(OfflineProfilePinStore.Status.MATCH, 0));
        FakeGateway gateway = new FakeGateway();
        gateway.verifyResponses.add(new SSLHandshakeException("certificate rejected"));
        FakeWake wake = new FakeWake();

        OfflineProfilePinFlow.Result result = run(cache, gateway, wake);

        assertEquals(OfflineProfilePinFlow.Status.UNAVAILABLE, result.status);
        assertEquals(0, cache.checkCalls);
        assertEquals(0, wake.calls);
        assertFalse(OfflineProfilePinFlow.isOfflineTransport(
                new IOException("wrapped", new SSLHandshakeException("bad cert"))));
    }

    @Test public void cancellationAfterLocalMatchPreventsWake() {
        FakeCache cache = new FakeCache(result(OfflineProfilePinStore.Status.MATCH, 0));
        FakeGateway gateway = offlineFirstGateway();
        FakeWake wake = new FakeWake();
        AtomicBoolean cancelled = new AtomicBoolean();
        cache.afterCheck = () -> cancelled.set(true);

        OfflineProfilePinFlow.Result result = run(cache, gateway, wake, cancelled,
                new FakeClock());

        assertEquals(OfflineProfilePinFlow.Status.CANCELLED, result.status);
        assertEquals(0, wake.calls);
        assertEquals(1, gateway.verifyCalls);
    }

    @Test public void cancellationDuringPollPreventsSecondVerify() {
        FakeCache cache = new FakeCache(result(OfflineProfilePinStore.Status.MATCH, 0));
        FakeGateway gateway = offlineFirstGateway();
        gateway.profileResponses.add(new ConnectException("still asleep"));
        FakeWake wake = new FakeWake();
        AtomicBoolean cancelled = new AtomicBoolean();
        gateway.afterWait = () -> cancelled.set(true);

        OfflineProfilePinFlow.Result result = run(cache, gateway, wake, cancelled,
                new FakeClock());

        assertEquals(OfflineProfilePinFlow.Status.CANCELLED, result.status);
        assertEquals(1, wake.calls);
        assertEquals(1, gateway.verifyCalls);
        assertEquals(0, cache.rememberCalls);
    }

    @Test public void removedOrUnprotectedProfileClearsCacheWithoutSecondVerify() {
        FakeCache cache = new FakeCache(result(OfflineProfilePinStore.Status.MATCH, 0));
        FakeGateway gateway = offlineFirstGateway();
        gateway.profileResponses.add(profiles(false, false));
        FakeWake wake = new FakeWake();

        OfflineProfilePinFlow.Result result = run(cache, gateway, wake);

        assertEquals(OfflineProfilePinFlow.Status.PROFILE_DENIED, result.status);
        assertEquals(1, gateway.verifyCalls);
        assertEquals(1, wake.calls);
        assertEquals(1, cache.forgetCalls);
        assertEquals(0, cache.rememberCalls);
    }

    @Test public void pairingReplacementAfterLocalCheckPreventsWake() {
        FakeCache cache = new FakeCache(result(OfflineProfilePinStore.Status.MATCH, 0));
        FakeGateway gateway = offlineFirstGateway();
        FakeWake wake = new FakeWake();
        ConnectionSource source = new ConnectionSource(connection());
        cache.afterCheck = () -> source.current = connection("replacement-token");

        OfflineProfilePinFlow.Result result = OfflineProfilePinFlow.run(
                HOST, connection(), PIN, cache, gateway, wake, source,
                new FakeClock()::nowMillis, millis -> true, () -> false);

        assertEquals(OfflineProfilePinFlow.Status.CANCELLED, result.status);
        assertEquals(0, wake.calls);
    }

    private static OfflineProfilePinFlow.Result run(FakeCache cache, FakeGateway gateway,
                                                    FakeWake wake) {
        return run(cache, gateway, wake, new AtomicBoolean(), new FakeClock());
    }

    private static OfflineProfilePinFlow.Result run(FakeCache cache, FakeGateway gateway,
                                                    FakeWake wake, AtomicBoolean cancelled,
                                                    FakeClock clock) {
        return OfflineProfilePinFlow.run(HOST, connection(), PIN, cache, gateway, wake,
                new ConnectionSource(connection()), clock::nowMillis,
                millis -> {
                    gateway.waits++;
                    clock.now += millis;
                    if (gateway.afterWait != null) gateway.afterWait.run();
                    return true;
                }, cancelled::get);
    }

    private static FakeGateway offlineFirstGateway() {
        FakeGateway gateway = new FakeGateway();
        gateway.verifyResponses.add(new ConnectException("offline"));
        return gateway;
    }

    private static OfflineProfilePinStore.Result result(OfflineProfilePinStore.Status status,
                                                         int retryAfterSeconds) {
        return new OfflineProfilePinStore.Result(status, retryAfterSeconds);
    }

    private static GatewayConnection connection() {
        return connection("token");
    }

    private static GatewayConnection connection(String token) {
        return new GatewayConnection("https://192.0.2.10:8785", token, CERTIFICATE, PROFILE);
    }

    private static HostGatewayClient.IntegrationProfiles profiles(boolean useProfile,
                                                                   boolean pinRequired) {
        HostGatewayClient.IntegrationProfile profile = new HostGatewayClient.IntegrationProfile(
                PROFILE, "Profile", false, false, false, false, false, false, false,
                useProfile, false, "ready", "ready", pinRequired);
        return new HostGatewayClient.IntegrationProfiles(
                Collections.singletonList(profile), PROFILE);
    }

    private static final class ConnectionSource implements OfflineProfilePinFlow.ConnectionSource {
        GatewayConnection current;

        ConnectionSource(GatewayConnection current) {
            this.current = current;
        }

        @Override public GatewayConnection current() {
            return current;
        }
    }

    private static final class FakeCache implements OfflineProfilePinFlow.Cache {
        final OfflineProfilePinStore.Result result;
        int checkCalls;
        int rememberCalls;
        int forgetCalls;
        Runnable afterCheck;
        Runnable afterRemember;

        FakeCache(OfflineProfilePinStore.Result result) {
            this.result = result;
        }

        @Override public OfflineProfilePinStore.Result check(
                String hostId, GatewayConnection connection, String pin) {
            checkCalls++;
            if (afterCheck != null) afterCheck.run();
            return result;
        }

        @Override public void rememberVerified(
                String hostId, GatewayConnection connection, String pin) {
            rememberCalls++;
            if (afterRemember != null) afterRemember.run();
        }

        @Override public void forget(String hostId, String profileId) {
            forgetCalls++;
        }
    }

    private static final class FakeGateway implements OfflineProfilePinFlow.Gateway {
        final List<Object> verifyResponses = new ArrayList<>();
        final List<Object> profileResponses = new ArrayList<>();
        int verifyCalls;
        int profileCalls;
        int waits;
        int verifyIndex;
        int profileIndex;
        Runnable afterWait;
        FakeWake expectedWake;
        Object defaultProfileResponse;

        @Override public void verifyProfilePin(GatewayConnection connection, String pin)
                throws IOException {
            verifyCalls++;
            Object response = verifyIndex < verifyResponses.size()
                    ? verifyResponses.get(verifyIndex++) : null;
            throwIfError(response);
        }

        @Override public HostGatewayClient.IntegrationProfiles getIntegrationProfiles(
                GatewayConnection connection) throws IOException {
            profileCalls++;
            if (expectedWake != null) assertTrue(expectedWake.calls > 0);
            Object response = profileIndex < profileResponses.size()
                    ? profileResponses.get(profileIndex++)
                    : defaultProfileResponse == null ? profiles(true, true)
                    : defaultProfileResponse;
            if (response == null) return null;
            if (response instanceof IOException) throw (IOException) response;
            return (HostGatewayClient.IntegrationProfiles) response;
        }

        private static void throwIfError(Object response) throws IOException {
            if (response instanceof IOException) throw (IOException) response;
        }
    }

    private static final class FakeWake implements OfflineProfilePinFlow.Wake {
        int calls;

        @Override public void send() {
            calls++;
        }
    }

    private static final class FakeClock implements OfflineProfilePinFlow.Clock {
        long now;

        @Override public long nowMillis() {
            return now;
        }
    }
}
