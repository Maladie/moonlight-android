package com.limelight.console;

import com.limelight.LimeLog;

import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/** Switches Playnite games behind MoonWaker's existing opaque privacy layer. */
final class PlayniteLaunchOrchestrator implements LaunchOrchestrator, AutoCloseable {
    interface Backend {
        HostGatewayClient.PlayniteCurrentGame current() throws Exception;
        HostGatewayClient.PlayniteHealth health() throws Exception;
        void start(String gameId) throws Exception;
        void showFullscreen() throws Exception;
        HostGatewayClient.PlayniteReadiness readiness() throws Exception;
    }

    private static final long DEFAULT_TIMEOUT_MS = 120_000L;
    private static final long POLL_MS = 250L;
    private static final long UNCHANGED_CONNECTOR_SETTLE_MS = 5_000L;
    private static final long RECONNECTED_SETTLE_MS = 500L;
    private final Backend backend;
    private final ExecutorService executor;
    private final long timeoutMs;
    private final boolean waitForTransportConnector;
    private final AtomicInteger generation = new AtomicInteger();

    PlayniteLaunchOrchestrator(HostGatewayClient client,
                               HostGatewayClient.Connection connection,
                               boolean waitForTransportConnector) {
        this(new Backend() {
            @Override public HostGatewayClient.PlayniteCurrentGame current() throws Exception {
                return client.getPlayniteCurrentGame(connection);
            }

            @Override public HostGatewayClient.PlayniteHealth health() throws Exception {
                return client.getPlayniteHealth(connection);
            }

            @Override public void start(String gameId) throws Exception {
                client.startPlayniteGame(connection, gameId);
            }

            @Override public void showFullscreen() throws Exception {
                client.showPlayniteFullscreen(connection);
            }

            @Override public HostGatewayClient.PlayniteReadiness readiness() throws Exception {
                return client.getPlayniteReadiness(connection);
            }
        }, DEFAULT_TIMEOUT_MS, waitForTransportConnector);
    }

    PlayniteLaunchOrchestrator(Backend backend, long timeoutMs) {
        this(backend, timeoutMs, false);
    }

