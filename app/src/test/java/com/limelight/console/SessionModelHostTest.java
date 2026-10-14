package com.limelight.console;

import com.limelight.Game;
import com.limelight.session.HostStreamRecord;
import com.limelight.session.HostSleepStateStore;
import com.limelight.session.SessionEvent;
import com.limelight.session.SessionModel;
import com.limelight.session.SessionModelDriver;
import com.limelight.session.SuspendedSessionStore;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Host polls and Gateway observations as session-model events (MW-04 §9 points 5, 6 and 8,
 * host sleep): the model alone decides what an observation changes; a reader only renders.
 */
public class SessionModelHostTest {
    private static final String TOKEN =
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    private SessionModelDriver m = new SessionModelDriver();

    private static final class Owner implements SessionModel.Controller {
        boolean live = true;
        @Override public boolean isRetainedTransportLive() { return live; }
        @Override public SessionModel.ParkOutcome parkRetainedTransport() {
            return SessionModel.ParkOutcome.PARKED;
        }
        @Override public void disconnectRetainedStream(
                SessionModel.TerminationCallback completion) { }
        @Override public void terminateRetainedSession(
                String gameId, SessionModel.TerminationCallback completion) { }
        @Override public void switchGame(SessionModel.SwitchRequest request,
                                         SessionModel.SwitchCallback completion) { }
    }

    // ---- MW-04 §9 point 5: transport lost in the background, then a reconnect ----------------

    @Test public void transportLostInTheBackgroundReconnectsWhileTheHostStillRunsIt() {
        Owner first = new Owner();
        m.enterHome(first, "s1", "host", "parent", 7, "game");
        saveReconnectRecord("s1", "host", 7, false);
        assertTrue(poll("host", true, 7).lostStreamSessionId.isEmpty());

        m.markReconnectRequired("s1");
        SessionModel.PollOutcome running = poll("host", true, 7);
        assertTrue("the host still runs it: nothing is lost", running.lostStreamSessionId.isEmpty());
        assertFalse(running.ended);
        assertEquals(SessionModel.State.RECONNECT_REQUIRED, m.state());
        assertEquals(SessionSnapshot.State.RECONNECT_REQUIRED, render("host").state);

        Owner second = new Owner();
        assertTrue(m.beginPreparing(second, "s1", "host", "parent", 7, "game", "t1", 1L));
        assertTrue(m.completePreparing(second, "s1", "host", 7, "t1", 1L));
        assertEquals(SessionModel.State.HOME_LIVE, m.state());
        assertTrue(poll("host", true, 7).lostStreamSessionId.isEmpty());
        assertEquals(SessionModel.State.HOME_LIVE, m.state());
    }

    @Test public void hostReportingZeroEndsOnlyTheMatchingStream() {
        m.enterHome(new Owner(), "s1", "host", "parent", 7, "game");
        m.markReconnectRequired("s1");
        saveReconnectRecord("s1", "host", 7, false);

        // Another host's 0, or this host's 0 while it is unreachable, ends nothing here.
        assertTrue(poll("other", true, 0).lostStreamSessionId.isEmpty());
        assertTrue(poll("host", false, 0).lostStreamSessionId.isEmpty());
        assertEquals(SessionModel.State.RECONNECT_REQUIRED, m.state());
        assertNotNull(m.model.records().pending);

        SessionModel.PollOutcome lost = poll("HOST", true, 0);
        assertEquals("s1", lost.lostStreamSessionId);
        assertEquals(SessionModel.State.NONE, m.state());
        assertNull(m.model.records().pending);
    }

    @Test public void hostReportingZeroAfterItsAppEndsTheLiveStreamItSaw() {
        m.enterHome(new Owner(), "s1", "host", "parent", 7, "game");
        // A first poll that saw nothing running yet is no end of anything.
        assertFalse(poll("host", true, 0).ended);
        assertEquals(SessionModel.State.HOME_LIVE, m.state());

        assertFalse(poll("host", true, 7).ended);
        // Another host's 0 does not end this host's stream.
        assertFalse(poll("other", true, 0).ended);
        SessionModel.PollOutcome ended = poll("host", true, 0);
        assertTrue(ended.ended);
        assertEquals("s1", ended.endedStreamSessionId);
        assertEquals(SessionModel.State.NONE, m.state());
    }

