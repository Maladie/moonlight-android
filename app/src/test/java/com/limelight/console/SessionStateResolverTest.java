package com.limelight.console;

import com.limelight.stream.RetainedStreamSessionCoordinator;

import org.junit.Test;

import java.util.Collections;

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

    @Test public void explicitSuspensionOnlyWinsWithoutLiveEvidence() {
        Facts suspended = suspendedFacts();
        assertEquals(SessionSnapshot.State.SUSPENDED,
                resolver.resolve(suspended.build()).state);

        suspended.runningApp = 42;
        assertEquals(SessionSnapshot.State.ACTIVE,
                resolver.resolve(suspended.build()).state);
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

    @Test public void corroboratedRetainedOrPlayniteEvidenceRestoresActive() {
        Facts retained = new Facts();
        retained.recentlyEnded = true;
        retained.retainedState = RetainedStreamSessionCoordinator.State.HOME_LIVE;
        retained.retainedHost = "host";
        retained.retainedApp = 42;
        assertEquals(SessionSnapshot.State.ACTIVE,
                resolver.resolve(retained.build()).state);

        Facts correlated = new Facts();
        correlated.recentlyEnded = true;
        correlated.runningApp = 42;
        correlated.resolvedGame = "game";
        assertEquals(SessionSnapshot.State.ACTIVE,
                resolver.resolve(correlated.build()).state);
    }

    @Test public void tombstoneSuppressesRawHostUntilPlayniteIdentityCorroboratesIt() {
        Facts facts = new Facts();
        facts.recentlyEnded = true;
        facts.runningApp = 42;

        SessionSnapshot suppressed = resolver.resolve(facts.build());
        assertEquals(SessionSnapshot.State.NONE, suppressed.state);
        assertEquals(PlayniteIdentityResolutionPolicy.Action.REQUEST,
                PlayniteIdentityResolutionPolicy.decide(
                        true, true, facts.runningApp, suppressed.state));

        facts.resolvedGame = "game-b";
        SessionSnapshot corroborated = resolver.resolve(facts.build());
        PlayniteLibraryGame game = new PlayniteLibraryGame("game-b", "Game B", true,
                false, 0L, "", "", "", "Steam");
        PlayniteDashboardItem item = new PlayniteDashboardItem(game, 42, "MoonWaker",
                PlayniteDashboardItem.MappingState.MAPPED);
        PlayniteSessionPresentation.Projection projection =
                PlayniteSessionPresentation.project(corroborated,
                        Collections.singletonList(item), "");

        assertEquals(SessionSnapshot.State.ACTIVE, corroborated.state);
        assertEquals(PlayniteSessionPresentation.State.RESUME_ACTIVE,
                projection.stateFor("game-b"));
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
        boolean hostOnline;
        String suspendedId = "";

        SessionStateResolver.Observations build() {
            SessionStateResolver.Observations observations =
                    new SessionStateResolver.Observations(host, runningApp, resolvedGame,
                    retainedState, retainedHost, retainedApp, retainedGame,
                    suspendedHost, suspendedApp, suspendedGame, suspendedResumedAt,
                    suspendedSleepObservedAt, recentlyEnded, pending, pendingHost,
                    pendingApp, sleepHost, sleepRequested, sleepObserved);
            observations.hostOnline = hostOnline;
            observations.suspendedId = suspendedId;
            return observations;
        }
    }
}
