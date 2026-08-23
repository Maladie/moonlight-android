package com.limelight.console;

import com.limelight.stream.RetainedStreamSessionCoordinator;

/** Pure precedence resolver over session facts read by ConsoleActivity. */
final class SessionStateResolver {
    static final class Observations {
        final String hostId;
        final int runningGameAppId;
        final String resolvedPlayniteGameId;
        final RetainedStreamSessionCoordinator.State retainedState;
        final String retainedHostId;
        final int retainedAppId;
        final String retainedPlayniteGameId;
        final String suspendedHostId;
        final int suspendedAppId;
        final String suspendedPlayniteGameId;
        final long suspendedResumedAt;
        final long suspendedSleepObservedAt;
        final boolean recentlyEnded;
        final boolean pendingResume;
        final String pendingResumeHostId;
        final int pendingResumeAppId;
        final String hostSleepHostId;
        final boolean hostSleepRequested;
        final boolean hostSleepObserved;

        Observations(String hostId, int runningGameAppId, String resolvedPlayniteGameId,
                     RetainedStreamSessionCoordinator.Snapshot retained,
                     SuspendedSessionStore.Session suspended, boolean recentlyEnded,
                     boolean pendingResume, String pendingResumeHostId,
                     int pendingResumeAppId, String hostSleepHostId,
                     HostSleepStateStore.State hostSleep) {
            this(hostId, runningGameAppId, resolvedPlayniteGameId,
                    retained.state, retained.hostId, retained.appId,
                    retained.playniteGameId,
                    suspended == null ? "" : suspended.hostId,
                    suspended == null ? 0 : suspended.sunshineAppId,
                    suspended == null ? "" : suspended.playniteGameId,
                    suspended == null ? -1L : suspended.resumedAt,
                    suspended == null ? 0L : suspended.sleepObservedAt,
                    recentlyEnded, pendingResume, pendingResumeHostId,
                    pendingResumeAppId, hostSleepHostId, hostSleep != null,
                    hostSleep != null && hostSleep.sleepObservedAt > 0L);
        }

        Observations(String hostId, int runningGameAppId, String resolvedPlayniteGameId,
                     RetainedStreamSessionCoordinator.State retainedState,
                     String retainedHostId, int retainedAppId,
                     String retainedPlayniteGameId, String suspendedHostId,
                     int suspendedAppId, String suspendedPlayniteGameId,
                     long suspendedResumedAt, long suspendedSleepObservedAt,
                     boolean recentlyEnded, boolean pendingResume,
                     String pendingResumeHostId, int pendingResumeAppId,
                     String hostSleepHostId, boolean hostSleepRequested,
                     boolean hostSleepObserved) {
            this.hostId = SessionSnapshot.normalize(hostId);
            this.runningGameAppId = runningGameAppId;
            this.resolvedPlayniteGameId = SessionSnapshot.normalize(
                    resolvedPlayniteGameId);
            this.retainedState = retainedState;
            this.retainedHostId = SessionSnapshot.normalize(retainedHostId);
            this.retainedAppId = retainedAppId;
            this.retainedPlayniteGameId = SessionSnapshot.normalize(
                    retainedPlayniteGameId);
            this.suspendedHostId = SessionSnapshot.normalize(suspendedHostId);
            this.suspendedAppId = suspendedAppId;
            this.suspendedPlayniteGameId = SessionSnapshot.normalize(
                    suspendedPlayniteGameId);
            this.suspendedResumedAt = suspendedResumedAt;
            this.suspendedSleepObservedAt = suspendedSleepObservedAt;
            this.recentlyEnded = recentlyEnded;
            this.pendingResume = pendingResume;
            this.pendingResumeHostId = SessionSnapshot.normalize(pendingResumeHostId);
            this.pendingResumeAppId = pendingResumeAppId;
            this.hostSleepHostId = SessionSnapshot.normalize(hostSleepHostId);
            this.hostSleepRequested = hostSleepRequested;
            this.hostSleepObserved = hostSleepObserved;
        }
    }

