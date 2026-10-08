package com.limelight.console;

import com.limelight.LimeLog;
import com.limelight.console.transition.LaunchTransitionController;
import com.limelight.console.transition.LaunchTransitionSnapshot;
import com.limelight.console.transition.LaunchTransitionSpec;
import com.limelight.console.transition.LaunchTransitionState;
import com.limelight.console.transition.LaunchTransitionType;
import com.limelight.session.SessionModel;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.BooleanSupplier;

public final class ConsoleStreamTransitionCoordinator implements AutoCloseable {
    public enum ProviderStartFailure { INTERACTION_REQUIRED, CLEANUP_PENDING }
    private static final long STREAM_SETUP_TIMEOUT_MS = 60_000L;
    /** Soft notice only: the host owns game start failure (phase 1) and has no phase-2 bound. */
    static final long START_TAKING_LONGER_MS = 180_000L;
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
        default void stopGame(String gameId, String processToken) throws IOException { }
        /** One Gateway session ensure; true only when the host reports the session ready. */
        default boolean unlockHostSession(BooleanSupplier cancelled)
                throws IOException, InterruptedException {
            return false;
        }
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
        String gameIdentityPendingMessage();
        String gameWindowPendingMessage();
        String gameExitedDuringStartMessage();
        String gameWindowNotFullscreenMessage();
        String gameWindowNotForegroundMessage();
        String hostScreensaverActiveMessage();
        String launcherInteractionRequiredMessage();
        String windowStabilizingMessage();
        String gameStartTimeoutMessage();
        String previousGameRunningMessage();
        String previousGameStopFailedMessage();

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
        default boolean controlTransportAuthorized() { return true; }

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
    /** The local Play of this exact request; the soft start threshold counts from it. */
    private final long requestStartedAtMillis;
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
    private boolean providerCleanupSucceeded;
    private boolean providerCleanupArmed;
    private boolean streamConnected;
    private boolean hostUnlockAttempted;
    /** The stale earlier launch's game the host reported running instead of this one. */
    private String previousGameId = "";
    private String previousProcessToken = "";
    private String previousGameName = "";
    private boolean previousGameStopFailed;
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
        this.requestStartedAtMillis = launchStartedAtMillis > 0L
                ? launchStartedAtMillis : clock.now();
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
            observation.cancel(false);
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

    /**
     * Activity recreation with a surviving transport: stop observing, keep the exact provider
     * owner (STARTING or STARTED, cleanup not claimed) without a holder for the next presentation.
     */
    public void detachForRecreation() {
        synchronized (PROVIDER_OWNERS_LOCK) {
            ProviderOwner owner = PROVIDER_OWNERS.get(providerOwnerKey());
            if (owner != null && owner.matches(transitionSpec) && owner.holder == this) {
                owner.holder = null;
                owner.custodian = this;
            }
        }
        // Stops observation and releases this presentation's observation pool; the owner entry
        // (and this coordinator as its custodian) stays for the in-flight start or cleanup.
        close();
        logTimeline("provider-detached-for-recreation");
    }

    /** The recreated presentation adopts only the exact holderless owner of its spec. */
    public boolean adoptRecreatedProviderOwnership() {
        if (transitionSpec.playniteGameId.isEmpty()) return true;
        boolean pendingAdopted;
        synchronized (PROVIDER_OWNERS_LOCK) {
            ProviderOwner owner = PROVIDER_OWNERS.get(providerOwnerKey());
            if (owner == null || !owner.matches(transitionSpec) || owner.holder != null
                    || owner.cleanupClaimed || owner.cleanupAfterStart) return false;
            owner.holder = this;
            owner.custodian = this;
            pendingAdopted = owner.pending;
        }
        synchronized (this) {
            // A pending (queued, never invoked) start is started by this adopter exactly once.
            providerStartInvoked = !pendingAdopted;
        }
        logTimeline("provider-adopted-after-recreation");
        return true;
    }

    /**
     * The retained transport ended with no presentation holding this host's provider owner:
     * stop the exact started process, or defer until an in-flight start returns.
     */
    public static void endDetachedOwner(String hostId) {
        ConsoleStreamTransitionCoordinator custodian;
        synchronized (PROVIDER_OWNERS_LOCK) {
            ProviderOwner owner = PROVIDER_OWNERS.get(normalizeOwnerPart(hostId));
            if (owner == null || owner.holder != null || owner.custodian == null
                    || owner.cleanupClaimed) return;
            // No presentation remains to observe: cleanup may bind the exact process from one
            // authenticated snapshot of this attempt.
            owner.bindOnCleanup = true;
            if (owner.pending) {
                PROVIDER_OWNERS.remove(normalizeOwnerPart(hostId));
                return;
            }
            if (owner.starting) {
                owner.cleanupAfterStart = true;
                return;
            }
            custodian = owner.custodian;
        }
        custodian.scheduleDetachedCompensation(true);
    }