    @Test public void hostZeroAnswersTheExactLostRetainedSessionThatStreamHomeFinishes() {
        m.enterHome(new Owner(), "s1", "host", "parent", 7, "game");
        // An auto-resuming reconnect record is no exemption from a lost transport.
        saveReconnectRecord("s1", "host", 7, true);
        assertEquals("a live stream's transport is not lost", "",
                poll("host", true, 0).lostStreamSessionId);
        assertEquals(SessionModel.State.HOME_LIVE, m.state());

        m.markReconnectRequired("s1");
        // ConsoleActivity.reportHostPoll finishes the StreamHome launched for exactly this id.
        assertEquals("s1", poll("host", true, 0).lostStreamSessionId);
        assertNull(m.model.records().pending);
        assertEquals(SessionModel.State.NONE, m.state());
    }

    @Test public void hostZeroEndsTheStreamItSawAndTheSuspendedSessionThatStreamResumed() {
        m.post(new SessionEvent.SuspendedSaved(new SuspendedSessionStore.Session(
                "suspend-a", "host", "parent", 7, "game", "Game", "", 1L, "parent")));
        m.enterHome(new Owner(), "s1", "host", "parent", 7, "game");
        assertTrue(m.call(new SessionEvent.SuspendedResumed("suspend-a", "host", "parent", 7,
                "game", "s1")));
        assertFalse(poll("host", true, 7).ended);
        // The stream leaves the model before the host reports 0: the identity the poll
        // remembered, not the model's state at the edge, names what ended.
        assertTrue(m.clearIfMatches("s1"));
        assertNotNull(m.model.records().suspendedForExecution("host", "parent"));

        SessionModel.PollOutcome ended = poll("host", true, 0);
        assertTrue(ended.ended);
        assertEquals("s1", ended.endedStreamSessionId);
        assertNull(m.model.records().suspendedForExecution("host", "parent"));
    }

    @Test public void hardResetForgetsTheLastPollOfItsHost() {
        m.enterHome(new Owner(), "s1", "host", "parent", 7, "game");
        poll("host", true, 7);
        m.hardResetIfHostMatches("host");
        m.enterHome(new Owner(), "s2", "host", "parent", 7, "game");
        assertFalse("no edge from a poll before the reset", poll("host", true, 0).ended);
        assertEquals(SessionModel.State.HOME_LIVE, m.state());
    }

    // ---- MW-04 §9 point 6: observation A arriving after a newer B -----------------------------

    @Test public void olderObservationArrivingAfterANewerOneIsRejected() {
        long a = dispatch("host");
        long b = dispatch("host");
        assertNotNull(observe("host", ready("boot", 2L, b)).accepted);

        SessionModel.ObservationResult late = observe("host", ready("boot", 1L, a));
        assertNull(late.accepted);
        assertEquals(b, late.projection.requestOrdinal);
        assertEquals(b, render("host").requestOrdinal);

        // A current dispatch with an older producer revision is stale too.
        long c = dispatch("host");
        assertNull(observe("host", ready("boot", 2L, c)).accepted);
        // A restarted producer is accepted; its retired boot never again.
        long d = dispatch("host");
        assertNotNull(observe("host", ready("boot-2", 1L, d)).accepted);
        long e = dispatch("host");
        assertNull(observe("host", ready("boot", 9L, e)).accepted);
        assertEquals("boot-2", render("host").producerBootId);
    }

    @Test public void observationForAnotherHostChangesNothingHere() {
        m.enterHome(new Owner(), "s1", "host", "parent", 7, "game");
        SessionModel.Snapshot before = m.snapshot();
        SessionSnapshot hostBefore = render("host");

        long other = dispatch("other");
        assertNotNull(observe("other", ready("boot", 1L, other)).accepted);

        assertEquals(hostBefore, render("host"));
        assertEquals(before.state, m.snapshot().state);
        assertEquals(before.streamSessionId, m.snapshot().streamSessionId);
        assertEquals("game", render("other").playniteGameId);
    }

