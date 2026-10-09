package com.limelight.console;

import com.limelight.console.transition.LaunchTransitionType;
import com.limelight.diagnostics.MoonWakerDiagnostics;
import com.limelight.nvstream.http.NvApp;
import com.limelight.nvstream.http.ComputerDetails;
import com.limelight.session.SessionModel;

import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Routes a user target from one fresh canonical session snapshot. */
final class SessionOrchestrator implements AutoCloseable {
    enum Rejection { INITIALIZING, UNPAIRED, TERMINATING }
    enum CloseResult { CLOSED, REUSED, CANCELLED, NEEDS_CONFIRMATION, PRESENTATION_REQUIRED }

    static final class PreparedWarmUp {
        final HostProfileKey profileKey;
        final NvApp target;
        final String activeGameId;
        final ComputerDetails host;
        final boolean ownsFreshSunshineSession;

        PreparedWarmUp(NvApp target, String activeGameId, ComputerDetails host,
                       boolean ownsFreshSunshineSession) {
            this(target, activeGameId, host,
                    host == null ? null : new HostProfileKey(host.uuid,
                            com.limelight.gateway.GatewayConnection.DEFAULT_PROFILE_ID),
                    ownsFreshSunshineSession);
        }

        PreparedWarmUp(NvApp target, String activeGameId, ComputerDetails host,
                       HostProfileKey profileKey,
                       boolean ownsFreshSunshineSession) {
            this.target = target;
            this.activeGameId = SessionSnapshot.normalize(activeGameId);
            this.host = host;
            this.profileKey = profileKey;
            this.ownsFreshSunshineSession = ownsFreshSunshineSession;
        }
    }

    private static final class Preparation {
        final long request;
        final String hostId;
        final HostProfileKey profileKey;
        PlayIntent pendingGame;
        boolean opaqueReady;
        PreparedWarmUp prepared;
        boolean launchClaimed;

        Preparation(long request, HostProfileKey profileKey) {
            this.request = request;
            this.profileKey = profileKey;
            this.hostId = SessionSnapshot.normalize(profileKey.hostId);
        }
    }

    interface Dispatcher { void post(Runnable action); }
    interface Effects {
        boolean isAvailable(); boolean isPaired(String hostId); SessionSnapshot resolve(String hostId);
        default SessionSnapshot resolve(HostProfileKey key) { return resolve(key.hostId); }
        default boolean canPrepareHost(String hostId) { return isPaired(hostId); }
        void returnToRetainedStream(); void reconnectSavedSession(PlayIntent intent);
        /** A live warm-up attempt carries the intent's game into the resumed neutral stream. */
        boolean warmUpCarriesGame();
        /** Starts the attaching stream presentation carrying the target; ends the request. */
        void attachPresentationForSwitch(long orchestrationId, PlayIntent intent, NvApp target);
        void reject(Rejection reason);
        void confirmReplacement(Runnable accepted);
        boolean canAttemptRetainedSwitch(PlayIntent intent);
        void showLoading(PlayIntent intent, LaunchTransitionType type, Runnable opaqueFrameReady);
        void refreshSession(PlayIntent intent, BooleanSupplier cancelled,
                            Consumer<Boolean> completion);
        HostLaunchPreflight.Result preflight(PlayIntent intent, HostLaunchPreflight.Action action,
                                             long orchestrationId,
                                             BooleanSupplier cancelled);
        CloseResult closePreviousSession(PlayIntent intent, NvApp target,
                                         boolean allowDestructiveClose,
                                         BooleanSupplier cancelled) throws Exception;
        void launch(PlayIntent intent, NvApp target, LaunchTransitionType type,
                    String sourceSuspendId, boolean ownsFreshSunshineSession);
        void preflightFailed(HostLaunchPreflight.Failure failure);
        /** Shows the failure screen for this exact path; the orchestrator recorded it. */
        void sessionFailed(PlayIntent intent, SessionFailure reason);
        PreparedWarmUp prepareHost(PlayIntent intent, long request,
                                   BooleanSupplier cancelled);
        void launchPreparedHost(PreparedWarmUp prepared,
                                PlayIntent pendingGame, long request);
        void preparationFailed();
    }

