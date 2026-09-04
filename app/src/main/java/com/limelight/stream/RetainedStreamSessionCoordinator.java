package com.limelight.stream;

import com.limelight.diagnostics.MoonWakerDiagnostics;
import com.limelight.gateway.GatewayConnection;

import java.lang.ref.WeakReference;

/**
 * Single in-process source of truth for a stream retained behind MoonWaker Home.
 * Persistent reconnect data remains owned by SessionResumeManager.
 */
public final class RetainedStreamSessionCoordinator {
    public enum State {
        NONE,
        PREPARING,
        HOME_LIVE,
        PARKED_LIVE,
        RECONNECT_REQUIRED,
        TERMINATING
    }

    public interface Controller {
        boolean isRetainedTransportLive();
        boolean parkRetainedTransport();
        default boolean parkPreparingTransport(Snapshot preparing) { return false; }
        default boolean cancelPreparingTransport(Snapshot preparing) { return false; }
        default boolean preparingHomeFrameSubmitted(Snapshot preparing) { return false; }
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
        public final String profileId;
        public final int appId;
        public final String oldGameId;
        public final String newGameId;
        public final String newGameName;
        public final String streamTargetName;
        public final String artworkPath;
        public final String transitionId;
        public final long attempt;
        public final boolean preparing;
        public final java.util.function.BooleanSupplier cancelled;

        public SwitchRequest(String streamSessionId, String hostId, int appId,
                             String oldGameId, String newGameId, String newGameName,
                             String streamTargetName, String artworkPath,
                             String transitionId, long attempt, boolean preparing,
                             java.util.function.BooleanSupplier cancelled) {
            this(streamSessionId, hostId, GatewayConnection.DEFAULT_PROFILE_ID,
                    appId, oldGameId, newGameId, newGameName, streamTargetName,
                    artworkPath, transitionId, attempt, preparing, cancelled);
        }

        public SwitchRequest(String streamSessionId, String hostId, String profileId, int appId,
                             String oldGameId, String newGameId, String newGameName,
                             String streamTargetName, String artworkPath,
                             String transitionId, long attempt, boolean preparing,
                             java.util.function.BooleanSupplier cancelled) {
            this.streamSessionId = normalize(streamSessionId);
            this.hostId = normalize(hostId);
            this.profileId = GatewayConnection.normalizeProfileId(profileId);
            this.appId = appId;
            this.oldGameId = normalize(oldGameId);
            this.newGameId = normalize(newGameId);
            this.newGameName = normalize(newGameName);
            this.streamTargetName = normalize(streamTargetName);
            this.artworkPath = normalize(artworkPath);
            this.transitionId = normalize(transitionId);
            this.attempt = attempt;
            this.preparing = preparing;
            this.cancelled = cancelled == null ? () -> false : cancelled;
        }
    }

    public enum TerminationResult { STARTED, IN_PROGRESS, NO_CONTROLLER }

    public static final class Snapshot {
        public final State state;
        public final String streamSessionId;
        public final String hostId;
        public final String profileId;
        public final int appId;
        public final String playniteGameId;
        public final String transitionId;
        public final long attempt;

        private Snapshot(State state, String streamSessionId, String hostId, String profileId,
                         int appId,
                         String playniteGameId, String transitionId, long attempt) {
            this.state = state;
            this.streamSessionId = streamSessionId;
            this.hostId = hostId;
            this.profileId = profileId;
            this.appId = appId;
            this.playniteGameId = playniteGameId;
            this.transitionId = transitionId;
            this.attempt = attempt;
        }
    }

    private static State state = State.NONE;
    private static WeakReference<Controller> controller = new WeakReference<>(null);
    private static String streamSessionId = "";
    private static String hostId = "";
    private static String profileId = GatewayConnection.DEFAULT_PROFILE_ID;
    private static int appId;
    private static String playniteGameId = "";
    private static String transitionId = "";
    private static long attempt;
    private static boolean preparingHomeFrameClaimed;
    private static String acceptedHomeTransitionId = "";
    private static long acceptedHomeAttempt;
    private static Controller switchOwner;
    private static String switchSessionId = "";
    private static String switchTransitionId = "";
    private static long switchAttempt;