    @Test public void terminationBoundaryRetiresTheObservationInTheSameTransition() {
        Owner owner = new Owner();
        m.enterHome(owner, "session", "host", "parent", 42, "game");
        assertTrue(m.bindControlGeneration(owner, "session", 1L));
        long ordinal = dispatch("host");
        assertEquals(SessionSnapshot.ObservationState.READY,
                observe("host", ready("boot", 1L, ordinal)).accepted.observationState);

        SessionModel.DirectTermination close = m.beginDirectTermination(m.snapshot());
        assertNotNull(close);
        SessionSnapshot closing = render("host");
        assertEquals(SessionSnapshot.State.TERMINATING, closing.state);
        assertEquals(SessionSnapshot.ObservationState.PENDING, closing.observationState);
        assertEquals("", closing.processToken);

        // The finished close reads as closing only within its bound, timed from the boundary.
        assertTrue(m.completeDirectTermination(close, true));
        assertEquals(SessionSnapshot.State.TERMINATING, render("host").state);
        m.now += SessionStateResolver.CLOSING_CONFIRMATION_MS;
        assertEquals(SessionSnapshot.State.UNCERTAIN, render("host").state);
    }

    // ---- MW-04 §9 point 8: process death with a saved session ---------------------------------

    @Test public void processDeathWithASavedSessionStartsWithoutALiveConnection() {
        m.enterHome(new Owner(), "s1", "host", "parent", 7, "game");
        saveReconnectRecord("s1", "host", 7, false);
        // The process dies: a new model starts over the same stores.
        m = new SessionModelDriver(m.resume, m.suspended, m.sleep);

        assertEquals(SessionModel.State.NONE, m.state());
        assertFalse(m.canResumeInstantly());
        assertNotNull(m.model.records().pending);
        SessionSnapshot unknown = render("host");
        assertFalse("resume material is not a live connection", unknown.retainedTransport);
        assertFalse("nothing names the stream yet", unknown.isReconnectRequired());

        // The Gateway names the recorded stream as held: it reads as paused, Play reconnects.
        long ordinal = dispatch("host");
        assertNotNull(observe("host", ready("boot", 1L, ordinal, "s1")).accepted);
        assertEquals(SessionSnapshot.State.RECONNECT_REQUIRED, render("host").state);
        assertFalse(render("host").retainedTransport);
    }

    // ---- host sleep observation ----------------------------------------------------------------

    @Test public void hostSleepIsObservedDownAndClearedOnceTheHostAnswersAgain() {
        m.post(new SessionEvent.HostSleepRequested("host", 1_000L));
        poll("host", true, 0);
        assertEquals(0L, m.model.records().hostSleep("host").sleepObservedAt);
        assertTrue(render("host").hostSleepRequested);

        poll("host", false, 0);
        HostSleepStateStore.State observed = m.model.records().hostSleep("host");
        assertTrue(observed.sleepObservedAt > 0L);
        assertTrue(render("host").hostSleepObserved);
        // Another host's poll is no word on this one.
        poll("other", true, 0);
        assertNotNull(m.model.records().hostSleep("host"));

        poll("host", true, 0);
        assertNull(m.model.records().hostSleep("host"));
    }

    @Test public void suspendedSessionSleepIsObservedAndAnotherVerifiedGameEndsIt() {
        m.post(new SessionEvent.SuspendedSaved(new SuspendedSessionStore.Session(
                "suspend-a", "host", "parent", 7, "game", "Game", "", 1L, "parent")));
        poll("host", false, 0);
        assertTrue(m.model.records().suspendedForExecution("host", "parent")
                .sleepObservedAt > 0L);
        assertEquals(SessionSnapshot.State.SUSPENDED, render("host").state);

        // Decision 3: the Gateway's verified game, not Sunshine's app, ends the suspension.
        long same = dispatch("host");
        observe("host", ready("boot", 1L, same));
        assertNotNull(m.model.records().suspendedForExecution("host", "parent"));
        long other = dispatch("host");
        observe("host", observation("boot", 2L, other, "other-game", "session"));
        assertNull(m.model.records().suspendedForExecution("host", "parent"));
    }

    @Test public void unreachableHostAndForgottenHostDropOnlyTheirOwnProjection() {
        long host = dispatch("host");
        observe("host", ready("boot", 1L, host));
        long other = dispatch("other");
        observe("other", ready("boot", 1L, other));
        assertEquals("game", render("host").display.runningGameId);

        poll("host", false, 0);
        assertTrue(SessionSnapshot.Display.NONE == render("host").display);
        assertEquals("game", render("other").display.runningGameId);

        m.post(new SessionEvent.HostForgotten("HOST"));
        assertEquals(SessionSnapshot.ObservationState.PENDING,
                render("host").observationState);
        assertEquals("boot", render("other").producerBootId);
    }