    private final Effects effects;
    private final Executor executor;
    private final Dispatcher dispatcher;
    private final AtomicLong generation = new AtomicLong();
    private PlayIntent lastIntent;
    /** The request still being decided; 0 once it was handed off, refused or failed. */
    private long inFlight;
    /** The request and pass evaluating the refresh of an unresolved observation. */
    private long refreshedRequest;
    private int refreshedPass;
    private volatile Preparation preparation;
    private volatile boolean closed;

    SessionOrchestrator(Effects effects, Executor executor, Dispatcher dispatcher) {
        this.effects = effects;
        this.executor = executor;
        this.dispatcher = dispatcher;
    }

    static boolean shouldSendWarmUpWake(
            SessionModel.State localState,
            boolean bridgeResponded, ComputerDetails.State moonlightState) {
        return localState != SessionModel.State.HOME_LIVE
                && localState != SessionModel.State.PARKED_LIVE
                && localState != SessionModel.State.PREPARING
                && localState != SessionModel.State.TERMINATING
                && !bridgeResponded
                && moonlightState == ComputerDetails.State.OFFLINE;
    }

    void play(PlayIntent intent) {
        if (handoffPreparingGame(intent)) return;
        lastIntent = intent;
        long request = generation.incrementAndGet();
        if (closed) {
            diagnostic("INFO", "orchestration.ignored", request, intent,
                    "reason", "CLOSED");
            return;
        }
        diagnostic("INFO", "orchestration.started", request, intent);
        if (!effects.isAvailable()) {
            diagnostic("WARN", "orchestration.rejected", request, intent,
                    "reason", Rejection.INITIALIZING.name());
            effects.reject(Rejection.INITIALIZING); return;
        }
        if (!effects.isPaired(intent.hostId)) {
            diagnostic("WARN", "orchestration.rejected", request, intent,
                    "reason", Rejection.UNPAIRED.name());
            effects.reject(Rejection.UNPAIRED); return;
        }
        inFlight = request;
        evaluate(request, intent, false, false, false, null, 0);
    }

    long prepareHost(String hostId) {
        return prepareHost(hostId,
                com.limelight.gateway.GatewayConnection.DEFAULT_PROFILE_ID);
    }

    long prepareHost(String hostId, String profileId) {
        PlayIntent intent = PlayIntent.autoWarmUp(hostId, profileId);
        long request = generation.incrementAndGet();
        preparation = null;
        if (closed) return 0L;
        if (!effects.isAvailable()) {
            return 0L;
        }
        if (!effects.canPrepareHost(intent.hostId)) {
            return 0L;
        }
        Preparation pending = new Preparation(request, intent.profileKey);
        preparation = pending;
        try {
            executor.execute(() -> {
                PreparedWarmUp prepared;
                try {
                    prepared = effects.prepareHost(
                            intent, request, () -> !currentPreparation(pending));
                } catch (RuntimeException error) {
                    dispatcher.post(() -> failPreparation(pending));
                    return;
                }
                dispatcher.post(() -> completePreparation(pending, prepared));
            });
        } catch (RuntimeException error) {
            failPreparation(pending);
            return 0L;
        }
        return request;
    }

    boolean cancelPreparation(String hostId) {
        Preparation pending = preparation;
        if (!currentPreparation(pending) || !pending.hostId.equals(
                SessionSnapshot.normalize(hostId))) return false;
        preparation = null;
        generation.compareAndSet(pending.request, pending.request + 1L);
        return true;
    }

    boolean hasPreparationForHost(String hostId) {
        Preparation pending = preparation;
        return currentPreparation(pending) && pending.hostId.equals(
                SessionSnapshot.normalize(hostId));
    }

    void cancel() {
        long cancelled = generation.getAndIncrement();
        preparation = null;
        // Only a request still being decided is cancelled; one already launched is not.
        if (!closed && inFlight == cancelled && lastIntent != null) {
            diagnostic("INFO", "orchestration.cancelled", cancelled, lastIntent);
        }
        inFlight = 0L;
    }
    void retry() {
        PlayIntent intent = lastIntent;
        if (intent != null && !closed) play(intent);
    }
    @Override public void close() { closed = true; lastIntent = null; cancel(); }

