package com.limelight.console;

import com.limelight.console.transition.LaunchTransitionType;
import com.limelight.nvstream.http.NvApp;

import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;

/** Routes a user target from one fresh canonical session snapshot. */
final class SessionOrchestrator implements AutoCloseable {
    enum Rejection { INITIALIZING, UNPAIRED, TERMINATING }

    interface Dispatcher { void post(Runnable action); }
    interface Effects {
        boolean isAvailable(); boolean isPaired(String hostId); SessionSnapshot resolve(String hostId);
        void returnToRetainedStream(); void reconnectSavedSession(); void reject(Rejection reason);
        void confirmReplacement(Runnable accepted);
        void showLoading(PlayIntent intent, LaunchTransitionType type, Runnable opaqueFrameReady);
        HostLaunchPreflight.Result preflight(PlayIntent intent, HostLaunchPreflight.Action action, BooleanSupplier cancelled);
        boolean closePreviousSession(PlayIntent intent, NvApp target, BooleanSupplier cancelled) throws Exception;
        void launch(PlayIntent intent, NvApp target, LaunchTransitionType type, String sourceSuspendId);
        void preflightFailed(HostLaunchPreflight.Failure failure); void orchestrationFailed(); void previousSessionCloseFailed();
    }

    private final Effects effects;
    private final Executor executor;
    private final Dispatcher dispatcher;
    private final AtomicLong generation = new AtomicLong();
    private volatile boolean closed;

    SessionOrchestrator(Effects effects, Executor executor, Dispatcher dispatcher) {
        this.effects = effects;
        this.executor = executor;
        this.dispatcher = dispatcher;
    }

    void play(PlayIntent intent) {
        long request = generation.incrementAndGet();
        if (closed) return;
        if (!effects.isAvailable()) { effects.reject(Rejection.INITIALIZING); return; }
        if (!effects.isPaired(intent.hostId)) { effects.reject(Rejection.UNPAIRED); return; }
        evaluate(request, intent, false, false, null, 0);
    }

    void cancel() { generation.incrementAndGet(); }
    @Override public void close() { closed = true; cancel(); }

    private void evaluate(long request, PlayIntent intent, boolean replacementAuthorized,
                          boolean opaqueReady, NvApp preparedTarget, int pass) {
        if (!current(request)) return;
        if (pass > 8) { effects.orchestrationFailed(); return; }

        SessionSnapshot snapshot = effects.resolve(intent.hostId);
        if (!current(request)) return;
        if (snapshot.state == SessionSnapshot.State.TERMINATING) {
            effects.reject(Rejection.TERMINATING);
            return;
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
                        replacementAuthorized, preparedTarget, pass);
            } else if (preparedTarget == null) {
                awaitPreflight(request, intent, replacementAuthorized, pass,
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
                        preparedTarget, pass);
            } else if (preparedTarget == null) {
                awaitPreflight(request, intent, replacementAuthorized, pass,
                        HostLaunchPreflight.Action.LAUNCH);
            } else {
                SessionSnapshot latest = effects.resolve(intent.hostId);
                if (!current(request)) return;
                if (latest.state == SessionSnapshot.State.NONE) {
                    effects.launch(intent, preparedTarget, freshType(intent), "");
                } else {
                    evaluate(request, intent, replacementAuthorized, true, preparedTarget, pass + 1);
                }
            }
            return;
        }
        if (!replacementAuthorized) {
            effects.confirmReplacement(() -> {
                if (current(request)) evaluate(request, intent, true, false, null, pass + 1);
            });
        } else if (!opaqueReady) {
            awaitOpaque(request, intent, freshType(intent), true, preparedTarget, pass);
        } else if (preparedTarget == null) {
            awaitPreflight(request, intent, true, pass, HostLaunchPreflight.Action.LAUNCH);
        } else {
            closeCompetingSession(request, intent, preparedTarget, pass);
        }
    }

    private void connect(long request, PlayIntent intent, boolean opaqueReady,
                         NvApp preparedTarget, SessionSnapshot snapshot, int pass) {
        if (!opaqueReady) {
            awaitOpaque(request, intent, LaunchTransitionType.GENERIC, false, preparedTarget, pass);
        } else if (preparedTarget == null) {
            awaitPreflight(request, intent, false, pass, HostLaunchPreflight.Action.LAUNCH);
        } else if (current(request)) {
            effects.launch(intent, preparedTarget, LaunchTransitionType.GENERIC,
                    snapshot.explicitSuspension ? snapshot.suspendId : "");
        }
    }

    private void awaitOpaque(long request, PlayIntent intent, LaunchTransitionType type,
                             boolean replacementAuthorized, NvApp preparedTarget, int pass) {
        effects.showLoading(intent, type, () -> {
            if (current(request)) evaluate(request, intent, replacementAuthorized, true, preparedTarget, pass + 1);
        });
    }

    private void awaitPreflight(long request, PlayIntent intent, boolean replacementAuthorized,
                                int pass, HostLaunchPreflight.Action action) {
        execute(request, () -> {
            HostLaunchPreflight.Result result = effects.preflight(intent, action, () -> !current(request));
            dispatcher.post(() -> {
                if (!current(request)) return;
                if (result.status == HostLaunchPreflight.Status.FAILED) effects.preflightFailed(result.failure);
                else if (result.status == HostLaunchPreflight.Status.READY)
                    evaluate(request, intent, replacementAuthorized, true, result.target, pass + 1);
            });
        });
    }

    private void closeCompetingSession(long request, PlayIntent intent, NvApp preparedTarget, int pass) {
        SessionSnapshot beforeClose = effects.resolve(intent.hostId);
        if (!current(request)) return;
        if (beforeClose.state == SessionSnapshot.State.NONE || intent.matches(beforeClose)) {
            evaluate(request, intent, true, true, preparedTarget, pass + 1);
            return;
        }
        execute(request, () -> {
            boolean closedPrevious;
            try { closedPrevious = effects.closePreviousSession(intent, preparedTarget, () -> !current(request)); }
            catch (Exception error) { closedPrevious = false; }
            boolean result = closedPrevious;
            dispatcher.post(() -> {
                if (!current(request)) return;
                if (!result) { effects.previousSessionCloseFailed(); return; }
                SessionSnapshot afterClose = effects.resolve(intent.hostId);
                if (!current(request)) return;
                if (afterClose.state == SessionSnapshot.State.NONE) {
                    effects.launch(intent, preparedTarget, freshType(intent), "");
                } else if (intent.matches(afterClose)) {
                    evaluate(request, intent, true, true, preparedTarget, pass + 1);
                } else effects.previousSessionCloseFailed();
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