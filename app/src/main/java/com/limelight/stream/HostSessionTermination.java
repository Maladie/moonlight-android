package com.limelight.stream;

import android.content.Context;

import com.limelight.binding.PlatformBinding;
import com.limelight.console.HostGatewayStore;
import com.limelight.console.PlayniteIdentityResolutionPolicy;
import com.limelight.console.PlayniteTransitionGateway;
import com.limelight.gateway.ControlOperationClient;
import com.limelight.gateway.GatewayConnection;
import com.limelight.gateway.GatewayTransport;
import com.limelight.nvstream.http.ComputerDetails;
import com.limelight.nvstream.http.NvHTTP;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.UUID;
import java.util.concurrent.Callable;

/** Activity-free host half of one exact whole-session termination. */
public final class HostSessionTermination {
    private static final long VERIFY_TIMEOUT_NANOS = 8_000_000_000L;
    // The provider rescans running games at once after a catalog update (5 s interval otherwise).
    private static final long STOP_EVIDENCE_TIMEOUT_MILLIS = 8_000L;

    /** Why a whole-session close ended; the caller tells the user. */
    public enum Outcome {
        CLOSED,
        /**
         * The stream closed, but the host named a provider game this close could not verify
         * (a failed or replaced start), so it was not stopped and may still run on the host.
         */
        CLOSED_GAME_UNVERIFIED,
        /** The Gateway refused the close: it could not prove the provider game or its absence. */
        GAME_UNVERIFIED,
        /** The host stream is another owner's, or Sunshine runs an app MoonWaker did not start. */
        OTHER_STREAM,
        /**
         * Nothing of this session is left to close: no stream record while Sunshine runs no
         * application; or, for an abandoned start, the host stream is a newer generation's
         * or another owner's.
         */
        NOTHING_TO_CLOSE,
        /**
         * One attempt's refusal, never a result: the host holds a newer generation of this
         * owner's stream (every later start on it advances the record), so the session and
         * its game still run under a binding this attempt did not name.
         */
        SUPERSEDED,
        FAILED;

        /** The whole session ended: the stream closed and no unverified game was left. */
        public boolean closed() {
            return this == CLOSED || this == NOTHING_TO_CLOSE;
        }

        /**
         * The host stream closed, possibly leaving an unverified game. Only a user's explicit
         * close of this session (cancel start, close session) may treat that as done.
         */
        public boolean streamClosed() {
            return closed() || this == CLOSED_GAME_UNVERIFIED;
        }

        public static Outcome of(boolean closed) {
            return closed ? CLOSED : FAILED;
        }
    }

    private HostSessionTermination() { }

    /**
     * A close the user ordered. A binding the host superseded still names a running session,
     * so the close is repeated once with the binding the host reports for this owner.
     */
    public static Outcome run(Context context, String hostId, String activeHost,
                              int port, int httpsPort, String uniqueId, byte[] certificate,
                              String streamSessionId, int appId, String gameId,
                              long expectedGeneration,
                              PlayniteTransitionGateway.ActorContext actor) {
        return close(false, context, hostId, activeHost, port, httpsPort, uniqueId,
                certificate, streamSessionId, appId, gameId, expectedGeneration, actor);
    }

    /**
     * Closes only what one start this client gave up left on the host: its exact generation.
     * A newer generation or another owner there means nothing of that start remains.
     */
    public static Outcome closeAbandonedStart(
            Context context, String hostId, String activeHost, int port, int httpsPort,
            String uniqueId, byte[] certificate, String streamSessionId, int appId,
            String gameId, long expectedGeneration,
            PlayniteTransitionGateway.ActorContext actor) {
        return close(true, context, hostId, activeHost, port, httpsPort, uniqueId,
                certificate, streamSessionId, appId, gameId, expectedGeneration, actor);
    }

    private static Outcome close(
            boolean abandonedStart, Context context, String hostId, String activeHost, int port,
            int httpsPort, String uniqueId, byte[] certificate, String streamSessionId,
            int appId, String gameId, long expectedGeneration,
            PlayniteTransitionGateway.ActorContext actor) {
        GatewayConnection gateway = new HostGatewayStore(context).loadForHost(
                hostId, activeHost, actor.actorProfileId);
        if (gateway == null || expectedGeneration <= 0L) return Outcome.FAILED;
        ControlOperationClient control = new ControlOperationClient(new GatewayTransport());
        return retrySuperseded(attempt(abandonedStart, context, hostId, activeHost, port,
                httpsPort, uniqueId, certificate, streamSessionId, appId, gameId,
                expectedGeneration, actor, gateway, control), () -> {
            ControlOperationClient.Permit owned = control.ownedSession(gateway);
            return attempt(false, context, hostId, activeHost, port, httpsPort, uniqueId,
                    certificate, owned.streamSessionId, owned.sunshineAppId, gameId,
                    owned.generation, actor, gateway, control);
        });
    }