    private boolean handoffPreparingGame(PlayIntent intent) {
        Preparation pending = preparation;
        if (intent.kind != PlayIntent.Kind.PLAYNITE_GAME
                || !currentPreparation(pending) || pending.launchClaimed
                || !pending.profileKey.equals(intent.profileKey)) {
            return false;
        }
        if (pending.pendingGame != null) return true;
        pending.pendingGame = intent;
        lastIntent = intent;
        inFlight = pending.request;
        effects.showLoading(intent, LaunchTransitionType.GAME, () ->
                dispatcher.post(() -> {
                    if (!currentPreparation(pending) || pending.launchClaimed) return;
                    pending.opaqueReady = true;
                    maybeLaunch(pending);
                }));
        return true;
    }

    private void completePreparation(Preparation pending, PreparedWarmUp prepared) {
        if (!currentPreparation(pending)) return;
        if (prepared == null || prepared.target == null
                || prepared.profileKey != null
                && !pending.profileKey.equals(prepared.profileKey)) {
            preparation = null;
            inFlight = 0L;
            if (pending.pendingGame != null) effects.preparationFailed();
            return;
        }
        pending.prepared = prepared;
        maybeLaunch(pending);
    }

    private void maybeLaunch(Preparation pending) {
        if (!currentPreparation(pending) || pending.launchClaimed
                || pending.prepared == null
                || pending.pendingGame != null && !pending.opaqueReady) return;
        pending.launchClaimed = true;
        preparation = null;
        inFlight = 0L;
        effects.launchPreparedHost(
                pending.prepared, pending.pendingGame, pending.request);
    }

    private void failPreparation(Preparation pending) {
        if (!currentPreparation(pending)) return;
        preparation = null;
        inFlight = 0L;
        effects.preparationFailed();
    }

    private boolean currentPreparation(Preparation pending) {
        return pending != null && !closed && preparation == pending
                && generation.get() == pending.request;
    }

