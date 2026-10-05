package com.limelight.console;

import com.limelight.gateway.GatewayConnection;

import java.util.Locale;
import java.util.Objects;

/** Immutable projection of already-owned session facts for one host. */
final class SessionSnapshot {
    enum ObservationState { PENDING, READY, IDLE, RECOVERY_REQUIRED, UNAVAILABLE }
    enum State {
        NONE,
        PREPARING,
        ACTIVE,
        SUSPENDED,
        RECONNECT_REQUIRED,
        TERMINATING,
        UNCERTAIN
    }

    final HostProfileKey profileKey;
    final String hostId;
    final String profileId;
    final String executionProfileId;
    final State state;
    final int hostGameAppId;
    final String playniteGameId;
    final boolean retainedTransport;
    final boolean explicitSuspension;
    final boolean reconnectRequired;
    final boolean resumeAvailable;
    final boolean neutralStreamTarget;
    final boolean suspensionSleepObserved;
    final boolean hostSleepRequested;
    final boolean hostSleepObserved;
    final String suspendId;
    final ObservationState observationState;
    final boolean observationCorrelated;
    final String producerBootId;
    final long producerRevision;
    final long requestOrdinal;
    final long transportGeneration;
    final long controlGeneration;
    final long actorLeaseEpoch;
    final String operationId;
    final String transitionId;
    final String streamSessionId;
    final String processEvidence;
    final int processId;
    final String processToken;
    final String windowEvidence;
    final String observationReason;
    /**
     * Sunshine's running app from the host poll this projection was given; 0 when none or not
     * supplied (gateway-path projections carry no poll). Not part of the projection identity
     * ({@link #equals}, {@link #signature}), so a gateway projection still compares equal.
     */
    final int hostRunningAppId;

    SessionSnapshot(String hostId, String profileId, String executionProfileId,
                    State state, int hostGameAppId, String playniteGameId,
                    boolean retainedTransport, boolean explicitSuspension,
                    boolean suspensionSleepObserved, boolean hostSleepRequested,
                    boolean hostSleepObserved, String suspendId,
                    boolean neutralStreamTarget, ObservationState observationState,
                    boolean observationCorrelated,
                    String producerBootId, long producerRevision, long requestOrdinal,
                    long transportGeneration, long controlGeneration,
                    long actorLeaseEpoch, String operationId, String transitionId,
                    String streamSessionId, String processEvidence, int processId,
                    String processToken, String windowEvidence, String observationReason,
                    int hostRunningAppId) {
        this.hostId = normalize(hostId);
        this.profileId = GatewayConnection.normalizeProfileId(profileId);
        this.executionProfileId = GatewayConnection.normalizeProfileId(executionProfileId);
        this.profileKey = this.hostId.isEmpty() ? null
                : new HostProfileKey(this.hostId, this.profileId);
        this.state = state;
        this.hostGameAppId = hostGameAppId;
        this.playniteGameId = normalize(playniteGameId);
        this.retainedTransport = retainedTransport;
        this.explicitSuspension = explicitSuspension;
        this.reconnectRequired = state == State.RECONNECT_REQUIRED;
        this.neutralStreamTarget = neutralStreamTarget;
        this.resumeAvailable = (state == State.ACTIVE || state == State.SUSPENDED
                || state == State.RECONNECT_REQUIRED)
                && !(neutralStreamTarget && this.playniteGameId.isEmpty());
        this.suspensionSleepObserved = suspensionSleepObserved;
        this.hostSleepRequested = hostSleepRequested;
        this.hostSleepObserved = hostSleepObserved;
        this.suspendId = suspendId == null ? "" : suspendId.trim();
        this.observationState = observationState;
        this.observationCorrelated = observationCorrelated;
        this.producerBootId = producerBootId == null ? "" : producerBootId;
        this.producerRevision = producerRevision;
        this.requestOrdinal = requestOrdinal;
        this.transportGeneration = transportGeneration;
        this.controlGeneration = controlGeneration;
        this.actorLeaseEpoch = actorLeaseEpoch;
        this.operationId = operationId == null ? "" : operationId;
        this.transitionId = transitionId == null ? "" : transitionId;
        this.streamSessionId = streamSessionId == null ? "" : streamSessionId;
        this.processEvidence = processEvidence == null ? "UNAVAILABLE" : processEvidence;
        this.processId = processId;
        this.processToken = processToken == null ? "" : processToken;
        this.windowEvidence = windowEvidence == null ? "UNAVAILABLE" : windowEvidence;
        this.observationReason = observationReason == null ? "" : observationReason;
        this.hostRunningAppId = hostRunningAppId;
    }

