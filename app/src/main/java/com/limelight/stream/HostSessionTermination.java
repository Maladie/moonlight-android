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

    private HostSessionTermination() { }

    public static boolean run(Context context, String hostId, String activeHost,
                              int port, int httpsPort, String uniqueId, byte[] certificate,
                              String streamSessionId, int appId, String gameId,
                              long expectedGeneration,
                              PlayniteTransitionGateway.ActorContext actor,
                              boolean providerAlreadyStopped) {
        GatewayConnection gateway = new HostGatewayStore(context).loadForHost(
                hostId, activeHost, actor.actorProfileId);
        if (gateway == null || expectedGeneration <= 0L) return false;
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
                ControlOperationClient.Result reconciled = control.reconcile(
                        gateway, operationId, streamSessionId, appId, running);
                return reconciled.succeeded();
            }
            if (permit.succeeded()) return true;
            if (!providerAlreadyStopped) {
                PlayniteTransitionGateway provider = PlayniteTransitionGateway.connect(
                        context, hostId, activeHost, actor);
                if (provider == null) throw new IllegalStateException("gateway_unavailable");
                PlayniteTransitionGateway.Snapshot current = provider.snapshotWithinControl(permit);
                String expectedGame = gameId == null ? "" : gameId.trim();
                boolean providerIdle = "idle".equalsIgnoreCase(current.gameState)
                        && (current.gameId == null || current.gameId.trim().isEmpty());
                String exact = providerIdle ? ""
                        : expectedGame.isEmpty() ? null
                        : PlayniteIdentityResolutionPolicy.verifiedStopTarget(
                                expectedGame, current.gameState, current.gameId);
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
            if (running != 0 && running != appId) {
                throw new IllegalStateException("unmanaged_stream");
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
            return effectSucceeded && control.complete(gateway, permit, true).succeeded();
        } catch (Exception error) {
            if (permit != null && effectSucceeded) {
                try {
                    return control.complete(gateway, permit, true).succeeded();
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
            return false;
        }
    }
}
