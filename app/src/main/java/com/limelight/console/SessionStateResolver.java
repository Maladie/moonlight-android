package com.limelight.console;

import com.limelight.stream.RetainedStreamSessionCoordinator;
import com.limelight.gateway.GatewayConnection;

/** Pure precedence resolver over session facts read by ConsoleActivity. */
final class SessionStateResolver {
    static final class Observations {
        final String hostId;
        final String profileId;
        final int runningGameAppId;
        final String resolvedPlayniteGameId;
        final RetainedStreamSessionCoordinator.State retainedState;
        final String retainedHostId;
        final String retainedProfileId;
        final int retainedAppId;
        final String retainedPlayniteGameId;
        final String suspendedHostId;
        final String suspendedProfileId;
        final int suspendedAppId;
        final String suspendedPlayniteGameId;
        String suspendedId;
        final long suspendedResumedAt;
        final long suspendedSleepObservedAt;
        final boolean recentlyEnded;
        final boolean pendingResume;
        final String pendingResumeHostId;
        final String pendingResumeProfileId;
        final int pendingResumeAppId;
        final String hostSleepHostId;
        final boolean hostSleepRequested;
        final boolean hostSleepObserved;
        boolean hostOnline;
        String bridgeGameState;
        boolean retainedOwnerLive;
        boolean neutralStreamTarget;

        Observations(String hostId, int runningGameAppId, String resolvedPlayniteGameId,
                     RetainedStreamSessionCoordinator.Snapshot retained,
                     SuspendedSessionStore.Session suspended, boolean recentlyEnded,
                     boolean pendingResume, String pendingResumeHostId,
                     int pendingResumeAppId, String hostSleepHostId,
                     HostSleepStateStore.State hostSleep, boolean hostOnline) {
            this(hostId, GatewayConnection.DEFAULT_PROFILE_ID, runningGameAppId,
                    resolvedPlayniteGameId, retained,
                    suspended, recentlyEnded, pendingResume, pendingResumeHostId,
                    GatewayConnection.DEFAULT_PROFILE_ID, pendingResumeAppId,
                    hostSleepHostId, hostSleep, hostOnline);
        }

        Observations(String hostId, String profileId, int runningGameAppId,
                     String resolvedPlayniteGameId,
                     RetainedStreamSessionCoordinator.Snapshot retained,
                     SuspendedSessionStore.Session suspended, boolean recentlyEnded,
                     boolean pendingResume, String pendingResumeHostId,
                     String pendingResumeProfileId, int pendingResumeAppId,
                     String hostSleepHostId, HostSleepStateStore.State hostSleep,
                     boolean hostOnline) {
            this(hostId, profileId, runningGameAppId, resolvedPlayniteGameId,
                    retained.state, retained.hostId, retained.profileId, retained.appId,
                    retained.playniteGameId,
                    suspended == null ? "" : suspended.hostId,
                    suspended == null ? GatewayConnection.DEFAULT_PROFILE_ID
                            : suspended.profileId,
                    suspended == null ? 0 : suspended.sunshineAppId,
                    suspended == null ? "" : suspended.playniteGameId,
                    suspended == null ? -1L : suspended.resumedAt,
                    suspended == null ? 0L : suspended.sleepObservedAt,
                    recentlyEnded, pendingResume, pendingResumeHostId,
                    pendingResumeProfileId, pendingResumeAppId, hostSleepHostId,
                    hostSleep != null,
                    hostSleep != null && hostSleep.sleepObservedAt > 0L);
            this.hostOnline = hostOnline;
            this.suspendedId = suspended == null ? "" : suspended.suspendId;
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
            this(hostId, GatewayConnection.DEFAULT_PROFILE_ID, runningGameAppId,
                    resolvedPlayniteGameId, retainedState, retainedHostId,
                    GatewayConnection.DEFAULT_PROFILE_ID, retainedAppId,
                    retainedPlayniteGameId, suspendedHostId,
                    GatewayConnection.DEFAULT_PROFILE_ID, suspendedAppId,
                    suspendedPlayniteGameId, suspendedResumedAt,
                    suspendedSleepObservedAt, recentlyEnded, pendingResume,
                    pendingResumeHostId, GatewayConnection.DEFAULT_PROFILE_ID,
                    pendingResumeAppId, hostSleepHostId, hostSleepRequested,
                    hostSleepObserved);
        }

