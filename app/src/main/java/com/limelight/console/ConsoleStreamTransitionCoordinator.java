package com.limelight.console;

import com.limelight.console.transition.LaunchTransitionController;
import com.limelight.console.transition.LaunchTransitionSnapshot;
import com.limelight.console.transition.LaunchTransitionSpec;
import com.limelight.console.transition.LaunchTransitionState;
import com.limelight.console.transition.LaunchTransitionType;

import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

public final class ConsoleStreamTransitionCoordinator implements AutoCloseable {
    interface Gateway {
        PlayniteTransitionGateway.Snapshot snapshot() throws IOException;
        PlayniteTransitionGateway.Events awaitEvents(long after, String transitionId)
                throws IOException;
        void showFullscreen() throws IOException;
        void focusGame() throws IOException;
        void focusInstallation(String gameId) throws IOException;
        boolean verifyInstallation(String gameId) throws IOException;
        void ensureInstalledGameTarget(String gameId, String gameName) throws IOException;
    }

    interface MonotonicClock {
        long now();
    }

    interface Sleeper {
        void sleep(long delayMs) throws InterruptedException;
    }

    public interface Scheduler {
        void postDelayed(Runnable action, long delayMs);
    }

    public interface Callbacks {
        String gatewayUnavailableMessage();
        String hostSessionLockedMessage();
        String streamDisplayNotConfiguredMessage();
        String readinessUnconfirmedMessage();
        String launcherInteractionRequiredMessage();
        String windowStabilizingMessage();

        boolean isPendingInstallation(String hostId, String gameId);
        String pendingInstallationName(String hostId, String gameId);
        void onInstallationCompleted(String hostId, String gameId, String gameName);
        void onInstallationCancelled(String hostId, String gameId, String gameName);
        void onInstallationFailed(String hostId, String gameId, String gameName);
        void onInstallationAttentionRequired(String hostId, String gameId, String gameName);

        void onInstallationVerified();
        void onInstallationStillNeedsConfirmation();
        void onInstallationVerificationFailed();
    }

    private final LaunchTransitionSpec transitionSpec;
    private final LaunchTransitionController transitionController;
    private final Gateway gateway;
    private final ExecutorService executor;
    private final MonotonicClock clock;
    private final Sleeper sleeper;
    private final Scheduler scheduler;
    private final Callbacks callbacks;

    private Future<?> observation;
    private boolean stopped = true;
    private boolean closed;
    private long epoch;

    public ConsoleStreamTransitionCoordinator(
            LaunchTransitionSpec transitionSpec,
            LaunchTransitionController transitionController,
            PlayniteTransitionGateway gateway,
            Scheduler scheduler,
            Callbacks callbacks) {
        this(transitionSpec, transitionController, adapt(gateway),
                Executors.newSingleThreadExecutor(),
                () -> System.nanoTime() / 1_000_000L,
                Thread::sleep, scheduler, callbacks);
    }

    ConsoleStreamTransitionCoordinator(
            LaunchTransitionSpec transitionSpec,
            LaunchTransitionController transitionController,
            Gateway gateway,
            ExecutorService executor,
            MonotonicClock clock,
            Sleeper sleeper,
            Scheduler scheduler,
            Callbacks callbacks) {
        this.transitionSpec = transitionSpec;
        this.transitionController = transitionController;
        this.gateway = gateway;
        this.executor = executor;
        this.clock = clock;
        this.sleeper = sleeper;
        this.scheduler = scheduler;
        this.callbacks = callbacks;
    }

    public synchronized void start() {
        if (closed || !stopped) return;
        stopped = false;
        long runEpoch = ++epoch;
        if (transitionSpec.type == LaunchTransitionType.GENERIC) return;
        if (gateway == null) {
            if (isCurrent(runEpoch)) {
                transitionController.timedOut(
                        transitionSpec.id, callbacks.gatewayUnavailableMessage());
            }
            return;
        }
        try {
            observation = executor.submit(() -> observe(runEpoch));
        } catch (RuntimeException error) {
            if (isCurrent(runEpoch)) {
                transitionController.error(
                        transitionSpec.id, callbacks.gatewayUnavailableMessage());
            }
        }
    }

    public synchronized void stop() {
        if (stopped && observation == null) return;
        stopped = true;
        epoch++;
        if (observation != null) {
            observation.cancel(true);
            observation = null;
        }
    }

    @Override
    public void close() {
        synchronized (this) {
            if (closed) return;
            closed = true;
        }
        stop();
        executor.shutdownNow();
    }

    public boolean isInstallationConfirmationStream() {
        return transitionSpec.type == LaunchTransitionType.GENERIC
                && !transitionSpec.playniteGameId.isEmpty();
    }