    @Test public void childOnItsParentsProfileSeesAndEndsOnlyItsOwnGame() {
        m.post(new SessionEvent.SuspendedSaved(new SuspendedSessionStore.Session(
                "suspend-c", "host", "child", 7, "child-game", "Game", "", 1L, "parent")));
        long first = m.call(new SessionEvent.ObservationDispatched("host", "child", "parent"))
                .ordinal;
        // The parent's game on the shared profile: no process start is told to the child.
        assertNotNull(m.call(new SessionEvent.HostObserved("host", "child", "parent",
                observation("boot", 1L, first, "parent-game", "session", "child", 0L)))
                .accepted);
        assertNotNull("the parent's game does not end the child's suspension",
                m.model.records().suspendedForExecution("host", "parent"));
        assertEquals("", render("host", "child").display.runningGameId);

        long second = m.call(new SessionEvent.ObservationDispatched("host", "child", "parent"))
                .ordinal;
        assertNotNull(m.call(new SessionEvent.HostObserved("host", "child", "parent",
                observation("boot", 2L, second, "other-game", "session", "child", 500L)))
                .accepted);
        assertNull("the child's own other game ends its suspension",
                m.model.records().suspendedForExecution("host", "parent"));
        assertEquals("other-game", render("host", "child").display.runningGameId);
    }

    @Test public void lateEventsOfAForgottenHostRecreateNothing() {
        long token = m.call(new SessionEvent.LocalCloseBegun("host", "parent", "parent", false));
        long ordinal = dispatch("host");
        poll("host", true, 7);
        m.post(new SessionEvent.HostForgotten("host"));

        // Replies and completions of work begun before the forget arrive afterwards.
        m.post(new SessionEvent.LocalCloseFinished("host", "parent", "parent", token, true));
        assertNull(observe("host", ready("boot", 1L, ordinal)));
        assertNull(m.call(new SessionEvent.ObservationUnavailable("host", "parent", "parent",
                ordinal, "late")));
        poll("host", true, 7);
        assertNull(m.snapshot().projection(SessionStateResolver.key("host", "parent", "parent")));
        assertEquals("a re-paired host does not read as closing", SessionSnapshot.State.UNCERTAIN,
                render("host").state);

        // Re-paired: a new read starts it again, and no app from before the forget ends.
        dispatch("host");
        assertFalse(poll("host", true, 0).ended);
    }

    @Test public void aTransitionThatThrowsAfterChangingAProjectionStillPublishesIt() {
        dispatch("host");
        m.diagnostics = (level, component, event, fields) -> {
            throw new IllegalStateException("diagnostics");
        };
        assertEquals(0L, (long) m.call(
                new SessionEvent.LocalCloseBegun("host", "parent", "parent", false)));
        assertEquals(SessionSnapshot.State.TERMINATING, render("host").state);
    }

    // ---- local closes and the host stream record ----------------------------------------------

    @Test public void localCloseReadsClosingUntilTheHostConfirmsItFree() {
        long token = m.call(new SessionEvent.LocalCloseBegun("host", "parent", "parent", false));
        assertEquals(SessionSnapshot.State.TERMINATING, render("host").state);
        m.post(new SessionEvent.LocalCloseFinished("host", "parent", "parent", token + 1L, true));
        assertEquals("a stale token ends nothing", SessionSnapshot.State.TERMINATING,
                render("host").state);

        m.post(new SessionEvent.LocalCloseFinished("host", "parent", "parent", token, true));
        assertEquals(SessionSnapshot.State.TERMINATING, render("host").state);
        long ordinal = dispatch("host");
        observe("host", idle("boot", ordinal));
        assertEquals(SessionSnapshot.State.NONE, render("host").state);
    }

    @Test public void hostStreamRecordRetiresOnlyAnUnchangedRecordTheModelDoesNotOwn() {
        saveReconnectRecord("s1", "host", 7, false);
        // A record that changed since the read keeps everything.
        assertEquals(HostStreamRecord.Action.KEEP, streamRecord("s0", null));
        // A start of this host owns its records meanwhile.
        Owner owner = new Owner();
        assertTrue(m.beginPreparing(owner, "s2", "host", "parent", 7, "", "t1", 1L));
        assertEquals(HostStreamRecord.Action.KEEP, streamRecord("s1", null));
        assertNotNull(m.model.records().pending);
        assertTrue(m.cancelPreparing(owner, "s2", "host", 7, "t1", 1L));

        assertEquals(HostStreamRecord.Action.RETIRE, streamRecord("s1", null));
        assertNull(m.model.records().pending);
    }