    PlayniteLaunchOrchestrator(Backend backend, long timeoutMs,
                               boolean waitForTransportConnector) {
        this.backend = backend;
        this.timeoutMs = timeoutMs;
        this.waitForTransportConnector = waitForTransportConnector;
        executor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "PlayniteLaunch");
            thread.setDaemon(true);
            return thread;
        });
    }

    @Override public void launch(Request request, Listener listener) {
        if (request == null || !HostGatewayClient.isPlayniteId(request.playniteGameGuid)) {
            listener.onFailure("This Playnite game is not available.");
            return;
        }
        int operation = generation.incrementAndGet();
        executor.execute(() -> run(operation, request.playniteGameGuid, listener));
    }

    void restoreFullscreen(Listener listener) {
        int operation = generation.incrementAndGet();
        executor.execute(() -> {
            try {
                listener.onStarting();
                listener.onProgress("Restoring Playnite Fullscreen…");
                backend.showFullscreen();
                pollUntilReady(operation, listener, "playnite");
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            } catch (Exception error) {
                if (operation == generation.get()) {
                    listener.onFailure("Playnite Fullscreen could not be restored. The desktop remains hidden.");
                }
            }
        });
    }

    private void run(int operation, String gameId, Listener listener) {
        try {
            listener.onStarting();
            if (waitForTransportConnector) {
                waitForConnectorSettle(operation, listener);
            }
            HostGatewayClient.PlayniteCurrentGame current = backend.current();
            boolean alreadyRunning = "running".equals(current.state) &&
                    gameId.equalsIgnoreCase(current.id);
            if (alreadyRunning) {
                listener.onProgress("Restoring the running game…");
            } else {
                listener.onProgress("Starting game in Playnite…");
                backend.start(gameId);
            }
            pollUntilReady(operation, listener, "game");
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        } catch (Exception error) {
            if (operation == generation.get()) {
                LimeLog.warning("Playnite launch failed: " + error.getMessage());
                listener.onFailure("Playnite could not prepare the game. The desktop remains hidden.");
            }
        }
    }

    private void waitForConnectorSettle(int operation, Listener listener) throws Exception {
        listener.onProgress("Waiting for Playnite after stream startupâ€¦");
        long deadline = System.currentTimeMillis() + Math.min(timeoutMs, 30_000L);
        long stableSince = 0L;
        int firstGeneration = -1;
        int lastGeneration = -1;
        while (operation == generation.get() && System.currentTimeMillis() < deadline) {
            HostGatewayClient.PlayniteHealth health;
            try {
                health = backend.health();
            } catch (Exception transientError) {
                stableSince = 0L;
                Thread.sleep(500L);
                continue;
            }
            long now = System.currentTimeMillis();
            if (!health.connectorConnected) {
                stableSince = 0L;
                Thread.sleep(POLL_MS);
                continue;
            }
            if (firstGeneration < 0) firstGeneration = health.connectorGeneration;
            if (lastGeneration != health.connectorGeneration || stableSince == 0L) {
                lastGeneration = health.connectorGeneration;
                stableSince = now;
            }
            boolean reconnected = health.connectorGeneration > firstGeneration;
            long requiredStableMs = reconnected ? RECONNECTED_SETTLE_MS :
                    UNCHANGED_CONNECTOR_SETTLE_MS;
            if (now - stableSince >= requiredStableMs) return;
            Thread.sleep(POLL_MS);
        }
        throw new java.io.IOException("Playnite connector did not stabilize after transport startup");
    }

    private void pollUntilReady(int operation, Listener listener, String targetKind)
            throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMs;
        String lastReason = "";
        while (operation == generation.get() && System.currentTimeMillis() < deadline) {
            HostGatewayClient.PlayniteReadiness readiness;
            try {
                readiness = backend.readiness();
            } catch (Exception transientError) {
                // game/start may already be accepted when a profile Bridge briefly restarts.
                // Never reissue the launch or report failure from a single missed sample.
                if (!"bridge_reconnecting".equals(lastReason)) {
                    lastReason = "bridge_reconnecting";
                    listener.onProgress("Reconnecting to Playnite Bridge…");
                    LimeLog.warning("Playnite readiness sample failed; retrying: " +
                            transientError.getMessage());
                }
                Thread.sleep(1_000L);
                continue;
            }
            if (!readiness.reason.equals(lastReason)) {
                lastReason = readiness.reason;
                listener.onProgress(statusFor(readiness.reason));
            }
            boolean targetMatches = targetKind.equals(readiness.targetKind);
            boolean ready = readiness.ready && targetMatches;
            ReadinessSample sample = new ReadinessSample(ready, ready, ready,
                    ready ? readiness.stableSamples : 0);
            listener.onRunning(sample);
            if (ready) return;
            Thread.sleep(POLL_MS);
        }
        if (operation == generation.get()) {
            listener.onFailure("The target is still starting. The desktop remains hidden.");
        }
    }

    private static String statusFor(String reason) {
        if (reason == null) return "Waiting for the game…";
        switch (reason.toLowerCase(Locale.ROOT)) {
            case "game_starting": return "Starting game…";
            case "waiting_for_game_process_id": return "Waiting for the game process…";
            case "waiting_for_game_window": return "Waiting for the game window…";
            case "target_not_foreground": return "Bringing the game to the foreground…";
            case "target_on_wrong_display": return "Moving the game to the streamed display…";
            case "stabilizing_target_window": return "Checking the game window…";
            case "stream_display_not_configured": return "Waiting for the streamed display…";
            default: return "Preparing the game…";
        }
    }

    void cancel() {
        generation.incrementAndGet();
    }

    @Override public void close() {
        cancel();
        executor.shutdownNow();
    }
}
