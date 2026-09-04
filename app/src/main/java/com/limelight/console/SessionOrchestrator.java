package com.limelight.console;

import com.limelight.console.transition.LaunchTransitionType;
import com.limelight.diagnostics.MoonWakerDiagnostics;
import com.limelight.nvstream.http.NvApp;
import com.limelight.nvstream.http.ComputerDetails;
import com.limelight.stream.RetainedStreamSessionCoordinator;

import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Routes a user target from one fresh canonical session snapshot. */
final class SessionOrchestrator implements AutoCloseable {
    enum Rejection { INITIALIZING, UNPAIRED, TERMINATING }
    enum CloseResult { CLOSED, REUSED, CANCELLED, NEEDS_CONFIRMATION }

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
        void returnToRetainedStream(); void reconnectSavedSession();
        default void reconnectSavedSession(PlayIntent intent) { reconnectSavedSession(); }
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
        void preflightFailed(HostLaunchPreflight.Failure failure); void orchestrationFailed();
        void uncertainSessionFailed();
        void previousSessionCloseFailed(String reason);
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
    private volatile Preparation preparation;
    private volatile boolean closed;

    SessionOrchestrator(Effects effects, Executor executor, Dispatcher dispatcher) {
        this.effects = effects;
        this.executor = executor;
        this.dispatcher = dispatcher;
    }

    static boolean shouldSendWarmUpWake(
            RetainedStreamSessionCoordinator.State localState,
            boolean bridgeResponded, ComputerDetails.State moonlightState) {
        return localState != RetainedStreamSessionCoordinator.State.HOME_LIVE
                && localState != RetainedStreamSessionCoordinator.State.PARKED_LIVE
                && localState != RetainedStreamSessionCoordinator.State.PREPARING
                && localState != RetainedStreamSessionCoordinator.State.TERMINATING
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
        if (!closed && lastIntent != null) {
            diagnostic("INFO", "orchestration.cancelled", cancelled, lastIntent);
        }
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
        effects.launchPreparedHost(
                pending.prepared, pending.pendingGame, pending.request);
    }

    private void failPreparation(Preparation pending) {
        if (!currentPreparation(pending)) return;
        preparation = null;
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
            diagnostic("ERROR", "orchestration.failed", request, intent,
                    "reason", "pass_limit");
            effects.orchestrationFailed(); return;
        }