    /** A superseded attempt is repeated once; a second refusal or no binding is a failure. */
    static Outcome retrySuperseded(Outcome first, Callable<Outcome> withOwnedBinding) {
        if (first != Outcome.SUPERSEDED) return first;
        try {
            Outcome second = withOwnedBinding.call();
            return second == Outcome.SUPERSEDED ? Outcome.FAILED : second;
        } catch (Exception unavailable) {
            return Outcome.FAILED;
        }
    }

    private static Outcome attempt(
            boolean abandonedStart, Context context, String hostId, String activeHost, int port,
            int httpsPort, String uniqueId, byte[] certificate, String streamSessionId,
            int appId, String gameId, long expectedGeneration,
            PlayniteTransitionGateway.ActorContext actor, GatewayConnection gateway,
            ControlOperationClient control) {
        String operationId = "control-full-quit-" + UUID.randomUUID();
        ControlOperationClient.Permit permit = null;
        boolean effectSucceeded = false;
        boolean effectResultUncertain = false;
        boolean gameLeftUnverified = false;
        int running = 0;
        try {
            X509Certificate serverCertificate = certificate == null ? null
                    : (X509Certificate) CertificateFactory.getInstance("X.509")
                    .generateCertificate(new ByteArrayInputStream(certificate));
            NvHTTP http = new NvHTTP(new ComputerDetails.AddressTuple(activeHost, port),
                    httpsPort, uniqueId, serverCertificate,
                    PlatformBinding.getCryptoProvider(context));
            running = http.getComputerDetails(true).runningGameId;
            try {
                permit = control.acquire(gateway, operationId, "full_quit", streamSessionId,
                        appId, expectedGeneration, running, "");
            } catch (Exception unavailable) {
                // A definitive rejection grants no permit, so there is nothing to reconcile.
                Outcome rejected = rejection(unavailable, running, abandonedStart);
                if (rejected != Outcome.FAILED) return rejected;
                ControlOperationClient.Result reconciled = control.reconcile(
                        gateway, operationId, streamSessionId, appId, running);
                return Outcome.of(reconciled.succeeded());
            }
            if (permit.succeeded()) return Outcome.CLOSED;
            // A foreign Sunshine stream fails the close before any provider game is stopped.
            if (running != 0 && running != appId) {
                throw new IllegalStateException("unmanaged_stream");
            }
            // The permit names the provider game (and its process token) the Gateway saw under
            // the lock that fences provider transitions. An empty name means the Gateway saw no
            // running game process (idle, or a start without a process yet), so there is nothing
            // this close may stop and no racy re-read may reject the quit before /cancel.
            if (!permit.providerGameId.isEmpty()) {
                PlayniteTransitionGateway provider = PlayniteTransitionGateway.connect(
                        context, hostId, activeHost, actor);
                if (provider == null) throw new IllegalStateException("gateway_unavailable");
                String expectedGame = gameId == null ? "" : gameId.trim();
                PlayniteTransitionGateway.Snapshot current = provider.stopEvidenceWithinControl(
                        permit, expectedGame, STOP_EVIDENCE_TIMEOUT_MILLIS);
                String exact = verifiedStopGame(expectedGame, current.gameState, current.gameId,
                        current.hasVerifiedProcessFor(current.gameId), current.processToken,
                        permit.providerGameId, permit.processToken);
                // An unverified game (a failed start, another game) is never stopped, but the
                // user's close still ends the stream, its virtual display and the permit.
                gameLeftUnverified = exact == null;
                if (exact != null && !exact.isEmpty()) {
                    effectResultUncertain = true;
                    try {
                        // A stop the host answered with a refusal is no uncertain effect: the
                        // game is left like an unverified one and the stream still closes.
                        // Reported as uncertain, it held the host until a hard close.
                        if (!provider.stopGameWithinControl(exact, permit)) {
                            gameLeftUnverified = true;
                        }
                    } catch (IOException lostResponse) {
                        try {
                            control.complete(gateway, permit, false);
                        } catch (IOException ignored) {
                            // The host keeps the exact operation uncertain.
                        }
                        if (!control.reconcile(gateway, permit, running).providerStopped()) {
                            throw lostResponse;
                        }
                    }
                    effectResultUncertain = false;
                }
            }
            if (running == appId) {
                effectResultUncertain = true;
                boolean accepted = http.quitApp();
                effectResultUncertain = false;
                if (!accepted) throw new IllegalStateException("sunshine_quit_rejected");
            }
            long deadline = System.nanoTime() + VERIFY_TIMEOUT_NANOS;
            while (running != 0 && System.nanoTime() < deadline) {
                running = http.getComputerDetails(true).runningGameId;
                if (running != 0 && running != appId) {
                    throw new IllegalStateException("unmanaged_stream");
                }
                if (running != 0) Thread.sleep(100L);
            }
            effectSucceeded = running == 0;
            return closed(effectSucceeded
                    && control.complete(gateway, permit, true).succeeded(), gameLeftUnverified);
        } catch (Exception error) {
            if (permit != null && effectSucceeded) {
                try {
                    return closed(control.complete(gateway, permit, true).succeeded(),
                            gameLeftUnverified);
                } catch (Exception ignored) {
                    // A retry with the same permit is idempotent and preserves recovery state.
                }
            } else if (permit != null) {
                try {
                    control.complete(gateway, permit, false, !effectResultUncertain);
                } catch (Exception ignored) {
                    // An actually uncertain dispatched effect remains sticky until recovery.
                }
            }
            if (error instanceof InterruptedException) Thread.currentThread().interrupt();
            return failure(error.getMessage(), running, abandonedStart);
        }
    }