    private RetainedStreamSessionCoordinator() { }

    public static synchronized void enterHome(Controller owner, String retainedStreamSessionId,
                                              String retainedHostId, int retainedAppId,
                                              String retainedPlayniteGameId) {
        enterHome(owner, retainedStreamSessionId, retainedHostId,
                GatewayConnection.DEFAULT_PROFILE_ID, retainedAppId,
                retainedPlayniteGameId);
    }

    public static synchronized void enterHome(Controller owner, String retainedStreamSessionId,
                                              String retainedHostId, String retainedProfileId,
                                              int retainedAppId,
                                              String retainedPlayniteGameId) {
        if (state == State.PREPARING) return;
        String sessionId = normalize(retainedStreamSessionId);
        if (sessionId.isEmpty()) throw new IllegalArgumentException("Stream session ID is required");
        if (!sessionId.equals(streamSessionId) || controller.get() != owner) {
            switchOwner = null;
            switchSessionId = "";
            switchTransitionId = "";
            switchAttempt = 0L;
        }
        controller = new WeakReference<>(owner);
        streamSessionId = sessionId;
        hostId = retainedHostId == null ? "" : retainedHostId;
        profileId = GatewayConnection.normalizeProfileId(retainedProfileId);
        appId = retainedAppId;
        playniteGameId = retainedPlayniteGameId == null ? "" : retainedPlayniteGameId;
        transitionId = "";
        attempt = 0L;
        preparingHomeFrameClaimed = false;
        acceptedHomeTransitionId = "";
        acceptedHomeAttempt = 0L;
        setStateLocked(State.HOME_LIVE);
    }

    public static synchronized boolean beginPreparing(
            Controller owner, String preparingStreamSessionId, String preparingHostId,
            int preparingAppId, String preparingPlayniteGameId,
            String preparingTransitionId, long preparingAttempt) {
        return beginPreparing(owner, preparingStreamSessionId, preparingHostId,
                GatewayConnection.DEFAULT_PROFILE_ID, preparingAppId,
                preparingPlayniteGameId, preparingTransitionId, preparingAttempt);
    }

    public static synchronized boolean beginPreparing(
            Controller owner, String preparingStreamSessionId, String preparingHostId,
            String preparingProfileId, int preparingAppId, String preparingPlayniteGameId,
            String preparingTransitionId, long preparingAttempt) {
        String sessionId = normalize(preparingStreamSessionId);
        String host = normalize(preparingHostId);
        String profile = GatewayConnection.normalizeProfileId(preparingProfileId);
        String transition = normalize(preparingTransitionId);
        if (owner == null || sessionId.isEmpty() || host.isEmpty()
                || preparingAppId <= 0 || transition.isEmpty() || preparingAttempt <= 0L) {
            return false;
        }
        if (profileId.equals(profile) && matchesPreparing(owner, sessionId, host, preparingAppId,
                transition, preparingAttempt)) return true;
        boolean exactReconnect = state == State.RECONNECT_REQUIRED
                && streamSessionId.equals(sessionId)
                && hostId.equalsIgnoreCase(host)
                && profileId.equals(profile)
                && appId == preparingAppId
                && playniteGameId.equalsIgnoreCase(normalize(preparingPlayniteGameId));
        if (state != State.NONE && !exactReconnect) return false;
        controller = new WeakReference<>(owner);
        streamSessionId = sessionId;
        hostId = host;
        profileId = profile;
        appId = preparingAppId;
        playniteGameId = normalize(preparingPlayniteGameId);
        transitionId = transition;
        attempt = preparingAttempt;
        preparingHomeFrameClaimed = false;
        acceptedHomeTransitionId = "";
        acceptedHomeAttempt = 0L;
        switchOwner = null;
        switchSessionId = "";
        switchTransitionId = "";
        switchAttempt = 0L;
        setStateLocked(State.PREPARING);
        return true;
    }

