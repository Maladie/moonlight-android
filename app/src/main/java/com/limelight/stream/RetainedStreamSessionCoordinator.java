package com.limelight.stream;

import java.lang.ref.WeakReference;

/**
 * Single in-process source of truth for a stream retained behind MoonWaker Home.
 * Persistent reconnect data remains owned by SessionResumeManager.
 */
public final class RetainedStreamSessionCoordinator {
    public enum State {
        NONE,
        HOME_LIVE,
        PARKED_LIVE,
        RECONNECT_REQUIRED,
        TERMINATING
    }

    public interface Controller {
        boolean parkRetainedTransport();
        void terminateRetainedSession(Runnable completion);
    }

    public enum TerminationResult { STARTED, IN_PROGRESS, NO_CONTROLLER }

    public static final class Snapshot {
        public final State state;
        public final String hostId;
        public final int appId;
        public final String playniteGameId;

        private Snapshot(State state, String hostId, int appId, String playniteGameId) {
            this.state = state;
            this.hostId = hostId;
            this.appId = appId;
            this.playniteGameId = playniteGameId;
        }
    }

    private static State state = State.NONE;
    private static WeakReference<Controller> controller = new WeakReference<>(null);
    private static String hostId = "";
    private static int appId;
    private static String playniteGameId = "";

    private RetainedStreamSessionCoordinator() { }

    public static synchronized void enterHome(Controller owner, String retainedHostId,
                                              int retainedAppId, String retainedPlayniteGameId) {
        controller = new WeakReference<>(owner);
        hostId = retainedHostId == null ? "" : retainedHostId;
        appId = retainedAppId;
        playniteGameId = retainedPlayniteGameId == null ? "" : retainedPlayniteGameId;
        state = State.HOME_LIVE;
    }

    public static synchronized Snapshot snapshot() {
        return new Snapshot(state, hostId, appId, playniteGameId);
    }

    public static synchronized State state() {
        return state;
    }

    public static synchronized boolean hasRetainedSession() {
        return state != State.NONE && state != State.TERMINATING;
    }

    public static boolean parkForBackground() {
        Controller owner;
        synchronized (RetainedStreamSessionCoordinator.class) {
            if (state == State.PARKED_LIVE) return true;
            if (state != State.HOME_LIVE) return false;
            owner = controller.get();
            if (owner == null) {
                state = State.RECONNECT_REQUIRED;
                return false;
            }
        }
        boolean parked = owner.parkRetainedTransport();
        synchronized (RetainedStreamSessionCoordinator.class) {
            state = parked ? State.PARKED_LIVE : State.RECONNECT_REQUIRED;
        }
        return parked;
    }

    public static synchronized void markParked() {
        if (state == State.HOME_LIVE || state == State.PARKED_LIVE) {
            state = State.PARKED_LIVE;
        }
    }

    public static synchronized void markReconnectRequired() {
        if (state != State.TERMINATING) state = State.RECONNECT_REQUIRED;
        controller.clear();
    }

    public static synchronized boolean canResumeInstantly() {
        return (state == State.HOME_LIVE || state == State.PARKED_LIVE)
                && controller.get() != null;
    }

    public static TerminationResult terminate(Runnable completion) {
        Controller owner;
        synchronized (RetainedStreamSessionCoordinator.class) {
            if (state == State.TERMINATING) return TerminationResult.IN_PROGRESS;
            state = State.TERMINATING;
            owner = controller.get();
            controller.clear();
        }
        if (owner == null) return TerminationResult.NO_CONTROLLER;
        owner.terminateRetainedSession(() -> {
            clear();
            if (completion != null) completion.run();
        });
        return TerminationResult.STARTED;
    }

    public static synchronized void clear() {
        state = State.NONE;
        controller.clear();
        hostId = "";
        appId = 0;
        playniteGameId = "";
    }
}