    private void evaluate(long request, PlayIntent intent, boolean replacementAuthorized,
                          boolean reuseOnly, boolean opaqueReady,
                          NvApp preparedTarget, int pass) {
        if (!current(request)) return;
        if (pass > 8) {
            fail(request, intent, SessionFailure.ORCHESTRATION_PASS_LIMIT, "");
            return;
        }

        SessionSnapshot snapshot = effects.resolve(intent.profileKey);
        if (!current(request)) return;
        // A game stop beside a live stream keeps only the way back to that stream open.
        if (snapshot.state == SessionSnapshot.State.TERMINATING
                || (snapshot.endingGame && !intent.matches(snapshot))) {
            diagnostic("WARN", "orchestration.rejected", request, intent,
                    "reason", Rejection.TERMINATING.name());
            inFlight = 0L;
            // Under the loading layer a toast would leave the loader up with no error.
            if (opaqueReady) fail(request, intent, SessionFailure.UNCERTAIN_TERMINATING, "");
            else effects.reject(Rejection.TERMINATING);
            return;
        }
        if (snapshot.observationState == SessionSnapshot.ObservationState.RECOVERY_REQUIRED
                && !intent.matches(snapshot)) {
            // Only correlated evidence naming another game refuses, and only under the loading
            // layer. An uncorrelated observation (the hole between two host polls) decides
            // nothing: it is refreshed and the request is evaluated on the fresh one, which
            // refuses when it is the very pass that refresh produced.
            boolean namesOtherGame = snapshot.observationCorrelated
                    && !snapshot.playniteGameId.isEmpty();
            if (namesOtherGame || !snapshot.observationCorrelated) {
                if (!opaqueReady) {
                    awaitOpaque(request, intent, freshType(intent), replacementAuthorized,
                            reuseOnly, preparedTarget, pass);
                } else if (namesOtherGame || evaluatesRefresh(request, pass)) {
                    fail(request, intent, SessionFailure.UNCERTAIN_RECOVERY_REQUIRED, "");
                } else {
                    refreshThenEvaluate(request, intent, replacementAuthorized,
                            reuseOnly, preparedTarget, pass);
                }
                return;
            }
        }
        if (snapshot.state == SessionSnapshot.State.PREPARING
                && intent.kind == PlayIntent.Kind.PLAYNITE_GAME) {
            if (!opaqueReady) {
                awaitOpaque(request, intent, LaunchTransitionType.GAME,
                        replacementAuthorized, reuseOnly, preparedTarget, pass);
            } else if (!reuseOnly && snapshot.playniteGameId.isEmpty()) {
                if (!effects.canAttemptRetainedSwitch(intent)) {
                    fail(request, intent, SessionFailure.UNCERTAIN_PREPARING_NOT_SWITCHABLE, "");
                    return;
                }
                evaluate(request, intent, false, true,
                        true, preparedTarget, pass + 1);
            } else if (!reuseOnly) {
                effects.refreshSession(intent, () -> !current(request), success ->
                        dispatcher.post(() -> {
                            if (!current(request)) return;
                            if (!success) {
                                fail(request, intent,
                                        SessionFailure.UNCERTAIN_PREPARING_REFRESH_FAILED, "");
                                return;
                            }
                            if (!effects.canAttemptRetainedSwitch(intent)) {
                                fail(request, intent, SessionFailure
                                        .UNCERTAIN_PREPARING_NOT_SWITCHABLE_AFTER_REFRESH, "");
                                return;
                            }
                            evaluate(request, intent, false, true,
                                    true, preparedTarget, pass + 1);
                        }));
            } else if (preparedTarget == null) {
                awaitPreflight(request, intent, false, true, pass,
                        HostLaunchPreflight.Action.SWITCH_RETAINED);
            } else {
                closeCompetingSession(request, intent, preparedTarget,
                        false, true, pass);
            }
            return;
        }
        if (snapshot.state == SessionSnapshot.State.UNCERTAIN) {
            if (intent.kind == PlayIntent.Kind.PLAYNITE_GAME) {
                if (!opaqueReady) {
                    awaitOpaque(request, intent, freshType(intent), replacementAuthorized,
                            reuseOnly, preparedTarget, pass);
                } else {
                    awaitFreshSession(request, intent, replacementAuthorized,
                            reuseOnly, preparedTarget, pass);
                }
            } else if (!opaqueReady) {
                awaitOpaque(request, intent, freshType(intent), false,
                        false, preparedTarget, pass);
            } else if (preparedTarget == null) {
                awaitPreflight(request, intent, false, false, pass,
                        HostLaunchPreflight.Action.LAUNCH);
            } else {
                launch(request, intent, preparedTarget, freshType(intent), "", true);
            }
            return;
        }

        boolean matches = intent.matches(snapshot) && !effects.canAttemptRetainedSwitch(intent);
        if (snapshot.state == SessionSnapshot.State.ACTIVE && matches) {
            if (snapshot.retainedTransport) {
                returnToRetained(request, intent);
            } else {
                connect(request, intent, opaqueReady, preparedTarget, snapshot, pass);
            }
            return;
        }
        boolean reconnectingWarmUp = snapshot.state == SessionSnapshot.State.RECONNECT_REQUIRED
                && snapshot.neutralStreamTarget
                && snapshot.playniteGameId.isEmpty()
                && intent.kind == PlayIntent.Kind.PLAYNITE_GAME;
        boolean carriesNoGame = reconnectingWarmUp && !matches && !effects.warmUpCarriesGame();
        // Orphaned neutral stream: none to carry and the host proves no game runs (IDLE).
        // Decided on every pass from this snapshot and never carried forward; it is replaced
        // without asking. An empty game id from a stale or unresolved observation proves
        // nothing: it is refreshed once, then the replacement is confirmed.
        boolean orphaned = carriesNoGame
                && snapshot.observationState == SessionSnapshot.ObservationState.IDLE;
        if (carriesNoGame && !orphaned && !replacementAuthorized) {
            if (!opaqueReady) {
                awaitOpaque(request, intent, freshType(intent), false,
                        reuseOnly, preparedTarget, pass);
                return;
            }
            if (!evaluatesRefresh(request, pass)) {
                refreshThenEvaluate(request, intent, false, reuseOnly, preparedTarget, pass);
                return;
            }
        }
        if (!carriesNoGame && snapshot.state == SessionSnapshot.State.RECONNECT_REQUIRED
                && (matches || reconnectingWarmUp)) {
            if (!opaqueReady) {
                awaitOpaque(request, intent, LaunchTransitionType.GENERIC,
                        replacementAuthorized, reuseOnly, preparedTarget, pass);
            } else if (preparedTarget == null) {
                awaitPreflight(request, intent, replacementAuthorized, reuseOnly, pass,
                        HostLaunchPreflight.Action.RECONNECT);
            } else {
                reconnect(request, intent);
            }
            return;
        }
        if (snapshot.state == SessionSnapshot.State.SUSPENDED && matches) {
            connect(request, intent, opaqueReady, preparedTarget, snapshot, pass);
            return;
        }
        if (snapshot.state == SessionSnapshot.State.NONE && !effects.canAttemptRetainedSwitch(intent)) {
            if (!opaqueReady) {
                awaitOpaque(request, intent, freshType(intent), replacementAuthorized,
                        reuseOnly, preparedTarget, pass);
            } else if (preparedTarget == null) {
                awaitPreflight(request, intent, replacementAuthorized, reuseOnly, pass,
                        HostLaunchPreflight.Action.LAUNCH);
            } else {
                SessionSnapshot latest = effects.resolve(intent.profileKey);
                if (!current(request)) return;
                if (latest.state == SessionSnapshot.State.NONE) {
                    launch(request, intent, preparedTarget, freshType(intent), "",
                            latest.hostGameAppId == 0);
                } else {
                    evaluate(request, intent, replacementAuthorized, reuseOnly,
                            true, preparedTarget, pass + 1);
                }
            }
            return;
        }
        boolean authorized = replacementAuthorized || orphaned;
        reuseOnly |= !authorized && effects.canAttemptRetainedSwitch(intent);
        if (!authorized && !reuseOnly) {
            effects.confirmReplacement(() -> {
                if (current(request)) evaluate(request, intent, true, false,
                        opaqueReady, preparedTarget, pass + 1);
            });
        } else if (!opaqueReady) {
            awaitOpaque(request, intent, freshType(intent), replacementAuthorized,
                    reuseOnly, preparedTarget, pass);
        } else if (preparedTarget == null) {
            awaitPreflight(request, intent, replacementAuthorized, reuseOnly, pass,
                    reuseOnly
                            ? HostLaunchPreflight.Action.SWITCH_RETAINED
                            : HostLaunchPreflight.Action.LAUNCH);
        } else {
            closeCompetingSession(request, intent, preparedTarget,
                    authorized, reuseOnly, pass);
        }
    }