    public static synchronized boolean isPreparing(
            Controller owner, String expectedStreamSessionId, String expectedHostId,
            int expectedAppId, String expectedTransitionId, long expectedAttempt) {
        return matchesPreparing(owner, expectedStreamSessionId, expectedHostId,
                expectedAppId, expectedTransitionId, expectedAttempt);
    }

    public static synchronized boolean completePreparing(
            Controller owner, String expectedStreamSessionId, String expectedHostId,
            int expectedAppId, String expectedTransitionId, long expectedAttempt) {
        if (!matchesPreparing(owner, expectedStreamSessionId, expectedHostId,
                expectedAppId, expectedTransitionId, expectedAttempt)) return false;
        setStateLocked(State.HOME_LIVE);
        transitionId = "";
        attempt = 0L;
        preparingHomeFrameClaimed = false;
        return true;
    }

    public static synchronized boolean cancelPreparing(
            Controller owner, String expectedStreamSessionId, String expectedHostId,
            int expectedAppId, String expectedTransitionId, long expectedAttempt) {
        if (!matchesPreparing(owner, expectedStreamSessionId, expectedHostId,
                expectedAppId, expectedTransitionId, expectedAttempt)) return false;
        clearLocked();
        return true;
    }

    public static boolean cancelPreparing(Snapshot expected) {
        Controller owner;
        synchronized (RetainedStreamSessionCoordinator.class) {
            if (expected == null || state != State.PREPARING
                    || !streamSessionId.equals(expected.streamSessionId)
                    || !hostId.equalsIgnoreCase(expected.hostId)
                    || !profileId.equals(expected.profileId)
                    || appId != expected.appId
                    || !playniteGameId.equalsIgnoreCase(expected.playniteGameId)
                    || !transitionId.equals(expected.transitionId)
                    || attempt != expected.attempt) {
                return false;
            }
            owner = controller.get();
            if (owner == null) return false;
        }
        return owner.cancelPreparingTransport(expected);
    }

    public static boolean preparingHomeFrameSubmitted(Snapshot expected) {
        Controller owner;
        synchronized (RetainedStreamSessionCoordinator.class) {
            if (!matchesPreparingSnapshot(expected) || preparingHomeFrameClaimed) return false;
            owner = controller.get();
            if (owner == null) return false;
            preparingHomeFrameClaimed = true;
        }
        boolean accepted = owner.preparingHomeFrameSubmitted(expected);
        boolean committed = false;
        synchronized (RetainedStreamSessionCoordinator.class) {
            if (accepted && matchesPreparingIdentity(expected)
                    && controller.get() == owner) {
                acceptedHomeTransitionId = expected.transitionId;
                acceptedHomeAttempt = expected.attempt;
                committed = true;
            } else if (!accepted && matchesPreparingSnapshot(expected)
                    && controller.get() == owner) {
                preparingHomeFrameClaimed = false;
            }
        }
        return committed;
    }

    public static synchronized boolean isPreparingHomeFrameAccepted(
            String expectedStreamSessionId, String expectedHostId, int expectedAppId,
            String expectedGameId, String expectedTransitionId, long expectedAttempt) {
        return (state == State.PREPARING || state == State.HOME_LIVE
                || state == State.PARKED_LIVE)
                && matchesPreparingIdentity(expectedStreamSessionId, expectedHostId,
                expectedAppId, expectedGameId)
                && acceptedHomeTransitionId.equals(normalize(expectedTransitionId))
                && acceptedHomeAttempt == expectedAttempt;
    }

    public static synchronized boolean markPreparingReconnectRequired(
            Controller owner, String expectedStreamSessionId, String expectedHostId,
            int expectedAppId, String expectedTransitionId, long expectedAttempt) {
        if (!matchesPreparing(owner, expectedStreamSessionId, expectedHostId,
                expectedAppId, expectedTransitionId, expectedAttempt)) return false;
        controller.clear();
        switchOwner = null;
        switchSessionId = "";
        switchTransitionId = "";
        switchAttempt = 0L;
        setStateLocked(State.RECONNECT_REQUIRED);
        transitionId = "";
        attempt = 0L;
        preparingHomeFrameClaimed = false;
        acceptedHomeTransitionId = "";
        acceptedHomeAttempt = 0L;
        return true;
    }

