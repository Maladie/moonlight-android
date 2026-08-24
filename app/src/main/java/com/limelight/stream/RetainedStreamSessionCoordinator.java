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
        public final String streamSessionId;
        public final String hostId;
        public final int appId;
        public final String playniteGameId;

        private Snapshot(State state, String streamSessionId, String hostId, int appId,
                         String playniteGameId) {
            this.state = state;
            this.streamSessionId = streamSessionId;
            this.hostId = hostId;
            this.appId = appId;
            this.playniteGameId = playniteGameId;
        }
    }

    private static State state = State.NONE;
    private static WeakReference<Controller> controller = new WeakReference<>(null);
    private static String streamSessionId = "";
    private static String hostId = "";
    private static int appId;
    private static String playniteGameId = "";

    private RetainedStreamSessionCoordinator() { }

    public static synchronized void enterHome(Controller owner, String retainedStreamSessionId,
                                              String retainedHostId, int retainedAppId,
                                              String retainedPlayniteGameId) {
        String sessionId = normalize(retainedStreamSessionId);
        if (sessionId.isEmpty()) throw new IllegalArgumentException("Stream session ID is required");
        controller = new WeakReference<>(owner);
        streamSessionId = sessionId;
        hostId = retainedHostId == null ? "" : retainedHostId;
        appId = retainedAppId;
        playniteGameId = retainedPlayniteGameId == null ? "" : retainedPlayniteGameId;
        state = State.HOME_LIVE;
    }

    public static synchronized Snapshot snapshot() {
        return new Snapshot(state, streamSessionId, hostId, appId, playniteGameId);
    }

    public static synchronized State state() {
        return state;
    }

    public static synchronized boolean hasRetainedSession() {
        return state != State.NONE && state != State.TERMINATING;
    }

    public static boolean parkForBackground(String expectedStreamSessionId) {
        Controller owner;
        String capturedId;
        synchronized (RetainedStreamSessionCoordinator.class) {
            if (!matches(expectedStreamSessionId)) return false;
            if (state == State.PARKED_LIVE) return true;
            if (state != State.HOME_LIVE) return false;
            capturedId = streamSessionId;
            owner = controller.get();
            if (owner == null) {
                state = State.RECONNECT_REQUIRED;
                return false;
            }
        }
        boolean parked = owner.parkRetainedTransport();
        synchronized (RetainedStreamSessionCoordinator.class) {
            if (!matches(capturedId) || state != State.HOME_LIVE) return false;
            state = parked ? State.PARKED_LIVE : State.RECONNECT_REQUIRED;
            if (!parked) controller.clear();
        }
        return parked;
    }

    public static synchronized void markParked(String expectedStreamSessionId) {
        if (!matches(expectedStreamSessionId)) return;
        if (state == State.HOME_LIVE || state == State.PARKED_LIVE) {
            state = State.PARKED_LIVE;
        }
    }

    public static synchronized void markReconnectRequired(String expectedStreamSessionId) {
        if (!matches(expectedStreamSessionId)) return;
        if (state != State.TERMINATING) state = State.RECONNECT_REQUIRED;
        controller.clear();
    }

    public static synchronized boolean canResumeInstantly() {
        return (state == State.HOME_LIVE || state == State.PARKED_LIVE)
                && controller.get() != null;
    }

    public static TerminationResult terminate(String expectedStreamSessionId,
                                              Runnable completion) {
        Controller owner;
        String capturedId;
        synchronized (RetainedStreamSessionCoordinator.class) {
            if (!matches(expectedStreamSessionId)) return TerminationResult.NO_CONTROLLER;
            if (state == State.TERMINATING) return TerminationResult.IN_PROGRESS;
            capturedId = streamSessionId;
            state = State.TERMINATING;
            owner = controller.get();
            controller.clear();
            if (owner == null) {
                state = State.RECONNECT_REQUIRED;
                return TerminationResult.NO_CONTROLLER;
            }
        }
        owner.terminateRetainedSession(() -> {
            if (clearIfMatches(capturedId) && completion != null) completion.run();
        });
        return TerminationResult.STARTED;
    }

    public static synchronized boolean clearIfMatches(String expectedStreamSessionId) {
        if (!matches(expectedStreamSessionId)) return false;
        clearLocked();
        return true;
    }

    static synchronized void clear() {
        clearLocked();
    }

    private static void clearLocked() {
        state = State.NONE;
        controller.clear();
        streamSessionId = "";
        hostId = "";
        appId = 0;
        playniteGameId = "";
    }

    private static boolean matches(String expectedStreamSessionId) {
        String expected = normalize(expectedStreamSessionId);
        return !expected.isEmpty() && expected.equals(streamSessionId);
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim();
    }
}
