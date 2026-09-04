package com.limelight.console;

import com.limelight.gateway.GatewayConnection;

import java.util.Locale;
import java.util.Objects;

/** Immutable projection of already-owned session facts for one host. */
final class SessionSnapshot {
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

    SessionSnapshot(String hostId, State state, int hostGameAppId,
                    String playniteGameId, boolean retainedTransport,
                    boolean explicitSuspension, boolean suspensionSleepObserved,
                    boolean hostSleepRequested, boolean hostSleepObserved) {
        this(hostId, GatewayConnection.DEFAULT_PROFILE_ID, state, hostGameAppId,
                playniteGameId, retainedTransport,
                explicitSuspension, suspensionSleepObserved, hostSleepRequested,
                hostSleepObserved, "", false);
    }

    SessionSnapshot(String hostId, String profileId, State state, int hostGameAppId,
                    String playniteGameId, boolean retainedTransport,
                    boolean explicitSuspension, boolean suspensionSleepObserved,
                    boolean hostSleepRequested, boolean hostSleepObserved) {
        this(hostId, profileId, state, hostGameAppId, playniteGameId, retainedTransport,
                explicitSuspension, suspensionSleepObserved, hostSleepRequested,
                hostSleepObserved, "", false);
    }

    SessionSnapshot(String hostId, State state, int hostGameAppId,
                    String playniteGameId, boolean retainedTransport,
                    boolean explicitSuspension, boolean suspensionSleepObserved,
                    boolean hostSleepRequested, boolean hostSleepObserved,
                    String suspendId) {
        this(hostId, GatewayConnection.DEFAULT_PROFILE_ID, state, hostGameAppId,
                playniteGameId, retainedTransport,
                explicitSuspension, suspensionSleepObserved, hostSleepRequested,
                hostSleepObserved, suspendId, false);
    }

    SessionSnapshot(String hostId, String profileId, State state, int hostGameAppId,
                    String playniteGameId, boolean retainedTransport,
                    boolean explicitSuspension, boolean suspensionSleepObserved,
                    boolean hostSleepRequested, boolean hostSleepObserved,
                    String suspendId) {
        this(hostId, profileId, state, hostGameAppId, playniteGameId, retainedTransport,
                explicitSuspension, suspensionSleepObserved, hostSleepRequested,
                hostSleepObserved, suspendId, false);
    }

    SessionSnapshot(String hostId, State state, int hostGameAppId,
                    String playniteGameId, boolean retainedTransport,
                    boolean explicitSuspension, boolean suspensionSleepObserved,
                    boolean hostSleepRequested, boolean hostSleepObserved,
                    String suspendId, boolean neutralStreamTarget) {
        this(hostId, GatewayConnection.DEFAULT_PROFILE_ID, state, hostGameAppId,
                playniteGameId, retainedTransport, explicitSuspension,
                suspensionSleepObserved, hostSleepRequested, hostSleepObserved,
                suspendId, neutralStreamTarget);
    }

    SessionSnapshot(String hostId, String profileId, State state, int hostGameAppId,
                    String playniteGameId, boolean retainedTransport,
                    boolean explicitSuspension, boolean suspensionSleepObserved,
                    boolean hostSleepRequested, boolean hostSleepObserved,
                    String suspendId, boolean neutralStreamTarget) {
        this.hostId = normalize(hostId);
        this.profileId = GatewayConnection.normalizeProfileId(profileId);
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
        return state + "|" + hostId + "|" + profileId + "|" + hostGameAppId + "|" + playniteGameId
                + "|" + retainedTransport + "|" + explicitSuspension + "|"
                + suspensionSleepObserved + "|" + hostSleepRequested + "|"
                + hostSleepObserved + "|" + suspendId + "|" + neutralStreamTarget;
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
                && hostId.equals(other.hostId) && profileId.equals(other.profileId)
                && state == other.state
                && playniteGameId.equals(other.playniteGameId)
                && suspendId.equals(other.suspendId);
    }

    @Override public int hashCode() {
        return Objects.hash(hostId, profileId, state, hostGameAppId, playniteGameId,
                retainedTransport, explicitSuspension, suspensionSleepObserved,
                hostSleepRequested, hostSleepObserved, suspendId, neutralStreamTarget);
    }

    static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }
}