    public void onStreamConnected() {
        if (!isInstallationConfirmationStream() || gateway == null) return;
        long actionEpoch = currentEpoch();
        if (actionEpoch < 0L) return;
        for (long delay : new long[]{0L, 1_500L, 4_000L}) {
            scheduler.postDelayed(() -> {
                if (!isCurrent(actionEpoch)) return;
                try {
                    executor.execute(() -> {
                        if (!isCurrent(actionEpoch)) return;
                        try {
                            gateway.focusInstallation(transitionSpec.playniteGameId);
                        } catch (IOException | RuntimeException ignored) {
                            // The prompt may close between these bounded attempts.
                        }
                    });
                } catch (RuntimeException ignored) {
                    // A delayed action may race with close().
                }
            }, delay);
        }
    }

    public void verifyInstallation() {
        long actionEpoch = currentEpoch();
        if (!isInstallationConfirmationStream() || gateway == null || actionEpoch < 0L) {
            callbacks.onInstallationVerificationFailed();
            return;
        }
        try {
            executor.execute(() -> {
                try {
                    boolean verified = gateway.verifyInstallation(transitionSpec.playniteGameId);
                    if (!isCurrent(actionEpoch)) return;
                    if (verified) callbacks.onInstallationVerified();
                    else callbacks.onInstallationStillNeedsConfirmation();
                } catch (IOException | RuntimeException error) {
                    if (isCurrent(actionEpoch)) callbacks.onInstallationVerificationFailed();
                }
            });
        } catch (RuntimeException error) {
            if (isCurrent(actionEpoch)) callbacks.onInstallationVerificationFailed();
        }
    }