    private void connect(long request, PlayIntent intent, boolean opaqueReady,
                         NvApp preparedTarget, SessionSnapshot snapshot, int pass) {
        if (!opaqueReady) {
            awaitOpaque(request, intent, LaunchTransitionType.GENERIC,
                    false, false, preparedTarget, pass);
        } else if (preparedTarget == null) {
            awaitPreflight(request, intent, false, false, pass,
                    HostLaunchPreflight.Action.LAUNCH);
        } else if (current(request)) {
            launch(request, intent, preparedTarget, LaunchTransitionType.GENERIC,
                    snapshot.explicitSuspension ? snapshot.suspendId : "", false);
        }
    }

    private void awaitOpaque(long request, PlayIntent intent, LaunchTransitionType type,
                             boolean replacementAuthorized, boolean reuseOnly,
                             NvApp preparedTarget, int pass) {
        effects.showLoading(intent, type, () -> {
            if (current(request)) evaluate(request, intent, replacementAuthorized,
                    reuseOnly, true, preparedTarget, pass + 1);
        });
    }

    private void awaitPreflight(long request, PlayIntent intent, boolean replacementAuthorized,
                                boolean reuseOnly, int pass,
                                HostLaunchPreflight.Action action) {
        execute(request, intent, () -> {
            HostLaunchPreflight.Result result = effects.preflight(
                    intent, action, request, () -> !current(request));
            diagnostic(result.status == HostLaunchPreflight.Status.FAILED ? "WARN" : "INFO",
                    "orchestration.preflight_completed", request, intent,
                    "status", result.status.name(), "operation", action.name(),
                    "stage", result.failure == null ? "" : result.failure.stage.name(),
                    "reason", result.failure == null ? result.status.name()
                            : result.failure.reason.name());
            dispatcher.post(() -> {
                if (!current(request)) return;
                if (result.status == HostLaunchPreflight.Status.FAILED) {
                    inFlight = 0L;
                    effects.preflightFailed(result.failure);
                }
                else if (result.status == HostLaunchPreflight.Status.READY)
                    evaluate(request, intent, replacementAuthorized, reuseOnly,
                            true, result.target, pass + 1);
            });
        });
    }