    private static Outcome closed(boolean closed, boolean gameLeftUnverified) {
        return !closed ? Outcome.FAILED
                : gameLeftUnverified ? Outcome.CLOSED_GAME_UNVERIFIED : Outcome.CLOSED;
    }

    /**
     * The game a full_quit stops: "" with the provider idle, else the caller's expected game
     * when the provider runs exactly it; null when no expected game verifies the running one.
     */
    static String stopTarget(String expectedGame, String gameState, String currentGame) {
        if ("idle".equalsIgnoreCase(gameState)
                && (currentGame == null || currentGame.trim().isEmpty())) return "";
        return expectedGame.isEmpty() ? null : PlayniteIdentityResolutionPolicy
                .verifiedStopTarget(expectedGame, gameState, currentGame);
    }

    /**
     * The game this close stops: "" with the provider idle; the expected game only when the
     * permit-fenced observation verifies its process with the permit's exact token; null when
     * the host names a game this close cannot verify (it is then left running).
     */
    static String verifiedStopGame(String expectedGame, String gameState, String currentGame,
                                   boolean processVerified, String processToken,
                                   String permitGame, String permitToken) {
        String exact = stopTarget(expectedGame, gameState, currentGame);
        if (exact == null || exact.isEmpty()) return exact;
        return processVerified && !permitToken.isEmpty() && permitToken.equals(processToken)
                && exact.equalsIgnoreCase(permitGame) ? exact : null;
    }

    /** The outcome of a Gateway rejection of the acquire; FAILED for anything else. */
    static Outcome rejection(Exception error, int runningAppId, boolean abandonedStart) {
        return error instanceof GatewayTransport.GatewayException
                ? failure(error.getMessage(), runningAppId, abandonedStart) : Outcome.FAILED;
    }

    /**
     * The outcome of a refused or failed close, by the host's reason, the application
     * Sunshine ran when the close began (0: none) and whether it closes an abandoned start:
     * of that, a newer generation or another owner on the host leaves nothing.
     */
    static Outcome failure(String reason, int runningAppId, boolean abandonedStart) {
        if (reason == null) return Outcome.FAILED;
        switch (reason) {
            case "retained_process_unconfirmed": return Outcome.GAME_UNVERIFIED;
            case "stream_owner_mismatch":
                return abandonedStart ? Outcome.NOTHING_TO_CLOSE : Outcome.OTHER_STREAM;
            case "unmanaged_stream": return Outcome.OTHER_STREAM;
            case "stream_generation_superseded":
                return abandonedStart ? Outcome.NOTHING_TO_CLOSE : Outcome.SUPERSEDED;
            // No record: a running application may be this session's stream left behind.
            case "managed_stream_unavailable":
                return runningAppId == 0 ? Outcome.NOTHING_TO_CLOSE : Outcome.FAILED;
            default: return Outcome.FAILED;
        }
    }
}
