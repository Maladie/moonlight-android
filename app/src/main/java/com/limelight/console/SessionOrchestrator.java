package com.limelight.console;

import com.limelight.console.transition.LaunchTransitionType;
import com.limelight.nvstream.http.NvApp;

import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;

/** Chooses effects from a fresh session snapshot; transition state remains external. */
final class SessionOrchestrator implements AutoCloseable {
    enum Rejection { INITIALIZING, UNPAIRED, TERMINATING, RETAINED_SWITCH_BLOCKED }

    interface Dispatcher {
        void post(Runnable action);
    }

    static final class RetainedTransport {
        static final RetainedTransport NONE = new RetainedTransport(false, false, "", 0, "");

        final boolean live;
        final boolean reusable;
        final String hostId;
        final int appId;
        final String gameId;

        RetainedTransport(boolean live, boolean reusable, String hostId,
                          int appId, String gameId) {
            this.live = live;
            this.reusable = reusable;
            this.hostId = SessionSnapshot.normalize(hostId);
            this.appId = appId;
            this.gameId = SessionSnapshot.normalize(gameId);
        }

        boolean matches(PlayIntent intent) {
            return live && intent.matches(hostId, appId, gameId);
        }
    }

    interface Effects {
        boolean isAvailable();
        boolean isPaired(String hostId);
        SessionSnapshot resolve(String hostId);
        RetainedTransport retainedTransport();
        boolean hasSavedReconnect(PlayIntent intent);
        void returnToRetainedStream();
        void reconnectSavedSession();
        void reject(Rejection reason);
        void confirmReplacement(Runnable accepted);
        void showLoading(PlayIntent intent, LaunchTransitionType type, Runnable opaqueFrameReady);
        HostLaunchPreflight.Result preflight(PlayIntent intent,
                                             HostLaunchPreflight.Action action,
                                             BooleanSupplier cancelled);
        void focusSuspendedGame(PlayIntent intent) throws Exception;
        boolean closePreviousSession(PlayIntent intent, NvApp target,
                                     BooleanSupplier cancelled)
                throws Exception;
        void launch(PlayIntent intent, NvApp target, LaunchTransitionType type);
        void preflightFailed(HostLaunchPreflight.Failure failure);
        void orchestrationFailed();
        void previousSessionCloseFailed();
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
        if (!effects.isAvailable()) {
            effects.reject(Rejection.INITIALIZING);
            return;
        }
        if (!effects.isPaired(intent.hostId)) {
            effects.reject(Rejection.UNPAIRED);
            return;
        }
        evaluate(request, intent, false, false, null, 0);
    }

    void cancel() {
        generation.incrementAndGet();
    }

    @Override public void close() {
        closed = true;
        cancel();
    }

    private void evaluate(long request, PlayIntent intent, boolean replacementAuthorized,
                          boolean opaqueReady, NvApp preparedTarget, int pass) {
        if (!current(request)) return;
        if (pass > 8) {
            effects.orchestrationFailed();
            return;
        }
        RetainedTransport retained = effects.retainedTransport();
        if (retained.live) {
            if (!retained.matches(intent)) {
                effects.reject(Rejection.RETAINED_SWITCH_BLOCKED);
                return;
            }
            if (retained.reusable) {
                effects.returnToRetainedStream();
                return;
            }
        }

        SessionSnapshot snapshot = effects.resolve(intent.hostId);
        if (!current(request)) return;
        if (snapshot.state == SessionSnapshot.State.TERMINATING) {
            effects.reject(Rejection.TERMINATING);
            return;
        }
        boolean matches = intent.matches(snapshot);
        if (snapshot.state == SessionSnapshot.State.RECONNECT_REQUIRED && matches
                && effects.hasSavedReconnect(intent)) {
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
        if (snapshot.state == SessionSnapshot.State.ACTIVE && matches) {
            connect(request, intent, opaqueReady, preparedTarget, false, pass);
            return;
        }
        if (snapshot.state == SessionSnapshot.State.SUSPENDED && matches) {
            connect(request, intent, opaqueReady, preparedTarget, true, pass);
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
                SessionSnapshot finalSnapshot = effects.resolve(intent.hostId);
                if (!current(request)) return;
                if (finalSnapshot.state == SessionSnapshot.State.NONE) {
                    effects.launch(intent, preparedTarget, freshType(intent));
                } else {
                    evaluate(request, intent, replacementAuthorized,
                            true, preparedTarget, pass + 1);
                }
            }
            return;
        }

        if (!replacementAuthorized) {
            effects.confirmReplacement(() -> {
                if (current(request)) {
                    evaluate(request, intent, true, false, null, pass + 1);
                }
            });
        } else if (!opaqueReady) {
            awaitOpaque(request, intent, freshType(intent), true, preparedTarget, pass);
        } else if (preparedTarget == null) {
            awaitPreflight(request, intent, true, pass,
                    HostLaunchPreflight.Action.LAUNCH);
        } else {
            closeCompetingSession(request, intent, preparedTarget, pass);
        }
    }

