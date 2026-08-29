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
        boolean isRetainedTransportLive();
        boolean parkRetainedTransport();
        void terminateRetainedSession(TerminationCallback completion);
        void switchGame(SwitchRequest request, SwitchCallback completion);
    }

    public interface TerminationCallback {
        void complete(boolean success);
    }

    public interface SwitchCallback {
        void complete(SwitchOutcome outcome, String error);
    }

    public enum SwitchOutcome { REUSED, CANCELLED, FAILED }
    public enum SwitchResult { STARTED, NOT_ELIGIBLE }

    public static final class SwitchRequest {
        public final String streamSessionId;
        public final String hostId;
        public final int appId;
        public final String oldGameId;
        public final String newGameId;
        public final String newGameName;
        public final String streamTargetName;
        public final String artworkPath;
        public final java.util.function.BooleanSupplier cancelled;

        public SwitchRequest(String streamSessionId, String hostId, int appId,
                             String oldGameId, String newGameId, String newGameName,
                             String streamTargetName, String artworkPath,
                             java.util.function.BooleanSupplier cancelled) {
            this.streamSessionId = normalize(streamSessionId);
            this.hostId = normalize(hostId);
            this.appId = appId;
            this.oldGameId = normalize(oldGameId);
            this.newGameId = normalize(newGameId);
            this.newGameName = normalize(newGameName);
            this.streamTargetName = normalize(streamTargetName);
            this.artworkPath = normalize(artworkPath);
            this.cancelled = cancelled == null ? () -> false : cancelled;
        }
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
    private static Controller switchOwner;
    private static String switchSessionId = "";

    private RetainedStreamSessionCoordinator() { }

    public static synchronized void enterHome(Controller owner, String retainedStreamSessionId,
                                              String retainedHostId, int retainedAppId,
                                              String retainedPlayniteGameId) {
        String sessionId = normalize(retainedStreamSessionId);
        if (sessionId.isEmpty()) throw new IllegalArgumentException("Stream session ID is required");
        if (!sessionId.equals(streamSessionId) || controller.get() != owner) {
            switchOwner = null;
            switchSessionId = "";
        }
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
            if (switchOwner != null) return false;
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
        switchOwner = null;
        switchSessionId = "";
    }

    public static synchronized boolean restoreReconnectIfTerminating(
            String expectedStreamSessionId, String expectedHostId,
            int expectedAppId, String expectedGameId) {
        if (state != State.TERMINATING || !matches(expectedStreamSessionId)
                || appId != expectedAppId
                || !hostId.equalsIgnoreCase(normalize(expectedHostId))
                || !playniteGameId.equalsIgnoreCase(normalize(expectedGameId))) {
            return false;
        }
        state = State.RECONNECT_REQUIRED;
        return true;
    }

    public static synchronized boolean markTerminating(String expectedStreamSessionId,
                                                       String terminatingHostId,
                                                       int terminatingAppId,
                                                       String terminatingGameId) {
        String expected = normalize(expectedStreamSessionId);
        if (expected.isEmpty()
                || (!streamSessionId.isEmpty() && !expected.equals(streamSessionId))) {
            return false;
        }
        controller.clear();
        switchOwner = null;
        switchSessionId = "";
        streamSessionId = expected;
        hostId = terminatingHostId == null ? "" : terminatingHostId;
        appId = terminatingAppId;
        playniteGameId = terminatingGameId == null ? "" : terminatingGameId;
        state = State.TERMINATING;
        return true;
    }

    public static synchronized boolean canResumeInstantly() {
        return (state == State.HOME_LIVE || state == State.PARKED_LIVE)
                && liveControllerLocked() != null;
    }

    public static synchronized boolean canSwitchGame(String expectedHostId,
                                                      int expectedAppId) {
        return state == State.HOME_LIVE && liveControllerLocked() != null
                && switchOwner == null
                && appId == expectedAppId
                && normalize(expectedHostId).equalsIgnoreCase(hostId);
    }

    public static SwitchResult switchGame(String expectedHostId, int expectedAppId,
                                          String newGameId, String newGameName,
                                          String streamTargetName, String artworkPath,
                                          java.util.function.BooleanSupplier cancelled,
                                          SwitchCallback completion) {
        Controller owner;
        SwitchRequest request;
        synchronized (RetainedStreamSessionCoordinator.class) {
            if (!canSwitchGame(expectedHostId, expectedAppId)) {
                return SwitchResult.NOT_ELIGIBLE;
            }
            owner = controller.get();
            request = new SwitchRequest(streamSessionId, hostId, appId,
                    playniteGameId, newGameId, newGameName, streamTargetName,
                    artworkPath, cancelled);
            switchOwner = owner;
            switchSessionId = streamSessionId;
        }
        owner.switchGame(request, (outcome, error) -> {
            boolean current;
            synchronized (RetainedStreamSessionCoordinator.class) {
                current = switchOwner == owner
                        && switchSessionId.equals(request.streamSessionId);
                if (current && outcome != SwitchOutcome.REUSED) {
                    switchOwner = null;
                    switchSessionId = "";
                }
            }
            if (completion != null) completion.complete(outcome, error);
        });
        return SwitchResult.STARTED;
    }

    public static synchronized boolean finishSwitch(String expectedStreamSessionId,
                                                    Controller expectedOwner) {
        if (switchOwner != expectedOwner
                || !switchSessionId.equals(normalize(expectedStreamSessionId))) {
            return false;
        }
        switchOwner = null;
        switchSessionId = "";
        return true;
    }

    public static synchronized boolean updateGameIfMatches(
            String expectedStreamSessionId, String expectedHostId, int expectedAppId,
            String expectedOldGameId, String newGameId) {
        if (state != State.HOME_LIVE || !matches(expectedStreamSessionId)
                || appId != expectedAppId
                || !hostId.equalsIgnoreCase(normalize(expectedHostId))
                || !playniteGameId.equalsIgnoreCase(normalize(expectedOldGameId))) {
            return false;
        }
        playniteGameId = normalize(newGameId);
        return true;
    }

    public static synchronized boolean ownsLiveOrParkedSession(
            Controller expectedOwner, String expectedStreamSessionId,
            String expectedHostId, int expectedAppId, String expectedGameId) {
        return (state == State.HOME_LIVE || state == State.PARKED_LIVE)
                && controller.get() == expectedOwner
                && switchOwner == null && matches(expectedStreamSessionId)
                && appId == expectedAppId
                && hostId.equalsIgnoreCase(normalize(expectedHostId))
                && playniteGameId.equalsIgnoreCase(normalize(expectedGameId));
    }

    public static synchronized boolean clearOwnedGame(
            Controller expectedOwner, String expectedStreamSessionId,
            String expectedHostId, int expectedAppId, String expectedGameId) {
        if (!ownsLiveOrParkedSession(expectedOwner, expectedStreamSessionId,
                expectedHostId, expectedAppId, expectedGameId)) return false;
        playniteGameId = "";
        return true;
    }

    public static synchronized boolean clearTerminatingGameIfMatches(
            String expectedStreamSessionId, String expectedHostId, int expectedAppId,
            String expectedGameId) {
        if (state != State.TERMINATING || !matches(expectedStreamSessionId)
                || appId != expectedAppId
                || !hostId.equalsIgnoreCase(normalize(expectedHostId))
                || !playniteGameId.equalsIgnoreCase(normalize(expectedGameId))) {
            return false;
        }
        playniteGameId = "";
        return true;
    }

    public static synchronized boolean retainNeutralAfterGameStopped(
            Controller expectedOwner, String expectedStreamSessionId,
            String expectedHostId, int expectedAppId, String expectedGameId) {
        String sessionId = normalize(expectedStreamSessionId);
        String expectedHost = normalize(expectedHostId);
        if (state == State.NONE) {
            if (expectedOwner == null || sessionId.isEmpty() || expectedHost.isEmpty()) {
                return false;
            }
            controller = new WeakReference<>(expectedOwner);
            streamSessionId = sessionId;
            hostId = expectedHost;
            appId = expectedAppId;
            playniteGameId = "";
            state = State.HOME_LIVE;
            return true;
        }
        return clearOwnedGame(expectedOwner, sessionId, expectedHost,
                expectedAppId, expectedGameId);
    }

    public static TerminationResult terminate(String expectedStreamSessionId,
                                              TerminationCallback completion) {
        Controller owner;
        String capturedId;
        State previousState;
        synchronized (RetainedStreamSessionCoordinator.class) {
            if (!matches(expectedStreamSessionId)) return TerminationResult.NO_CONTROLLER;
            if (state == State.TERMINATING) return TerminationResult.IN_PROGRESS;
            capturedId = streamSessionId;
            previousState = state;
            owner = liveControllerLocked();
            controller.clear();
            if (owner == null) {
                return TerminationResult.NO_CONTROLLER;
            }
            state = State.TERMINATING;
        }
        owner.terminateRetainedSession(success -> {
            boolean current;
            synchronized (RetainedStreamSessionCoordinator.class) {
                current = matches(capturedId) && state == State.TERMINATING;
                if (current) {
                    if (success) {
                        clearLocked();
                    } else {
                        state = previousState;
                        controller = new WeakReference<>(owner);
                    }
                }
            }
            if (current && completion != null) completion.complete(success);
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
        switchOwner = null;
        switchSessionId = "";
    }

    private static boolean matches(String expectedStreamSessionId) {
        String expected = normalize(expectedStreamSessionId);
        return !expected.isEmpty() && expected.equals(streamSessionId);
    }

    private static Controller liveControllerLocked() {
        Controller owner = controller.get();
        if (owner != null && owner.isRetainedTransportLive()) return owner;
        if (state == State.HOME_LIVE || state == State.PARKED_LIVE) {
            state = State.RECONNECT_REQUIRED;
            controller.clear();
            switchOwner = null;
            switchSessionId = "";
        }
        return null;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim();
    }
}