        SessionSnapshot snapshot = effects.resolve(intent.profileKey);
        if (!current(request)) return;
        if (snapshot.state == SessionSnapshot.State.TERMINATING) {
            diagnostic("WARN", "orchestration.rejected", request, intent,
                    "reason", Rejection.TERMINATING.name());
            effects.reject(Rejection.TERMINATING);
            return;
        }
        if (snapshot.state == SessionSnapshot.State.PREPARING
                && intent.kind == PlayIntent.Kind.PLAYNITE_GAME) {
            if (!opaqueReady) {
                awaitOpaque(request, intent, LaunchTransitionType.GAME,
                        replacementAuthorized, reuseOnly, preparedTarget, pass);
            } else if (!reuseOnly && snapshot.playniteGameId.isEmpty()) {
                if (!effects.canAttemptRetainedSwitch(intent)) {
                    effects.uncertainSessionFailed();
                    return;
                }
                evaluate(request, intent, false, true,
                        true, preparedTarget, pass + 1);
            } else if (!reuseOnly) {
                effects.refreshSession(intent, () -> !current(request), success ->
                        dispatcher.post(() -> {
                            if (!current(request)) return;
                            if (!success || !effects.canAttemptRetainedSwitch(intent)) {
                                effects.uncertainSessionFailed();
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
                return;
            }
            if (snapshot.hostGameAppId == 0) {
                snapshot = new SessionSnapshot(snapshot.hostId, snapshot.profileId,
                        SessionSnapshot.State.NONE, 0, "", false,
                        false, false, snapshot.hostSleepRequested,
                        snapshot.hostSleepObserved);
            } else if (intent.sunshineAppId == snapshot.hostGameAppId) {
                snapshot = new SessionSnapshot(snapshot.hostId, snapshot.profileId,
                        SessionSnapshot.State.ACTIVE, snapshot.hostGameAppId,
                        "", snapshot.retainedTransport, false, false,
                        snapshot.hostSleepRequested, snapshot.hostSleepObserved);
            }
        }

        boolean matches = intent.matches(snapshot);
        if (snapshot.state == SessionSnapshot.State.ACTIVE && matches) {
            if (snapshot.retainedTransport) {
                returnToRetained(request, intent);
            } else {
                connect(request, intent, opaqueReady, preparedTarget, snapshot, pass);
            }
            return;
        }
        if (snapshot.state == SessionSnapshot.State.RECONNECT_REQUIRED && matches) {
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
        if (snapshot.state == SessionSnapshot.State.NONE) {
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
        reuseOnly |= !replacementAuthorized && effects.canAttemptRetainedSwitch(intent);
        if (!replacementAuthorized && !reuseOnly) {
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
                    replacementAuthorized, reuseOnly, pass);
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
                if (result.status == HostLaunchPreflight.Status.FAILED) effects.preflightFailed(result.failure);
                else if (result.status == HostLaunchPreflight.Status.READY)
                    evaluate(request, intent, replacementAuthorized, reuseOnly,
                            true, result.target, pass + 1);
            });
        });
    }

    private void awaitFreshSession(long request, PlayIntent intent,
                                   boolean replacementAuthorized, boolean reuseOnly,
                                   NvApp preparedTarget, int pass) {
        effects.refreshSession(intent, () -> !current(request), success ->
                dispatcher.post(() -> {
                    if (!current(request)) return;
                    if (!success || effects.resolve(intent.profileKey).state
                            == SessionSnapshot.State.UNCERTAIN) {
                        diagnostic("WARN", "orchestration.refresh_uncertain", request, intent,
                                "status", success ? "UNCERTAIN" : "FAILED");
                        effects.uncertainSessionFailed();
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
        if (beforeClose.state == SessionSnapshot.State.NONE || intent.matches(beforeClose)) {
            evaluate(request, intent, replacementAuthorized, reuseOnly,
                    true, preparedTarget, pass + 1);
            return;
        }
        execute(request, intent, () -> {
            CloseResult closeResult;
            String closeFailure = "";
            diagnostic("INFO", "orchestration.close_started", request, intent);
            try { closeResult = effects.closePreviousSession(
                    intent, preparedTarget, replacementAuthorized,
                    () -> !current(request)); }
            catch (Exception error) {
                closeResult = null;
                closeFailure = error.getMessage() == null ? "" : error.getMessage();
                diagnostic("ERROR", "orchestration.close_failed", request, intent,
                        "error_type", error.getClass().getName());
            }
            if (closeResult != null) diagnostic("INFO",
                    "orchestration.close_completed", request, intent,
                    "status", closeResult.name());
            CloseResult result = closeResult;
            String failureReason = closeFailure;
            dispatcher.post(() -> {
                if (!current(request)) return;
                if (result == null) { effects.previousSessionCloseFailed(failureReason); return; }
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
                SessionSnapshot afterClose = effects.resolve(intent.profileKey);
                if (!current(request)) return;
                if (afterClose.state == SessionSnapshot.State.NONE) {
                    launch(request, intent, preparedTarget, freshType(intent), "",
                            afterClose.hostGameAppId == 0);
                } else if (intent.matches(afterClose)) {
                    evaluate(request, intent, true, false,
                            true, preparedTarget, pass + 1);
                } else effects.previousSessionCloseFailed("");
            });
        });
    }

    private void execute(long request, PlayIntent intent, Runnable action) {
        try { executor.execute(() -> { if (current(request)) action.run(); }); }
        catch (RuntimeException error) {
            diagnostic("ERROR", "orchestration.execution_failed", request, intent,
                    "error_type", error.getClass().getName());
            if (current(request)) effects.orchestrationFailed();
        }
    }

    private void returnToRetained(long request, PlayIntent intent) {
        diagnostic("INFO", "orchestration.return_retained", request, intent);
        effects.returnToRetainedStream();
    }

    private void reconnect(long request, PlayIntent intent) {
        diagnostic("INFO", "orchestration.reconnect", request, intent);
        effects.reconnectSavedSession(intent);
    }

    private void launch(long request, PlayIntent intent, NvApp target,
                        LaunchTransitionType type, String sourceSuspendId,
                        boolean ownsFreshSunshineSession) {
        diagnostic("INFO", "orchestration.launch", request, intent,
                "operation", type.name(), "app_id", target.getAppId());
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
