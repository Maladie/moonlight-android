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
        /** Sunshine runs a stream this session does not own. */
        OTHER_STREAM,
        FAILED;

        /** The whole session ended: the stream closed and no unverified game was left. */
        public boolean closed() {
            return this == CLOSED;
        }

        /**
         * The host stream closed, possibly leaving an unverified game. Only a user's explicit
         * close of this session (cancel start, close session) may treat that as done.
         */
        public boolean streamClosed() {
            return this == CLOSED || this == CLOSED_GAME_UNVERIFIED;
        }

        public static Outcome of(boolean closed) {
            return closed ? CLOSED : FAILED;
        }
    }

    private HostSessionTermination() { }

    public static Outcome run(Context context, String hostId, String activeHost,
                              int port, int httpsPort, String uniqueId, byte[] certificate,
                              String streamSessionId, int appId, String gameId,
                              long expectedGeneration,
                              PlayniteTransitionGateway.ActorContext actor) {
        GatewayConnection gateway = new HostGatewayStore(context).loadForHost(
                hostId, activeHost, actor.actorProfileId);
        if (gateway == null || expectedGeneration <= 0L) return Outcome.FAILED;
        ControlOperationClient control = new ControlOperationClient(new GatewayTransport());
        String operationId = "control-full-quit-" + UUID.randomUUID();
        ControlOperationClient.Permit permit = null;
        boolean effectSucceeded = false;
        boolean effectResultUncertain = false;
        boolean gameLeftUnverified = false;
        try {
            X509Certificate serverCertificate = certificate == null ? null
                    : (X509Certificate) CertificateFactory.getInstance("X.509")
                    .generateCertificate(new ByteArrayInputStream(certificate));
            NvHTTP http = new NvHTTP(new ComputerDetails.AddressTuple(activeHost, port),
                    httpsPort, uniqueId, serverCertificate,
                    PlatformBinding.getCryptoProvider(context));
            int running = http.getComputerDetails(true).runningGameId;
            try {
                permit = control.acquire(gateway, operationId, "full_quit", streamSessionId,
                        appId, expectedGeneration, running, "");
            } catch (Exception unavailable) {
                // A definitive rejection grants no permit, so there is nothing to reconcile.
                Outcome rejected = rejection(unavailable);
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
                        provider.stopGameWithinControl(exact, permit);
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
            return failure(error.getMessage());
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
    static Outcome rejection(Exception error) {
        return error instanceof GatewayTransport.GatewayException
                ? failure(error.getMessage()) : Outcome.FAILED;
    }

    static Outcome failure(String reason) {
        return "retained_process_unconfirmed".equals(reason) ? Outcome.GAME_UNVERIFIED
                : "unmanaged_stream".equals(reason) ? Outcome.OTHER_STREAM : Outcome.FAILED;
    }
}
