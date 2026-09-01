package com.limelight.console;

import com.limelight.LimeLog;
import com.limelight.console.transition.LaunchTransitionController;
import com.limelight.console.transition.LaunchTransitionSnapshot;
import com.limelight.console.transition.LaunchTransitionSpec;
import com.limelight.console.transition.LaunchTransitionState;
import com.limelight.console.transition.LaunchTransitionType;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

public final class ConsoleStreamTransitionCoordinator implements AutoCloseable {
    public enum ProviderStartFailure { INTERACTION_REQUIRED, CLEANUP_PENDING }
    private static final long STREAM_SETUP_TIMEOUT_MS = 60_000L;
    private static final ExecutorService PROVIDER_ACTIONS =
            Executors.newSingleThreadExecutor(action -> {
                Thread thread = new Thread(action, "MoonWaker-ProviderLaunch");
                thread.setDaemon(true);
                return thread;
            });
    private static final Object PROVIDER_OWNERS_LOCK = new Object();
    private static final Map<String, ProviderOwner> PROVIDER_OWNERS = new HashMap<>();

    interface Gateway {
        PlayniteTransitionGateway.Snapshot snapshot() throws IOException;
        PlayniteTransitionGateway.Events awaitEvents(long after, String transitionId)
                throws IOException;
        void showFullscreen() throws IOException;
        void focusGame() throws IOException;
        void focusInstallation(String gameId) throws IOException;
        boolean verifyInstallation(String gameId) throws IOException;
        void ensureInstalledGameTarget(String gameId, String gameName) throws IOException;
        default void startGame(String gameId) throws IOException { }
        default void stopGame(String gameId) throws IOException { }
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
        String targetWrongDisplayMessage();
        String readinessUnconfirmedMessage();
        String launcherInteractionRequiredMessage();
        String windowStabilizingMessage();

        boolean isPendingInstallation(String hostId, String gameId);
        String pendingInstallationName(String hostId, String gameId);
        void onInstallationCompleted(String hostId, String gameId, String gameName);
        void onInstallationCancelled(String hostId, String gameId, String gameName);
        void onInstallationFailed(String hostId, String gameId, String gameName);
        void onInstallationAttentionRequired(String hostId, String gameId, String gameName);

        default void onProviderGameStopped(String transitionId, String gameId) { }
        default void onHostGuidePolicy(String transitionId, String gameId, boolean allowed) { }
        default void onProviderGameStartAccepted(String transitionId, String gameId) { }
        default void onProviderGameStartFailed(String transitionId, String gameId,
                                               ProviderStartFailure failure) { }
        default void onProviderGameCleanupComplete(
                String transitionId, String gameId, boolean success) { }

        void onInstallationVerified();
        void onInstallationStillNeedsConfirmation();
        void onInstallationVerificationFailed();
    }

    private final LaunchTransitionSpec transitionSpec;
    private final LaunchTransitionController transitionController;
    private final Gateway gateway;
    private final ExecutorService executor;
    private final Executor providerExecutor;
    private final MonotonicClock clock;
    private final long launchStartedAtMillis;
    private final Sleeper sleeper;
    private final Scheduler scheduler;
    private final Callbacks callbacks;

    private Future<?> observation;
    private boolean stopped = true;
    private boolean closed;
    private boolean providerStartRequested;
    private boolean providerStartInvoked;
    private boolean providerCleanupRequested;
    private boolean providerCleanupScheduled;
    private boolean providerCleanupCompleted;
    private boolean providerCleanupArmed;
    private boolean streamConnected;
    private long epoch;

    public ConsoleStreamTransitionCoordinator(
            LaunchTransitionSpec transitionSpec,
            LaunchTransitionController transitionController,
            PlayniteTransitionGateway gateway,
            Scheduler scheduler,
            Callbacks callbacks) {
        this(transitionSpec, transitionController, gateway, scheduler, callbacks, 0L);
    }

    public ConsoleStreamTransitionCoordinator(
            LaunchTransitionSpec transitionSpec,
            LaunchTransitionController transitionController,
            PlayniteTransitionGateway gateway,
            Scheduler scheduler,
            Callbacks callbacks,
            long launchStartedAtMillis) {
        this(transitionSpec, transitionController, adapt(gateway),
                Executors.newFixedThreadPool(2),
                PROVIDER_ACTIONS,
                android.os.SystemClock::uptimeMillis,
                launchStartedAtMillis, Thread::sleep, scheduler, callbacks);
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
        this(transitionSpec, transitionController, gateway, executor, executor,
                clock, 0L, sleeper, scheduler, callbacks);
    }