    private void connect(long request, PlayIntent intent, boolean opaqueReady,
                         NvApp preparedTarget, boolean suspended, int pass) {
        if (!opaqueReady) {
            awaitOpaque(request, intent, LaunchTransitionType.GENERIC,
                    false, preparedTarget, pass);
            return;
        }
        if (preparedTarget == null) {
            awaitPreflight(request, intent, false, pass,
                    HostLaunchPreflight.Action.LAUNCH);
        } else if (suspended) {
            focusAndConnect(request, intent, preparedTarget, pass);
        } else if (current(request)) {
            effects.launch(intent, preparedTarget, LaunchTransitionType.GENERIC);
        }
    }

    private void awaitOpaque(long request, PlayIntent intent, LaunchTransitionType type,
                             boolean replacementAuthorized, NvApp preparedTarget, int pass) {
        effects.showLoading(intent, type, () -> {
            if (current(request)) {
                evaluate(request, intent, replacementAuthorized,
                        true, preparedTarget, pass + 1);
            }
        });
    }

    private void awaitPreflight(long request, PlayIntent intent,
                                boolean replacementAuthorized, int pass,
                                HostLaunchPreflight.Action action) {
        execute(request, () -> {
            HostLaunchPreflight.Result result = effects.preflight(
                    intent, action, () -> !current(request));
            dispatcher.post(() -> {
                if (!current(request)) return;
                if (result.status == HostLaunchPreflight.Status.FAILED) {
                    effects.preflightFailed(result.failure);
                } else if (result.status == HostLaunchPreflight.Status.READY) {
                    evaluate(request, intent, replacementAuthorized,
                            true, result.target, pass + 1);
                }
            });
        });
    }

    private void focusAndConnect(long request, PlayIntent intent,
                                 NvApp preparedTarget, int pass) {
        execute(request, () -> {
            try {
                effects.focusSuspendedGame(intent);
            } catch (Exception ignored) {
                // Resuming the Sunshine target remains valid when focus fails.
            }
            dispatcher.post(() -> {
                if (!current(request)) return;
                SessionSnapshot latest = effects.resolve(intent.hostId);
                if (latest.state == SessionSnapshot.State.SUSPENDED
                        && intent.matches(latest)) {
                    effects.launch(intent, preparedTarget, LaunchTransitionType.GENERIC);
                } else {
                    evaluate(request, intent, false, true, preparedTarget, pass + 1);
                }
            });
        });
    }

    private void closeCompetingSession(long request, PlayIntent intent,
                                       NvApp preparedTarget, int pass) {
        SessionSnapshot beforeClose = effects.resolve(intent.hostId);
        if (!current(request)) return;
        if (beforeClose.state == SessionSnapshot.State.NONE || intent.matches(beforeClose)) {
            evaluate(request, intent, true, true, preparedTarget, pass + 1);
            return;
        }
        execute(request, () -> {
            boolean closedPrevious;
            try {
                closedPrevious = effects.closePreviousSession(
                        intent, preparedTarget, () -> !current(request));
            } catch (Exception error) {
                closedPrevious = false;
            }
            boolean result = closedPrevious;
            dispatcher.post(() -> {
                if (!current(request)) return;
                if (!result) {
                    effects.previousSessionCloseFailed();
                    return;
                }
                SessionSnapshot afterClose = effects.resolve(intent.hostId);
                if (!current(request)) return;
                if (afterClose.state == SessionSnapshot.State.NONE) {
                    effects.launch(intent, preparedTarget, freshType(intent));
                } else if (intent.matches(afterClose)) {
                    evaluate(request, intent, true, true, preparedTarget, pass + 1);
                } else {
                    effects.previousSessionCloseFailed();
                }
            });
        });
    }

    private void execute(long request, Runnable action) {
        try {
            executor.execute(() -> {
                if (current(request)) action.run();
            });
        } catch (RuntimeException ignored) {
            if (current(request)) effects.orchestrationFailed();
        }
    }

    private boolean current(long request) {
        return !closed && generation.get() == request;
    }

    private static LaunchTransitionType freshType(PlayIntent intent) {
        switch (intent.kind) {
            case PLAYNITE_GAME:
                return LaunchTransitionType.GAME;
            case PLAYNITE_FULLSCREEN:
                return LaunchTransitionType.PLAYNITE;
            default:
                return LaunchTransitionType.GENERIC;
        }
    }
}