    public static synchronized Snapshot snapshot() {
        return new Snapshot(state, streamSessionId, hostId, profileId, appId, playniteGameId,
                transitionId, attempt);
    }

    private static boolean matchesPreparingSnapshot(Snapshot expected) {
        return expected != null && state == State.PREPARING
                && streamSessionId.equals(expected.streamSessionId)
                && hostId.equalsIgnoreCase(expected.hostId)
                && profileId.equals(expected.profileId)
                && appId == expected.appId
                && playniteGameId.equalsIgnoreCase(expected.playniteGameId)
                && transitionId.equals(expected.transitionId)
                && attempt == expected.attempt;
    }

    private static boolean matchesPreparingIdentity(Snapshot expected) {
        return expected != null && matchesPreparingIdentity(expected.streamSessionId,
                expected.hostId, expected.appId, expected.playniteGameId);
    }

    private static boolean matchesPreparingIdentity(String expectedStreamSessionId,
                                                     String expectedHostId,
                                                     int expectedAppId,
                                                     String expectedGameId) {
        return streamSessionId.equals(normalize(expectedStreamSessionId))
                && hostId.equalsIgnoreCase(normalize(expectedHostId))
                && appId == expectedAppId
                && playniteGameId.equalsIgnoreCase(normalize(expectedGameId));
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
        Snapshot preparing = null;
        synchronized (RetainedStreamSessionCoordinator.class) {
            if (!matches(expectedStreamSessionId)) return false;
            if (switchOwner != null) return false;
            if (state == State.PARKED_LIVE) return true;
            if (state == State.PREPARING) {
                owner = controller.get();
                if (owner == null) return false;
                preparing = snapshot();
                capturedId = streamSessionId;
            } else {
                if (state != State.HOME_LIVE) return false;
                capturedId = streamSessionId;
                owner = controller.get();
                if (owner == null) {
                    setStateLocked(State.RECONNECT_REQUIRED);
                    return false;
                }
            }
        }
        if (preparing != null) return owner.parkPreparingTransport(preparing);
        boolean parked = owner.parkRetainedTransport();
        synchronized (RetainedStreamSessionCoordinator.class) {
            if (!matches(capturedId) || state != State.HOME_LIVE) return false;
            setStateLocked(parked ? State.PARKED_LIVE : State.RECONNECT_REQUIRED);
            if (!parked) controller.clear();
        }
        return parked;
    }

    public static synchronized void markParked(String expectedStreamSessionId) {
        if (state == State.PREPARING) return;
        if (!matches(expectedStreamSessionId)) return;
        if (state == State.HOME_LIVE || state == State.PARKED_LIVE) {
            setStateLocked(State.PARKED_LIVE);
        }
    }

    public static synchronized void markReconnectRequired(String expectedStreamSessionId) {
        if (state == State.PREPARING) return;
        if (!matches(expectedStreamSessionId)) return;
        if (state != State.TERMINATING) setStateLocked(State.RECONNECT_REQUIRED);
        controller.clear();
        switchOwner = null;
        switchSessionId = "";
        switchTransitionId = "";
        switchAttempt = 0L;
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
        setStateLocked(State.RECONNECT_REQUIRED);
        return true;
    }

    public static synchronized boolean markTerminating(String expectedStreamSessionId,
                                                       String terminatingHostId,
                                                       int terminatingAppId,
                                                       String terminatingGameId) {
        if (state == State.PREPARING) return false;
        String expected = normalize(expectedStreamSessionId);
        if (expected.isEmpty()
                || (!streamSessionId.isEmpty() && !expected.equals(streamSessionId))) {
            return false;
        }
        controller.clear();
        switchOwner = null;
        switchSessionId = "";
        switchTransitionId = "";
        switchAttempt = 0L;
        streamSessionId = expected;
        hostId = terminatingHostId == null ? "" : terminatingHostId;
        appId = terminatingAppId;
        playniteGameId = terminatingGameId == null ? "" : terminatingGameId;
        transitionId = "";
        attempt = 0L;
        setStateLocked(State.TERMINATING);
        return true;
    }