    ConsoleStreamTransitionCoordinator(
            LaunchTransitionSpec transitionSpec,
            LaunchTransitionController transitionController,
            Gateway gateway,
            ExecutorService executor,
            Executor providerExecutor,
            MonotonicClock clock,
            long launchStartedAtMillis,
            Sleeper sleeper,
            Scheduler scheduler,
            Callbacks callbacks) {
        this.transitionSpec = transitionSpec;
        this.transitionController = transitionController;
        this.gateway = gateway;
        this.executor = executor;
        this.providerExecutor = providerExecutor;
        this.clock = clock;
        this.launchStartedAtMillis = launchStartedAtMillis;
        this.sleeper = sleeper;
        this.scheduler = scheduler;
        this.callbacks = callbacks;
        this.providerCleanupArmed = startsProviderGame();
    }

    public synchronized void start() {
        if (closed || !stopped || providerCleanupRequested) return;
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
        if (startsProviderGame()) {
            scheduler.postDelayed(() -> onStreamSetupTimeout(runEpoch),
                    STREAM_SETUP_TIMEOUT_MS);
            requestProviderGameStart(runEpoch);
            return;
        }
        startObservation(runEpoch);
    }

    private void startObservation(long runEpoch) {
        synchronized (this) {
            if (!isCurrent(runEpoch) || observation != null) return;
        }
        try {
            Future<?> submitted = executor.submit(() -> observe(runEpoch));
            synchronized (this) {
                if (isCurrent(runEpoch)) observation = submitted;
                else submitted.cancel(true);
            }
        } catch (RuntimeException error) {
            if (isCurrent(runEpoch)) {
                requestProviderCleanup();
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

    public synchronized void cancel() {
        requestProviderCleanup();
    }

    /** Commits a revealed provider launch; later observer failures cannot stop it. */
    public synchronized void commitProviderLaunch() {
        providerCleanupArmed = false;
    }

    /** Stops observation and releases only this exact provider attempt. */
    public boolean detachForSwitch() {
        synchronized (PROVIDER_OWNERS_LOCK) {
            String key = providerOwnerKey();
            ProviderOwner owner = PROVIDER_OWNERS.get(key);
            if (owner == null) {
                if (transitionSpec.type != LaunchTransitionType.GAME_CONNECTION) {
                    return false;
                }
            } else {
                if (!owner.matches(transitionSpec)) return false;
                PROVIDER_OWNERS.remove(key);
            }
        }
        stop();
        return true;
    }

    /** Stops observation after an exact game stop, including an already-cleared owner. */
    public boolean detachAfterConfirmedGameStop() {
        synchronized (PROVIDER_OWNERS_LOCK) {
            String key = providerOwnerKey();
            ProviderOwner owner = PROVIDER_OWNERS.get(key);
            if (owner != null) {
                if (!owner.matches(transitionSpec)) return false;
                PROVIDER_OWNERS.remove(key);
            }
        }
        stop();
        return true;
    }

    /** Adopts a detached owner for an observation-only replacement attempt. */
    public boolean adoptDetachedProviderOwnership() {
        if (transitionSpec.playniteGameId.isEmpty()) return true;
        synchronized (PROVIDER_OWNERS_LOCK) {
            String key = providerOwnerKey();
            if (PROVIDER_OWNERS.containsKey(key)) return false;
            PROVIDER_OWNERS.put(key, new ProviderOwner(transitionSpec));
        }
        return true;
    }

    /** Transfers exact observation ownership without starting or stopping a game. */
    public boolean transferProviderOwnershipTo(
            ConsoleStreamTransitionCoordinator replacement) {
        if (replacement == null
                || transitionSpec.playniteGameId.isEmpty()
                || !providerOwnerKey().equals(replacement.providerOwnerKey())
                || !normalizeOwnerPart(transitionSpec.playniteGameId).equals(
                normalizeOwnerPart(replacement.transitionSpec.playniteGameId))) {
            return transitionSpec.playniteGameId.isEmpty();
        }
        synchronized (PROVIDER_OWNERS_LOCK) {
            String key = providerOwnerKey();
            ProviderOwner owner = PROVIDER_OWNERS.get(key);
            if (owner != null && !owner.matches(transitionSpec)) return false;
            if (owner == null && startsProviderGame()) return false;
            PROVIDER_OWNERS.put(key, new ProviderOwner(replacement.transitionSpec));
        }
        stop();
        return true;
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

    public boolean startsProviderGame() {
        return transitionSpec.type == LaunchTransitionType.GAME
                && isProviderRecordId(transitionSpec.playniteGameId);
    }

    public void onStreamConnected() {
        synchronized (this) {
            if (!closed && !stopped) streamConnected = true;
        }
        if (startsProviderGame()) {
            requestProviderGameStart(currentEpoch());
            return;
        }
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

    public void onStreamFailed() {
        requestProviderCleanup();
    }

    private void requestProviderGameStart(long actionEpoch) {
        synchronized (this) {
            if (!startsProviderGame() || gateway == null || providerStartRequested
                    || providerCleanupRequested || !isCurrent(actionEpoch)) return;
            // Providers that enumerate pads only at game startup wait until the
            // stream has announced controllers. The provider declares exceptions.
            if (!streamConnected && !transitionSpec.startProviderBeforeStream) return;
            providerStartRequested = true;
        }
        try {
            providerExecutor.execute(() -> runProviderGameStart(actionEpoch));
        } catch (RuntimeException error) {
            synchronized (this) {
                providerStartRequested = false;
            }
            if (isCurrent(actionEpoch)) {
                requestProviderCleanup();
                transitionController.error(
                        transitionSpec.id, callbacks.readinessUnconfirmedMessage());
            }
        }
    }

    private void runProviderGameStart(long actionEpoch) {
        synchronized (this) {
            if (!isCurrent(actionEpoch) || providerCleanupRequested) return;
        }
        if (isOwnedByCurrentAttempt()) {
            synchronized (this) {
                if (!isCurrent(actionEpoch) || providerCleanupRequested) return;
                providerStartInvoked = true;
            }
            logTimeline("provider-start-reused");
            callbacks.onProviderGameStartAccepted(
                    transitionSpec.id, transitionSpec.playniteGameId);
            startObservation(actionEpoch);
            return;
        }
        synchronized (this) {
            if (!isCurrent(actionEpoch) || providerCleanupRequested) return;
            providerStartInvoked = true;
        }
        logTimeline("provider-start-requested");
        Exception startError = null;
        try {
            gateway.startGame(transitionSpec.playniteGameId);
        } catch (IOException | RuntimeException error) {
            startError = error;
        }
        logTimeline(startError == null ? "provider-start-complete"
                : "provider-start-failed reason=" + timelineReason(startError));
        if (startError == null) {
            if (!acceptProviderStart(actionEpoch)) return;
            callbacks.onProviderGameStartAccepted(
                    transitionSpec.id, transitionSpec.playniteGameId);
            if (isCurrent(actionEpoch)) {
                startObservation(actionEpoch);
            }
            return;
        }
        if (!isCurrent(actionEpoch)) return;
        if (isLauncherInteractionRequired(startError) || isHostSessionLocked(startError)) {
            callbacks.onProviderGameStartFailed(transitionSpec.id,
                    transitionSpec.playniteGameId,
                    ProviderStartFailure.INTERACTION_REQUIRED);
            synchronized (this) {
                providerStartInvoked = false;
            }
            providerStartFailed(startError);
        } else {
            callbacks.onProviderGameStartFailed(transitionSpec.id,
                    transitionSpec.playniteGameId,
                    ProviderStartFailure.CLEANUP_PENDING);
            requestProviderCleanup();
            providerStartFailed(startError);
        }
    }

    private void requestProviderCleanup() {
        synchronized (this) {
            if (!providerCleanupArmed) {
                providerCleanupRequested = true;
                stop();
                completeProviderCleanup(true);
                return;
            }
        }
        boolean scheduleCleanup;
        boolean ownsProvider = isOwnedByCurrentAttempt();
        synchronized (this) {
            providerCleanupRequested = true;
            if (ownsProvider) providerStartInvoked = true;
        }
        stop();
        synchronized (this) {
            if (providerCleanupCompleted || providerCleanupScheduled) return;
            scheduleCleanup = providerStartInvoked;
            if (scheduleCleanup) providerCleanupScheduled = true;
        }
        if (!scheduleCleanup) {
            completeProviderCleanup(true);
            return;
        }
        try {
            providerExecutor.execute(this::compensateProviderStart);
        } catch (RuntimeException error) {
            LimeLog.warning("Launch timeline transition=" + transitionSpec.id
                    + " provider-stop-dispatch-failed: " + error.getMessage());
            completeProviderCleanup(false);
        }
    }

    private void compensateProviderStart() {
        if (!mayStopCurrentAttempt()) {
            logTimeline("provider-stop-skipped-newer-owner");
            completeProviderCleanup(false);
            return;
        }
        logTimeline("provider-stop-requested");
        try {
            gateway.stopGame(transitionSpec.playniteGameId);
            clearCurrentAttemptOwner();
            logTimeline("provider-stop-complete");
            completeProviderCleanup(true);
        } catch (IOException | RuntimeException error) {
            LimeLog.warning("Launch timeline transition=" + transitionSpec.id
                    + " provider-stop-failed: " + error.getMessage());
            completeProviderCleanup(false);
        }
    }

    private void completeProviderCleanup(boolean success) {
        synchronized (this) {
            if (providerCleanupCompleted) return;
            providerCleanupCompleted = true;
        }
        callbacks.onProviderGameCleanupComplete(
                transitionSpec.id, transitionSpec.playniteGameId, success);
    }

    private void onStreamSetupTimeout(long capturedEpoch) {
        synchronized (this) {
            if (!isCurrent(capturedEpoch) || streamConnected) return;
        }
        requestProviderCleanup();
        transitionController.timedOut(
                transitionSpec.id, callbacks.readinessUnconfirmedMessage());
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
                String readinessReason = snapshot.reason == null ? "" : snapshot.reason;
                if (snapshot.windowReady && !context.targetWindowReadyLogged) {
                    context.targetWindowReadyLogged = true;
                    logTimeline("target-window-ready");
                }
                if (!readinessReason.equals(context.loggedReadinessReason)) {
                    context.loggedReadinessReason = readinessReason;
                    logTimeline("snapshot state=" + snapshot.gameState
                            + " target=" + snapshot.targetKind
                            + " process=" + snapshot.processId
                            + " window=" + snapshot.windowReady
                            + " reason=" + readinessReason);
                }
                context.lastReadinessReason = readinessReason;
                if ("host_session_locked".equals(readinessReason)) {
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
                        && !"host_session_locked".equals(readinessReason)) {
                    gateway.showFullscreen();
                    if (!isCurrent(runEpoch)) return;
                    context.fullscreenRequested = true;
                }

                long now = clock.now();
                if ("game".equalsIgnoreCase(snapshot.targetKind)
                        && !snapshot.windowReady
                        && "target_not_foreground".equals(readinessReason)
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
                        && !context.gameStopNotified
                        && "idle".equalsIgnoreCase(snapshot.gameState)) {
                    transitionController.gameStopping(transitionSpec.id,
                            transitionSpec.hostId, transitionSpec.playniteGameId);
                    if (!isCurrent(runEpoch)) return;
                    context.gameWasRunning = false;
                    context.gameStopNotified = true;
                    clearCurrentAttemptOwner();
                    callbacks.onProviderGameStopped(
                            transitionSpec.id, transitionSpec.playniteGameId);
                }

                PlayniteTransitionGateway.Events events =
                        gateway.awaitEvents(context.sequence, transitionSpec.id);
                if (!isCurrent(runEpoch)) return;
                context.sequence = events.latestSequence;
                for (PlayniteTransitionGateway.Event event : events.values) {
                    if (context.baselineEstablished || isPendingInstallationEvent(event)) {
                        applyEvent(runEpoch, context, event);
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
                    requestProviderCleanup();
                    transitionController.timedOut(transitionSpec.id,
                            readinessFailureMessage(context.lastReadinessReason));
                    return;
                }
            } catch (IOException | RuntimeException error) {
                context.failures++;
                if (context.failures >= 3 && isCurrent(runEpoch)) {
                    requestProviderCleanup();
                    transitionController.error(
                            transitionSpec.id, callbacks.gatewayUnavailableMessage());
                    return;
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
        if (!snapshot.gatewayReady || !isCurrent(runEpoch)) return;
        // Queue the host decision before readiness can reopen input on the UI thread.
        callbacks.onHostGuidePolicy(transitionSpec.id, snapshot.gameId, snapshot.hostGuideAllowed);
        transitionController.gatewayConnected(transitionSpec.id, transitionSpec.hostId);
        LaunchTransitionType kind = "game".equalsIgnoreCase(snapshot.targetKind)
                ? LaunchTransitionType.GAME : LaunchTransitionType.PLAYNITE;
        String gameId = snapshot.gameId == null || snapshot.gameId.isEmpty()
                ? transitionSpec.playniteGameId : snapshot.gameId;
        if ("host_session_locked".equals(snapshot.reason)) {
            transitionController.targetWindowLost(transitionSpec.id, transitionSpec.hostId,
                    kind, gameId, callbacks.hostSessionLockedMessage());
            return;
        }
        if (!snapshot.connectorReady) return;
        if (kind == LaunchTransitionType.GAME
                && "launcher_interaction_required".equals(snapshot.reason)) {
            transitionController.launcherInteractionRequired(
                    transitionSpec.id, transitionSpec.hostId, gameId,
                    callbacks.launcherInteractionRequiredMessage());
            return;
        }
        if (kind == LaunchTransitionType.GAME
                && "failed".equalsIgnoreCase(snapshot.gameState)) {
            requestProviderCleanup();
            transitionController.error(transitionSpec.id,
                    readinessFailureMessage(snapshot.reason));
            return;
        }
        boolean targetWasRunning = transitionController.snapshot().state
                == LaunchTransitionState.GAME_RUNNING;
        if (snapshot.processId > 0) {
            transitionController.targetProcessRunning(
                    transitionSpec.id, transitionSpec.hostId, kind, gameId);
        }
        if (!snapshot.windowReady && isPrivacyGateFailure(snapshot.reason)) {
            transitionController.targetWindowLost(transitionSpec.id, transitionSpec.hostId,
                    kind, gameId, readinessFailureMessage(snapshot.reason));
            return;
        }
        if (snapshot.windowReady) {
            transitionController.targetWindowReady(
                    transitionSpec.id, transitionSpec.hostId, kind, gameId);
        } else if (kind == LaunchTransitionType.GAME && targetWasRunning) {
            transitionController.targetWindowLost(transitionSpec.id, transitionSpec.hostId,
                    kind, gameId, windowLostMessage(snapshot.reason));
        } else if (snapshot.stableSamples > 0
                || "stabilizing_target_window".equals(snapshot.reason)) {
            transitionController.targetWindowStabilizing(transitionSpec.id,
                    transitionSpec.hostId, kind, gameId,
                    callbacks.windowStabilizingMessage());
        }
    }

    private void applyEvent(long runEpoch, ObservationContext context,
                            PlayniteTransitionGateway.Event event) {
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
            context.gameWasRunning = true;
            transitionController.targetProcessRunning(transitionSpec.id, transitionSpec.hostId,
                    LaunchTransitionType.GAME, event.gameId);
        } else if ("game-stopping".equals(name)) {
            if (context.gameWasRunning) {
                transitionController.gameStopping(
                        transitionSpec.id, transitionSpec.hostId, event.gameId);
            }
        } else if ("game-stopped".equals(name)) {
            if (context.gameWasRunning) {
                transitionController.gameStopping(
                        transitionSpec.id, transitionSpec.hostId, event.gameId);
                context.gameWasRunning = false;
            }
            if (transitionSpec.playniteGameId.equals(event.gameId)
                    && !context.gameStopNotified && isCurrent(runEpoch)) {
                context.gameStopNotified = true;
                clearCurrentAttemptOwner();
                callbacks.onProviderGameStopped(
                        transitionSpec.id, transitionSpec.playniteGameId);
            }
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
        if ("target_on_wrong_display".equals(reason)) {
            return callbacks.targetWrongDisplayMessage();
        }
        return callbacks.readinessUnconfirmedMessage();
    }

    private String windowLostMessage(String reason) {
        if ("host_session_locked".equals(reason)
                || "stream_display_not_configured".equals(reason)
                || "target_on_wrong_display".equals(reason)) {
            return readinessFailureMessage(reason);
        }
        return callbacks.windowStabilizingMessage();
    }

    private static boolean isPrivacyGateFailure(String reason) {
        return "stream_display_not_configured".equals(reason)
                || "target_on_wrong_display".equals(reason);
    }

    static long timeoutFor(LaunchTransitionState state) {
        switch (state) {
            case PREPARING_SESSION:
                return 90_000L;
            case CONNECTING_STREAM:
                return 60_000L;
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

    private boolean isOwnedByCurrentAttempt() {
        synchronized (PROVIDER_OWNERS_LOCK) {
            ProviderOwner owner = PROVIDER_OWNERS.get(providerOwnerKey());
            return owner != null && owner.matches(transitionSpec);
        }
    }

    private boolean acceptProviderStart(long actionEpoch) {
        synchronized (PROVIDER_OWNERS_LOCK) {
            synchronized (this) {
                if (!isCurrent(actionEpoch) || providerCleanupRequested) return false;
                PROVIDER_OWNERS.put(providerOwnerKey(), new ProviderOwner(transitionSpec));
                return true;
            }
        }
    }

    private boolean mayStopCurrentAttempt() {
        synchronized (PROVIDER_OWNERS_LOCK) {
            ProviderOwner owner = PROVIDER_OWNERS.get(providerOwnerKey());
            return owner == null || owner.matches(transitionSpec);
        }
    }

    private void clearCurrentAttemptOwner() {
        synchronized (PROVIDER_OWNERS_LOCK) {
            String key = providerOwnerKey();
            ProviderOwner owner = PROVIDER_OWNERS.get(key);
            if (owner != null && owner.matches(transitionSpec)) {
                PROVIDER_OWNERS.remove(key);
            }
        }
    }

    static void resetProviderOwnershipForTests() {
        synchronized (PROVIDER_OWNERS_LOCK) {
            PROVIDER_OWNERS.clear();
        }
    }

    private String providerOwnerKey() {
        return normalizeOwnerPart(transitionSpec.hostId);
    }

    private static String normalizeOwnerPart(String value) {
        return value == null ? "" : value.trim().toLowerCase(java.util.Locale.ROOT);
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

            @Override public void startGame(String gameId) throws IOException {
                gateway.startGame(gameId);
            }

            @Override public void stopGame(String gameId) throws IOException {
                gateway.stopGame(gameId);
            }
        };
    }

    private static boolean isProviderRecordId(String value) {
        return value != null && value.matches(
                "(?i)[a-z][a-z0-9_-]{1,31}:[A-Za-z0-9._-]{1,128}");
    }

    private void providerStartFailed(Exception error) {
        String reason = error.getMessage() == null ? "" : error.getMessage();
        if (reason.contains("host_session_locked")) {
            transitionController.gatewayConnected(transitionSpec.id, transitionSpec.hostId);
            transitionController.targetWindowLost(
                    transitionSpec.id, transitionSpec.hostId,
                    LaunchTransitionType.GAME, transitionSpec.playniteGameId,
                    callbacks.hostSessionLockedMessage());
        } else if (reason.contains("launcher_interaction_required")) {
            transitionController.launcherInteractionRequired(
                    transitionSpec.id, transitionSpec.hostId,
                    transitionSpec.playniteGameId,
                    callbacks.launcherInteractionRequiredMessage());
        } else {
            transitionController.error(
                    transitionSpec.id, callbacks.readinessUnconfirmedMessage());
        }
    }

    private static boolean isLauncherInteractionRequired(Exception error) {
        return error.getMessage() != null
                && error.getMessage().contains("launcher_interaction_required");
    }

    private static boolean isHostSessionLocked(Exception error) {
        return error.getMessage() != null
                && error.getMessage().contains("host_session_locked");
    }

    private void logTimeline(String milestone) {
        long elapsed = launchStartedAtMillis <= 0L
                ? 0L : Math.max(0L, clock.now() - launchStartedAtMillis);
        LimeLog.info("Launch timeline epoch=" + launchStartedAtMillis
                + " transition=" + transitionSpec.id
                + " host=" + transitionSpec.hostId
                + " game=" + transitionSpec.playniteGameId
                + " +" + elapsed + "ms " + milestone);
    }

    private static String timelineReason(Exception error) {
        String value = error == null || error.getMessage() == null
                ? "unknown" : error.getMessage().replaceAll("\\s+", " ").trim();
        return value.length() <= 200 ? value : value.substring(0, 200);
    }

    private static final class ObservationContext {
        long sequence;
        int failures;
        boolean baselineEstablished;
        boolean gameWasRunning;
        boolean gameStopNotified;
        boolean fullscreenRequested;
        boolean lockScreenPresented;
        boolean targetWindowReadyLogged;
        int gameFocusAttempts;
        long lastGameFocusAttempt;
        String lastReadinessReason = "";
        String loggedReadinessReason = "";
        LaunchTransitionState observedState;
        long stateSince;

        ObservationContext(long now) {
            stateSince = now;
        }
    }

    private static final class ProviderOwner {
        final String transitionId;
        final String gameId;

        ProviderOwner(LaunchTransitionSpec spec) {
            transitionId = spec.id;
            gameId = normalizeOwnerPart(spec.playniteGameId);
        }

        boolean matches(LaunchTransitionSpec spec) {
            return transitionId.equals(spec.id)
                    && gameId.equals(normalizeOwnerPart(spec.playniteGameId));
        }
    }
}