        Observations(String hostId, String profileId, int runningGameAppId,
                     String resolvedPlayniteGameId,
                     RetainedStreamSessionCoordinator.State retainedState,
                     String retainedHostId, String retainedProfileId, int retainedAppId,
                     String retainedPlayniteGameId, String suspendedHostId,
                     String suspendedProfileId, int suspendedAppId,
                     String suspendedPlayniteGameId, long suspendedResumedAt,
                     long suspendedSleepObservedAt, boolean recentlyEnded,
                     boolean pendingResume, String pendingResumeHostId,
                     String pendingResumeProfileId, int pendingResumeAppId,
                     String hostSleepHostId, boolean hostSleepRequested,
                     boolean hostSleepObserved) {
            this.hostId = SessionSnapshot.normalize(hostId);
            this.profileId = GatewayConnection.normalizeProfileId(profileId);
            this.runningGameAppId = runningGameAppId;
            this.resolvedPlayniteGameId = SessionSnapshot.normalize(
                    resolvedPlayniteGameId);
            this.retainedState = retainedState;
            this.retainedHostId = SessionSnapshot.normalize(retainedHostId);
            this.retainedProfileId = GatewayConnection.normalizeProfileId(retainedProfileId);
            this.retainedAppId = retainedAppId;
            this.retainedPlayniteGameId = SessionSnapshot.normalize(
                    retainedPlayniteGameId);
            this.suspendedHostId = SessionSnapshot.normalize(suspendedHostId);
            this.suspendedProfileId = GatewayConnection.normalizeProfileId(suspendedProfileId);
            this.suspendedAppId = suspendedAppId;
            this.suspendedPlayniteGameId = SessionSnapshot.normalize(
                    suspendedPlayniteGameId);
            this.suspendedResumedAt = suspendedResumedAt;
            this.suspendedSleepObservedAt = suspendedSleepObservedAt;
            this.recentlyEnded = recentlyEnded;
            this.pendingResume = pendingResume;
            this.pendingResumeHostId = SessionSnapshot.normalize(pendingResumeHostId);
            this.pendingResumeProfileId = GatewayConnection.normalizeProfileId(
                    pendingResumeProfileId);
            this.pendingResumeAppId = pendingResumeAppId;
            this.hostSleepHostId = SessionSnapshot.normalize(hostSleepHostId);
            this.hostSleepRequested = hostSleepRequested;
            this.hostSleepObserved = hostSleepObserved;
            this.hostOnline = false;
            this.bridgeGameState = this.resolvedPlayniteGameId.isEmpty()
                    ? "" : "running";
        }
    }

