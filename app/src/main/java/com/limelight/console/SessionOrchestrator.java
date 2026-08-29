package com.limelight.console;

import com.limelight.console.transition.LaunchTransitionType;
import com.limelight.nvstream.http.NvApp;

import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Routes a user target from one fresh canonical session snapshot. */
final class SessionOrchestrator implements AutoCloseable {
    enum Rejection { INITIALIZING, UNPAIRED, TERMINATING }
    enum CloseResult { CLOSED, REUSED, CANCELLED, NEEDS_CONFIRMATION }

    interface Dispatcher { void post(Runnable action); }
    interface Effects {
        boolean isAvailable(); boolean isPaired(String hostId); SessionSnapshot resolve(String hostId);
        void returnToRetainedStream(); void reconnectSavedSession(); void reject(Rejection reason);
        void confirmReplacement(Runnable accepted);
        boolean canAttemptRetainedSwitch(PlayIntent intent);
        void showLoading(PlayIntent intent, LaunchTransitionType type, Runnable opaqueFrameReady);
        void refreshSession(PlayIntent intent, BooleanSupplier cancelled,
                            Consumer<Boolean> completion);
        HostLaunchPreflight.Result preflight(PlayIntent intent, HostLaunchPreflight.Action action, BooleanSupplier cancelled);
        CloseResult closePreviousSession(PlayIntent intent, NvApp target,
                                         boolean allowDestructiveClose,
                                         BooleanSupplier cancelled) throws Exception;
        void launch(PlayIntent intent, NvApp target, LaunchTransitionType type,
                    String sourceSuspendId, boolean ownsFreshSunshineSession);
        void preflightFailed(HostLaunchPreflight.Failure failure); void orchestrationFailed();
        void uncertainSessionFailed();
        void previousSessionCloseFailed(String reason);
    }

    private final Effects effects;
    private final Executor executor;
    private final Dispatcher dispatcher;
    private final AtomicLong generation = new AtomicLong();
    private PlayIntent lastIntent;
    private volatile boolean closed;

    SessionOrchestrator(Effects effects, Executor executor, Dispatcher dispatcher) {
        this.effects = effects;
        this.executor = executor;
        this.dispatcher = dispatcher;
    }

    void play(PlayIntent intent) {
        lastIntent = intent;
        long request = generation.incrementAndGet();
        if (closed) return;
        if (!effects.isAvailable()) { effects.reject(Rejection.INITIALIZING); return; }
        if (!effects.isPaired(intent.hostId)) { effects.reject(Rejection.UNPAIRED); return; }
        evaluate(request, intent, false, false, false, null, 0);
    }

    void cancel() { generation.incrementAndGet(); }
    void retry() {
        PlayIntent intent = lastIntent;
        if (intent != null && !closed) play(intent);
    }
    @Override public void close() { closed = true; lastIntent = null; cancel(); }

    private void evaluate(long request, PlayIntent intent, boolean replacementAuthorized,
                          boolean reuseOnly, boolean opaqueReady,
                          NvApp preparedTarget, int pass) {
        if (!current(request)) return;
        if (pass > 8) { effects.orchestrationFailed(); return; }

        SessionSnapshot snapshot = effects.resolve(intent.hostId);
        if (!current(request)) return;
        if (snapshot.state == SessionSnapshot.State.TERMINATING) {
            effects.reject(Rejection.TERMINATING);
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
                snapshot = new SessionSnapshot(snapshot.hostId,
                        SessionSnapshot.State.NONE, 0, "", false,
                        false, false, snapshot.hostSleepRequested,
                        snapshot.hostSleepObserved);
            } else if (intent.sunshineAppId == snapshot.hostGameAppId) {
                snapshot = new SessionSnapshot(snapshot.hostId,
                        SessionSnapshot.State.ACTIVE, snapshot.hostGameAppId,
                        "", snapshot.retainedTransport, false, false,
                        snapshot.hostSleepRequested, snapshot.hostSleepObserved);
            }
        }

        boolean matches = intent.matches(snapshot);
        if (snapshot.state == SessionSnapshot.State.ACTIVE && matches) {
            if (snapshot.retainedTransport) {
                effects.returnToRetainedStream();
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
                effects.reconnectSavedSession();
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
                SessionSnapshot latest = effects.resolve(intent.hostId);
                if (!current(request)) return;
                if (latest.state == SessionSnapshot.State.NONE) {
                    effects.launch(intent, preparedTarget, freshType(intent), "",
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
            effects.launch(intent, preparedTarget, LaunchTransitionType.GENERIC,
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
        execute(request, () -> {
            HostLaunchPreflight.Result result = effects.preflight(intent, action, () -> !current(request));
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
                    if (!success || effects.resolve(intent.hostId).state
                            == SessionSnapshot.State.UNCERTAIN) {
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
        SessionSnapshot beforeClose = effects.resolve(intent.hostId);
        if (!current(request)) return;
        if (beforeClose.state == SessionSnapshot.State.NONE || intent.matches(beforeClose)) {
            evaluate(request, intent, replacementAuthorized, reuseOnly,
                    true, preparedTarget, pass + 1);
            return;
        }
        execute(request, () -> {
            CloseResult closeResult;
            String closeFailure = "";
            try { closeResult = effects.closePreviousSession(
                    intent, preparedTarget, replacementAuthorized,
                    () -> !current(request)); }
            catch (Exception error) {
                closeResult = null;
                closeFailure = error.getMessage() == null ? "" : error.getMessage();
            }
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
                    effects.returnToRetainedStream();
                    return;
                }
                SessionSnapshot afterClose = effects.resolve(intent.hostId);
                if (!current(request)) return;
                if (afterClose.state == SessionSnapshot.State.NONE) {
                    effects.launch(intent, preparedTarget, freshType(intent), "",
                            afterClose.hostGameAppId == 0);
                } else if (intent.matches(afterClose)) {
                    evaluate(request, intent, true, false,
                            true, preparedTarget, pass + 1);
                } else effects.previousSessionCloseFailed("");
            });
        });
    }

    private void execute(long request, Runnable action) {
        try { executor.execute(() -> { if (current(request)) action.run(); }); }
        catch (RuntimeException ignored) { if (current(request)) effects.orchestrationFailed(); }
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
