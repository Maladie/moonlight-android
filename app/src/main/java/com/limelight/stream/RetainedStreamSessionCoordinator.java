package com.limelight.stream;

import com.limelight.diagnostics.MoonWakerDiagnostics;
import com.limelight.gateway.GatewayConnection;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;

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
        default String getExecutionProfileId() { return null; }
        boolean parkRetainedTransport();
        default boolean parkPreparingTransport(Snapshot preparing) { return false; }
        default boolean cancelPreparingTransport(Snapshot preparing) { return false; }
        default boolean preparingHomeFrameSubmitted(Snapshot preparing) { return false; }
        void disconnectRetainedStream(TerminationCallback completion);
        void terminateRetainedSession(TerminationCallback completion);
        void switchGame(SwitchRequest request, SwitchCallback completion);
    }

    public interface TerminationCallback {
        void complete(boolean success);
        default void transportUnsafe() { }
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
        public final String executionProfileId;
        public final String targetProfileId;
        public final boolean targetIsChild;
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
            this(streamSessionId, hostId, profileId, profileId, profileId, false,
                    appId, oldGameId, newGameId, newGameName, streamTargetName, artworkPath,
                    transitionId, attempt, preparing, cancelled);
        }

        public SwitchRequest(String streamSessionId, String hostId, String profileId,
                             String executionProfileId, String targetProfileId, boolean targetIsChild,
                             int appId, String oldGameId, String newGameId, String newGameName,
                             String streamTargetName, String artworkPath, String transitionId,
                             long attempt, boolean preparing,
                             java.util.function.BooleanSupplier cancelled) {
            this.streamSessionId = normalize(streamSessionId);
            this.hostId = normalize(hostId);
            this.profileId = GatewayConnection.normalizeProfileId(profileId);
            this.executionProfileId = GatewayConnection.normalizeProfileId(executionProfileId);
            this.targetProfileId = GatewayConnection.normalizeProfileId(targetProfileId);
            this.targetIsChild = targetIsChild;
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

    public static final class DirectTermination {
        private final TerminationOperation operation;

        private DirectTermination(TerminationOperation operation) {
            this.operation = operation;
        }
    }

    public static final class Snapshot {
        public final State state;
        public final String streamSessionId;
        public final String hostId;
        public final String profileId;
        public final String executionProfileId;
        public final int appId;
        public final String playniteGameId;
        public final String transitionId;
        public final long attempt;
        public final long controlGeneration;
        public final long transportGeneration;
        /** Advances each time the session enters or leaves TERMINATING, seen or not. */
        public final long terminationEpoch;

        private Snapshot(State state, String streamSessionId, String hostId, String profileId,
                         int appId,
                         String playniteGameId, String transitionId, long attempt) {
            this.state = state;
            this.streamSessionId = streamSessionId;
            this.hostId = hostId;
            this.profileId = profileId;
            this.executionProfileId = RetainedStreamSessionCoordinator.executionProfileId;
            this.appId = appId;
            this.playniteGameId = playniteGameId;
            this.transitionId = transitionId;
            this.attempt = attempt;
            this.controlGeneration = RetainedStreamSessionCoordinator.controlGeneration;
            this.transportGeneration = RetainedStreamSessionCoordinator.transportGeneration;
            this.terminationEpoch = RetainedStreamSessionCoordinator.terminationEpoch;
        }
    }

    /** One-shot proof that the host and retained transport accepted an exact actor handoff. */
    public static final class ProviderTransferAuthorization {
        public final String previousTransitionId;
        public final String replacementTransitionId;
        public final String streamSessionId;
        public final String hostId;
        public final String previousProfileId;
        public final String profileId;
        public final String executionProfileId;
        public final int appId;
        public final String playniteGameId;
        public final String processToken;
        public final int processId;
        public final long previousGeneration;
        public final long generation;
        public final long actorLeaseEpoch;
        private final Controller owner;
        private boolean consumed;

        private ProviderTransferAuthorization(
                Controller owner, String previousTransitionId, String replacementTransitionId,
                String streamSessionId, String hostId, String previousProfileId,
                String profileId, String executionProfileId, int appId,
                String playniteGameId, String processToken, int processId,
                long previousGeneration, long generation, long actorLeaseEpoch) {
            this.owner = owner;
            this.previousTransitionId = normalize(previousTransitionId);
            this.replacementTransitionId = normalize(replacementTransitionId);
            this.streamSessionId = normalize(streamSessionId);
            this.hostId = normalize(hostId);
            this.previousProfileId = GatewayConnection.normalizeProfileId(previousProfileId);
            this.profileId = GatewayConnection.normalizeProfileId(profileId);
            this.executionProfileId = GatewayConnection.normalizeProfileId(executionProfileId);
            this.appId = appId;
            this.playniteGameId = normalize(playniteGameId);
            this.processToken = normalize(processToken);
            this.processId = processId;
            this.previousGeneration = previousGeneration;
            this.generation = generation;
            this.actorLeaseEpoch = actorLeaseEpoch;
        }
    }

    private static State state = State.NONE;
    private static long terminationEpoch;
    private static WeakReference<Controller> controller = new WeakReference<>(null);
    private static String streamSessionId = "";
    private static String hostId = "";
    private static String profileId = GatewayConnection.DEFAULT_PROFILE_ID;
    private static String executionProfileId = GatewayConnection.DEFAULT_PROFILE_ID;
    private static int appId;
    private static String playniteGameId = "";
    private static String transitionId = "";
    private static long attempt;
    private static long controlGeneration;
    private static long transportGeneration;
    private static long nextTransportGeneration;
    private static boolean preparingHomeFrameClaimed;
    private static String acceptedHomeTransitionId = "";
    private static long acceptedHomeAttempt;
    private static Controller switchOwner;
    private static String switchSessionId = "";
    private static String switchTransitionId = "";
    private static long switchAttempt;
    private static TerminationOperation terminationOperation;

    private static final class TerminationOperation {
        final String streamSessionId;
        final boolean terminateGame;
        final boolean direct;
        final State previousState;
        final Controller previousOwner;
        boolean transportUnsafe;
        final List<TerminationCallback> completions = new ArrayList<>();

        TerminationOperation(String streamSessionId, boolean terminateGame, boolean direct,
                             State previousState, Controller previousOwner,
                             TerminationCallback completion) {
            this.streamSessionId = streamSessionId;
            this.terminateGame = terminateGame;
            this.direct = direct;
            this.previousState = previousState;
            this.previousOwner = previousOwner;
            if (completion != null) completions.add(completion);
        }
    }

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
        establishTransportGenerationLocked(owner, sessionId);
        if (!sessionId.equals(streamSessionId)) controlGeneration = 0L;
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
        executionProfileId = origin(owner, profileId);
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
        if (!exactReconnect) establishTransportGenerationLocked(owner, sessionId);
        if (!sessionId.equals(streamSessionId)) controlGeneration = 0L;
        controller = new WeakReference<>(owner);
        streamSessionId = sessionId;
        hostId = host;
        profileId = profile;
        executionProfileId = origin(owner, profileId);
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

    public static synchronized boolean bindControlGeneration(
            Controller expectedOwner, String expectedStreamSessionId, long generation) {
        if (generation <= 0L || controller.get() != expectedOwner
                || !matches(expectedStreamSessionId)) return false;
        controlGeneration = generation;
        return true;
    }

    /** Atomically publishes the same host-authorized actor/generation to both local owners. */
    public static synchronized boolean commitReauthorization(
            Controller owner, String sessionId, String origin, String actor,
            long expectedGeneration, long generation,
            java.util.function.BooleanSupplier commitConnection) {
        if (controller.get() != owner || !matches(sessionId)
                || (state != State.PREPARING && state != State.HOME_LIVE && state != State.PARKED_LIVE)
                || !executionProfileId.equals(GatewayConnection.normalizeProfileId(origin))
                || expectedGeneration <= 0L || controlGeneration != expectedGeneration
                || generation <= expectedGeneration || !commitConnection.getAsBoolean()) return false;
        profileId = GatewayConnection.normalizeProfileId(actor);
        controlGeneration = generation;
        return true;
    }

    /** Commits reauthorization and returns exact, one-shot provider ownership proof. */
    public static synchronized ProviderTransferAuthorization commitProviderTransferReauthorization(
            Controller owner, String sessionId, String origin, String actor,
            String previousTransitionId, String replacementTransitionId,
            String expectedHostId, int expectedAppId, String expectedGameId,
            String expectedProcessToken, int expectedProcessId,
            long expectedGeneration, long generation, long actorLeaseEpoch,
            java.util.function.BooleanSupplier commitConnection) {
        String previousActor = profileId;
        String process = normalize(expectedProcessToken);
        if (process.isEmpty() || expectedProcessId <= 0 || actorLeaseEpoch <= 0L
                || normalize(previousTransitionId).isEmpty()
                || normalize(replacementTransitionId).isEmpty()
                || !hostId.equalsIgnoreCase(normalize(expectedHostId))
                || appId != expectedAppId
                || !playniteGameId.equalsIgnoreCase(normalize(expectedGameId))
                || !commitReauthorization(owner, sessionId, origin, actor,
                expectedGeneration, generation, commitConnection)) return null;
        return new ProviderTransferAuthorization(owner, previousTransitionId,
                replacementTransitionId, streamSessionId, hostId, previousActor, profileId,
                executionProfileId, appId, playniteGameId, process, expectedProcessId,
                expectedGeneration, generation, actorLeaseEpoch);
    }

    /** Consumes provider proof only while every retained binding is still exact. */
    public static synchronized boolean consumeProviderTransferAuthorization(
            ProviderTransferAuthorization authorization) {
        if (authorization == null || authorization.consumed
                || controller.get() != authorization.owner
                || !matches(authorization.streamSessionId)
                || (state != State.PREPARING && state != State.HOME_LIVE
                && state != State.PARKED_LIVE)
                || !hostId.equalsIgnoreCase(authorization.hostId)
                || !profileId.equals(authorization.profileId)
                || !executionProfileId.equals(authorization.executionProfileId)
                || appId != authorization.appId
                || !playniteGameId.equalsIgnoreCase(authorization.playniteGameId)
                || controlGeneration != authorization.generation
                || authorization.previousGeneration <= 0L
                || authorization.generation <= authorization.previousGeneration
                || authorization.actorLeaseEpoch <= 0L
                || authorization.processId <= 0) return false;
        authorization.consumed = true;
        return true;
    }

    /** A failed local handoff must not advertise the obsolete generation as reusable. */
    public static synchronized boolean discardReauthorization(
            Controller owner, String sessionId, long previousGeneration, long generation) {
        if (state == State.TERMINATING || controller.get() != owner || !matches(sessionId)
                || (controlGeneration != previousGeneration && controlGeneration != generation)) return false;
        clearLocked();
        return true;
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
        State previousState = state;
        Controller previousOwner = controller.get();
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
        if (terminationOperation == null) {
            terminationOperation = new TerminationOperation(
                    expected, true, true, previousState, previousOwner, null);
        }
        return true;
    }

    public static synchronized DirectTermination beginDirectTermination(
            String expectedStreamSessionId, String terminatingHostId,
            int terminatingAppId, String terminatingGameId) {
        TerminationOperation previous = terminationOperation;
        if (!markTerminating(expectedStreamSessionId, terminatingHostId,
                terminatingAppId, terminatingGameId)
                || terminationOperation == previous
                || terminationOperation == null || !terminationOperation.direct) {
            return null;
        }
        return new DirectTermination(terminationOperation);
    }

    /** Begins recovery only if every captured local ownership fence is still current. */
    public static synchronized DirectTermination beginDirectTermination(Snapshot expected) {
        if (expected == null || state != expected.state
                || (state != State.HOME_LIVE && state != State.PARKED_LIVE
                && state != State.RECONNECT_REQUIRED)
                || !streamSessionId.equals(expected.streamSessionId)
                || !hostId.equalsIgnoreCase(expected.hostId)
                || !profileId.equals(expected.profileId)
                || !executionProfileId.equals(expected.executionProfileId)
                || appId != expected.appId
                || !playniteGameId.equalsIgnoreCase(expected.playniteGameId)
                || !transitionId.equals(expected.transitionId)
                || attempt != expected.attempt
                || controlGeneration != expected.controlGeneration
                || transportGeneration != expected.transportGeneration) return null;
        return beginDirectTermination(expected.streamSessionId, expected.hostId,
                expected.appId, expected.playniteGameId);
    }

    public static synchronized DirectTermination beginDirectTermination(
            Controller expectedOwner, String expectedStreamSessionId,
            String terminatingHostId, int terminatingAppId, String terminatingGameId) {
        if (state != State.PREPARING) {
            return beginDirectTermination(expectedStreamSessionId, terminatingHostId,
                    terminatingAppId, terminatingGameId);
        }
        String expected = normalize(expectedStreamSessionId);
        if (expectedOwner == null || controller.get() != expectedOwner
                || !streamSessionId.equals(expected)
                || !hostId.equalsIgnoreCase(normalize(terminatingHostId))
                || appId != terminatingAppId
                || !playniteGameId.equalsIgnoreCase(normalize(terminatingGameId))) {
            return null;
        }
        Controller previousOwner = controller.get();
        controller.clear();
        switchOwner = null;
        switchSessionId = "";
        switchTransitionId = "";
        switchAttempt = 0L;
        transitionId = "";
        attempt = 0L;
        setStateLocked(State.TERMINATING);
        terminationOperation = new TerminationOperation(
                expected, true, true, State.PREPARING, previousOwner, null);
        return new DirectTermination(terminationOperation);
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
                && executionProfileId.equals(expected.executionProfileId)
                && playniteGameId.equalsIgnoreCase(expected.playniteGameId)
                && canSwitchGame(expected.hostId, expected.profileId, expected.appId);
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
                ? controller.get() : (state == State.HOME_LIVE || state == State.PARKED_LIVE) ? liveControllerLocked() : null;
        return owner != null && switchOwner == null
                && appId == expectedAppId
                && normalize(expectedHostId).equalsIgnoreCase(hostId)
                && GatewayConnection.normalizeProfileId(expectedProfileId).equals(profileId);
    }

    private static String origin(Controller owner, String actor) {
        String value = owner == null ? null : owner.getExecutionProfileId();
        return GatewayConnection.normalizeProfileId(value == null ? actor : value);
    }

    public static synchronized boolean canSwitchOrigin(String host, String origin, int targetAppId) {
        return executionProfileId.equals(GatewayConnection.normalizeProfileId(origin))
                && canSwitchGame(host, profileId, targetAppId);
    }

    public static synchronized boolean bindActor(Controller owner, String sessionId,
                                                  String actor, String origin) {
        if (controller.get() != owner || !streamSessionId.equals(sessionId)
                || !executionProfileId.equals(GatewayConnection.normalizeProfileId(origin))) return false;
        profileId = GatewayConnection.normalizeProfileId(actor);
        return true;
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
        return switchGame(expectedHostId, expectedProfileId, expectedProfileId, false,
                expectedAppId, newGameId, newGameName, streamTargetName, artworkPath,
                cancelled, completion);
    }

    public static SwitchResult switchGame(String expectedHostId, String expectedExecutionProfileId,
                                          String targetProfileId, boolean targetIsChild,
                                          int expectedAppId, String newGameId, String newGameName,
                                          String streamTargetName, String artworkPath,
                                          java.util.function.BooleanSupplier cancelled,
                                          SwitchCallback completion) {
        Controller owner;
        SwitchRequest request;
        synchronized (RetainedStreamSessionCoordinator.class) {
            if (!canSwitchOrigin(expectedHostId, expectedExecutionProfileId, expectedAppId)) {
                return SwitchResult.NOT_ELIGIBLE;
            }
            owner = controller.get();
            request = new SwitchRequest(streamSessionId, hostId, profileId,
                    executionProfileId, targetProfileId, targetIsChild, appId,
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
        if (request == null || (state != State.HOME_LIVE && state != State.PARKED_LIVE)
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

    /** Confirms every retained binding immediately before an external switch mutation. */
    public static synchronized boolean ownsSwitchBeforeMutation(
            Controller expectedOwner, SwitchRequest request,
            long expectedControlGeneration) {
        return request != null && !request.preparing
                && expectedControlGeneration > 0L
                && (state == State.HOME_LIVE || state == State.PARKED_LIVE)
                && controller.get() == expectedOwner && switchOwner == expectedOwner
                && streamSessionId.equals(request.streamSessionId)
                && switchSessionId.equals(request.streamSessionId)
                && hostId.equalsIgnoreCase(request.hostId)
                && profileId.equals(request.profileId)
                && executionProfileId.equals(request.executionProfileId)
                && appId == request.appId
                && playniteGameId.equalsIgnoreCase(request.oldGameId)
                && transitionId.equals(request.transitionId)
                && attempt == request.attempt
                && switchTransitionId.equals(request.transitionId)
                && switchAttempt == request.attempt
                && controlGeneration == expectedControlGeneration;
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
            String expectedHostId, int expectedAppId) {
        return (state == State.HOME_LIVE || state == State.PARKED_LIVE)
                && controller.get() == expectedOwner
                && switchOwner == null && matches(expectedStreamSessionId)
                && appId == expectedAppId
                && hostId.equalsIgnoreCase(normalize(expectedHostId));
    }

    public static synchronized boolean ownsLiveOrParkedSession(
            Controller expectedOwner, String expectedStreamSessionId,
            String expectedHostId, int expectedAppId, String expectedGameId) {
        return ownsLiveOrParkedSession(expectedOwner, expectedStreamSessionId,
                expectedHostId, expectedAppId)
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

    public static synchronized boolean markTransportUnsafe(
            Controller expectedOwner, String expectedStreamSessionId,
            String expectedHostId, int expectedAppId) {
        if (expectedOwner == null || controller.get() != expectedOwner
                || !matches(expectedStreamSessionId)
                || appId != expectedAppId
                || !hostId.equalsIgnoreCase(normalize(expectedHostId))) return false;
        setStateLocked(State.RECONNECT_REQUIRED);
        controller.clear();
        switchOwner = null;
        switchSessionId = "";
        switchTransitionId = "";
        switchAttempt = 0L;
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
            establishTransportGenerationLocked(expectedOwner, sessionId);
            streamSessionId = sessionId;
            hostId = expectedHost;
            executionProfileId = origin(expectedOwner, profileId);
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
        return finish(expectedStreamSessionId, completion, true);
    }

    public static TerminationResult disconnect(String expectedStreamSessionId,
                                               TerminationCallback completion) {
        return finish(expectedStreamSessionId, completion, false);
    }

    private static TerminationResult finish(String expectedStreamSessionId,
                                            TerminationCallback completion,
                                            boolean terminateGame) {
        Controller owner = null;
        String capturedId = null;
        State previousState = null;
        TerminationOperation operation = null;
        boolean conflictingOperation = false;
        synchronized (RetainedStreamSessionCoordinator.class) {
            if (!matches(expectedStreamSessionId)) return TerminationResult.NO_CONTROLLER;
            if (state == State.PREPARING) return TerminationResult.NO_CONTROLLER;
            if (state == State.TERMINATING) {
                if (terminationOperation != null
                        && terminationOperation.streamSessionId.equals(streamSessionId)
                        && terminationOperation.terminateGame == terminateGame
                        && completion != null) {
                    terminationOperation.completions.add(completion);
                } else if (terminationOperation != null
                        && terminationOperation.terminateGame != terminateGame) {
                    conflictingOperation = true;
                }
                if (!conflictingOperation) return TerminationResult.IN_PROGRESS;
            } else {
                capturedId = streamSessionId;
                previousState = state;
                owner = liveControllerLocked();
                controller.clear();
                if (owner == null) {
                    return TerminationResult.NO_CONTROLLER;
                }
                setStateLocked(State.TERMINATING);
                operation = new TerminationOperation(
                        capturedId, terminateGame, false,
                        previousState, owner, completion);
                terminationOperation = operation;
            }
        }
        if (conflictingOperation) {
            if (completion != null) completion.complete(false);
            return TerminationResult.IN_PROGRESS;
        }
        Controller capturedOwner = owner;
        String sessionId = capturedId;
        State restoreState = previousState;
        TerminationOperation capturedOperation = operation;
        TerminationCallback finished = new TerminationCallback() {
            @Override public void transportUnsafe() {
                synchronized (RetainedStreamSessionCoordinator.class) {
                    if (terminationOperation == capturedOperation) {
                        capturedOperation.transportUnsafe = true;
                    }
                }
            }

            @Override public void complete(boolean success) {
                boolean current;
                List<TerminationCallback> completions;
                synchronized (RetainedStreamSessionCoordinator.class) {
                    current = terminationOperation == capturedOperation
                            && matches(sessionId) && state == State.TERMINATING;
                    if (current) {
                        if (success) {
                            clearLocked();
                        } else if (capturedOperation.transportUnsafe) {
                            setStateLocked(State.RECONNECT_REQUIRED);
                            controller.clear();
                        } else {
                            setStateLocked(restoreState);
                            controller = new WeakReference<>(capturedOwner);
                        }
                    }
                    if (terminationOperation == capturedOperation) {
                        terminationOperation = null;
                    }
                    completions = new ArrayList<>(capturedOperation.completions);
                    capturedOperation.completions.clear();
                }
                boolean result = current && success;
                for (TerminationCallback waiting : completions) {
                    try {
                        waiting.complete(result);
                    } catch (RuntimeException ignored) {
                        // Every caller waiting on this operation receives its terminal result.
                    }
                }
            }
        };
        try {
            if (terminateGame) capturedOwner.terminateRetainedSession(finished);
            else capturedOwner.disconnectRetainedStream(finished);
        } catch (RuntimeException error) {
            finished.complete(false);
        }
        return TerminationResult.STARTED;
    }

    public static boolean completeDirectTermination(
            DirectTermination token, boolean success) {
        return completeDirectTermination(token, success, false);
    }

    public static void completeDirectTransportStop(
            DirectTermination token, TransportStopCoordinator.Result result) {
        completeDirectTermination(token,
                result == TransportStopCoordinator.Result.STOPPED,
                result != TransportStopCoordinator.Result.STOPPED);
    }

    public static void abandonDirectTermination(DirectTermination token) {
        completeDirectTermination(token, false, true);
    }

    private static boolean completeDirectTermination(
            DirectTermination token, boolean success, boolean transportUnsafe) {
        List<TerminationCallback> completions;
        synchronized (RetainedStreamSessionCoordinator.class) {
            if (token == null || terminationOperation != token.operation
                    || !terminationOperation.direct
                    || state != State.TERMINATING
                    || !terminationOperation.streamSessionId.equals(streamSessionId)) {
                return false;
            }
            TerminationOperation operation = terminationOperation;
            terminationOperation = null;
            completions = new ArrayList<>(operation.completions);
            operation.completions.clear();
            if (success || operation.previousState == State.NONE) {
                clearLocked();
            } else if (transportUnsafe) {
                setStateLocked(State.RECONNECT_REQUIRED);
                controller.clear();
            } else {
                setStateLocked(operation.previousState);
                controller = new WeakReference<>(operation.previousOwner);
            }
        }
        for (TerminationCallback completion : completions) {
            try {
                completion.complete(success);
            } catch (RuntimeException ignored) {
                // Every compatible direct-operation joiner receives the terminal result.
            }
        }
        return true;
    }

    public static synchronized boolean clearIfMatches(String expectedStreamSessionId) {
        if (state == State.PREPARING) return false;
        if (!matches(expectedStreamSessionId)) return false;
        clearLocked();
        return true;
    }

    public static synchronized boolean clearAfterAuthoritativeEndIfMatches(
            String expectedStreamSessionId) {
        if (state == State.PREPARING || state == State.TERMINATING) return false;
        if (!matches(expectedStreamSessionId)) return false;
        clearLocked();
        return true;
    }

    public static boolean hardResetIfHostMatches(String expectedHostId) {
        Controller owner;
        Snapshot preparing;
        synchronized (RetainedStreamSessionCoordinator.class) {
            String expected = normalize(expectedHostId);
            if (expected.isEmpty() || !expected.equalsIgnoreCase(hostId)) return false;
            preparing = state == State.PREPARING ? snapshot() : null;
            owner = preparing != null ? controller.get() : liveControllerLocked();
            clearLocked();
        }
        if (owner != null) {
            try {
                if (preparing != null) owner.cancelPreparingTransport(preparing);
                else owner.disconnectRetainedStream(success -> { });
            } catch (RuntimeException ignored) {
                // The authoritative local reset is already complete.
            }
        }
        return true;
    }

    static synchronized void clear() {
        clearLocked();
    }

    private static void clearLocked() {
        setStateLocked(State.NONE);
        terminationOperation = null;
        controller.clear();
        streamSessionId = "";
        hostId = "";
        profileId = GatewayConnection.DEFAULT_PROFILE_ID;
        executionProfileId = GatewayConnection.DEFAULT_PROFILE_ID;
        appId = 0;
        playniteGameId = "";
        transitionId = "";
        attempt = 0L;
        controlGeneration = 0L;
        transportGeneration = 0L;
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

    private static void establishTransportGenerationLocked(Controller owner,
                                                            String sessionId) {
        if (transportGeneration > 0L && streamSessionId.equals(sessionId)
                && controller.get() == owner) return;
        if (nextTransportGeneration == Long.MAX_VALUE) nextTransportGeneration = 0L;
        transportGeneration = ++nextTransportGeneration;
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

    /**
     * Runs {@code body} while no state transition can happen, so a caller can validate a
     * snapshot and act on it as one step. The body must not wait for another lock that a
     * coordinator caller may hold.
     */
    public static synchronized <T> T underStateLock(java.util.function.Supplier<T> body) {
        return body.get();
    }

    private static void setStateLocked(State next) {
        if (state == next) return;
        State previous = state;
        state = next;
        if (previous == State.TERMINATING || next == State.TERMINATING) terminationEpoch++;
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