    static SessionSnapshot pending(String hostId, String actorProfileId,
                                   String executionProfileId) {
        return new SessionSnapshot(hostId, actorProfileId, executionProfileId,
                State.UNCERTAIN, 0, "", false, false, false,
                false, false, "", false, ObservationState.PENDING, false,
                "", 0L, 0L, 0L, 0L, 0L, "", "", "",
                "UNAVAILABLE", 0, "", "UNAVAILABLE", "observation_pending", 0);
    }

    boolean hasActiveSession() { return state == State.ACTIVE; }
    boolean matches(String gameId, Integer appId) {
        if (!resumeAvailable) return false;
        if (!playniteGameId.isEmpty()) return playniteGameId.equals(normalize(gameId));
        return hostGameAppId != 0 && appId != null && appId == hostGameAppId;
    }
    boolean isSuspended() { return state == State.SUSPENDED; }
    boolean isReconnectRequired() { return reconnectRequired; }
    boolean isResumeAvailable() { return resumeAvailable; }

    String signature() {
        return state + "|" + hostId + "|" + profileId + "|" + executionProfileId
                + "|" + hostGameAppId + "|" + playniteGameId
                + "|" + retainedTransport + "|" + explicitSuspension + "|"
                + suspensionSleepObserved + "|" + hostSleepRequested + "|"
                + hostSleepObserved + "|" + suspendId + "|" + neutralStreamTarget
                + "|" + observationState + "|" + observationCorrelated + "|"
                + producerBootId + "|"
                + producerRevision + "|" + requestOrdinal + "|" + transportGeneration
                + "|" + controlGeneration + "|" + actorLeaseEpoch + "|" + operationId
                + "|" + transitionId + "|" + streamSessionId + "|" + processEvidence
                + "|" + processId + "|" + processToken + "|" + windowEvidence
                + "|" + observationReason;
    }

    @Override public boolean equals(Object value) {
        if (this == value) return true;
        if (!(value instanceof SessionSnapshot)) return false;
        SessionSnapshot other = (SessionSnapshot) value;
        return hostGameAppId == other.hostGameAppId
                && retainedTransport == other.retainedTransport
                && explicitSuspension == other.explicitSuspension
                && suspensionSleepObserved == other.suspensionSleepObserved
                && hostSleepRequested == other.hostSleepRequested
                && hostSleepObserved == other.hostSleepObserved
                && neutralStreamTarget == other.neutralStreamTarget
                && observationCorrelated == other.observationCorrelated
                && producerRevision == other.producerRevision
                && requestOrdinal == other.requestOrdinal
                && transportGeneration == other.transportGeneration
                && controlGeneration == other.controlGeneration
                && actorLeaseEpoch == other.actorLeaseEpoch
                && processId == other.processId
                && hostId.equals(other.hostId) && profileId.equals(other.profileId)
                && executionProfileId.equals(other.executionProfileId)
                && state == other.state
                && observationState == other.observationState
                && playniteGameId.equals(other.playniteGameId)
                && suspendId.equals(other.suspendId)
                && producerBootId.equals(other.producerBootId)
                && operationId.equals(other.operationId)
                && transitionId.equals(other.transitionId)
                && streamSessionId.equals(other.streamSessionId)
                && processEvidence.equals(other.processEvidence)
                && processToken.equals(other.processToken)
                && windowEvidence.equals(other.windowEvidence)
                && observationReason.equals(other.observationReason);
    }

    @Override public int hashCode() {
        return Objects.hash(hostId, profileId, executionProfileId, state,
                hostGameAppId, playniteGameId,
                retainedTransport, explicitSuspension, suspensionSleepObserved,
                hostSleepRequested, hostSleepObserved, suspendId, neutralStreamTarget,
                observationState, observationCorrelated, producerBootId,
                producerRevision, requestOrdinal,
                transportGeneration, controlGeneration, actorLeaseEpoch, operationId,
                transitionId, streamSessionId, processEvidence, processId, processToken,
                windowEvidence, observationReason);
    }

    static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }
}
