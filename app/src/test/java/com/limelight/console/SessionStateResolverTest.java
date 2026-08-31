package com.limelight.console;

import com.limelight.stream.RetainedStreamSessionCoordinator;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class SessionStateResolverTest {
    private final SessionStateResolver resolver = new SessionStateResolver();

    @Test public void matchingLiveRetainedBeatsTemporaryZeroHostPoll() {
        Facts facts = new Facts();
        facts.retainedState = RetainedStreamSessionCoordinator.State.PARKED_LIVE;
        facts.retainedHost = " HOST ";
        facts.retainedApp = 42;
        facts.retainedGame = "Game-A";

        SessionSnapshot snapshot = resolver.resolve(facts.build());

        assertEquals(SessionSnapshot.State.ACTIVE, snapshot.state);
        assertEquals(42, snapshot.hostGameAppId);
        assertEquals("game-a", snapshot.playniteGameId);
        assertTrue(snapshot.retainedTransport);
    }

    @Test public void retainedDataFromAnotherHostIsIgnored() {
        Facts facts = new Facts();
        facts.retainedState = RetainedStreamSessionCoordinator.State.HOME_LIVE;
        facts.retainedHost = "other";
        facts.retainedApp = 42;

        assertEquals(SessionSnapshot.State.NONE, resolver.resolve(facts.build()).state);
    }

    @Test public void staleRetainedTargetDoesNotSuppressCurrentSunshineEvidence() {
        Facts facts = new Facts();
        facts.runningApp = 99;
        facts.resolvedGame = "current";
        facts.retainedState = RetainedStreamSessionCoordinator.State.HOME_LIVE;
        facts.retainedHost = "host";
        facts.retainedApp = 42;
        facts.retainedGame = "stale";

        SessionSnapshot snapshot = resolver.resolve(facts.build());

        assertEquals(SessionSnapshot.State.ACTIVE, snapshot.state);
        assertEquals(99, snapshot.hostGameAppId);
        assertEquals("current", snapshot.playniteGameId);
        assertFalse(snapshot.retainedTransport);

        facts.retainedApp = 99;
        snapshot = resolver.resolve(facts.build());
        assertEquals("current", snapshot.playniteGameId);
        assertFalse(snapshot.retainedTransport);
    }

    @Test public void matchingTerminatingBlocksEveryResumeSource() {
        Facts facts = new Facts();
        facts.runningApp = 42;
        facts.resolvedGame = "game";
        facts.retainedState = RetainedStreamSessionCoordinator.State.TERMINATING;
        facts.retainedHost = "host";
        facts.retainedApp = 42;
        facts.suspendedHost = "host";
        facts.suspendedApp = 42;
        facts.suspendedGame = "game";
        facts.suspendedResumedAt = 0L;
        facts.pending = true;
        facts.pendingHost = "host";
        facts.pendingApp = 42;

        SessionSnapshot snapshot = resolver.resolve(facts.build());

        assertEquals(SessionSnapshot.State.TERMINATING, snapshot.state);
        assertFalse(snapshot.isResumeAvailable());
    }

    @Test public void terminatingHostStillBlocksAfterTargetIdentityDisappears() {
        Facts facts = new Facts();
        facts.runningApp = 99;
        facts.resolvedGame = "new-poll-value";
        facts.retainedState = RetainedStreamSessionCoordinator.State.TERMINATING;
        facts.retainedHost = "host";
        facts.retainedApp = 42;
        facts.retainedGame = "old-game";

        assertEquals(SessionSnapshot.State.TERMINATING,
                resolver.resolve(facts.build()).state);
    }

    @Test public void preparingBeatsBridgeAndSuspendedResumeFacts() {
        Facts facts = suspendedFacts();
        facts.retainedState = RetainedStreamSessionCoordinator.State.PREPARING;
        facts.retainedHost = "host";
        facts.retainedApp = 42;
        facts.retainedGame = "observed-game";
        facts.resolvedGame = "other-game";
        facts.bridgeState = "ambiguous";
        facts.pending = true;
        facts.pendingHost = "host";
        facts.pendingApp = 42;

        SessionSnapshot snapshot = resolver.resolve(facts.build());

        assertEquals(SessionSnapshot.State.PREPARING, snapshot.state);
        assertEquals(42, snapshot.hostGameAppId);
        assertEquals("observed-game", snapshot.playniteGameId);
        assertFalse(snapshot.retainedTransport);
        assertFalse(snapshot.explicitSuspension);
        assertFalse(snapshot.isResumeAvailable());
    }

    @Test public void neutralPreparingHasNoResumeIdentity() {
        Facts facts = new Facts();
        facts.retainedState = RetainedStreamSessionCoordinator.State.PREPARING;
        facts.retainedHost = "host";
        facts.retainedApp = 42;

        SessionSnapshot snapshot = resolver.resolve(facts.build());

        assertEquals(SessionSnapshot.State.PREPARING, snapshot.state);
        assertEquals("", snapshot.playniteGameId);
        assertFalse(snapshot.isResumeAvailable());
        assertFalse(snapshot.matches("game", 42));
    }

    @Test public void activePlayniteIdentityIsPreservedForSharedTargetDisambiguation() {
        Facts facts = new Facts();
        facts.runningApp = 42;
        facts.resolvedGame = "Game-B";

        SessionSnapshot snapshot = resolver.resolve(facts.build());

        assertEquals(SessionSnapshot.State.ACTIVE, snapshot.state);
        assertEquals("game-b", snapshot.playniteGameId);
        assertFalse(snapshot.matches("game-a", 42));
        assertTrue(snapshot.matches("game-b", 42));
    }

    @Test public void staleIdleProjectedAsUnknownIsUncertainWithoutSunshineStream() {
        Facts facts = new Facts();
        facts.bridgeState = "unknown";

        SessionSnapshot snapshot = resolver.resolve(facts.build());

        assertEquals(SessionSnapshot.State.UNCERTAIN, snapshot.state);
        assertFalse(snapshot.isResumeAvailable());
    }

    @Test public void exactLiveRetainedTransportBeatsUnknownBridge() {
        for (RetainedStreamSessionCoordinator.State state : new RetainedStreamSessionCoordinator.State[] {
                RetainedStreamSessionCoordinator.State.HOME_LIVE,
                RetainedStreamSessionCoordinator.State.PARKED_LIVE }) {
            Facts facts = new Facts();
            facts.retainedState = state;
            facts.retainedHost = "host";
            facts.retainedApp = 42;
            facts.retainedGame = "game";
            facts.bridgeState = "unknown";

            SessionSnapshot snapshot = resolver.resolve(facts.build());

            assertEquals(SessionSnapshot.State.ACTIVE, snapshot.state);
            assertTrue(snapshot.retainedTransport);
            assertEquals("game", snapshot.playniteGameId);
        }
    }

    @Test public void unknownBridgeRemainsUncertainWithoutExactLiveOwner() {
        Facts deadOwner = new Facts();
        deadOwner.retainedState = RetainedStreamSessionCoordinator.State.HOME_LIVE;
        deadOwner.retainedHost = "host";
        deadOwner.retainedApp = 42;
        deadOwner.retainedOwnerLive = false;
        deadOwner.bridgeState = "unknown";
        assertEquals(SessionSnapshot.State.UNCERTAIN,
                resolver.resolve(deadOwner.build()).state);

        Facts otherHost = new Facts();
        otherHost.retainedState = RetainedStreamSessionCoordinator.State.PARKED_LIVE;
        otherHost.retainedHost = "other";
        otherHost.retainedApp = 42;
        otherHost.bridgeState = "unknown";
        assertEquals(SessionSnapshot.State.UNCERTAIN,
                resolver.resolve(otherHost.build()).state);

        Facts otherApp = new Facts();
        otherApp.runningApp = 7;
        otherApp.retainedState = RetainedStreamSessionCoordinator.State.HOME_LIVE;
        otherApp.retainedHost = "host";
        otherApp.retainedApp = 42;
        otherApp.bridgeState = "unknown";
        assertEquals(SessionSnapshot.State.UNCERTAIN,
                resolver.resolve(otherApp.build()).state);
    }

    @Test public void bridgeRunningWithoutStreamCreatesGameOnlyActiveSession() {
        Facts facts = new Facts();
        facts.resolvedGame = "game";
        facts.bridgeState = "running";

        SessionSnapshot snapshot = resolver.resolve(facts.build());

        assertEquals(SessionSnapshot.State.ACTIVE, snapshot.state);
        assertEquals(0, snapshot.hostGameAppId);
        assertEquals("game", snapshot.playniteGameId);
    }

    @Test public void freshBridgeIdleClearsRetainedGameNameButKeepsNeutralStream() {
        Facts facts = new Facts();
        facts.retainedState = RetainedStreamSessionCoordinator.State.HOME_LIVE;
        facts.retainedHost = "host";
        facts.retainedApp = 42;
        facts.retainedGame = "stale";
        facts.bridgeState = "idle";

        SessionSnapshot snapshot = resolver.resolve(facts.build());

        assertEquals(SessionSnapshot.State.ACTIVE, snapshot.state);
        assertEquals("", snapshot.playniteGameId);
        assertTrue(snapshot.retainedTransport);
    }

    @Test public void freshBridgeIdleSuppressesStaleSuspendedGame() {
        Facts facts = suspendedFacts();
        facts.bridgeState = "idle";
        facts.hostOnline = true;

        assertEquals(SessionSnapshot.State.NONE, resolver.resolve(facts.build()).state);

        facts.retainedState = RetainedStreamSessionCoordinator.State.HOME_LIVE;
        facts.retainedHost = "host";
        facts.retainedApp = 42;
        facts.retainedGame = "game";
        SessionSnapshot stream = resolver.resolve(facts.build());
        assertEquals(SessionSnapshot.State.ACTIVE, stream.state);
        assertEquals("", stream.playniteGameId);
    }

    @Test public void verifiedBridgeGameBeatsRecentApkEndMarker() {
        Facts facts = new Facts();
        facts.resolvedGame = "game";
        facts.bridgeState = "running";
        facts.recentlyEnded = true;

        assertEquals(SessionSnapshot.State.ACTIVE, resolver.resolve(facts.build()).state);
    }

    @Test public void explicitSuspensionWinsMatchingHostAppButNotDifferentApp() {
        Facts suspended = suspendedFacts();
        assertEquals(SessionSnapshot.State.SUSPENDED,
                resolver.resolve(suspended.build()).state);

        suspended.runningApp = 42;
        assertEquals(SessionSnapshot.State.SUSPENDED,
                resolver.resolve(suspended.build()).state);

        suspended.runningApp = 99;
        assertEquals(SessionSnapshot.State.ACTIVE,
                resolver.resolve(suspended.build()).state);
    }

    @Test public void offlineStaleRunningAppDoesNotOverrideSuspension() {
        Facts facts = suspendedFacts();
        facts.runningApp = 42;
        facts.hostOnline = false;

        SessionSnapshot snapshot = resolver.resolve(facts.build());

        assertEquals(SessionSnapshot.State.SUSPENDED, snapshot.state);
        assertEquals("game", snapshot.playniteGameId);
    }

    @Test public void pendingResumeAloneIsReconnectRequiredNeverActive() {
        Facts facts = new Facts();
        facts.pending = true;
        facts.pendingHost = "host";
        facts.pendingApp = 42;

        SessionSnapshot snapshot = resolver.resolve(facts.build());

        assertEquals(SessionSnapshot.State.RECONNECT_REQUIRED, snapshot.state);
        assertFalse(snapshot.hasActiveSession());
        assertTrue(snapshot.isResumeAvailable());
    }

    @Test public void wrongHostReconnectIsIgnoredAndDifferentLiveAppWins() {
        Facts wrongHost = new Facts();
        wrongHost.pending = true;
        wrongHost.pendingHost = "other";
        wrongHost.pendingApp = 42;
        assertEquals(SessionSnapshot.State.NONE,
                resolver.resolve(wrongHost.build()).state);

        wrongHost.pending = false;
        wrongHost.retainedState =
                RetainedStreamSessionCoordinator.State.RECONNECT_REQUIRED;
        wrongHost.retainedHost = "other";
        wrongHost.retainedApp = 42;
        assertEquals(SessionSnapshot.State.NONE,
                resolver.resolve(wrongHost.build()).state);

        Facts differentLiveApp = new Facts();
        differentLiveApp.pending = true;
        differentLiveApp.pendingHost = "host";
        differentLiveApp.pendingApp = 42;
        differentLiveApp.runningApp = 99;
        SessionSnapshot snapshot = resolver.resolve(differentLiveApp.build());
        assertEquals(SessionSnapshot.State.ACTIVE, snapshot.state);
        assertEquals(99, snapshot.hostGameAppId);
    }

    @Test public void recentEndedSuppressesRawSuspendedAndPendingMarkers() {
        Facts raw = new Facts();
        raw.runningApp = 42;
        raw.recentlyEnded = true;
        assertEquals(SessionSnapshot.State.NONE, resolver.resolve(raw.build()).state);

        Facts suspended = suspendedFacts();
        suspended.recentlyEnded = true;
        assertEquals(SessionSnapshot.State.NONE, resolver.resolve(suspended.build()).state);

        Facts pending = new Facts();
        pending.pending = true;
        pending.pendingHost = "host";
        pending.pendingApp = 42;
        pending.recentlyEnded = true;
        assertEquals(SessionSnapshot.State.NONE, resolver.resolve(pending.build()).state);
    }

    @Test public void retainedTransportRestoresActiveDespiteRecentEndMarker() {
        Facts retained = new Facts();
        retained.recentlyEnded = true;
        retained.retainedState = RetainedStreamSessionCoordinator.State.HOME_LIVE;
        retained.retainedHost = "host";
        retained.retainedApp = 42;
        assertEquals(SessionSnapshot.State.ACTIVE,
                resolver.resolve(retained.build()).state);
    }

    @Test public void recentEndMarkerSuppressesCorrelatedStaleHostState() {
        Facts facts = new Facts();
        facts.recentlyEnded = true;
        facts.runningApp = 42;
        facts.resolvedGame = "game-b";
        facts.bridgeState = "idle";

        SessionSnapshot snapshot = resolver.resolve(facts.build());
        assertEquals(SessionSnapshot.State.NONE, snapshot.state);
        assertEquals(PlayniteIdentityResolutionPolicy.Action.REQUEST,
                PlayniteIdentityResolutionPolicy.decide(
                        true, true, snapshot.state));
    }

    @Test public void hostSleepMarkerAloneNeverCreatesGameSession() {
        Facts facts = new Facts();
        facts.sleepHost = "host";
        facts.sleepRequested = true;
        facts.sleepObserved = true;

        SessionSnapshot snapshot = resolver.resolve(facts.build());

        assertEquals(SessionSnapshot.State.NONE, snapshot.state);
        assertFalse(snapshot.isResumeAvailable());
        assertTrue(snapshot.hostSleepRequested);
        assertTrue(snapshot.hostSleepObserved);

        facts.sleepHost = "other";
        snapshot = resolver.resolve(facts.build());
        assertFalse(snapshot.hostSleepRequested);
    }

    @Test public void onlineZeroPollKeepsExplicitSuspensionResumableAndCorrelated() {
        Facts facts = suspendedFacts();
        facts.hostOnline = true;
        facts.suspendedId = "suspend-a";

        SessionSnapshot snapshot = resolver.resolve(facts.build());

        assertEquals(SessionSnapshot.State.SUSPENDED, snapshot.state);
        assertEquals("suspend-a", snapshot.suspendId);
        assertTrue(snapshot.isSuspended());
        assertTrue(snapshot.isResumeAvailable());
    }

    @Test public void resolvingSameObservationsIsPureAndRepeatable() {
        Facts facts = suspendedFacts();
        SessionStateResolver.Observations observations = facts.build();

        SessionSnapshot first = resolver.resolve(observations);
        SessionSnapshot second = resolver.resolve(observations);

        assertEquals(first, second);
        assertEquals(SessionSnapshot.State.SUSPENDED, first.state);
    }

    @Test public void neutralSessionWithoutGameNeverOffersResume() {
        for (RetainedStreamSessionCoordinator.State retainedState : new RetainedStreamSessionCoordinator.State[] {
                RetainedStreamSessionCoordinator.State.HOME_LIVE,
                RetainedStreamSessionCoordinator.State.PARKED_LIVE }) {
            Facts facts = new Facts();
            facts.retainedState = retainedState;
            facts.retainedHost = "host";
            facts.retainedApp = 42;
            facts.neutralStreamTarget = true;
            assertFalse(resolver.resolve(facts.build()).isResumeAvailable());
        }

        Facts sunshine = new Facts();
        sunshine.runningApp = 42;
        sunshine.neutralStreamTarget = true;
        assertFalse(resolver.resolve(sunshine.build()).isResumeAvailable());

        Facts reconnect = new Facts();
        reconnect.pending = true;
        reconnect.pendingHost = "host";
        reconnect.pendingApp = 42;
        reconnect.neutralStreamTarget = true;
        assertFalse(resolver.resolve(reconnect.build()).isResumeAvailable());
    }

    @Test public void neutralTargetWithRealGameKeepsResumeIdentity() {
        Facts facts = new Facts();
        facts.runningApp = 42;
        facts.resolvedGame = "game";
        facts.bridgeState = "running";
        facts.neutralStreamTarget = true;

        SessionSnapshot snapshot = resolver.resolve(facts.build());

        assertTrue(snapshot.isResumeAvailable());
        assertEquals("game", snapshot.playniteGameId);
    }

    private static Facts suspendedFacts() {
        Facts facts = new Facts();
        facts.suspendedHost = "host";
        facts.suspendedApp = 42;
        facts.suspendedGame = "game";
        facts.suspendedResumedAt = 0L;
        return facts;
    }

    private static final class Facts {
        String host = "host";
        int runningApp;
        String resolvedGame = "";
        RetainedStreamSessionCoordinator.State retainedState =
                RetainedStreamSessionCoordinator.State.NONE;
        String retainedHost = "";
        int retainedApp;
        String retainedGame = "";
        String suspendedHost = "";
        int suspendedApp;
        String suspendedGame = "";
        long suspendedResumedAt = -1L;
        long suspendedSleepObservedAt;
        boolean recentlyEnded;
        boolean pending;
        String pendingHost = "";
        int pendingApp;
        String sleepHost = "";
        boolean sleepRequested;
        boolean sleepObserved;
        boolean hostOnline = true;
        String bridgeState = "";
        String suspendedId = "";
        boolean neutralStreamTarget;
        boolean retainedOwnerLive = true;

        SessionStateResolver.Observations build() {
            SessionStateResolver.Observations observations =
                    new SessionStateResolver.Observations(host, runningApp, resolvedGame,
                    retainedState, retainedHost, retainedApp, retainedGame,
                    suspendedHost, suspendedApp, suspendedGame, suspendedResumedAt,
                    suspendedSleepObservedAt, recentlyEnded, pending, pendingHost,
                    pendingApp, sleepHost, sleepRequested, sleepObserved);
            observations.hostOnline = hostOnline;
            observations.bridgeGameState = bridgeState.isEmpty()
                    ? observations.bridgeGameState : bridgeState;
            observations.suspendedId = suspendedId;
            observations.neutralStreamTarget = neutralStreamTarget;
            observations.retainedOwnerLive = retainedOwnerLive;
            return observations;
        }
    }
}