    private void observe(long runEpoch) {
        ObservationContext context = new ObservationContext(clock.now());
        while (isCurrent(runEpoch) && !Thread.currentThread().isInterrupted()) {
            try {
                PlayniteTransitionGateway.Snapshot snapshot = gateway.snapshot();
                if (!isCurrent(runEpoch)) return;
                context.lastReadinessReason = snapshot.reason;
                if ("host_session_locked".equals(snapshot.reason)) {
                    if (!context.lockScreenPresented) {
                        context.lockScreenPresented = true;
                        applySnapshot(runEpoch, snapshot);
                    }
                } else {
                    context.lockScreenPresented = false;
                    applySnapshot(runEpoch, snapshot);
                }
                if (!isCurrent(runEpoch)) return;

                if (!context.fullscreenRequested
                        && transitionSpec.type == LaunchTransitionType.PLAYNITE
                        && !snapshot.windowReady
                        && !"host_session_locked".equals(snapshot.reason)) {
                    gateway.showFullscreen();
                    if (!isCurrent(runEpoch)) return;
                    context.fullscreenRequested = true;
                }

                long now = clock.now();
                if ("game".equalsIgnoreCase(snapshot.targetKind)
                        && !snapshot.windowReady
                        && "target_not_foreground".equals(snapshot.reason)
                        && context.gameFocusAttempts < 3
                        && now - context.lastGameFocusAttempt >= 3_000L) {
                    context.lastGameFocusAttempt = now;
                    context.gameFocusAttempts++;
                    try {
                        gateway.focusGame();
                    } catch (IOException ignored) {
                        // Readiness stays closed; a later bounded attempt may succeed.
                    }
                    if (!isCurrent(runEpoch)) return;
                }

                if ("running".equalsIgnoreCase(snapshot.gameState) && snapshot.processId > 0) {
                    context.gameWasRunning = true;
                }
                if (context.gameWasRunning
                        && "idle".equalsIgnoreCase(snapshot.gameState)
                        && "playnite".equalsIgnoreCase(snapshot.targetKind)) {
                    transitionController.gameStopping(transitionSpec.id,
                            transitionSpec.hostId, transitionSpec.playniteGameId);
                    if (!isCurrent(runEpoch)) return;
                    transitionController.playniteReturning(
                            transitionSpec.id, transitionSpec.hostId);
                    context.gameWasRunning = false;
                }

                PlayniteTransitionGateway.Events events =
                        gateway.awaitEvents(context.sequence, transitionSpec.id);
                if (!isCurrent(runEpoch)) return;
                context.sequence = events.latestSequence;
                for (PlayniteTransitionGateway.Event event : events.values) {
                    if (context.baselineEstablished || isPendingInstallationEvent(event)) {
                        applyEvent(runEpoch, event);
                    }
                }
                context.baselineEstablished = true;
                context.failures = 0;

                LaunchTransitionSnapshot current = transitionController.snapshot();
                if (current.state != context.observedState) {
                    context.observedState = current.state;
                    context.stateSince = clock.now();
                }
                long timeout = timeoutFor(current.state);
                if (timeout > 0L && clock.now() - context.stateSince >= timeout
                        && !"host_session_locked".equals(context.lastReadinessReason)
                        && isCurrent(runEpoch)) {
                    transitionController.timedOut(transitionSpec.id,
                            readinessFailureMessage(context.lastReadinessReason));
                }
            } catch (IOException | RuntimeException error) {
                context.failures++;
                if (context.failures >= 3 && isCurrent(runEpoch)) {
                    transitionController.error(
                            transitionSpec.id, callbacks.gatewayUnavailableMessage());
                }
                if (!isCurrent(runEpoch)) return;
                try {
                    sleeper.sleep(Math.min(4_000L, context.failures * 1_000L));
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    private void applySnapshot(long runEpoch, PlayniteTransitionGateway.Snapshot snapshot) {
        if (!snapshot.gatewayReady || !snapshot.connectorReady || !isCurrent(runEpoch)) return;
        transitionController.gatewayConnected(transitionSpec.id, transitionSpec.hostId);
        LaunchTransitionType kind = "game".equalsIgnoreCase(snapshot.targetKind)
                ? LaunchTransitionType.GAME : LaunchTransitionType.PLAYNITE;
        String gameId = snapshot.gameId == null || snapshot.gameId.isEmpty()
                ? transitionSpec.playniteGameId : snapshot.gameId;
        if (kind == LaunchTransitionType.GAME
                && "launcher_interaction_required".equals(snapshot.reason)) {
            transitionController.launcherInteractionRequired(
                    transitionSpec.id, transitionSpec.hostId, gameId,
                    callbacks.launcherInteractionRequiredMessage());
            return;
        }
        if (kind == LaunchTransitionType.GAME
                && "failed".equalsIgnoreCase(snapshot.gameState)) {
            transitionController.error(transitionSpec.id,
                    readinessFailureMessage(snapshot.reason));
            return;
        }
        if ("host_session_locked".equals(snapshot.reason)) {
            transitionController.targetWindowLost(transitionSpec.id, transitionSpec.hostId,
                    kind, gameId, callbacks.hostSessionLockedMessage());
            return;
        }
        if (snapshot.processId > 0) {
            transitionController.targetProcessRunning(
                    transitionSpec.id, transitionSpec.hostId, kind, gameId);
        }
        if (snapshot.windowReady) {
            transitionController.targetWindowReady(
                    transitionSpec.id, transitionSpec.hostId, kind, gameId);
        } else if (kind == LaunchTransitionType.GAME
                && transitionController.snapshot().state == LaunchTransitionState.GAME_RUNNING) {
            transitionController.targetWindowLost(transitionSpec.id, transitionSpec.hostId,
                    kind, gameId, callbacks.windowStabilizingMessage());
        } else if (snapshot.stableSamples > 0
                || "stabilizing_target_window".equals(snapshot.reason)) {
            transitionController.targetWindowStabilizing(transitionSpec.id,
                    transitionSpec.hostId, kind, gameId,
                    callbacks.windowStabilizingMessage());
        }
    }

    private void applyEvent(long runEpoch, PlayniteTransitionGateway.Event event) {
        if (!isCurrent(runEpoch)) return;
        String name = event.name == null ? "" : event.name;
        if ("game-installed".equals(name)) {
            if (!callbacks.isPendingInstallation(transitionSpec.hostId, event.gameId)) return;
            String gameName = installationName(event);
            try {
                gateway.ensureInstalledGameTarget(event.gameId, gameName);
            } catch (IOException | RuntimeException ignored) { }
            if (isCurrent(runEpoch)) {
                callbacks.onInstallationCompleted(
                        transitionSpec.hostId, event.gameId, gameName);
            }
        } else if ("game-installation-cancelled".equals(name)) {
            if (!callbacks.isPendingInstallation(transitionSpec.hostId, event.gameId)) return;
            if (!isCurrent(runEpoch)) return;
            callbacks.onInstallationCancelled(
                    transitionSpec.hostId, event.gameId, installationName(event));
        } else if ("game-installation-failed".equals(name)) {
            if (!callbacks.isPendingInstallation(transitionSpec.hostId, event.gameId)) return;
            if (!isCurrent(runEpoch)) return;
            callbacks.onInstallationFailed(
                    transitionSpec.hostId, event.gameId, installationName(event));
        } else if ("game-installation-attention-required".equals(name)) {
            if (!callbacks.isPendingInstallation(transitionSpec.hostId, event.gameId)) return;
            if (!isCurrent(runEpoch)) return;
            callbacks.onInstallationAttentionRequired(
                    transitionSpec.hostId, event.gameId, installationName(event));
        } else if ("game-starting".equals(name)) {
            transitionController.targetStarting(transitionSpec.id, transitionSpec.hostId,
                    LaunchTransitionType.GAME, event.gameId);
        } else if ("game-running".equals(name)) {
            transitionController.targetProcessRunning(transitionSpec.id, transitionSpec.hostId,
                    LaunchTransitionType.GAME, event.gameId);
        } else if ("game-stopping".equals(name) || "game-stopped".equals(name)) {
            transitionController.gameStopping(
                    transitionSpec.id, transitionSpec.hostId, event.gameId);
            if (!isCurrent(runEpoch)) return;
            transitionController.playniteReturning(
                    transitionSpec.id, transitionSpec.hostId);
        } else if ("privacy-gate-closed".equals(name)) {
            transitionController.targetWindowLost(transitionSpec.id, transitionSpec.hostId,
                    LaunchTransitionType.GAME, transitionSpec.playniteGameId,
                    callbacks.windowStabilizingMessage());
        } else if ("bridge-disconnected".equals(name)) {
            transitionController.playniteStopping(
                    transitionSpec.id, transitionSpec.hostId);
        }
    }

    private boolean isPendingInstallationEvent(PlayniteTransitionGateway.Event event) {
        if (event == null) return false;
        return ("game-installed".equals(event.name)
                || "game-installation-cancelled".equals(event.name)
                || "game-installation-failed".equals(event.name))
                && callbacks.isPendingInstallation(transitionSpec.hostId, event.gameId);
    }

    private String installationName(PlayniteTransitionGateway.Event event) {
        return event.gameName == null || event.gameName.isEmpty()
                ? callbacks.pendingInstallationName(transitionSpec.hostId, event.gameId)
                : event.gameName;
    }

    private String readinessFailureMessage(String reason) {
        if ("host_session_locked".equals(reason)) {
            return callbacks.hostSessionLockedMessage();
        }
        if ("stream_display_not_configured".equals(reason)) {
            return callbacks.streamDisplayNotConfiguredMessage();
        }
        return callbacks.readinessUnconfirmedMessage();
    }

    static long timeoutFor(LaunchTransitionState state) {
        switch (state) {
            case PREPARING_SESSION:
                return 90_000L;
            case CONNECTING_STREAM:
                return 30_000L;
            case WAITING_FOR_VIDEO_SURFACE:
                return 15_000L;
            case PLAYNITE_STARTING:
            case PLAYNITE_PROCESS_RUNNING:
                return 45_000L;
            case PLAYNITE_FULLSCREEN_STARTING:
                return 30_000L;
            case GAME_START_REQUESTED:
            case GAME_STARTING:
            case GAME_PROCESS_RUNNING:
                return 120_000L;
            case GAME_WINDOW_STABILIZING:
                return 30_000L;
            case PLAYNITE_RETURNING:
                return 45_000L;
            case PLAYNITE_STOPPING:
            case CLOSING_STREAM:
                return 15_000L;
            default:
                return 0L;
        }
    }

    private synchronized boolean isCurrent(long capturedEpoch) {
        return !closed && !stopped && epoch == capturedEpoch;
    }

    private synchronized long currentEpoch() {
        return closed || stopped ? -1L : epoch;
    }

    private static Gateway adapt(PlayniteTransitionGateway gateway) {
        if (gateway == null) return null;
        return new Gateway() {
            @Override public PlayniteTransitionGateway.Snapshot snapshot() throws IOException {
                return gateway.snapshot();
            }

            @Override public PlayniteTransitionGateway.Events awaitEvents(
                    long after, String transitionId) throws IOException {
                return gateway.awaitEvents(after, transitionId);
            }

            @Override public void showFullscreen() throws IOException {
                gateway.showFullscreen();
            }

            @Override public void focusGame() throws IOException {
                gateway.focusGame();
            }

            @Override public void focusInstallation(String gameId) throws IOException {
                gateway.focusInstallation(gameId);
            }

            @Override public boolean verifyInstallation(String gameId) throws IOException {
                return gateway.verifyInstallation(gameId);
            }

            @Override public void ensureInstalledGameTarget(
                    String gameId, String gameName) throws IOException {
                gateway.ensureInstalledGameTarget(gameId, gameName);
            }
        };
    }

    private static final class ObservationContext {
        long sequence;
        int failures;
        boolean baselineEstablished;
        boolean gameWasRunning;
        boolean fullscreenRequested;
        boolean lockScreenPresented;
        int gameFocusAttempts;
        long lastGameFocusAttempt;
        String lastReadinessReason = "";
        LaunchTransitionState observedState;
        long stateSince;

        ObservationContext(long now) {
            stateSince = now;
        }
    }
}