    SessionSnapshot resolve(Observations facts) {
        boolean retainedMatches = facts.hostId.equals(facts.retainedHostId)
                && (facts.runningGameAppId == 0
                || facts.retainedAppId == 0
                || facts.runningGameAppId == facts.retainedAppId)
                && (facts.resolvedPlayniteGameId.isEmpty()
                || facts.retainedPlayniteGameId.isEmpty()
                || facts.resolvedPlayniteGameId.equals(facts.retainedPlayniteGameId));
        boolean suspendedMatches = facts.hostId.equals(facts.suspendedHostId);
        boolean pendingMatches = facts.pendingResume
                && facts.hostId.equals(facts.pendingResumeHostId);
        boolean sleepMatches = facts.hostId.equals(facts.hostSleepHostId);
        boolean explicitSuspension = suspendedMatches && facts.suspendedResumedAt == 0L;
        boolean suspensionSleepObserved = explicitSuspension
                && facts.suspendedSleepObservedAt > 0L;
        boolean sleepRequested = sleepMatches && facts.hostSleepRequested;
        boolean sleepObserved = sleepRequested && facts.hostSleepObserved;

        if (retainedMatches && facts.retainedState
                == RetainedStreamSessionCoordinator.State.TERMINATING) {
            return snapshot(facts, SessionSnapshot.State.TERMINATING,
                    facts.retainedAppId, facts.retainedPlayniteGameId, false,
                    explicitSuspension, suspensionSleepObserved,
                    sleepRequested, sleepObserved);
        }

        boolean retainedLive = retainedMatches && (facts.retainedState
                == RetainedStreamSessionCoordinator.State.HOME_LIVE
                || facts.retainedState == RetainedStreamSessionCoordinator.State.PARKED_LIVE);
        boolean correlatedLive = facts.runningGameAppId != 0
                && !facts.resolvedPlayniteGameId.isEmpty();
        boolean hostLive = facts.runningGameAppId != 0
                && (!facts.recentlyEnded || correlatedLive);
        if (retainedLive || hostLive) {
            int appId = retainedLive && facts.retainedAppId != 0
                    ? facts.retainedAppId : facts.runningGameAppId;
            String gameId = facts.resolvedPlayniteGameId;
            if (gameId.isEmpty() && retainedLive) gameId = facts.retainedPlayniteGameId;
            if (gameId.isEmpty() && suspendedMatches && facts.suspendedResumedAt > 0L
                    && facts.suspendedAppId == appId) {
                gameId = facts.suspendedPlayniteGameId;
            }
            return snapshot(facts, SessionSnapshot.State.ACTIVE, appId, gameId,
                    retainedLive, explicitSuspension, suspensionSleepObserved,
                    sleepRequested, sleepObserved);
        }

        if (facts.recentlyEnded) {
            return snapshot(facts, SessionSnapshot.State.NONE, 0, "", false,
                    false, false, sleepRequested, sleepObserved);
        }

        if (explicitSuspension) {
            return snapshot(facts, SessionSnapshot.State.SUSPENDED,
                    facts.suspendedAppId, facts.suspendedPlayniteGameId, false,
                    true, suspensionSleepObserved, sleepRequested, sleepObserved);
        }

        boolean retainedReconnect = retainedMatches && facts.retainedState
                == RetainedStreamSessionCoordinator.State.RECONNECT_REQUIRED;
        if (retainedReconnect || pendingMatches) {
            int appId = retainedReconnect ? facts.retainedAppId : facts.pendingResumeAppId;
            String gameId = retainedReconnect ? facts.retainedPlayniteGameId : "";
            if (gameId.isEmpty() && suspendedMatches && facts.suspendedResumedAt > 0L
                    && facts.suspendedAppId == appId) {
                gameId = facts.suspendedPlayniteGameId;
            }
            return snapshot(facts, SessionSnapshot.State.RECONNECT_REQUIRED,
                    appId, gameId, false, false, false,
                    sleepRequested, sleepObserved);
        }

        return snapshot(facts, SessionSnapshot.State.NONE, 0, "", false,
                false, false, sleepRequested, sleepObserved);
    }

    private static SessionSnapshot snapshot(Observations facts, SessionSnapshot.State state,
                                            int appId, String gameId,
                                            boolean retainedTransport,
                                            boolean explicitSuspension,
                                            boolean suspensionSleepObserved,
                                            boolean hostSleepRequested,
                                            boolean hostSleepObserved) {
        return new SessionSnapshot(facts.hostId, state, appId, gameId,
                retainedTransport, explicitSuspension, suspensionSleepObserved,
                hostSleepRequested, hostSleepObserved);
    }
}