    SessionSnapshot resolve(Observations facts) {
        String bridgeState = SessionSnapshot.normalize(facts.bridgeGameState);
        boolean bridgeRunning = "running".equals(bridgeState)
                && !facts.resolvedPlayniteGameId.isEmpty();
        boolean bridgeIdle = "idle".equals(bridgeState);
        boolean bridgeUncertain = !bridgeState.isEmpty()
                && !bridgeRunning && !bridgeIdle;
        boolean retainedProfileMatches = facts.hostId.equals(facts.retainedHostId)
                && facts.profileId.equals(facts.retainedProfileId);
        boolean retainedMatches = retainedProfileMatches
                && (facts.runningGameAppId == 0
                || facts.retainedAppId == 0
                || facts.runningGameAppId == facts.retainedAppId)
                && (facts.resolvedPlayniteGameId.isEmpty()
                || facts.retainedPlayniteGameId.isEmpty()
                || facts.resolvedPlayniteGameId.equals(facts.retainedPlayniteGameId));
        boolean retainedLive = facts.retainedOwnerLive && retainedMatches
                && (facts.retainedState == RetainedStreamSessionCoordinator.State.HOME_LIVE
                || facts.retainedState == RetainedStreamSessionCoordinator.State.PARKED_LIVE);
        boolean suspendedMatches = facts.hostId.equals(facts.suspendedHostId)
                && facts.profileId.equals(facts.suspendedProfileId);
        boolean pendingMatches = facts.pendingResume
                && facts.hostId.equals(facts.pendingResumeHostId)
                && facts.profileId.equals(facts.pendingResumeProfileId);
        boolean sleepMatches = facts.hostId.equals(facts.hostSleepHostId);
        boolean explicitSuspension = suspendedMatches && facts.suspendedResumedAt == 0L;
        boolean suspensionSleepObserved = explicitSuspension
                && facts.suspendedSleepObservedAt > 0L;
        boolean sleepRequested = sleepMatches && facts.hostSleepRequested;
        boolean sleepObserved = sleepRequested && facts.hostSleepObserved;

        if (retainedProfileMatches && facts.retainedState
                == RetainedStreamSessionCoordinator.State.TERMINATING) {
            return snapshot(facts, SessionSnapshot.State.TERMINATING,
                    facts.retainedAppId, facts.retainedPlayniteGameId, false,
                    explicitSuspension, suspensionSleepObserved,
                    sleepRequested, sleepObserved);
        }

        if (retainedProfileMatches && facts.retainedState
                == RetainedStreamSessionCoordinator.State.PREPARING) {
            return snapshot(facts, SessionSnapshot.State.PREPARING,
                    facts.retainedAppId, facts.retainedPlayniteGameId, false,
                    false, false, sleepRequested, sleepObserved);
        }

        if (facts.hostOnline && bridgeUncertain && !retainedLive) {
            return snapshot(facts, SessionSnapshot.State.UNCERTAIN,
                    facts.runningGameAppId, facts.resolvedPlayniteGameId, false,
                    explicitSuspension, suspensionSleepObserved,
                    sleepRequested, sleepObserved);
        }

        if (facts.recentlyEnded && !retainedLive && !bridgeRunning) {
            return snapshot(facts, SessionSnapshot.State.NONE, 0, "", false,
                    false, false, sleepRequested, sleepObserved);
        }

        if (explicitSuspension && !retainedLive && !bridgeRunning && !bridgeIdle
                && (facts.runningGameAppId == 0
                || facts.runningGameAppId == facts.suspendedAppId)) {
            return snapshot(facts, SessionSnapshot.State.SUSPENDED,
                    facts.suspendedAppId, facts.suspendedPlayniteGameId, false,
                    true, suspensionSleepObserved, sleepRequested, sleepObserved);
        }

        boolean hostLive = facts.hostOnline && facts.runningGameAppId != 0;
        boolean bridgeLive = facts.hostOnline && bridgeRunning;
        if (retainedLive || hostLive || bridgeLive) {
            int appId = retainedLive && facts.retainedAppId != 0
                    ? facts.retainedAppId : facts.runningGameAppId;
            String gameId = bridgeRunning ? facts.resolvedPlayniteGameId : "";
            if (gameId.isEmpty() && retainedLive && !bridgeIdle) {
                gameId = facts.retainedPlayniteGameId;
            }
            if (gameId.isEmpty() && suspendedMatches && facts.suspendedResumedAt > 0L
                    && facts.suspendedAppId == appId && !bridgeIdle) {
                gameId = facts.suspendedPlayniteGameId;
            }
            return snapshot(facts, SessionSnapshot.State.ACTIVE, appId, gameId,
                    retainedLive, explicitSuspension, suspensionSleepObserved,
                    sleepRequested, sleepObserved);
        }


        if (explicitSuspension && !bridgeIdle) {
            return snapshot(facts, SessionSnapshot.State.SUSPENDED,
                    facts.suspendedAppId, facts.suspendedPlayniteGameId, false,
                    true, suspensionSleepObserved, sleepRequested, sleepObserved);
        }

        boolean retainedReconnect = retainedMatches && facts.retainedState
                == RetainedStreamSessionCoordinator.State.RECONNECT_REQUIRED;
        if (retainedReconnect || pendingMatches) {
            int appId = retainedReconnect ? facts.retainedAppId : facts.pendingResumeAppId;
            String gameId = retainedReconnect && !bridgeIdle
                    ? facts.retainedPlayniteGameId : "";
            if (gameId.isEmpty() && suspendedMatches && facts.suspendedResumedAt > 0L
                    && facts.suspendedAppId == appId && !bridgeIdle) {
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
        return new SessionSnapshot(facts.hostId, facts.profileId, state, appId, gameId,
                retainedTransport, explicitSuspension, suspensionSleepObserved,
                hostSleepRequested, hostSleepObserved,
                explicitSuspension ? facts.suspendedId : "",
                facts.neutralStreamTarget);
    }
}