    /** Whether this pass evaluates the snapshot a refresh of an unresolved observation produced. */
    private boolean evaluatesRefresh(long request, int pass) {
        return refreshedRequest == request && refreshedPass == pass;
    }

    private void refreshThenEvaluate(long request, PlayIntent intent,
                                     boolean replacementAuthorized, boolean reuseOnly,
                                     NvApp preparedTarget, int pass) {
        refreshedRequest = request;
        refreshedPass = pass + 1;
        awaitFreshSession(request, intent, replacementAuthorized, reuseOnly, preparedTarget, pass);
    }

    private void awaitFreshSession(long request, PlayIntent intent,
                                   boolean replacementAuthorized, boolean reuseOnly,
                                   NvApp preparedTarget, int pass) {
        effects.refreshSession(intent, () -> !current(request), success ->
                dispatcher.post(() -> {
                    if (!current(request)) return;
                    if (!success) {
                        fail(request, intent, SessionFailure.UNCERTAIN_REFRESH_FAILED, "");
                        return;
                    }
                    if (effects.resolve(intent.profileKey).state
                            == SessionSnapshot.State.UNCERTAIN) {
                        fail(request, intent, SessionFailure.UNCERTAIN_AFTER_REFRESH, "");
                        return;
                    }
                    evaluate(request, intent, replacementAuthorized, reuseOnly,
                            true, preparedTarget, pass + 1);
                }));
    }

    private void closeCompetingSession(long request, PlayIntent intent, NvApp preparedTarget,
                                       boolean replacementAuthorized, boolean reuseOnly,
                                       int pass) {
        SessionSnapshot beforeClose = effects.resolve(intent.profileKey);
        if (!current(request)) return;
        if ((beforeClose.state == SessionSnapshot.State.NONE || intent.matches(beforeClose))
                && !effects.canAttemptRetainedSwitch(intent)) {
            evaluate(request, intent, replacementAuthorized, reuseOnly,
                    true, preparedTarget, pass + 1);
            return;
        }
        execute(request, intent, () -> {
            CloseResult closeResult;
            SessionFailure.Raised closeFailure = null;
            diagnostic("INFO", "orchestration.close_started", request, intent);
            try { closeResult = effects.closePreviousSession(
                    intent, preparedTarget, replacementAuthorized,
                    () -> !current(request)); }
            catch (Exception error) {
                closeResult = null;
                closeFailure = error instanceof SessionFailure.Raised
                        ? (SessionFailure.Raised) error
                        : SessionFailure.CLOSE_UNEXPECTED_ERROR.raise();
                diagnostic("ERROR", "orchestration.close_failed", request, intent,
                        "error_type", error.getClass().getName());
            }
            if (closeResult != null) diagnostic("INFO",
                    "orchestration.close_completed", request, intent,
                    "status", closeResult.name());
            CloseResult result = closeResult;
            SessionFailure.Raised failure = closeFailure;
            dispatcher.post(() -> {
                if (!current(request)) return;
                if (result == null) { fail(request, intent, failure.reason, failure.detail); return; }
                if (result == CloseResult.CANCELLED) return;
                if (result == CloseResult.NEEDS_CONFIRMATION) {
                    effects.confirmReplacement(() -> {
                        if (current(request)) evaluate(request, intent, true,
                                false, true, preparedTarget, pass + 1);
                    });
                    return;
                }
                if (result == CloseResult.REUSED) {
                    returnToRetained(request, intent);
                    return;
                }
                if (result == CloseResult.PRESENTATION_REQUIRED) {
                    diagnostic("INFO", "orchestration.presentation_required", request, intent);
                    inFlight = 0L;
                    effects.attachPresentationForSwitch(request, intent, preparedTarget);
                    return;
                }
                SessionSnapshot afterClose = effects.resolve(intent.profileKey);
                if (!current(request)) return;
                // The verified close is authoritative. UNCERTAIN here only means that no
                // host observation newer than the close has been accepted yet; every
                // local fact already shows nothing left to close.
                if (afterClose.state == SessionSnapshot.State.NONE
                        || afterClose.state == SessionSnapshot.State.UNCERTAIN) {
                    launch(request, intent, preparedTarget, freshType(intent), "",
                            afterClose.hostGameAppId == 0);
                } else if (intent.matches(afterClose)) {
                    evaluate(request, intent, true, false,
                            true, preparedTarget, pass + 1);
                } else fail(request, intent, SessionFailure.CLOSE_SNAPSHOT_NOT_IDLE, "");
            });
        });
    }