    // ---- helpers ---------------------------------------------------------------------------------


    private SessionModel.PollOutcome poll(String host, boolean online, int runningAppId) {
        return m.call(new SessionEvent.HostPolled(host, "parent", "parent", online, !online,
                runningAppId, 5_000L));
    }

    private long dispatch(String host) {
        return m.call(new SessionEvent.ObservationDispatched(host, "parent", "parent")).ordinal;
    }

    private SessionModel.ObservationResult observe(String host,
                                                   HostGatewayClient.SessionObservation seen) {
        return m.call(new SessionEvent.HostObserved(host, "parent", "parent", seen));
    }

    private HostStreamRecord.Action streamRecord(String expectedLocal,
                                                 com.limelight.gateway.ControlOperationClient
                                                         .Permit owned) {
        return m.call(new SessionEvent.HostStreamRecordObserved("host", "parent", "parent",
                expectedLocal, owned, null));
    }

    private SessionSnapshot render(String host) {
        return render(host, "parent");
    }

    private SessionSnapshot render(String host, String actor) {
        SessionModel.Snapshot session = m.snapshot();
        HostSleepStateStore.State sleep = session.records.hostSleep(host);
        return SessionStateResolver.render(session, host, actor, "parent",
                new SessionStateResolver.LocalFacts(
                        session.records.suspendedForExecution(host, "parent"),
                        session.records.recentlyEndedForExecution(host, "parent"),
                        session.records.pending, sleep != null,
                        sleep != null && sleep.sleepObservedAt > 0L, false, 0), m.now);
    }

    /** Writes a reconnect record as Game stores it, then lets the model read its stores. */
    private void saveReconnectRecord(String sessionId, String host, int appId,
                                     boolean autoResume) {
        m.resume.edit().putBoolean("pending", true).putBoolean("autoResume", autoResume)
                .putString("streamSessionId", sessionId).putString("pcUuid", host)
                .putString("profileId", "parent")
                .putString(Game.EXTRA_EXECUTION_PROFILE_ID, "parent")
                .putInt("appId", appId).apply();
        m.refreshRecords();
    }

    private HostGatewayClient.SessionObservation ready(String boot, long revision, long ordinal) {
        return ready(boot, revision, ordinal, "session");
    }

    private HostGatewayClient.SessionObservation ready(String boot, long revision, long ordinal,
                                                       String session) {
        return observation(boot, revision, ordinal, "game", session);
    }

    private static HostGatewayClient.SessionObservation observation(
            String boot, long revision, long ordinal, String gameId, String session) {
        return observation(boot, revision, ordinal, gameId, session, "parent", 0L);
    }

    private static HostGatewayClient.SessionObservation observation(
            String boot, long revision, long ordinal, String gameId, String session,
            String actor, long processStartedUnixMs) {
        HostGatewayClient.SessionObservation seen = new HostGatewayClient.SessionObservation(
                boot, revision, 1_000L, 15_000L,
                "running", 1_000L, actor, "parent", ordinal, "", "operation", session, 1L,
                1L, "running", HostGatewayClient.Evidence.VERIFIED, "running",
                HostGatewayClient.Evidence.VERIFIED, gameId,
                HostGatewayClient.Evidence.VERIFIED, 42, TOKEN,
                HostGatewayClient.Evidence.VERIFIED, true, "game", 3, "target_window_ready",
                false, true, false);
        seen.processStartedUnixMs = processStartedUnixMs;
        return seen;
    }

    private static HostGatewayClient.SessionObservation idle(String boot, long ordinal) {
        return new HostGatewayClient.SessionObservation(boot, ordinal, 1_000L, 15_000L,
                "idle", 1_000L, "parent", "parent", ordinal, "", "operation", "", 0L, 1L,
                "idle", HostGatewayClient.Evidence.VERIFIED, "idle",
                HostGatewayClient.Evidence.ABSENT, "", HostGatewayClient.Evidence.ABSENT, 0, "",
                HostGatewayClient.Evidence.ABSENT, false, "game", 0, "no_running_game",
                false, true, false);
    }
}