    public static synchronized boolean canResumeInstantly() {
        return (state == State.HOME_LIVE || state == State.PARKED_LIVE)
                && liveControllerLocked() != null;
    }

    public static synchronized boolean canResumeInstantly(Snapshot expected) {
        return expected != null && state == expected.state
                && (state == State.HOME_LIVE || state == State.PARKED_LIVE)
                && streamSessionId.equals(expected.streamSessionId)
                && hostId.equalsIgnoreCase(expected.hostId)
                && profileId.equals(expected.profileId)
                && appId == expected.appId
                && playniteGameId.equalsIgnoreCase(expected.playniteGameId)
                && liveControllerLocked() != null;
    }

    public static synchronized boolean canSwitchGame(Snapshot expected) {
        return expected != null && state == expected.state
                && streamSessionId.equals(expected.streamSessionId)
                && transitionId.equals(expected.transitionId) && attempt == expected.attempt
                && profileId.equals(expected.profileId)
                && playniteGameId.equalsIgnoreCase(expected.playniteGameId)
                && canSwitchGame(expected.hostId, expected.appId);
    }

    public static synchronized boolean canSwitchGame(String expectedHostId,
                                                      int expectedAppId) {
        return canSwitchGame(expectedHostId, GatewayConnection.DEFAULT_PROFILE_ID,
                expectedAppId);
    }

    public static synchronized boolean canSwitchGame(String expectedHostId,
                                                      String expectedProfileId,
                                                      int expectedAppId) {
        Controller owner = state == State.PREPARING
                ? controller.get() : state == State.HOME_LIVE ? liveControllerLocked() : null;
        return owner != null && switchOwner == null
                && appId == expectedAppId
                && normalize(expectedHostId).equalsIgnoreCase(hostId)
                && GatewayConnection.normalizeProfileId(expectedProfileId).equals(profileId);
    }

    public static synchronized boolean isPreparingSwitchOwned(
            String expectedStreamSessionId, String expectedHostId, int expectedAppId,
            String expectedTransitionId, long expectedAttempt) {
        return (state == State.PREPARING || state == State.HOME_LIVE)
                && switchOwner != null
                && switchSessionId.equals(normalize(expectedStreamSessionId))
                && hostId.equalsIgnoreCase(normalize(expectedHostId))
                && appId == expectedAppId
                && switchTransitionId.equals(normalize(expectedTransitionId))
                && switchAttempt == expectedAttempt;
    }

    public static SwitchResult switchGame(String expectedHostId, int expectedAppId,
                                          String newGameId, String newGameName,
                                          String streamTargetName, String artworkPath,
                                          java.util.function.BooleanSupplier cancelled,
                                          SwitchCallback completion) {
        return switchGame(expectedHostId, GatewayConnection.DEFAULT_PROFILE_ID,
                expectedAppId, newGameId, newGameName, streamTargetName,
                artworkPath, cancelled, completion);
    }

    public static SwitchResult switchGame(String expectedHostId, String expectedProfileId,
                                          int expectedAppId, String newGameId,
                                          String newGameName, String streamTargetName,
                                          String artworkPath,
                                          java.util.function.BooleanSupplier cancelled,
                                          SwitchCallback completion) {
        Controller owner;
        SwitchRequest request;
        synchronized (RetainedStreamSessionCoordinator.class) {
            if (!canSwitchGame(expectedHostId, expectedProfileId, expectedAppId)) {
                return SwitchResult.NOT_ELIGIBLE;
            }
            owner = controller.get();
            request = new SwitchRequest(streamSessionId, hostId, profileId, appId,
                    playniteGameId, newGameId, newGameName, streamTargetName,
                    artworkPath, transitionId, attempt, state == State.PREPARING,
                    cancelled);
            switchOwner = owner;
            switchSessionId = streamSessionId;
            switchTransitionId = transitionId;
            switchAttempt = attempt;
        }
        owner.switchGame(request, (outcome, error) -> {
            boolean current;
            synchronized (RetainedStreamSessionCoordinator.class) {
                current = switchOwner == owner
                        && switchSessionId.equals(request.streamSessionId)
                        && switchTransitionId.equals(request.transitionId)
                        && switchAttempt == request.attempt;
                if (current && outcome != SwitchOutcome.REUSED) {
                    switchOwner = null;
                    switchSessionId = "";
                    switchTransitionId = "";
                    switchAttempt = 0L;
                }
            }
            if (completion != null && (current || !request.preparing)) {
                completion.complete(outcome, error);
            }
        });
        return SwitchResult.STARTED;
    }