    private void execute(long request, PlayIntent intent, Runnable action) {
        try { executor.execute(() -> { if (current(request)) action.run(); }); }
        catch (RuntimeException error) {
            diagnostic("ERROR", "orchestration.execution_failed", request, intent,
                    "error_type", error.getClass().getName());
            if (current(request)) {
                fail(request, intent, SessionFailure.ORCHESTRATION_EXECUTOR_REJECTED, "");
            }
        }
    }

    /** Records the failure with the snapshot at this moment, then shows its screen. */
    private void fail(long request, PlayIntent intent, SessionFailure reason, String detail) {
        inFlight = 0L;
        reason.record(request, intent.hostId, intent.profileId,
                effects.resolve(intent.profileKey), detail);
        effects.sessionFailed(intent, reason);
    }

    private void returnToRetained(long request, PlayIntent intent) {
        diagnostic("INFO", "orchestration.return_retained", request, intent);
        inFlight = 0L;
        effects.returnToRetainedStream();
    }

    private void reconnect(long request, PlayIntent intent) {
        diagnostic("INFO", "orchestration.reconnect", request, intent);
        inFlight = 0L;
        effects.reconnectSavedSession(intent);
    }

    private void launch(long request, PlayIntent intent, NvApp target,
                        LaunchTransitionType type, String sourceSuspendId,
                        boolean ownsFreshSunshineSession) {
        diagnostic("INFO", "orchestration.launch", request, intent,
                "operation", type.name(), "app_id", target.getAppId());
        inFlight = 0L;
        effects.launch(intent, target, type, sourceSuspendId, ownsFreshSunshineSession);
    }

    private static void diagnostic(String level, String event, long request,
                                   PlayIntent intent, Object... fields) {
        if (intent == null) return;
        Object[] values = new Object[fields.length + 12];
        Object[] base = {"orchestration_id", request, "host_id", intent.hostId,
                "profile_id", intent.profileId, "game_id", intent.playniteGameId,
                "app_id", intent.sunshineAppId,
                "kind", intent.kind.name()};
        System.arraycopy(base, 0, values, 0, base.length);
        System.arraycopy(fields, 0, values, base.length, fields.length);
        MoonWakerDiagnostics.record(level, "android.session-orchestrator", event, values);
    }

    private boolean current(long request) { return !closed && generation.get() == request; }

    private static LaunchTransitionType freshType(PlayIntent intent) {
        switch (intent.kind) {
            case PLAYNITE_GAME: return LaunchTransitionType.GAME;
            case PLAYNITE_FULLSCREEN: return LaunchTransitionType.PLAYNITE;
            default: return LaunchTransitionType.GENERIC;
        }
    }
}
