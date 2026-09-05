package com.limelight.console;

import com.limelight.gateway.GatewayConnection;

import java.io.IOException;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.ProtocolException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.security.GeneralSecurityException;
import java.util.function.BooleanSupplier;

import javax.net.ssl.SSLException;

/**
 * Coordinates the one-shot offline profile PIN path.  It deliberately knows nothing about
 * Android views or host state; the activity supplies the live connection and side effects.
 */
final class OfflineProfilePinFlow {
    static final long PROFILE_READY_TIMEOUT_MS = 90_000L;
    static final long PROFILE_POLL_INTERVAL_MS = 1_000L;

    enum Status {
        VERIFIED,
        INVALID,
        COOLDOWN,
        MISSING,
        PROFILE_DENIED,
        UNAVAILABLE,
        TIMEOUT,
        CANCELLED
    }

    static final class Result {
        final Status status;
        final int retryAfterSeconds;
        final HostGatewayClient.GatewayException gatewayError;
        final IOException transportError;
        final HostGatewayClient.IntegrationProfiles profiles;

        private Result(Status status, int retryAfterSeconds,
                       HostGatewayClient.GatewayException gatewayError,
                       IOException transportError,
                       HostGatewayClient.IntegrationProfiles profiles) {
            this.status = status;
            this.retryAfterSeconds = Math.max(0, retryAfterSeconds);
            this.gatewayError = gatewayError;
            this.transportError = transportError;
            this.profiles = profiles;
        }

        static Result verified(HostGatewayClient.IntegrationProfiles profiles) {
            return new Result(Status.VERIFIED, 0, null, null, profiles);
        }

        static Result invalid(int retryAfterSeconds,
                              HostGatewayClient.GatewayException error) {
            return new Result(Status.INVALID, retryAfterSeconds, error, null, null);
        }

        static Result cooldown(int retryAfterSeconds,
                               HostGatewayClient.GatewayException error) {
            return new Result(Status.COOLDOWN, retryAfterSeconds, error, null, null);
        }

        static Result missing() {
            return new Result(Status.MISSING, 0, null, null, null);
        }

        static Result profileDenied(HostGatewayClient.IntegrationProfiles profiles,
                                    HostGatewayClient.GatewayException error) {
            return new Result(Status.PROFILE_DENIED, 0, error, null, profiles);
        }

        static Result unavailable(HostGatewayClient.GatewayException error,
                                  IOException transportError) {
            return new Result(Status.UNAVAILABLE, 0, error, transportError, null);
        }

        static Result timeout() {
            return new Result(Status.TIMEOUT, 0, null, null, null);
        }

        static Result cancelled() {
            return new Result(Status.CANCELLED, 0, null, null, null);
        }
    }

    interface Cache {
        OfflineProfilePinStore.Result check(String hostId, GatewayConnection connection,
                                            String pin);

        void rememberVerified(String hostId, GatewayConnection connection, String pin);

        void forget(String hostId, String profileId);
    }

    interface Gateway {
        void verifyProfilePin(GatewayConnection connection, String pin) throws IOException;

        HostGatewayClient.IntegrationProfiles getIntegrationProfiles(
                GatewayConnection connection) throws IOException;
    }

    interface Wake {
        void send() throws IOException;
    }

    interface ConnectionSource {
        GatewayConnection current();
    }

    interface Waiter {
        boolean await(long millis);
    }

    interface Clock {
        long nowMillis();
    }

    private OfflineProfilePinFlow() {
    }

    static Cache cacheOf(OfflineProfilePinStore store) {
        if (store == null) throw new IllegalArgumentException("Missing offline PIN store");
        return new Cache() {
            @Override public OfflineProfilePinStore.Result check(
                    String hostId, GatewayConnection connection, String pin) {
                return store.check(hostId, connection, pin);
            }

            @Override public void rememberVerified(
                    String hostId, GatewayConnection connection, String pin) {
                store.rememberVerified(hostId, connection, pin);
            }

            @Override public void forget(String hostId, String profileId) {
                store.forget(hostId, profileId);
            }
        };
    }