    public static synchronized boolean updatePreparingSwitch(
            Controller expectedOwner, SwitchRequest request,
            String expectedCurrentTransitionId, String expectedGameId,
            String newGameId, String newTransitionId) {
        String currentTransition = normalize(expectedCurrentTransitionId);
        String nextTransition = normalize(newTransitionId);
        if (request == null || !request.preparing || nextTransition.isEmpty()
                || switchOwner != expectedOwner
                || !switchSessionId.equals(request.streamSessionId)
                || !switchTransitionId.equals(request.transitionId)
                || switchAttempt != request.attempt
                || !matchesPreparing(expectedOwner, request.streamSessionId,
                request.hostId, request.appId, currentTransition, request.attempt)
                || !playniteGameId.equalsIgnoreCase(normalize(expectedGameId))) {
            return false;
        }
        playniteGameId = normalize(newGameId);
        transitionId = nextTransition;
        return true;
    }

    public static synchronized boolean finishSwitch(String expectedStreamSessionId,
                                                    Controller expectedOwner) {
        if (switchOwner != expectedOwner
                || !switchSessionId.equals(normalize(expectedStreamSessionId))) {
            return false;
        }
        if ((!switchTransitionId.isEmpty() || switchAttempt != 0L)
                && (state != State.HOME_LIVE
                || !transitionId.isEmpty() || attempt != 0L)) {
            return false;
        }
        switchOwner = null;
        switchSessionId = "";
        switchTransitionId = "";
        switchAttempt = 0L;
        return true;
    }

    public static synchronized boolean finishSwitch(String expectedStreamSessionId,
                                                     String expectedTransitionId,
                                                     long expectedAttempt,
                                                     Controller expectedOwner) {
        if (switchOwner != expectedOwner
                || !switchSessionId.equals(normalize(expectedStreamSessionId))
                || !switchTransitionId.equals(normalize(expectedTransitionId))
                || switchAttempt != expectedAttempt) {
            return false;
        }
        switchOwner = null;
        switchSessionId = "";
        switchTransitionId = "";
        switchAttempt = 0L;
        return true;
    }

    public static synchronized boolean updateOwnedSwitchGame(
            Controller expectedOwner, SwitchRequest request,
            String expectedOldGameId, String newGameId) {
        if (request == null || state != State.HOME_LIVE
                || controller.get() != expectedOwner || switchOwner != expectedOwner
                || !switchSessionId.equals(request.streamSessionId)
                || !switchTransitionId.equals(request.transitionId)
                || switchAttempt != request.attempt
                || !matches(request.streamSessionId) || appId != request.appId
                || !hostId.equalsIgnoreCase(request.hostId)
                || (!playniteGameId.equalsIgnoreCase(normalize(expectedOldGameId))
                && !playniteGameId.equalsIgnoreCase(normalize(newGameId)))) return false;
        playniteGameId = normalize(newGameId);
        return true;
    }