    private void scheduleDetachedCompensation(boolean bindFromSnapshot) {
        synchronized (this) {
            // A revealed (committed) launch is never stopped because its transport ended.
            if (!providerCleanupArmed) return;
        }
        try {
            providerExecutor.execute(() -> compensateProviderStart(bindFromSnapshot));
        } catch (RuntimeException error) {
            LimeLog.warning("Launch timeline transition=" + transitionSpec.id
                    + " provider-stop-dispatch-failed: " + error.getMessage());
        }
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
                if (!owner.matches(transitionSpec) || owner.starting || owner.pending) return false;
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
                normalizeOwnerPart(replacement.transitionSpec.playniteGameId))
                || !transitionSpec.profileId.equals(replacement.transitionSpec.profileId)) {
            return transitionSpec.playniteGameId.isEmpty();
        }
        synchronized (PROVIDER_OWNERS_LOCK) {
            String key = providerOwnerKey();
            ProviderOwner owner = PROVIDER_OWNERS.get(key);
            if (owner != null && !owner.matches(transitionSpec)) return false;
            if (owner != null && (owner.cleanupClaimed || owner.starting || owner.pending)) {
                return false;
            }
            if (owner == null && startsProviderGame()) return false;
            PROVIDER_OWNERS.put(key, owner == null
                    ? new ProviderOwner(replacement.transitionSpec)
                    : owner.transferTo(replacement.transitionSpec));
        }
        stop();
        return true;
    }

    public static final class ProviderProcessIdentity {
        public final String processToken;
        public final int processId;

        private ProviderProcessIdentity(String processToken, int processId) {
            this.processToken = processToken;
            this.processId = processId;
        }
    }

    /** Returns the exact, unclaimed process owned by this provider observation. */
    public ProviderProcessIdentity providerProcessIdentityForTransfer() {
        synchronized (PROVIDER_OWNERS_LOCK) {
            ProviderOwner owner = PROVIDER_OWNERS.get(providerOwnerKey());
            if (owner == null || !owner.matches(transitionSpec) || owner.cleanupClaimed
                    || owner.starting || owner.pending
                    || owner.processToken.isEmpty() || owner.processId <= 0) return null;
            return new ProviderProcessIdentity(owner.processToken, owner.processId);
        }
    }

    /** Binds an authenticated snapshot only when it identifies this exact owner. */
    public ProviderProcessIdentity providerProcessIdentityForTransfer(
            PlayniteTransitionGateway.Snapshot snapshot) {
        if (snapshot == null) return null;
        captureCurrentAttemptProcess(snapshot);
        return providerProcessIdentityForTransfer();
    }

    /** Transfers a cross-actor owner only with one exact post-reauthorization proof. */
    public boolean transferProviderOwnershipTo(
            ConsoleStreamTransitionCoordinator replacement,
            SessionModel.ProviderTransferAuthorization authorization, SessionModel model) {
        if (replacement == null || authorization == null
                || transitionSpec.playniteGameId.isEmpty()
                || !providerOwnerKey().equals(replacement.providerOwnerKey())
                || !transitionSpec.id.equals(authorization.previousTransitionId)
                || !replacement.transitionSpec.id.equals(authorization.replacementTransitionId)
                || !normalizeOwnerPart(transitionSpec.hostId).equals(
                normalizeOwnerPart(authorization.hostId))
                || transitionSpec.sunshineAppId != authorization.appId
                || replacement.transitionSpec.sunshineAppId != authorization.appId
                || !normalizeOwnerPart(transitionSpec.playniteGameId).equals(
                normalizeOwnerPart(authorization.playniteGameId))
                || !normalizeOwnerPart(replacement.transitionSpec.playniteGameId).equals(
                normalizeOwnerPart(authorization.playniteGameId))
                || !transitionSpec.profileId.equals(authorization.previousProfileId)
                || !replacement.transitionSpec.profileId.equals(authorization.profileId)) {
            return false;
        }
        synchronized (PROVIDER_OWNERS_LOCK) {
            String key = providerOwnerKey();
            ProviderOwner owner = PROVIDER_OWNERS.get(key);
            if (owner == null || !owner.matches(transitionSpec) || owner.cleanupClaimed
                    || !owner.processToken.equals(authorization.processToken)
                    || owner.processId != authorization.processId
                    || !model.consumeProviderTransferAuthorization(
                    authorization)) return false;
            PROVIDER_OWNERS.put(key, owner.transferTo(replacement.transitionSpec));
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
        executor.shutdown();
    }

    public boolean isInstallationConfirmationStream() {
        return transitionSpec.type == LaunchTransitionType.GENERIC
                && !transitionSpec.playniteGameId.isEmpty();
    }

    public boolean startsProviderGame() {
        return transitionSpec.type == LaunchTransitionType.GAME
                && !transitionSpec.playniteGameId.isEmpty();
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
            if (!streamConnected && (!transitionSpec.startProviderBeforeStream
                    || !callbacks.controlTransportAuthorized())) return;
            providerStartRequested = true;
        }
        // Reserve the exact attempt before queueing, so a recreated presentation can adopt a
        // start that was queued but never invoked.
        if (!reservePendingStart(actionEpoch)) return;
        try {
            providerExecutor.execute(() -> runProviderGameStart(actionEpoch));
        } catch (RuntimeException error) {
            synchronized (this) {
                providerStartRequested = false;
            }
            releaseOwnPendingStart();
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
        OwnerState ownerState = ownerState();
        if (ownerState == OwnerState.STARTED) {
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
        if (ownerState == OwnerState.STARTING) {
            // Join the one in-flight start; its result is delivered to the current holder.
            if (!holdStartingOwner()) return;
            synchronized (this) {
                if (!isCurrent(actionEpoch) || providerCleanupRequested) return;
                providerStartInvoked = true;
            }
            logTimeline("provider-start-joined");
            return;
        }
        if (ownerState == OwnerState.PENDING ? !claimPendingStart(actionEpoch)
                : !reserveProviderStart(actionEpoch)) return;
        logTimeline("provider-start-requested");
        Exception startError = null;
        try {
            gateway.startGame(transitionSpec.playniteGameId);
        } catch (IOException | RuntimeException error) {
            startError = error;
        }
        logTimeline(startError == null ? "provider-start-complete"
                : "provider-start-failed reason=" + timelineReason(startError));
        completeReservedStart(transitionSpec, startError);
    }

    /** Runs on the coordinator that holds the reservation when the one start returned. */
    private void onReservedStartResult(Exception startError) {
        long actionEpoch = currentEpoch();
        if (startError == null) {
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
        // Mark cleanup before releasing a pending reservation, so a concurrent reservation
        // either observes the flag or is released here.
        synchronized (this) {
            providerCleanupRequested = true;
        }
        releaseOwnPendingStart();
        synchronized (this) {
            if (!providerCleanupArmed) {
                providerCleanupRequested = true;
                stop();
                completeProviderCleanup(true);
                return;
            }
        }
        if (deferCleanupUntilStartCompletes()) {
            synchronized (this) {
                providerCleanupRequested = true;
                providerCleanupScheduled = true;
            }
            stop();
            logTimeline("provider-stop-deferred-until-start");
            return;
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
            providerExecutor.execute(() -> compensateProviderStart(false));
        } catch (RuntimeException error) {
            LimeLog.warning("Launch timeline transition=" + transitionSpec.id
                    + " provider-stop-dispatch-failed: " + error.getMessage());
            completeProviderCleanup(false);
        }
    }

    private void compensateProviderStart(boolean bindFromSnapshot) {
        // An attached attempt stops only an identity bound by its own observation. A holderless
        // owner (runtime ended, no presentation) may bind it from one authenticated snapshot.
        // Without an exact identity the cleanup is reported unconfirmed, never as success.
        // A host reporting this request's own start failed without a process leaves nothing
        // to stop: the cleanup completes without a stop.
        String ownedProcessToken = claimCurrentAttemptProcessToken();
        PlayniteTransitionGateway.Snapshot observed = null;
        long observedAt = 0L;
        if (ownedProcessToken.isEmpty() && gateway != null) {
            try {
                observed = gateway.snapshot();
                observedAt = clock.now();
            } catch (IOException | RuntimeException unavailable) {
                // Stays unconfirmed below.
            }
            if (bindFromSnapshot) {
                captureCurrentAttemptProcess(observed);
                ownedProcessToken = claimCurrentAttemptProcessToken();
            }
        }
        if (ownedProcessToken.isEmpty()) {
            boolean nothingToStop = nothingToStop(observed, observedAt);
            if (nothingToStop) clearCurrentAttemptOwner();
            logTimeline(nothingToStop ? "provider-cleanup-nothing-to-stop"
                    : "provider-stop-unconfirmed");
            completeProviderCleanup(nothingToStop);
            return;
        }
        logTimeline("provider-stop-requested");
        try {
            PlayniteTransitionGateway.Snapshot current = gateway.snapshot();
            String exactGame = PlayniteIdentityResolutionPolicy.verifiedStopTarget(
                    transitionSpec.playniteGameId, current.gameState, current.gameId);
            if (exactGame == null || exactGame.isEmpty()
                    || !ownedProcessToken.equals(current.processToken)) {
                throw new IOException("provider_process_not_exact");
            }
            gateway.stopGame(exactGame, ownedProcessToken);
            clearCurrentAttemptOwner();
            logTimeline("provider-stop-complete");
            completeProviderCleanup(true);
        } catch (IOException | RuntimeException error) {
            releaseCurrentAttemptCleanupClaim();
            LimeLog.warning("Launch timeline transition=" + transitionSpec.id
                    + " provider-stop-failed: " + error.getMessage());
            completeProviderCleanup(false);
        }
    }

    /**
     * The host reports this request's game failed with no process: its "failed" state names this
     * exact game and began after this request started. An unnamed game or a failure carried
     * over from an earlier attempt is no evidence.
     */
    private boolean nothingToStop(PlayniteTransitionGateway.Snapshot snapshot, long observedAt) {
        return snapshot != null && snapshot.gatewayReady
                && "failed".equalsIgnoreCase(snapshot.gameState)
                && snapshot.processId == 0
                && snapshot.hasVerifiedGameFor(transitionSpec.playniteGameId)
                && stateBeganForThisRequest(snapshot, observedAt);
    }

    /**
     * The host's current state episode began after this request's local Play. The episode age
     * is measured on the host clock alone, so wall-clock skew between the devices does not
     * matter; transport latency can only make an older episode look up to that much newer.
     */
    private boolean stateBeganForThisRequest(PlayniteTransitionGateway.Snapshot snapshot,
                                             long observedAt) {
        return snapshot.stateAgeMs >= 0L
                && observedAt - snapshot.stateAgeMs >= requestStartedAtMillis;
    }

    /** The stale earlier launch's game name, when the host reported one. */
    public synchronized String previousGameName() {
        return previousGameName;
    }

    /**
     * Stops the stale earlier launch's game by its verified process token; this attempt's own
     * start then continues under the same observation.
     */
    public void stopPreviousGame() {
        String gameId;
        String processToken;
        synchronized (this) {
            gameId = previousGameId;
            processToken = previousProcessToken;
        }
        long actionEpoch = currentEpoch();
        if (gameId.isEmpty() || processToken.isEmpty() || gateway == null || actionEpoch < 0L) {
            return;
        }
        try {
            providerExecutor.execute(() -> {
                boolean stopped;
                try {
                    gateway.stopGame(gameId, processToken);
                    stopped = true;
                } catch (IOException | RuntimeException error) {
                    LimeLog.warning("Launch timeline transition=" + transitionSpec.id
                            + " previous-game-stop-failed: " + error.getMessage());
                    stopped = false;
                }
                logTimeline(stopped ? "previous-game-stopped" : "previous-game-stop-failed");
                if (!isCurrent(actionEpoch)) return;
                synchronized (this) {
                    if (!processToken.equals(previousProcessToken)) return;
                    previousGameStopFailed = !stopped;
                    if (stopped) {
                        previousGameId = "";
                        previousProcessToken = "";
                        previousGameName = "";
                    }
                }
                if (stopped) {
                    transitionController.targetStarting(transitionSpec.id, transitionSpec.hostId,
                            LaunchTransitionType.GAME, transitionSpec.playniteGameId);
                } else {
                    transitionController.previousGameRunning(transitionSpec.id,
                            transitionSpec.hostId, callbacks.previousGameStopFailedMessage());
                }
            });
        } catch (RuntimeException error) {
            LimeLog.warning("Launch timeline transition=" + transitionSpec.id
                    + " previous-game-stop-dispatch-failed: " + error.getMessage());
        }
    }

    /**
     * The result of this attempt's provider cleanup once it completed (true: no started process
     * remains or its exact process was stopped), null while none has completed. A cancel after a
     * host-reported failure reads it, because that cleanup already ran.
     */
    public synchronized Boolean providerCleanupResult() {
        return providerCleanupCompleted ? providerCleanupSucceeded : null;
    }

    private void completeProviderCleanup(boolean success) {
        synchronized (this) {
            if (providerCleanupCompleted) return;
            providerCleanupCompleted = true;
            providerCleanupSucceeded = success;
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
                if (snapshot.gatewayReady) captureCurrentAttemptProcess(snapshot);
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
                    // Re-read the host after a confirmed unlock; otherwise the manual gate.
                    if (tryHostUnlock(runEpoch)) continue;
                    if (!isCurrent(runEpoch)) return;
                    if (!context.lockScreenPresented) {
                        context.lockScreenPresented = true;
                        applySnapshot(runEpoch, snapshot);
                    }
                } else {
                    context.lockScreenPresented = false;
                    applySnapshot(runEpoch, snapshot);
                }
                if (!isCurrent(runEpoch)) return;

                if (snapshot.gatewayReady && !context.fullscreenRequested
                        && transitionSpec.type == LaunchTransitionType.PLAYNITE
                        && !snapshot.windowReady
                        && !"host_session_locked".equals(readinessReason)) {
                    gateway.showFullscreen();
                    if (!isCurrent(runEpoch)) return;
                    context.fullscreenRequested = true;
                }

                long now = clock.now();
                // Behind a screen saver the focus request of a reconnect is what makes the
                // Bridge close the saver; a start is covered by the Bridge's own launch.
                // Waking the saver has its own budget so it cannot use up the real focus attempts.
                boolean focusRequired = "target_not_foreground".equals(readinessReason);
                boolean wakeRequired = "host_screensaver_active".equals(readinessReason)
                        && "running".equalsIgnoreCase(snapshot.gameState);
                if (snapshot.gatewayReady && "game".equalsIgnoreCase(snapshot.targetKind)
                        && !snapshot.windowReady
                        && ((focusRequired && context.gameFocusAttempts < 3)
                            || (wakeRequired && context.screensaverWakeAttempts < 3))
                        && now - context.lastGameFocusAttempt >= 3_000L) {
                    context.lastGameFocusAttempt = now;
                    if (focusRequired) {
                        context.gameFocusAttempts++;
                    } else {
                        context.screensaverWakeAttempts++;
                    }
                    try {
                        gateway.focusGame();
                    } catch (IOException ignored) {
                        // Readiness stays closed; a later bounded attempt may succeed.
                    }
                    if (!isCurrent(runEpoch)) return;
                }

                if (snapshot.gatewayReady) {
                    if ("running".equalsIgnoreCase(snapshot.gameState)
                            && snapshot.processId > 0) {
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
                if (transitionSpec.type == LaunchTransitionType.GAME
                        && LaunchTransitionController.isGameStartPending(current.state)
                        && clock.now() - requestStartedAtMillis >= START_TAKING_LONGER_MS
                        && isCurrent(runEpoch)) {
                    transitionController.startTakingLonger(transitionSpec.id);
                }
                if (current.state != context.observedState) {
                    context.observedState = current.state;
                    context.stateSince = clock.now();
                }
                long timeout = timeoutFor(current.state);
                if (timeout > 0L && clock.now() - context.stateSince >= timeout
                        && !"host_session_locked".equals(context.lastReadinessReason)
                        && isCurrent(runEpoch)) {
                    transitionController.timedOut(transitionSpec.id,
                            readinessFailureMessage(context.lastReadinessReason),
                            prefersReveal(context.lastReadinessReason));
                    return;
                }
            } catch (IOException | RuntimeException error) {
                context.failures++;
                if (context.failures >= 3 && isCurrent(runEpoch)) {
                    transitionController.timedOut(
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
        String gameId = SessionSnapshot.normalize(snapshot.gameId);
        if ("host_session_locked".equals(snapshot.reason)) {
            transitionController.hostSessionLocked(transitionSpec.id, transitionSpec.hostId,
                    kind, gameId, callbacks.hostSessionLockedMessage());
            return;
        }
        if (!snapshot.connectorReady) return;
        String previousLaunch = SessionSnapshot.normalize(snapshot.previousLaunchGameId);
        if (kind == LaunchTransitionType.GAME
                && "previous_launch_game_running".equals(snapshot.reason)
                && !previousLaunch.isEmpty()
                && !previousLaunch.equals(SessionSnapshot.normalize(transitionSpec.playniteGameId))
                && !snapshot.previousLaunchProcessToken.isEmpty()) {
            boolean stopFailed;
            synchronized (this) {
                if (!snapshot.previousLaunchProcessToken.equals(previousProcessToken)) {
                    previousGameStopFailed = false;
                }
                previousGameId = snapshot.previousLaunchGameId.trim();
                previousProcessToken = snapshot.previousLaunchProcessToken;
                previousGameName = snapshot.previousLaunchName;
                stopFailed = previousGameStopFailed;
            }
            transitionController.previousGameRunning(transitionSpec.id, transitionSpec.hostId,
                    stopFailed ? callbacks.previousGameStopFailedMessage()
                            : callbacks.previousGameRunningMessage());
            return;
        }
        // A launcher prompt may precede any game identity: the reason alone is the evidence.
        if (kind == LaunchTransitionType.GAME
                && "launcher_interaction_required".equals(snapshot.reason)
                && (gameId.isEmpty()
                || snapshot.hasVerifiedGameFor(transitionSpec.playniteGameId))) {
            transitionController.launcherInteractionRequired(
                    transitionSpec.id, transitionSpec.hostId, transitionSpec.playniteGameId,
                    callbacks.launcherInteractionRequiredMessage());
            return;
        }
        boolean exactVerifiedGame = kind != LaunchTransitionType.GAME
                || snapshot.hasVerifiedGameFor(transitionSpec.playniteGameId);
        if (!exactVerifiedGame) return;
        boolean currentRequestState = !startsProviderGame()
                || stateBeganForThisRequest(snapshot, clock.now());
        if (kind == LaunchTransitionType.GAME && currentRequestState
                && "failed".equalsIgnoreCase(snapshot.gameState)) {
            requestProviderCleanup();
            transitionController.error(transitionSpec.id,
                    readinessFailureMessage(snapshot.reason), prefersReveal(snapshot.reason));
            return;
        }
        boolean targetWasRunning = transitionController.snapshot().state
                == LaunchTransitionState.GAME_RUNNING;
        boolean gameReportedRunning = kind == LaunchTransitionType.GAME
                && "running".equalsIgnoreCase(snapshot.gameState);
        // Phase 2 starts once the host has the game's process: a verified token, or the provider
        // already running this exact game (its window probe correlated the process, while the
        // tokenizing process scan may lag it by seconds). Readiness still needs both verified.
        // A provider start counts only a host state that began after this request: an older
        // "running" (an earlier game or attempt) keeps phase 1 ("Starting the game").
        if (currentRequestState
                && (snapshot.hasVerifiedProcessFor(gameId) || gameReportedRunning)) {
            transitionController.targetProcessRunning(
                    transitionSpec.id, transitionSpec.hostId, kind, gameId,
                    snapshot.windowReady ? "" : readinessPendingMessage(snapshot.reason));
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
        } else if (currentRequestState && (snapshot.stableSamples > 0
                || "stabilizing_target_window".equals(snapshot.reason))) {
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
        if ("game_exited_during_start".equals(reason)) {
            return callbacks.gameExitedDuringStartMessage();
        }
        if ("game_start_timeout".equals(reason)) {
            return callbacks.gameStartTimeoutMessage();
        }
        String pending = readinessPendingMessage(reason);
        if (!pending.isEmpty()) return pending;
        return callbacks.readinessUnconfirmedMessage();
    }

    private String windowLostMessage(String reason) {
        if ("host_session_locked".equals(reason)
                || "stream_display_not_configured".equals(reason)
                || "target_on_wrong_display".equals(reason)) {
            return readinessFailureMessage(reason);
        }
        String pending = readinessPendingMessage(reason);
        if (!pending.isEmpty()) return pending;
        return callbacks.windowStabilizingMessage();
    }

    private String readinessPendingMessage(String reason) {
        if ("waiting_for_game_identity".equals(reason)) {
            return callbacks.gameIdentityPendingMessage();
        }
        if ("waiting_for_game_window".equals(reason)) {
            return callbacks.gameWindowPendingMessage();
        }
        if ("target_not_fullscreen".equals(reason)) {
            return callbacks.gameWindowNotFullscreenMessage();
        }
        if ("target_not_foreground".equals(reason)) {
            return callbacks.gameWindowNotForegroundMessage();
        }
        // Not a lock: the Bridge is waking the screensaver itself, and the broker would
        // answer "ready", so the one tryHostUnlock attempt is kept for a real lock.
        if ("host_screensaver_active".equals(reason)) {
            return callbacks.hostScreensaverActiveMessage();
        }
        return "";
    }

    /** A launcher waiting for input or a window off the streamed display: reveal is the remedy. */
    static boolean prefersReveal(String reason) {
        return "game_start_timeout".equals(reason)
                || "target_on_wrong_display".equals(reason)
                || "launcher_interaction_required".equals(reason);
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
            // GAME_START_REQUESTED, GAME_STARTING, GAME_PROCESS_RUNNING and
            // GAME_WINDOW_STABILIZING have no client bound: the host fails phase 1 and does not
            // bound phase 2, so the client shows a soft notice instead (START_TAKING_LONGER_MS).
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

    /** PENDING: reserved, external start not yet invoked. STARTING: invoked, in flight. */
    enum OwnerState { NONE, PENDING, STARTING, STARTED }

    OwnerState ownerState() {
        synchronized (PROVIDER_OWNERS_LOCK) {
            ProviderOwner owner = PROVIDER_OWNERS.get(providerOwnerKey());
            if (owner == null || !owner.matches(transitionSpec)) return OwnerState.NONE;
            if (owner.pending) return OwnerState.PENDING;
            return owner.starting ? OwnerState.STARTING : OwnerState.STARTED;
        }
    }

    /**
     * Reserves a free slot as PENDING. Another attempt's entry is never replaced here: its
     * cleanup must run first, so the worker decides at run time exactly as before.
     */
    boolean reservePendingStart(long actionEpoch) {
        synchronized (PROVIDER_OWNERS_LOCK) {
            synchronized (this) {
                // Atomic with the reservation: a cleanup that already won leaves no entry.
                if (!isCurrent(actionEpoch) || providerCleanupRequested) return false;
                ProviderOwner current = PROVIDER_OWNERS.get(providerOwnerKey());
                if (current != null) return true;
                ProviderOwner reserved = new ProviderOwner(transitionSpec);
                reserved.pending = true;
                reserved.holder = this;
                reserved.custodian = this;
                PROVIDER_OWNERS.put(providerOwnerKey(), reserved);
                return true;
            }
        }
    }

    synchronized long currentEpochForTests() {
        return currentEpoch();
    }

    /** Claims the one external start of a pending reservation this coordinator holds. */
    private boolean claimPendingStart(long actionEpoch) {
        synchronized (PROVIDER_OWNERS_LOCK) {
            synchronized (this) {
                if (!isCurrent(actionEpoch) || providerCleanupRequested) return false;
                ProviderOwner owner = PROVIDER_OWNERS.get(providerOwnerKey());
                if (owner == null || !owner.matches(transitionSpec) || !owner.pending
                        || owner.holder != this || owner.cleanupClaimed) return false;
                owner.pending = false;
                owner.starting = true;
                providerStartInvoked = true;
                return true;
            }
        }
    }

    /** A never-invoked reservation needs no stop; it is simply released. */
    private void releaseOwnPendingStart() {
        synchronized (PROVIDER_OWNERS_LOCK) {
            String key = providerOwnerKey();
            ProviderOwner owner = PROVIDER_OWNERS.get(key);
            if (owner != null && owner.matches(transitionSpec) && owner.pending
                    && (owner.holder == this || owner.custodian == this)) {
                PROVIDER_OWNERS.remove(key);
            }
        }
    }

    private boolean isOwnedByCurrentAttempt() {
        return ownerState() == OwnerState.STARTED;
    }

    private boolean holdStartingOwner() {
        synchronized (PROVIDER_OWNERS_LOCK) {
            ProviderOwner owner = PROVIDER_OWNERS.get(providerOwnerKey());
            if (owner == null || !owner.matches(transitionSpec) || !owner.starting
                    || (owner.holder != null && owner.holder != this)) return false;
            owner.holder = this;
            owner.custodian = this;
            return true;
        }
    }

    /** Reserves the exact owner in STARTING before the one external start call. */
    private boolean reserveProviderStart(long actionEpoch) {
        synchronized (PROVIDER_OWNERS_LOCK) {
            synchronized (this) {
                if (!isCurrent(actionEpoch) || providerCleanupRequested) return false;
                ProviderOwner current = PROVIDER_OWNERS.get(providerOwnerKey());
                if (current != null && (current.cleanupClaimed || current.starting
                        || current.pending)) return false;
                ProviderOwner reserved = new ProviderOwner(transitionSpec);
                reserved.starting = true;
                reserved.holder = this;
                reserved.custodian = this;
                PROVIDER_OWNERS.put(providerOwnerKey(), reserved);
                providerStartInvoked = true;
                return true;
            }
        }
    }

    /** Settles the reservation by spec identity and delivers the result to its holder once. */
    private static void completeReservedStart(LaunchTransitionSpec spec, Exception startError) {
        ConsoleStreamTransitionCoordinator holder;
        ConsoleStreamTransitionCoordinator compensate = null;
        boolean bindFromSnapshot = false;
        synchronized (PROVIDER_OWNERS_LOCK) {
            String key = normalizeOwnerPart(spec.hostId);
            ProviderOwner owner = PROVIDER_OWNERS.get(key);
            if (owner == null || !owner.matches(spec) || !owner.starting) return;
            holder = owner.holder;
            bindFromSnapshot = owner.bindOnCleanup;
            if (startError == null) {
                owner.starting = false;
                if (owner.cleanupAfterStart) {
                    owner.cleanupAfterStart = false;
                    compensate = owner.custodian;
                    holder = null;
                }
            } else {
                PROVIDER_OWNERS.remove(key);
                if (owner.cleanupAfterStart) {
                    compensate = owner.custodian;
                    holder = null;
                }
            }
        }
        if (compensate != null) {
            if (startError == null) compensate.scheduleDetachedCompensation(bindFromSnapshot);
            else compensate.completeProviderCleanup(true);
        }
        if (holder != null) holder.onReservedStartResult(startError);
    }

    /** True when our own in-flight start must finish before its exact process can be stopped. */
    private boolean deferCleanupUntilStartCompletes() {
        synchronized (PROVIDER_OWNERS_LOCK) {
            ProviderOwner owner = PROVIDER_OWNERS.get(providerOwnerKey());
            if (owner == null || !owner.matches(transitionSpec) || !owner.starting
                    || (owner.holder != this && owner.custodian != this)) return false;
            owner.cleanupAfterStart = true;
            owner.holder = null;
            owner.custodian = this;
            return true;
        }
    }

    private void captureCurrentAttemptProcess(PlayniteTransitionGateway.Snapshot snapshot) {
        if (snapshot == null || !snapshot.hasVerifiedProcessFor(
                transitionSpec.playniteGameId)) return;
        String exactGame = PlayniteIdentityResolutionPolicy.verifiedStopTarget(
                transitionSpec.playniteGameId, snapshot.gameState, snapshot.gameId);
        if (exactGame == null || exactGame.isEmpty() || snapshot.processId <= 0
                || !snapshot.processToken.matches("[0-9a-f]{64}")) return;
        synchronized (PROVIDER_OWNERS_LOCK) {
            ProviderOwner owner = PROVIDER_OWNERS.get(providerOwnerKey());
            if (owner != null && owner.matches(transitionSpec)) {
                owner.bindProcessIdentity(snapshot.processToken, snapshot.processId);
            }
        }
    }

    private String claimCurrentAttemptProcessToken() {
        synchronized (PROVIDER_OWNERS_LOCK) {
            ProviderOwner owner = PROVIDER_OWNERS.get(providerOwnerKey());
            if (owner == null || !owner.matches(transitionSpec)
                    || owner.cleanupClaimed || owner.processToken.isEmpty()) return "";
            owner.cleanupClaimed = true;
            return owner.processToken;
        }
    }

    private void releaseCurrentAttemptCleanupClaim() {
        synchronized (PROVIDER_OWNERS_LOCK) {
            ProviderOwner owner = PROVIDER_OWNERS.get(providerOwnerKey());
            if (owner != null && owner.matches(transitionSpec)) {
                owner.cleanupClaimed = false;
            }
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

            @Override public void stopGame(String gameId, String processToken)
                    throws IOException {
                gateway.stopGame(gameId, processToken);
            }

            @Override public boolean unlockHostSession(BooleanSupplier cancelled)
                    throws IOException, InterruptedException {
                return gateway.unlockHostSession(cancelled);
            }
        };
    }

    private void providerStartFailed(Exception error) {
        String reason = error.getMessage() == null ? "" : error.getMessage();
        if (reason.contains("host_session_locked")) {
            transitionController.gatewayConnected(transitionSpec.id, transitionSpec.hostId);
            transitionController.hostSessionLocked(
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

    /**
     * The one host unlock of this transition: a locked Windows session is first handed to the
     * Gateway's session ensure (which unlocks through the login broker). Only when that is
     * refused, fails or times out does the caller fall back to the manual privacy gate. A result
     * that arrives after this transition was stopped or replaced is ignored.
     */
    private boolean tryHostUnlock(long runEpoch) {
        synchronized (this) {
            if (hostUnlockAttempted || !isCurrent(runEpoch)) return false;
            hostUnlockAttempted = true;
        }
        logTimeline("host-unlock-requested");
        boolean ready = false;
        try {
            // A stopped or replaced transition ends the poll and cancels the attempt, so the
            // broker does not unlock Windows for a launch the user already left.
            ready = gateway.unlockHostSession(() -> !isCurrent(runEpoch));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (IOException | RuntimeException error) {
            logTimeline("host-unlock-error reason=" + timelineReason(error));
        }
        logTimeline(ready ? "host-unlock-ready" : "host-unlock-not-ready");
        return ready && isCurrent(runEpoch);
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
        int screensaverWakeAttempts;
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
        final String profileId;
        String processToken;
        int processId;
        boolean cleanupClaimed;
        /** Reserved before queueing; the external start has not been invoked. */
        boolean pending;
        /** The one external start call is in flight; not yet a started provider. */
        boolean starting;
        boolean cleanupAfterStart;
        boolean bindOnCleanup;
        /** Coordinator receiving the start result; null while detached for recreation. */
        ConsoleStreamTransitionCoordinator holder;
        /** Last holder, used to stop the exact process if no presentation adopts it. */
        ConsoleStreamTransitionCoordinator custodian;

        ProviderOwner(LaunchTransitionSpec spec) {
            this(spec, "", 0);
        }

        ProviderOwner(LaunchTransitionSpec spec, String processToken, int processId) {
            transitionId = spec.id;
            gameId = normalizeOwnerPart(spec.playniteGameId);
            profileId = spec.profileId;
            this.processToken = processToken == null ? "" : processToken;
            this.processId = Math.max(0, processId);
        }

        boolean matches(LaunchTransitionSpec spec) {
            return transitionId.equals(spec.id)
                    && gameId.equals(normalizeOwnerPart(spec.playniteGameId))
                    && profileId.equals(spec.profileId);
        }

        void bindProcessIdentity(String observedProcessToken, int observedProcessId) {
            if (processToken.isEmpty()) {
                processToken = observedProcessToken;
                processId = Math.max(0, observedProcessId);
            }
        }

        ProviderOwner transferTo(LaunchTransitionSpec replacement) {
            return new ProviderOwner(replacement, processToken, processId);
        }
    }
}