    static Result run(String hostId, GatewayConnection pairing, String pin,
                      Cache cache, Gateway gateway, Wake wake,
                      ConnectionSource connections, Clock clock, Waiter waiter,
                      BooleanSupplier cancelled) {
        if (hostId == null || hostId.isEmpty() || pairing == null || pin == null
                || cache == null || gateway == null || wake == null || connections == null
                || clock == null || waiter == null || cancelled == null) {
            return Result.unavailable(null, null);
        }

        GatewayConnection connection = currentPairing(pairing, connections, cancelled);
        if (connection == null) return Result.cancelled();

        try {
            gateway.verifyProfilePin(connection, pin);
            return rememberIfCurrent(hostId, pairing, pin, cache, connections, cancelled);
        } catch (HostGatewayClient.GatewayException error) {
            if (isInvalidPin(error)) {
                return Result.invalid(error.retryAfterSeconds, error);
            }
            if (isCooldown(error)) {
                return Result.cooldown(error.retryAfterSeconds, error);
            }
            // A server response is authoritative that transport exists. Never use the
            // cached verifier or wake for HTTP/auth/protocol decisions.
            return Result.unavailable(error, null);
        } catch (IOException error) {
            if (!isOfflineTransport(error)) return Result.unavailable(null, error);
        }

        connection = currentPairing(pairing, connections, cancelled);
        if (connection == null) return Result.cancelled();
        OfflineProfilePinStore.Result local = cache.check(hostId, connection, pin);
        if (local == null) return Result.missing();
        switch (local.status) {
            case MATCH:
                break;
            case INVALID:
                return Result.invalid(local.retryAfterSeconds, null);
            case COOLDOWN:
                return Result.cooldown(local.retryAfterSeconds, null);
            case MISSING:
            default:
                return Result.missing();
        }

        if (currentPairing(pairing, connections, cancelled) == null) {
            return Result.cancelled();
        }
        try {
            wake.send();
        } catch (IOException error) {
            return Result.unavailable(null, error);
        }

        HostGatewayClient.IntegrationProfiles profiles = null;
        long deadline = safeDeadline(clock.nowMillis(), PROFILE_READY_TIMEOUT_MS);
        while (!cancelled.getAsBoolean()) {
            if (clock.nowMillis() >= deadline) return Result.timeout();
            connection = currentPairing(pairing, connections, cancelled);
            if (connection == null) return Result.cancelled();
            try {
                profiles = gateway.getIntegrationProfiles(connection);
                if (profiles != null) {
                    if (clock.nowMillis() >= deadline) return Result.timeout();
                    break;
                }
            } catch (HostGatewayClient.GatewayException error) {
                // A reachable gateway rejected the readiness request. Do not retry PIN or
                // open the selected profile based on stale cached metadata.
                return Result.unavailable(error, null);
            } catch (IOException error) {
                if (!isOfflineTransport(error)) return Result.unavailable(null, error);
            }
            if (clock.nowMillis() >= deadline) return Result.timeout();
            if (!waiter.await(Math.min(PROFILE_POLL_INTERVAL_MS,
                    Math.max(1L, deadline - clock.nowMillis())))) {
                return cancelled.getAsBoolean() ? Result.cancelled() : Result.timeout();
            }
        }
        if (cancelled.getAsBoolean()) return Result.cancelled();
        if (profiles == null) return Result.timeout();

        HostGatewayClient.IntegrationProfile profile = profiles.find(pairing.profileId());
        if (profile == null || !profile.useProfile || !profile.pinRequired) {
            if (currentPairing(pairing, connections, cancelled) == null) {
                return Result.cancelled();
            }
            cache.forget(hostId, pairing.profileId());
            return Result.profileDenied(profiles, null);
        }

        connection = currentPairing(pairing, connections, cancelled);
        if (connection == null) return Result.cancelled();
        try {
            gateway.verifyProfilePin(connection, pin);
        } catch (HostGatewayClient.GatewayException error) {
            if (isInvalidPin(error)) {
                // The server has now disproved the cached verifier. Only this authoritative
                // result may clear it; an ordinary online wrong PIN above must not do so.
                if (currentPairing(pairing, connections, cancelled) == null) {
                    return Result.cancelled();
                }
                cache.forget(hostId, pairing.profileId());
                return Result.invalid(error.retryAfterSeconds, error);
            }
            if (isCooldown(error)) return Result.cooldown(error.retryAfterSeconds, error);
            return Result.unavailable(error, null);
        } catch (IOException error) {
            return Result.unavailable(null, error);
        }
        return rememberIfCurrent(hostId, pairing, pin, cache, connections, cancelled, profiles);
    }

    private static Result rememberIfCurrent(String hostId, GatewayConnection pairing, String pin,
                                            Cache cache, ConnectionSource connections,
                                            BooleanSupplier cancelled) {
        return rememberIfCurrent(hostId, pairing, pin, cache, connections, cancelled, null);
    }

    private static Result rememberIfCurrent(String hostId, GatewayConnection pairing, String pin,
                                            Cache cache, ConnectionSource connections,
                                            BooleanSupplier cancelled,
                                            HostGatewayClient.IntegrationProfiles profiles) {
        if (cancelled.getAsBoolean()) return Result.cancelled();
        GatewayConnection connection = currentPairing(pairing, connections, cancelled);
        if (connection == null) return Result.cancelled();
        cache.rememberVerified(hostId, connection, pin);
        return Result.verified(profiles);
    }

    private static GatewayConnection currentPairing(GatewayConnection pairing,
                                                    ConnectionSource connections,
                                                    BooleanSupplier cancelled) {
        if (cancelled.getAsBoolean()) return null;
        GatewayConnection current = connections.current();
        return samePairing(pairing, current) ? current : null;
    }

    static boolean samePairing(GatewayConnection expected, GatewayConnection current) {
        return expected != null && current != null
                && expected.profileId().equals(current.profileId())
                && expected.token().equals(current.token())
                && expected.certificateSha256().equals(current.certificateSha256());
    }

    static boolean isOfflineTransport(IOException error) {
        if (error == null) return false;
        boolean offline = false;
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof GeneralSecurityException || cause instanceof SSLException
                    || cause instanceof ProtocolException) return false;
            if (cause instanceof ConnectException || cause instanceof SocketTimeoutException
                    || cause instanceof NoRouteToHostException
                    || cause instanceof UnknownHostException) {
                offline = true;
            }
        }
        return offline;
    }

    private static boolean isInvalidPin(HostGatewayClient.GatewayException error) {
        String message = error == null ? "" : error.getMessage();
        return "invalid_pin".equals(message);
    }

    private static boolean isCooldown(HostGatewayClient.GatewayException error) {
        if (error == null) return false;
        String message = error.getMessage();
        return error.statusCode == 429 || "rate_limited".equals(message)
                || "pin_rate_limited".equals(message);
    }

    private static long safeDeadline(long now, long duration) {
        return Long.MAX_VALUE - now < duration ? Long.MAX_VALUE : now + duration;
    }
}
