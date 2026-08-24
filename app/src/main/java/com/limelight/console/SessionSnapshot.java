package com.limelight.console;

import java.util.Locale;
import java.util.Objects;

/** Immutable projection of already-owned session facts for one host. */
final class SessionSnapshot {
    enum State {
        NONE,
        ACTIVE,
        SUSPENDED,
        SUSPENDED_UNVERIFIED,
        RECONNECT_REQUIRED,
        TERMINATING
    }

    final String hostId;
    final State state;
    final int hostGameAppId;
    final String playniteGameId;
    final boolean retainedTransport;
    final boolean explicitSuspension;
    final boolean reconnectRequired;
    final boolean resumeAvailable;
    final boolean suspensionSleepObserved;
    final boolean hostSleepRequested;
    final boolean hostSleepObserved;
    final String suspendId;

    SessionSnapshot(String hostId, State state, int hostGameAppId,
                    String playniteGameId, boolean retainedTransport,
                    boolean explicitSuspension, boolean suspensionSleepObserved,
                    boolean hostSleepRequested, boolean hostSleepObserved) {
        this(hostId, state, hostGameAppId, playniteGameId, retainedTransport,
                explicitSuspension, suspensionSleepObserved, hostSleepRequested,
                hostSleepObserved, "");
    }

    SessionSnapshot(String hostId, State state, int hostGameAppId,
                    String playniteGameId, boolean retainedTransport,
                    boolean explicitSuspension, boolean suspensionSleepObserved,
                    boolean hostSleepRequested, boolean hostSleepObserved,
                    String suspendId) {
        this.hostId = normalize(hostId);
        this.state = state;
        this.hostGameAppId = hostGameAppId;
        this.playniteGameId = normalize(playniteGameId);
        this.retainedTransport = retainedTransport;
        this.explicitSuspension = explicitSuspension;
        this.reconnectRequired = state == State.RECONNECT_REQUIRED;
        this.resumeAvailable = state == State.ACTIVE || state == State.SUSPENDED
                || state == State.SUSPENDED_UNVERIFIED
                || state == State.RECONNECT_REQUIRED;
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
    boolean isSuspended() { return state == State.SUSPENDED
                || state == State.SUSPENDED_UNVERIFIED; }
    boolean isReconnectRequired() { return reconnectRequired; }
    boolean isResumeAvailable() { return resumeAvailable; }

    String signature() {
        return state + "|" + hostId + "|" + hostGameAppId + "|" + playniteGameId
                + "|" + retainedTransport + "|" + explicitSuspension + "|"
                + suspensionSleepObserved + "|" + hostSleepRequested + "|"
                + hostSleepObserved + "|" + suspendId;
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
                && hostId.equals(other.hostId) && state == other.state
                && playniteGameId.equals(other.playniteGameId)
                && suspendId.equals(other.suspendId);
    }

    @Override public int hashCode() {
        return Objects.hash(hostId, state, hostGameAppId, playniteGameId,
                retainedTransport, explicitSuspension, suspensionSleepObserved,
                hostSleepRequested, hostSleepObserved, suspendId);
    }

    static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }
}