    public static synchronized boolean updateGameIfMatches(
            String expectedStreamSessionId, String expectedHostId, int expectedAppId,
            String expectedOldGameId, String newGameId) {
        if (state != State.HOME_LIVE || switchOwner != null
                || !matches(expectedStreamSessionId)
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
            setStateLocked(State.HOME_LIVE);
            return true;
        }
        if ((state == State.HOME_LIVE || state == State.PARKED_LIVE)
                && controller.get() == expectedOwner && switchOwner == null
                && matches(sessionId) && appId == expectedAppId
                && hostId.equalsIgnoreCase(expectedHost) && playniteGameId.isEmpty()) {
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
            if (state == State.PREPARING) return TerminationResult.NO_CONTROLLER;
            if (state == State.TERMINATING) return TerminationResult.IN_PROGRESS;
            capturedId = streamSessionId;
            previousState = state;
            owner = liveControllerLocked();
            controller.clear();
            if (owner == null) {
                return TerminationResult.NO_CONTROLLER;
            }
            setStateLocked(State.TERMINATING);
        }
        owner.terminateRetainedSession(success -> {
            boolean current;
            synchronized (RetainedStreamSessionCoordinator.class) {
                current = matches(capturedId) && state == State.TERMINATING;
                if (current) {
                    if (success) {
                        clearLocked();
                    } else {
                        setStateLocked(previousState);
                        controller = new WeakReference<>(owner);
                    }
                }
            }
            if (current && completion != null) completion.complete(success);
        });
        return TerminationResult.STARTED;
    }

    public static synchronized boolean clearIfMatches(String expectedStreamSessionId) {
        if (state == State.PREPARING) return false;
        if (!matches(expectedStreamSessionId)) return false;
        clearLocked();
        return true;
    }

    public static synchronized boolean hardResetIfHostMatches(String expectedHostId) {
        String expected = normalize(expectedHostId);
        if (expected.isEmpty() || !expected.equalsIgnoreCase(hostId)) return false;
        clearLocked();
        return true;
    }

    static synchronized void clear() {
        clearLocked();
    }

    private static void clearLocked() {
        setStateLocked(State.NONE);
        controller.clear();
        streamSessionId = "";
        hostId = "";
        profileId = GatewayConnection.DEFAULT_PROFILE_ID;
        appId = 0;
        playniteGameId = "";
        transitionId = "";
        attempt = 0L;
        preparingHomeFrameClaimed = false;
        acceptedHomeTransitionId = "";
        acceptedHomeAttempt = 0L;
        switchOwner = null;
        switchSessionId = "";
        switchTransitionId = "";
        switchAttempt = 0L;
    }

    private static boolean matches(String expectedStreamSessionId) {
        String expected = normalize(expectedStreamSessionId);
        return !expected.isEmpty() && expected.equals(streamSessionId);
    }

    private static Controller liveControllerLocked() {
        Controller owner = controller.get();
        if (owner != null && owner.isRetainedTransportLive()) return owner;
        if (state == State.HOME_LIVE || state == State.PARKED_LIVE) {
            setStateLocked(State.RECONNECT_REQUIRED);
            controller.clear();
            switchOwner = null;
            switchSessionId = "";
            switchTransitionId = "";
            switchAttempt = 0L;
        }
        return null;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim();
    }

    private static boolean matchesPreparing(
            Controller expectedOwner, String expectedStreamSessionId,
            String expectedHostId, int expectedAppId,
            String expectedTransitionId, long expectedAttempt) {
        return state == State.PREPARING
                && controller.get() == expectedOwner
                && matches(expectedStreamSessionId)
                && hostId.equalsIgnoreCase(normalize(expectedHostId))
                && appId == expectedAppId
                && transitionId.equals(normalize(expectedTransitionId))
                && attempt == expectedAttempt;
    }

    private static void setStateLocked(State next) {
        if (state == next) return;
        State previous = state;
        state = next;
        MoonWakerDiagnostics.record("INFO", "android.retained-stream",
                "retained_stream.state_changed",
                "from", previous.name(),
                "to", next.name(),
                "state", next.name(),
                "stream_session_id", streamSessionId,
                "host_id", hostId,
                "profile_id", profileId,
                "app_id", appId,
                "game_id", playniteGameId,
                "transition_id", transitionId,
                "attempt", attempt);
    }
}
