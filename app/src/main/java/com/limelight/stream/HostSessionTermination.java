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
        /** The running provider game could not be verified as the one to stop. */
        GAME_UNVERIFIED,
        /** Sunshine runs a stream this session does not own. */
        OTHER_STREAM,
        FAILED;

        public boolean closed() {
            return this == CLOSED;
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
                              PlayniteTransitionGateway.ActorContext actor,
                              boolean providerAlreadyStopped) {
        GatewayConnection gateway = new HostGatewayStore(context).loadForHost(
                hostId, activeHost, actor.actorProfileId);
        if (gateway == null || expectedGeneration <= 0L) return Outcome.FAILED;
        ControlOperationClient control = new ControlOperationClient(new GatewayTransport());
        String operationId = "control-full-quit-" + UUID.randomUUID();
        ControlOperationClient.Permit permit = null;
        boolean effectSucceeded = false;
        boolean effectResultUncertain = false;
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
            // The Gateway grants a full_quit only with the provider idle or its exact game
            // verified, under the lock that fences provider transitions. A permit without a
            // provider game is that idle proof; a re-read can only lose its dispatch ordering
            // to the dashboard poll and fail closed, rejecting the quit before /cancel.
            if (!providerAlreadyStopped && !permit.providerGameId.isEmpty()) {
                PlayniteTransitionGateway provider = PlayniteTransitionGateway.connect(
                        context, hostId, activeHost, actor);
                if (provider == null) throw new IllegalStateException("gateway_unavailable");
                String expectedGame = gameId == null ? "" : gameId.trim();
                PlayniteTransitionGateway.Snapshot current = provider.stopEvidenceWithinControl(
                        permit, expectedGame, STOP_EVIDENCE_TIMEOUT_MILLIS);
                String exact = stopTarget(expectedGame, current.gameState, current.gameId);
                if (exact == null) throw new IllegalStateException("provider_game_mismatch");
                if (!exact.isEmpty()) {
                    if (!current.hasVerifiedProcessFor(exact)
                            || permit.processToken.isEmpty()
                            || !permit.processToken.equals(current.processToken)
                            || !exact.equalsIgnoreCase(permit.providerGameId)) {
                        throw new IllegalStateException("provider_process_mismatch");
                    }
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
            return Outcome.of(effectSucceeded
                    && control.complete(gateway, permit, true).succeeded());
        } catch (Exception error) {
            if (permit != null && effectSucceeded) {
                try {
                    return Outcome.of(control.complete(gateway, permit, true).succeeded());
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

    /** The outcome of a Gateway rejection of the acquire; FAILED for anything else. */
    static Outcome rejection(Exception error) {
        return error instanceof GatewayTransport.GatewayException
                ? failure(error.getMessage()) : Outcome.FAILED;
    }

    static Outcome failure(String reason) {
        return "provider_game_mismatch".equals(reason)
                || "provider_process_mismatch".equals(reason)
                || "retained_process_unconfirmed".equals(reason) ? Outcome.GAME_UNVERIFIED
                : "unmanaged_stream".equals(reason) ? Outcome.OTHER_STREAM : Outcome.FAILED;
    }
}
