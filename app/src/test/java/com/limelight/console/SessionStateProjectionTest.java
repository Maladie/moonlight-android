package com.limelight.console;

import com.limelight.session.SessionEvent;
import com.limelight.session.SessionModel;
import com.limelight.session.SessionModelDriver;
import com.limelight.session.SessionResumeManager;
import com.limelight.session.SuspendedSessionStore;


import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class SessionStateProjectionTest {
    private final SessionModelDriver m = new SessionModelDriver();
    private static final String TOKEN =
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    private SessionStateResolver.ProjectionOwner owner;
    private SessionStateResolver.DiagnosticSink diagnostics;

    /** A standalone projection owner; its records go to {@link #diagnostics}. */
    private SessionStateResolver.ProjectionOwner newOwner(String host, String actor,
                                                          String execution) {
        return new SessionStateResolver.ProjectionOwner(
                (level, component, event, fields) -> diagnostics.record(level, component, event,
                        fields), host, actor, execution);
    }

    // The model's own projection, driven through real dispatch and reply events.
    private long dispatch(String host) {
        return m.call(new SessionEvent.ObservationDispatched(host, "child", "parent")).ordinal;
    }

    private SessionModel.ObservationResult observe(
            String host, HostGatewayClient.SessionObservation observation) {
        return m.call(new SessionEvent.HostObserved(host, "child", "parent", observation));
    }

    /** The frozen projection the published model snapshot holds for {@code host}. */
    private SessionSnapshot held(String host, long nowMs) {
        return SessionStateResolver.render(m.snapshot(), host, "child", "parent",
                SessionStateResolver.LocalFacts.NONE, nowMs);
    }

    @Before public void setUp() {
        m.now = 0L;
        clearRetainedTransport();
        diagnostics = (level, component, event, fields) -> true;
        owner = newOwner("host", "child", "parent");
    }

    @After public void tearDown() {
        clearRetainedTransport();
    }

    private void clearRetainedTransport() {
        m.clear();
    }

    @Test public void delayedDispatchAndRetiredBootAreRejected() {
        long delayed = owner.nextRequestOrdinal();
        long current = owner.nextRequestOrdinal();
        assertNull(owner.accept(pending("boot-a", 1, delayed, "episode-a"),
                m.snapshot(), 0L));
        assertNotNull(owner.accept(pending("boot-a", 2, current, "episode-a"),
                m.snapshot(), 0L));

        long restarted = owner.nextRequestOrdinal();
        assertNotNull(owner.accept(pending("boot-b", 1, restarted, "episode-b"),
                m.snapshot(), 1L));
        long lateOldBoot = owner.nextRequestOrdinal();
        assertNull(owner.accept(pending("boot-a", 3, lateOldBoot, "episode-a"),
                m.snapshot(), 2L));
    }

    /**
     * The TV stopped its stream and the host did not confirm the end: one fact, held by the
     * model's projection for exactly that actor and execution profile. "Check again" is a
     * forced host read: silence keeps the fact, the host's answer resolves it. It restores no
     * local stream ownership.
     */
    @Test public void unconfirmedEndIsTheProjectionsFactUntilTheHostAnswers() {
        assertFalse(held("host", 0L).terminationUnconfirmed);
        m.call(new SessionEvent.TerminationUnconfirmed("host", "child", "parent"));

        SessionSnapshot unconfirmed = held("host", 0L);
        assertTrue(unconfirmed.terminationUnconfirmed);
        assertFalse(unconfirmed.isResumeAvailable());
        assertEquals(SessionModel.State.NONE, m.snapshot().state);
        // Another actor on the same host never sees it.
        assertFalse(SessionStateResolver.render(m.snapshot(), "host", "parent", "parent",
                SessionStateResolver.LocalFacts.NONE, 0L).terminationUnconfirmed);

        // Check again, the host does not answer: still unconfirmed, nothing invented.
        long unanswered = dispatch("host");
        m.call(new SessionEvent.ObservationUnavailable("host", "child", "parent", unanswered,
                "observation_unavailable"));
        assertTrue(held("host", 1_000L).terminationUnconfirmed);

        // Check again, the host answers: its observation tells what runs from here.
        assertNotNull(observe("host", idle("boot-a", dispatch("host"))).accepted);
        assertFalse(held("host", 2_000L).terminationUnconfirmed);
    }

    @Test public void firstRenderWithoutObservationIsPendingAndUncertain() {
        SessionSnapshot snapshot = owner.current(
                m.snapshot(), 0L);

        assertEquals(SessionSnapshot.ObservationState.PENDING, snapshot.observationState);
        assertEquals(SessionSnapshot.State.UNCERTAIN, snapshot.state);
        assertEquals("", snapshot.playniteGameId);
    }

    @Test public void suspensionPreservesLocalStateButNotUnverifiedGameIdentity() {
        SuspendedSessionStore.Session suspended = new SuspendedSessionStore.Session(
                "suspend-a", "host", "child", 42, "game", "Game", "", 1L,
                "parent");
        SessionStateResolver.LocalFacts local = new SessionStateResolver.LocalFacts(
                suspended, false, null, false, false, false);

        SessionSnapshot snapshot = owner.rendered(m.snapshot(), local, 0L);

        assertEquals(SessionSnapshot.State.SUSPENDED, snapshot.state);
        assertEquals(SessionSnapshot.ObservationState.PENDING, snapshot.observationState);
        assertEquals("", snapshot.playniteGameId);
        assertEquals("suspend-a", snapshot.suspendId);
    }

    /** Decision 3: the Gateway's observation, not Sunshine's running app, names the stream. */
    @Test public void hostAdoptedStreamRecordReadsAsPausedAndPlayReconnectsIt() {
        // The record the console writes for a stream the host kept for this actor while the
        // app was gone: autoResume false, so it counts only while the Gateway names its stream.
        SessionResumeManager.PendingSession adopted = SessionModelDriver.reconnectRecord(
                "session", "host", "child", "parent", 42, "game", false);
        assertFalse("no observation names it yet", owner.rendered(m.snapshot(),
                local(adopted), 0L).isReconnectRequired());
        long ordinal = owner.nextRequestOrdinal();
        assertNotNull(owner.accept(ready("boot-a", ordinal), m.snapshot(), 0L));

        SessionSnapshot paused = owner.rendered(m.snapshot(), local(adopted), 0L);

        assertEquals(SessionSnapshot.State.RECONNECT_REQUIRED, paused.state);
        assertFalse(paused.retainedTransport);
        assertTrue(ConsoleHostPresentation.holdsPausedSession(
                ConsoleHostPresentation.State.ONLINE, paused));
        SessionOrchestrator.State play = SessionOrchestrator.State.forPlay(paused,
                false, false, false, false, true, true, false, true, true);
        assertEquals(SessionOrchestrator.ActionKind.RECONNECT, SessionOrchestrator.transition(
                play, SessionOrchestrator.UserIntent.play(PlayIntent.playniteGame(
                        "host", "child", 42, "Game", false, "game", "game"))).kind);

        // A record of a stream the Gateway does not name is nothing to resume.
        SessionResumeManager.PendingSession gone = SessionModelDriver.reconnectRecord(
                "gone-stream", "host", "child", "parent", 42, "game", false);
        assertFalse(owner.rendered(m.snapshot(), local(gone), 0L).isReconnectRequired());
        // Another execution profile's record is never this profile's session.
        SessionResumeManager.PendingSession foreign = SessionModelDriver.reconnectRecord(
                "session", "host", "other", "other", 42, "game", false);
        assertFalse(owner.rendered(m.snapshot(), local(foreign), 0L).isReconnectRequired());
    }

    private static SessionStateResolver.LocalFacts local(
            SessionResumeManager.PendingSession pending) {
        return new SessionStateResolver.LocalFacts(null, false, pending, false, false, false);
    }
    @Test public void acceptReturnsTheProjectionOfItsOwnObservationNotALaterOne() {
        long first = owner.nextRequestOrdinal();
        SessionSnapshot own = owner.accept(pending("boot-a", 1, first, "episode-a"),
                m.snapshot(), 0L);
        long second = owner.nextRequestOrdinal();
        assertNotNull(owner.accept(pending("boot-a", 2, second, "episode-a"),
                m.snapshot(), 1L));

        assertEquals(first, own.requestOrdinal);
        assertEquals(second, owner.current(
                m.snapshot(), 2L).requestOrdinal);
    }

    @Test public void unknownEpisodeCannotEraseLiveTransportOrExtendItsDeadline() {
        m.enterHome(new Controller(), "session", "host",
                "child", 42, "game");
        long first = owner.nextRequestOrdinal();
        assertNotNull(owner.accept(pending("boot-a", 1, first, "episode-a"),
                m.snapshot(), 0L));
        long second = owner.nextRequestOrdinal();
        assertNotNull(owner.accept(pending("boot-a", 2, second, "episode-a"),
                m.snapshot(), 10_000L));

        SessionSnapshot expired = owner.current(
                m.snapshot(), 15_000L);
        assertEquals(SessionSnapshot.State.ACTIVE, expired.state);
        assertTrue(expired.retainedTransport);
        assertTrue(expired.transportGeneration > 0L);
        assertEquals(SessionSnapshot.ObservationState.RECOVERY_REQUIRED,
                expired.observationState);
    }

    @Test public void unavailableCannotRestartTheAcceptedUnknownEpisodeDeadline() {
        m.enterHome(new Controller(), "session", "host",
                "child", 42, "game");
        long first = owner.nextRequestOrdinal();
        assertNotNull(owner.accept(pending("boot-a", 1, first, "episode-a"),
                m.snapshot(), 0L));
        long failed = owner.nextRequestOrdinal();
        owner.unavailable(failed, m.snapshot(),
                10_000L, "offline");
        long second = owner.nextRequestOrdinal();
        assertNotNull(owner.accept(pending("boot-a", 2, second, "episode-a"),
                m.snapshot(), 16_000L));

        assertEquals(SessionSnapshot.ObservationState.RECOVERY_REQUIRED,
                owner.current(m.snapshot(), 16_000L)
                        .observationState);
    }

    /** The model projects a failed read against its state when the failure arrives. */
    @Test public void failedReadCannotPublishItsPreDispatchModelSnapshot() {
        SessionModelDriver model = new SessionModelDriver();
        long ordinal = model.call(new SessionEvent.ObservationDispatched(
                "host", "child", "parent")).ordinal;
        model.enterHome(new Controller(), "session", "host", "child", 42, "game");
        model.now = 16_000L;

        SessionSnapshot failed = model.call(new SessionEvent.ObservationUnavailable(
                "host", "child", "parent", ordinal, "timeout"));

        assertEquals(SessionSnapshot.State.ACTIVE, failed.state);
        assertTrue(failed.transportGeneration > 0L);
    }
    @Test public void unavailableWithoutTransportConvergesAndVerifiedFactsTerminate() {
        long failed = owner.nextRequestOrdinal();
        SessionSnapshot pending = owner.unavailable(
                failed, m.snapshot(), 0L, "offline");
        assertEquals(SessionSnapshot.ObservationState.PENDING, pending.observationState);
        SessionSnapshot unavailable = owner.current(
                m.snapshot(), 15_000L);
        assertEquals(SessionSnapshot.ObservationState.UNAVAILABLE,
                unavailable.observationState);
        assertEquals(SessionSnapshot.State.NONE, unavailable.state);

        SessionStateResolver.ProjectionOwner idleOwner = newOwner("other", "child", "parent");
        long ordinal = idleOwner.nextRequestOrdinal();
        assertNotNull(idleOwner.accept(idle("boot-idle", ordinal),
                m.snapshot(), 20_000L));
        assertEquals(SessionSnapshot.ObservationState.IDLE,
                idleOwner.current(m.snapshot(), 20_000L)
                        .observationState);
    }

    @Test public void readyRequiresExactVerifiedProcessAndWindowOnLiveTransport() {
        Controller controller = new Controller();
        m.enterHome(controller, "session", "host",
                "child", 42, "game");
        assertTrue(m.bindControlGeneration(
                controller, "session", 1L));
        long ordinal = owner.nextRequestOrdinal();
        assertNotNull(owner.accept(ready("boot-ready", ordinal),
                m.snapshot(), 0L));
        SessionSnapshot snapshot = owner.current(
                m.snapshot(), 0L);
        assertEquals(SessionSnapshot.ObservationState.READY, snapshot.observationState);
        assertEquals("child", snapshot.profileId);
        assertEquals("parent", snapshot.executionProfileId);
        assertEquals("VERIFIED", snapshot.processEvidence);
        assertEquals(TOKEN, snapshot.processToken);
    }

    @Test public void projectionDiagnosticCarriesAcceptedEvidenceButNotTheProcessToken() {
        Controller controller = new Controller();
        m.enterHome(controller, "session", "host",
                "child", 42, "game");
        assertTrue(m.bindControlGeneration(
                controller, "session", 1L));
        long ordinal = owner.nextRequestOrdinal();
        assertNotNull(owner.accept(ready("boot-ready", ordinal),
                m.snapshot(), 0L));
        SessionSnapshot snapshot = owner.current(
                m.snapshot(), 0L);

        java.util.Map<String, Object> fields = new java.util.HashMap<>();
        Object[] pairs = SessionStateResolver.projectionDiagnostic(
                snapshot, HostGatewayClient.Evidence.VERIFIED, 15_000L);
        for (int index = 0; index + 1 < pairs.length; index += 2) {
            fields.put((String) pairs[index], pairs[index + 1]);
        }
        assertEquals("child", fields.get("profile_id"));
        assertEquals("parent", fields.get("execution_profile_id"));
        assertEquals("READY", fields.get("observation_state"));
        assertEquals("VERIFIED", fields.get("game_evidence"));
        assertEquals("VERIFIED", fields.get("process_evidence"));
        assertEquals("boot-ready", fields.get("producer_boot_id"));
        assertEquals(ordinal, fields.get("request_ordinal"));
        assertEquals(snapshot.transportGeneration, fields.get("transport_generation"));
        assertEquals(12, ((String) fields.get("process_token_fp")).length());
        assertFalse(java.util.Arrays.asList(pairs).contains(TOKEN));
    }

    private final java.util.List<Object[]> recorded = new java.util.ArrayList<>();

    private void recordInto(boolean... results) {
        java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        diagnostics = (level, component, event, fields) -> {
            int call = calls.getAndIncrement();
            boolean queued = call < results.length ? results[call] : true;
            if (queued) {
                Object[] entry = new Object[fields.length + 1];
                entry[0] = event;
                System.arraycopy(fields, 0, entry, 1, fields.length);
                recorded.add(entry);
            }
            return queued;
        };
    }

    private static Object field(Object[] entry, String key) {
        for (int index = 1; index + 1 < entry.length; index += 2) {
            if (key.equals(entry[index])) return entry[index + 1];
        }
        return null;
    }

    @Test public void recordThatCannotBeQueuedIsRetriedOnTheNextPublication() {
        recordInto(false);
        long ordinal = owner.nextRequestOrdinal();
        assertNotNull(owner.accept(ready("boot-fresh", ordinal),
                m.snapshot(), 0L));
        assertEquals(0L, owner.diagnosticEventsForTests());

        owner.current(m.snapshot(), 100L);
        assertEquals(1L, owner.diagnosticEventsForTests());
        assertEquals("projection.published", recorded.get(0)[0]);
        owner.current(m.snapshot(), 200L);
        assertEquals(1L, owner.diagnosticEventsForTests());
    }

    @Test public void boundarySamplesRecordUnchangedAndUnpublishedProjections() {
        recordInto();
        SessionModelDriver model = new SessionModelDriver();
        model.diagnostics = diagnostics;
        model.now = 1_000L;
        long ordinal = model.call(new SessionEvent.ObservationDispatched(
                "host", "child", "parent")).ordinal;
        assertNotNull(model.call(new SessionEvent.HostObserved("host", "child", "parent",
                ready("boot-fresh", ordinal))).accepted);
        model.call(new SessionEvent.ObservationDispatched("other-host", "parent", "parent"));
        recorded.clear();

        model.now = 1_500L;
        assertEquals(2, (int) model.call(new SessionEvent.ProjectionsSampled("phase-17:start")));
        model.now = 2_000L;
        assertEquals(2, (int) model.call(new SessionEvent.ProjectionsSampled("phase-17:end")));
        assertEquals(4, recorded.size());
        Object[] first = recorded.get(0);
        assertEquals("projection.sampled", first[0]);
        assertEquals("phase-17:start", field(first, "sample_marker"));
        assertEquals("READY", field(first, "observation_state"));
        assertEquals(500L, field(first, "published_age_ms"));
        assertNotNull(field(first, "dropped"));
        assertEquals("phase-17:end", field(recorded.get(2), "sample_marker"));
        assertEquals(1_000L, field(recorded.get(2), "published_age_ms"));
        assertEquals("UNPUBLISHED", field(recorded.get(1), "observation_state"));
        assertEquals(-1L, field(recorded.get(1), "published_age_ms"));

        assertEquals(-1, (int) model.call(new SessionEvent.ProjectionsSampled("bad marker")));
        assertEquals(-1, (int) model.call(new SessionEvent.ProjectionsSampled(null)));
        assertEquals(4, recorded.size());

        // A sampling clock behind the publication clock is reported, not clamped to "fresh".
        model.now = 900L;
        assertEquals(2, (int) model.call(new SessionEvent.ProjectionsSampled("phase-17:skew")));
        assertEquals(-100L, field(recorded.get(4), "published_age_ms"));
    }
    @Test public void sampleConfirmationReportsUndrainedWriterAndRejectsBadMarkers() {
        recordInto();
        assertTrue(SessionStateResolver.confirmSamples(diagnostics, "phase-19:end"));
        Object[] confirmation = recorded.get(0);
        assertEquals("projection.sample_drained", confirmation[0]);
        assertEquals("phase-19:end", field(confirmation, "sample_marker"));
        assertEquals("not_drained", field(confirmation, "status"));
        assertEquals(-1L, field(confirmation, "dropped"));
        assertFalse(SessionStateResolver.confirmSamples(diagnostics, "bad marker"));
        assertEquals(1, recorded.size());
    }

    @Test public void projectionDiagnosticIsEmittedOncePerDistinctPublication() {
        recordInto();
        long ordinal = owner.nextRequestOrdinal();
        assertNotNull(owner.accept(ready("boot-fresh", ordinal),
                m.snapshot(), 0L));
        long afterAccept = owner.diagnosticEventsForTests();
        owner.current(m.snapshot(), 100L);
        owner.current(m.snapshot(), 200L);
        assertEquals(afterAccept, owner.diagnosticEventsForTests());

        owner.current(m.snapshot(), 20_000L);
        assertEquals(afterAccept + 1, owner.diagnosticEventsForTests());
    }

    @Test public void readyProjectionThatGoesStaleBetweenPollsGetsABoundedPendingWindow() {
        Controller controller = new Controller();
        m.enterHome(controller, "session", "host",
                "child", 42, "game");
        assertTrue(m.bindControlGeneration(
                controller, "session", 1L));
        long first = owner.nextRequestOrdinal();
        assertNotNull(owner.accept(ready("boot-ready", first),
                m.snapshot(), 0L));
        long second = owner.nextRequestOrdinal();
        // Same episode, provider freshness 5 s (the lab provider value), polled every ~6 s.
        HostGatewayClient.SessionObservation again = new HostGatewayClient.SessionObservation(
                "boot-ready", 2L, 1_000L, 5_000L, "running", 1_000L, "child", "parent",
                second, "operation", "session", 1L, 1L, "running",
                HostGatewayClient.Evidence.VERIFIED, "running",
                HostGatewayClient.Evidence.VERIFIED, "game",
                HostGatewayClient.Evidence.VERIFIED, 42, TOKEN,
                HostGatewayClient.Evidence.VERIFIED, true, "game", 3, "target_window_ready",
                false, true, false);
        assertNotNull(owner.accept(again, m.snapshot(), 20_000L));
        assertEquals(SessionSnapshot.ObservationState.READY,
                owner.current(m.snapshot(), 20_000L)
                        .observationState);

        SessionSnapshot stale = owner.current(m.snapshot(), 26_000L);
        assertEquals(SessionSnapshot.ObservationState.PENDING, stale.observationState);
        assertEquals(SessionSnapshot.State.ACTIVE, stale.state);

        assertEquals(SessionSnapshot.ObservationState.RECOVERY_REQUIRED,
                owner.current(m.snapshot(), 35_000L)
                        .observationState);
    }

    @Test public void freshStreamCanBecomeReadyBeforeItIsRetained() {
        long ordinal = owner.nextRequestOrdinal();
        assertNotNull(owner.accept(ready("boot-fresh", ordinal),
                m.snapshot(), 0L));

        SessionSnapshot snapshot = owner.current(
                m.snapshot(), 0L);
        assertEquals(SessionSnapshot.ObservationState.READY, snapshot.observationState);
        assertEquals(SessionSnapshot.State.UNCERTAIN, snapshot.state);
        assertFalse(snapshot.retainedTransport);
    }

    @Test public void idleGameEvidenceKeepsNeutralRetainedTransportReusable() {
        Controller controller = new Controller();
        m.enterHome(controller, "session", "host",
                "child", 42, "");
        assertTrue(m.bindControlGeneration(
                controller, "session", 1L));
        long ordinal = owner.nextRequestOrdinal();
        assertNotNull(owner.accept(idle("boot-idle-retained", ordinal),
                m.snapshot(), 0L));
        long refreshedOrdinal = owner.nextRequestOrdinal();
        assertNotNull(owner.accept(idle("boot-idle-retained", 2L, refreshedOrdinal),
                m.snapshot(), 10_000L));

        SessionSnapshot snapshot = owner.current(
                m.snapshot(), 16_000L);
        assertEquals(SessionSnapshot.ObservationState.IDLE, snapshot.observationState);
        assertEquals(SessionSnapshot.State.ACTIVE, snapshot.state);
        assertTrue(snapshot.retainedTransport);
        assertEquals("", snapshot.playniteGameId);
    }

    @Test public void unavailableAttemptIsCanonicalBeforeAnyAcceptedObservation() {
        long ordinal = owner.nextRequestOrdinal();
        owner.unavailable(ordinal, m.snapshot(), 0L, "offline");
        assertEquals(SessionSnapshot.ObservationState.UNAVAILABLE,
                owner.current(m.snapshot(), 15_000L)
                        .observationState);
    }

    @Test public void delayedFailureAfterNewerSuccessDoesNotReplaceReadyOrDeadline() {
        long delayed = owner.nextRequestOrdinal();
        long current = owner.nextRequestOrdinal();
        assertNotNull(owner.accept(ready("boot-ready", current),
                m.snapshot(), 0L));

        SessionSnapshot snapshot = owner.unavailable(delayed,
                m.snapshot(), 1_000L, "offline");

        assertEquals(SessionSnapshot.ObservationState.READY, snapshot.observationState);
        assertEquals("game", snapshot.playniteGameId);
        assertEquals("target_window_ready", snapshot.observationReason);
    }

    @Test public void mismatchedTransportCannotPublishGameProcessOrWindowEvidence() {
        Controller controller = new Controller();
        m.enterHome(controller, "session", "host",
                "child", 42, "");
        assertTrue(m.bindControlGeneration(
                controller, "session", 1L));
        long ordinal = owner.nextRequestOrdinal();
        assertNotNull(owner.accept(observation("boot-mismatch", 1L, ordinal, "running",
                        "running", HostGatewayClient.Evidence.VERIFIED, "game",
                        HostGatewayClient.Evidence.VERIFIED, 42, TOKEN,
                        HostGatewayClient.Evidence.VERIFIED, true, "target_window_ready",
                        "operation", "wrong-session"),
                m.snapshot(), 0L));

        SessionSnapshot snapshot = owner.current(
                m.snapshot(), 0L);
        assertFalse(snapshot.observationCorrelated);
        assertEquals(SessionSnapshot.ObservationState.PENDING, snapshot.observationState);
        assertEquals("", snapshot.playniteGameId);
        assertEquals("UNAVAILABLE", snapshot.processEvidence);
        assertEquals("UNAVAILABLE", snapshot.windowEvidence);
    }

    @Test public void expiredObservationWithoutMatchingTransportDropsGameIdentity() {
        long ordinal = owner.nextRequestOrdinal();
        assertNotNull(owner.accept(ready("boot-ready", ordinal),
                m.snapshot(), 0L));
        assertEquals("game", owner.current(
                m.snapshot(), 0L).playniteGameId);
        assertEquals("", owner.current(
                m.snapshot(), 15_001L).playniteGameId);
    }

    @Test public void reconnectRequiredWithStaleEvidenceNamesNoGame() {
        Controller controller = new Controller();
        m.enterHome(controller, "session", "host", "child", 42, "game");
        assertTrue(m.bindControlGeneration(controller, "session", 1L));
        m.markReconnectRequired("session");
        long ordinal = owner.nextRequestOrdinal();
        assertNotNull(owner.accept(ready("boot", ordinal), m.snapshot(), 0L));

        // Lost transport is never READY; once the episode expires it stays RECOVERY_REQUIRED,
        // and the game is named only while the evidence is fresh and correlated.
        SessionSnapshot fresh = owner.current(m.snapshot(), 15_000L);
        assertEquals(SessionSnapshot.State.RECONNECT_REQUIRED, fresh.state);
        assertEquals(SessionSnapshot.ObservationState.RECOVERY_REQUIRED,
                fresh.observationState);
        assertTrue(fresh.observationCorrelated);
        assertEquals("game", fresh.playniteGameId);

        SessionSnapshot stale = owner.current(m.snapshot(), 15_001L);
        assertEquals(SessionSnapshot.State.RECONNECT_REQUIRED, stale.state);
        assertEquals(SessionSnapshot.ObservationState.RECOVERY_REQUIRED,
                stale.observationState);
        assertFalse(stale.observationCorrelated);
        assertEquals("observation_stale", stale.observationReason);
        assertEquals("", stale.playniteGameId);
    }

    @Test public void recoveryIsExplicitFencedAndNextPlayHasFreshIdentity() {
        Controller controller = new Controller();
        m.enterHome(controller, "session", "host",
                "child", 42, "game");
        assertTrue(m.bindControlGeneration(
                controller, "session", 4L));
        SessionModel.Snapshot firstTransport =
                m.snapshot();
        long ordinal = owner.nextRequestOrdinal();
        assertNotNull(owner.accept(pending("boot-recovery", 1, ordinal, "episode-recovery"),
                firstTransport, 0L));

        SessionSnapshot recovery = owner.current(firstTransport, 15_000L);
        assertEquals(SessionSnapshot.ObservationState.RECOVERY_REQUIRED,
                recovery.observationState);
        assertEquals(SessionModel.State.HOME_LIVE,
                m.state());
        assertFalse(controller.disconnected);
        assertFalse(controller.terminated);

        // A Gateway control generation bound since then does not stale the recovery.
        assertTrue(m.bindControlGeneration(
                controller, "session", 5L));
        SessionModel.DirectTermination recoveryToken =
                m.beginDirectTermination(firstTransport);
        assertNotNull(recoveryToken);
        assertTrue(m.completeDirectTermination(
                recoveryToken, true));

        // The next Play is a new transport, with its own generation.
        Controller nextController = new Controller();
        assertTrue(m.beginPreparing(nextController,
                "session-next", "host", "child", 42, "game") > 0L);
        assertTrue(m.bindControlGeneration(
                nextController, "session-next", 5L));
        SessionModel.Snapshot nextTransport =
                m.snapshot();
        assertTrue(nextTransport.transportGeneration > firstTransport.transportGeneration);
        assertTrue(nextTransport.preparingOperation > 0L);
        assertFalse(m.completeDirectTermination(
                recoveryToken, true));
        assertEquals("session-next",
                m.snapshot().streamSessionId);

        long nextOrdinal = owner.nextRequestOrdinal();
        assertNotNull(owner.accept(readyForNextPlay("boot-next", nextOrdinal),
                nextTransport, 16_001L));
        SessionSnapshot nextPlay = owner.current(nextTransport, 16_001L);
        assertEquals("operation-next", nextPlay.operationId);
        assertEquals(nextTransport.transportGeneration, nextPlay.transportGeneration);
        assertEquals(SessionSnapshot.ObservationState.READY, nextPlay.observationState);
        assertTrue(m.completePreparing(nextController,
                "session-next", "host", 42, m.snapshot().preparingOperation));
    }

    @Test public void observationsBeforeTheLocalTerminationCannotPublishReadyAgain() {
        m.enterHome(new Controller(), "session", "host", "child", 42, "game");
        assertNotNull(observe("host", ready("boot", dispatch("host"))).accepted);
        assertEquals(SessionSnapshot.ObservationState.READY,
                held("host", 1L).observationState);
        long inFlight = dispatch("host");

        assertTrue(m.markTerminating("session", "host", 42, "game"));
        assertEquals(SessionSnapshot.ObservationState.PENDING,
                held("host", 2L).observationState);
        clearRetainedTransport();

        // The stale READY (observed before the verified stop) neither returns as the
        // projection nor lets a request dispatched before the termination finish.
        assertEquals(SessionSnapshot.ObservationState.PENDING,
                held("host", 3L).observationState);
        assertNull(observe("host", ready("boot", 2L, inFlight)).accepted);
        assertEquals(SessionSnapshot.ObservationState.PENDING,
                held("host", 5L).observationState);

        assertNotNull(observe("host", idle("boot", 3L, dispatch("host"))).accepted);
        assertEquals(SessionSnapshot.ObservationState.IDLE,
                held("host", 7L).observationState);
    }

    /** The boundary itself, without the model: both edges retire what was observed before. */
    @Test public void terminationBoundaryRetiresTheObservationAndEveryEarlierRead() {
        assertNotNull(owner.accept(ready("boot", owner.nextRequestOrdinal()),
                m.snapshot(), 0L));
        long inFlight = owner.nextRequestOrdinal();

        owner.terminationBoundary(false);
        SessionSnapshot entered = owner.current(m.snapshot(), 1L);
        assertEquals(SessionSnapshot.ObservationState.PENDING, entered.observationState);
        assertEquals("entering arms no closing window",
                SessionSnapshot.State.UNCERTAIN, entered.state);
        assertNull(owner.accept(ready("boot", 2L, inFlight), m.snapshot(), 2L));

        owner.terminationBoundary(true);
        assertEquals(SessionSnapshot.State.TERMINATING, owner.current(m.snapshot(), 3L).state);
        assertEquals(SessionSnapshot.State.UNCERTAIN, owner.current(m.snapshot(),
                3L + SessionStateResolver.CLOSING_CONFIRMATION_MS).state);
    }

    @Test public void verifiedCloseProjectsClosingUntilAFreshIdlePollIsAccepted() {
        // The snapshot SessionOrchestrator re-resolves right after a CLOSED result.
        m.enterHome(new Controller(), "session", "host", "child", 42, "game");
        assertNotNull(observe("host", ready("boot", dispatch("host"))).accepted);
        assertTrue(m.markTerminating("session", "host", 42, "game"));
        clearRetainedTransport();

        // Closing, not "uncertain": the console says so until the host is free.
        assertEquals(SessionSnapshot.State.TERMINATING, held("host", 3L).state);
        assertNotNull(observe("host", idle("boot", 3L, dispatch("host"))).accepted);
        assertEquals(SessionSnapshot.State.NONE, held("host", 7L).state);
    }

    @Test public void hostApiCloseReadsClosingUntilTheHostConfirmsItFree() {
        long first = owner.nextRequestOrdinal();
        assertNotNull(owner.accept(ready("boot", first), m.snapshot(), 0L));
        SessionModel.Snapshot none = m.snapshot();

        long close = owner.beginLocalClose(1L, false);
        assertEquals(SessionSnapshot.State.TERMINATING, owner.current(none, 1L).state);
        assertEquals(SessionSnapshot.State.TERMINATING,
                owner.rendered(none, SessionStateResolver.LocalFacts.NONE, 1L).state);
        long inFlight = owner.nextRequestOrdinal();
        owner.finishLocalClose(close, true);

        // A poll dispatched before the close finished describes the game going away.
        assertEquals(SessionSnapshot.State.TERMINATING, owner.current(none, 2L).state);
        assertNull(owner.accept(ready("boot", 2L, inFlight), none, 3L));
        assertEquals(SessionSnapshot.State.TERMINATING, owner.current(none, 4L).state);
        long afterwards = owner.nextRequestOrdinal();
        assertNotNull(owner.accept(idle("boot", 3L, afterwards), none, 5L));
        assertEquals(SessionSnapshot.State.NONE, owner.current(none, 6L).state);
    }

    @Test public void failedHostApiCloseRestoresTheProjectionItInterrupted() {
        long first = owner.nextRequestOrdinal();
        assertNotNull(owner.accept(ready("boot", first), m.snapshot(), 0L));
        SessionModel.Snapshot none = m.snapshot();
        SessionSnapshot before = owner.current(none, 1L);

        long close = owner.beginLocalClose(1L, false);
        owner.finishLocalClose(close, false);

        SessionSnapshot after = owner.current(none, 2L);
        assertEquals(before.state, after.state);
        assertEquals(SessionSnapshot.ObservationState.READY, after.observationState);
    }

    @Test public void anEarlierCloseCannotEndANewerOneButItsResultCounts() {
        long first = owner.nextRequestOrdinal();
        assertNotNull(owner.accept(ready("boot", first), m.snapshot(), 0L));
        SessionModel.Snapshot none = m.snapshot();
        long earlier = owner.beginLocalClose(1L, false);
        long newer = owner.beginLocalClose(2L, false);

        owner.finishLocalClose(earlier, true);
        SessionSnapshot afterEarlier = owner.current(none, 3L);
        assertEquals("the newer close keeps going",
                SessionSnapshot.State.TERMINATING, afterEarlier.state);
        assertEquals("the earlier close's success retired the observation",
                "observation_pending", afterEarlier.observationReason);
        owner.finishLocalClose(newer, false);
        assertEquals("the earlier success armed the closing window",
                SessionSnapshot.State.TERMINATING, owner.current(none, 4L).state);
        assertEquals(SessionSnapshot.State.UNCERTAIN, owner.current(none,
                3L + SessionStateResolver.CLOSING_CONFIRMATION_MS).state);
    }

    @Test public void aCloseWhoseTaskNeverFinishesReadsClosingOnlyWithinTheBound() {
        SessionModel.Snapshot none = m.snapshot();
        // The task threw or never ran (executor shut down): no finishLocalClose arrives.
        owner.beginLocalClose(100L, false);

        assertEquals(SessionSnapshot.State.TERMINATING, owner.current(none,
                100L + SessionStateResolver.CLOSING_CONFIRMATION_MS - 1L).state);
        assertEquals(SessionSnapshot.State.UNCERTAIN, owner.current(none,
                100L + SessionStateResolver.CLOSING_CONFIRMATION_MS).state);
        assertEquals(SessionSnapshot.State.UNCERTAIN, owner.rendered(none,
                SessionStateResolver.LocalFacts.NONE,
                100L + SessionStateResolver.CLOSING_CONFIRMATION_MS).state);
    }

    @Test public void endingAGameBesideALiveStreamKeepsTheStreamActive() {
        m.enterHome(new Controller(), "session", "host", "child", 42, "game");
        SessionModel.Snapshot live = m.snapshot();

        long stop = owner.beginLocalClose(1L, true);
        SessionSnapshot ending = owner.current(live, 2L);
        assertEquals(SessionSnapshot.State.ACTIVE, ending.state);
        assertTrue(ending.endingGame);
        assertTrue(ending.isResumeAvailable());

        owner.finishLocalClose(stop, true);
        SessionSnapshot after = owner.current(live, 3L);
        assertEquals(SessionSnapshot.State.ACTIVE, after.state);
        assertFalse(after.endingGame);
    }

    @Test public void endingAGameWithoutAStreamReadsClosing() {
        SessionSnapshot ending = owner.current(m.snapshot(), 2L);
        assertFalse(ending.endingGame);
        owner.beginLocalClose(1L, true);
        ending = owner.current(m.snapshot(), 2L);
        assertEquals(SessionSnapshot.State.TERMINATING, ending.state);
        assertTrue(ending.endingGame);
    }

    @Test public void runningGameIsHeldForTheStatusLineButNeverForADecision() {
        long ordinal = owner.nextRequestOrdinal();
        SessionSnapshot fresh = owner.accept(running(ordinal, 1, 0L, "game",
                HOST_NOW - 12 * 60_000L - 50_000L, 0L), m.snapshot(), 0L);
        assertEquals("game", fresh.playniteGameId);
        assertEquals("game|12|-1", fresh.display.key());

        // The observation is stale after 5 s: decisions lose the game, the status keeps it.
        SessionSnapshot stale = owner.current(m.snapshot(), 6_000L);
        assertEquals("", stale.playniteGameId);
        assertEquals(SessionSnapshot.ObservationState.PENDING, stale.observationState);
        assertEquals("observation_stale", stale.observationReason);
        assertEquals("game|12|-1", stale.display.key());

        // The key (and so a render) changes once a minute, on the host's clock.
        assertEquals("game|12|-1", owner.rendered(m.snapshot(),
                SessionStateResolver.LocalFacts.NONE, 9_999L).display.key());
        SessionSnapshot nextMinute = owner.rendered(m.snapshot(),
                SessionStateResolver.LocalFacts.NONE, 10_000L);
        assertEquals("game|13|-1", nextMinute.display.key());
        // The display is no part of the projection identity.
        assertEquals(stale, nextMinute);
        assertEquals(stale.signature(), nextMinute.signature());

        // Without a confirming observation the hold ends at its limit.
        assertEquals(45_000L, SessionStateResolver.STATUS_DISPLAY_MS);
        assertEquals("game", owner.current(m.snapshot(), 45_000L).display.runningGameId);
        assertTrue(SessionSnapshot.Display.NONE
                == owner.current(m.snapshot(), 45_001L).display);
    }

    @Test public void heldRunningGameFollowsLaterObservations() {
        long first = owner.nextRequestOrdinal();
        owner.accept(running(first, 1, 0L, "game", HOST_NOW - 60_000L, 0L), m.snapshot(), 0L);
        // Pending evidence says nothing; a confirming observation renews the hold.
        long second = owner.nextRequestOrdinal();
        assertNotNull(owner.accept(pending("boot-a", 2, second, "episode-b"),
                m.snapshot(), 40_000L));
        assertEquals("game", owner.current(m.snapshot(), 40_000L).display.runningGameId);
        long third = owner.nextRequestOrdinal();
        assertNotNull(owner.accept(running(third, 3, 44_000L, "game", HOST_NOW - 60_000L, 0L),
                m.snapshot(), 44_000L));
        assertEquals("game|2|-1", owner.current(m.snapshot(), 80_000L).display.key());

        // Another game replaces it with its own process start.
        long fourth = owner.nextRequestOrdinal();
        assertNotNull(owner.accept(running(fourth, 4, 81_000L, "other",
                HOST_NOW + 81_000L - 300_000L, 0L), m.snapshot(), 81_000L));
        assertEquals("other|5|-1", owner.current(m.snapshot(), 81_000L).display.key());

        // A game that is stopping, and an idle host, refute it.
        long fifth = owner.nextRequestOrdinal();
        assertNotNull(owner.accept(observation("boot-a", 5, fifth,
                "stopping", "stopping", HostGatewayClient.Evidence.VERIFIED, "other",
                HostGatewayClient.Evidence.VERIFIED, 42, TOKEN,
                HostGatewayClient.Evidence.PENDING, false, "stopping"),
                m.snapshot(), 82_000L));
        assertTrue(SessionSnapshot.Display.NONE
                == owner.current(m.snapshot(), 82_000L).display);
        long sixth = owner.nextRequestOrdinal();
        assertNotNull(owner.accept(running(sixth, 6, 83_000L, "game",
                HOST_NOW + 83_000L - 60_000L, 0L), m.snapshot(), 83_000L));
        assertEquals("game|1|-1", owner.current(m.snapshot(), 83_000L).display.key());
        long seventh = owner.nextRequestOrdinal();
        assertNotNull(owner.accept(idle("boot-a", 7, seventh), m.snapshot(), 84_000L));
        assertTrue(SessionSnapshot.Display.NONE
                == owner.current(m.snapshot(), 84_000L).display);
    }

    @Test public void childHoldsOnlyTheGameOfItsOwnSessionParentAnyRunningGame() {
        // The host tells a child no process start for a game that is not its own session's.
        long ordinal = owner.nextRequestOrdinal();
        SessionSnapshot foreign = owner.accept(
                running(ordinal, 1, 0L, "game", 0L, 0L), m.snapshot(), 0L);
        assertTrue(SessionSnapshot.Display.NONE == foreign.display);
        long own = owner.nextRequestOrdinal();
        assertEquals("game|3|-1", owner.accept(running(own, 2, 1_000L, "game",
                HOST_NOW - 3 * 60_000L, 0L), m.snapshot(), 1_000L).display.key());
        // The same game stops being the child's own: it is dropped at once.
        long later = owner.nextRequestOrdinal();
        assertTrue(SessionSnapshot.Display.NONE == owner.accept(
                running(later, 3, 2_000L, "game", 0L, 0L), m.snapshot(), 2_000L).display);

        SessionStateResolver.ProjectionOwner parent =
                newOwner("host", "parent", "parent");
        HostGatewayClient.SessionObservation seen = new HostGatewayClient.SessionObservation(
                "boot-a", 1L, HOST_NOW, 5_000L, "running",
                HOST_NOW, "parent", "parent", parent.nextRequestOrdinal(), "operation",
                "", 1L, 1L, "running", HostGatewayClient.Evidence.VERIFIED,
                "running", HostGatewayClient.Evidence.VERIFIED, "game",
                HostGatewayClient.Evidence.VERIFIED, 42, TOKEN,
                HostGatewayClient.Evidence.VERIFIED, true, "game", 3, "target_window_ready",
                false, true, false);
        assertEquals("game|-1|-1", parent.accept(seen, m.snapshot(), 0L).display.key());
    }

    @Test public void durationsOverTheLimitAreNotShown() {
        Controller controller = new Controller();
        m.enterHome(controller, "session", "host", "child", 42, "");
        assertTrue(m.bindControlGeneration(controller, "session", 1L));
        long limit = SessionStateResolver.MAX_STATUS_DURATION_MS;
        assertEquals(7L * 24L * 60L * 60_000L, limit);
        long first = owner.nextRequestOrdinal();
        assertEquals("game|10080|10080", owner.accept(running(first, 1, 0L, "session", "game",
                HOST_NOW - limit, HOST_NOW - limit), m.snapshot(), 0L).display.key());
        // A host clock set forward: the game stays named, without an absurd duration.
        long second = owner.nextRequestOrdinal();
        assertEquals("game|-1|-1", owner.accept(running(second, 2, 1_000L, "session", "game",
                HOST_NOW - limit - 1L, HOST_NOW - limit - 1L),
                m.snapshot(), 1_000L).display.key());
    }

    @Test public void streamDurationBelongsToTheStreamTheTransportHolds() {
        Controller controller = new Controller();
        m.enterHome(controller, "session", "host", "child", 42, "");
        assertTrue(m.bindControlGeneration(controller, "session", 1L));
        long first = owner.nextRequestOrdinal();
        SessionSnapshot live = owner.accept(running(first, 1, 0L, "session", "game",
                HOST_NOW - 5 * 60_000L, HOST_NOW - 65 * 60_000L), m.snapshot(), 0L);
        assertEquals(SessionSnapshot.State.ACTIVE, live.state);
        assertEquals("game|5|65", live.display.key());
        // Between polls both durations stay.
        assertEquals("game|5|65", owner.current(m.snapshot(), 6_000L).display.key());

        // The host names another stream than the transport holds: not this stream's start.
        long second = owner.nextRequestOrdinal();
        assertNotNull(owner.accept(running(second, 2, 7_000L, "other-session", "game",
                HOST_NOW - 5 * 60_000L, HOST_NOW - 65 * 60_000L), m.snapshot(), 7_000L));
        assertEquals("game|5|-1", owner.current(m.snapshot(), 7_000L).display.key());

        // No stream of this actor on the host: 0 is unknown, not "since 1970".
        long third = owner.nextRequestOrdinal();
        assertNotNull(owner.accept(running(third, 3, 8_000L, "session", "game",
                HOST_NOW - 5 * 60_000L, 0L), m.snapshot(), 8_000L));
        assertEquals("game|5|-1", owner.current(m.snapshot(), 8_000L).display.key());
    }

    @Test public void closingBoundRunsFromTheModelsPublicationThenUncertainAgain() {
        m.enterHome(new Controller(), "session", "host", "child", 42, "game");
        assertNotNull(observe("host", ready("boot", dispatch("host"))).accepted);
        assertTrue(m.markTerminating("session", "host", 42, "game"));
        m.now = 100L; // the model publishes the boundary now: the closing bound starts
        clearRetainedTransport();

        assertEquals(SessionSnapshot.State.TERMINATING, held("host", 100L).state);
        assertEquals(SessionSnapshot.State.TERMINATING, held("host",
                100L + SessionStateResolver.CLOSING_CONFIRMATION_MS - 1L).state);
        assertEquals("no free state in time: the ordinary uncertain path applies",
                SessionSnapshot.State.UNCERTAIN, held("host",
                100L + SessionStateResolver.CLOSING_CONFIRMATION_MS).state);
    }

    /** A read dispatched before a termination boundary is rejected by the model after it. */
    @Test public void responseCarryingASnapshotFromBeforeTheBoundaryIsRejected() {
        SessionModelDriver model = new SessionModelDriver();
        Controller controller = new Controller();
        model.enterHome(controller, "session", "host", "child", 42, "game");
        assertTrue(model.bindControlGeneration(controller, "session", 1L));
        long first = model.call(new SessionEvent.ObservationDispatched(
                "host", "child", "parent")).ordinal;
        assertNotNull(model.call(new SessionEvent.HostObserved("host", "child", "parent",
                ready("boot", first))).accepted);

        // Dispatch, then both TERMINATING edges pass during the I/O.
        long inFlight = model.call(new SessionEvent.ObservationDispatched(
                "host", "child", "parent")).ordinal;
        assertTrue(model.markTerminating("session", "host", 42, "game"));
        model.clear();

        model.now = 4L;
        SessionModel.ObservationResult late = model.call(new SessionEvent.HostObserved(
                "host", "child", "parent", ready("boot", 2L, inFlight)));
        assertNull(late.accepted);
        assertEquals(SessionSnapshot.ObservationState.PENDING, late.projection.observationState);
        assertFalse(late.projection.retainedTransport);
    }
    @Test public void terminationRolledBackToReconnectAlsoFencesTheOldObservation() {
        m.enterHome(new Controller(), "session", "host", "child", 42, "game");
        assertNotNull(observe("host", ready("boot", dispatch("host"))).accepted);
        long inFlight = dispatch("host");

        SessionModel.DirectTermination direct = m.beginDirectTermination(
                "session", "host", 42, "game");
        m.abandonDirectTermination(direct);

        assertEquals(SessionSnapshot.ObservationState.PENDING,
                held("host", 3L).observationState);
        assertNull(observe("host", ready("boot", 2L, inFlight)).accepted);
    }

    @Test public void projectionCarriesTheStreamerAppOfTheLiveObservationOnly() {
        assertEquals(SessionSnapshot.STREAMER_APP_UNKNOWN,
                owner.current(m.snapshot(), 0L).streamerAppId);
        HostGatewayClient.SessionObservation seen =
                pending("boot-a", 1, owner.nextRequestOrdinal(), "episode-a");
        seen.streamerAppId = 42;
        owner.accept(seen, m.snapshot(), 1_000L);
        // Decisions follow the observation's streamer, not the serverinfo poll.
        SessionSnapshot observed = owner.current(m.snapshot(), 1_000L);
        assertTrue(SessionOrchestrator.anotherAppRuns(observed, 7));
        assertFalse(SessionOrchestrator.anotherAppRuns(observed, 42));
        assertFalse(SessionOrchestrator.ownsFreshSession(observed));
        // Host-global: told while the observation is live, even uncorrelated and unresolved.
        assertEquals(42, owner.current(m.snapshot(), 1_000L
                + SessionStateResolver.OBSERVATION_LIVENESS_MS).streamerAppId);
        assertEquals(SessionSnapshot.STREAMER_APP_UNKNOWN, owner.current(m.snapshot(), 1_001L
                + SessionStateResolver.OBSERVATION_LIVENESS_MS).streamerAppId);
        HostGatewayClient.SessionObservation none =
                pending("boot-a", 2, owner.nextRequestOrdinal(), "episode-a");
        none.streamerAppId = 0;
        owner.accept(none, m.snapshot(), 20_000L);
        assertEquals(0, owner.current(m.snapshot(), 20_000L).streamerAppId);
        // A termination boundary retires it with the observation.
        owner.terminationBoundary(true);
        assertEquals(SessionSnapshot.STREAMER_APP_UNKNOWN,
                owner.current(m.snapshot(), 20_001L).streamerAppId);
    }
    @Test public void renderedProjectionOfAnOlderSnapshotPublishesNothing() {
        Controller controller = new Controller();
        m.enterHome(controller, "earlier-session", "host",
                "child", 42, "game");
        SessionModel.Snapshot delivered =
                m.snapshot();
        m.enterHome(controller, "session", "host", "child", 42, "game");
        recordInto();
        long ordinal = owner.nextRequestOrdinal();
        assertNotNull(owner.accept(ready("boot-ready", ordinal),
                m.snapshot(), 0L));
        int records = recorded.size();
        long events = owner.diagnosticEventsForTests();

        SessionSnapshot rendered = owner.rendered(
                delivered, SessionStateResolver.LocalFacts.NONE, 10L);

        assertEquals(SessionSnapshot.ObservationState.PENDING, rendered.observationState);
        assertEquals("observation_correlation_mismatch", rendered.observationReason);
        assertEquals(records, recorded.size());
        assertEquals(events, owner.diagnosticEventsForTests());
        SessionSnapshot live = owner.current(m.snapshot(), 20L);
        assertEquals(SessionSnapshot.ObservationState.READY, live.observationState);
        assertEquals(events, owner.diagnosticEventsForTests());
    }

    @Test public void renderedProjectionHidesAnObservationAcrossATerminationBoundary() {
        m.enterHome(new Controller(), "session", "host", "child", 42, "game");
        assertNotNull(observe("host", ready("boot", dispatch("host"))).accepted);

        assertTrue(m.markTerminating("session", "host", 42, "game"));
        clearRetainedTransport();
        SessionSnapshot rendered = held("host", 3L);

        assertEquals(SessionSnapshot.ObservationState.PENDING, rendered.observationState);
        assertEquals("UNAVAILABLE", rendered.processEvidence);
        assertEquals("", rendered.playniteGameId);
    }

    @Test public void closingOneHostsStreamShowsClosingOnlyOnThatHost() {
        dispatch("other-host");
        assertEquals(SessionSnapshot.State.UNCERTAIN, held("other-host", 0L).state);
        m.enterHome(new Controller(), "session", "host", "child", 42, "game");
        dispatch("host");

        assertTrue(m.markTerminating("session", "host", 42, "game"));
        assertEquals(SessionSnapshot.State.TERMINATING, held("host", 1L).state);
        assertEquals(SessionSnapshot.State.UNCERTAIN, held("other-host", 1L).state);

        clearRetainedTransport();
        assertEquals(SessionSnapshot.State.TERMINATING, held("host", 2L).state);
        assertEquals(SessionSnapshot.State.UNCERTAIN, held("other-host", 2L).state);
    }

    /**
     * Host ids differ in case between records and polls. Closing one host's stream is a
     * termination boundary for that host's observations only: its read in flight is rejected,
     * another host's read still counts.
     */
    @Test public void terminationBoundaryReachesOnlyTheClosedHostWhateverTheIdsCase() {
        assertNotNull(observe("HOST-A", ready("boot", dispatch("HOST-A"))).accepted);
        assertNotNull(observe("other-host", ready("boot", dispatch("other-host"))).accepted);
        long closedInFlight = dispatch("HOST-A");
        long otherInFlight = dispatch("other-host");
        m.enterHome(new Controller(), "session", " Host-a ", "child", 42, "game");

        assertTrue(m.markTerminating("session", " Host-a ", 42, "game"));
        clearRetainedTransport();

        assertNull(observe("HOST-A", ready("boot", 2L, closedInFlight)).accepted);
        assertEquals(SessionSnapshot.State.TERMINATING, held("HOST-A", 1L).state);
        assertNotNull(observe("other-host", ready("boot", 2L, otherInFlight)).accepted);
        assertEquals(SessionSnapshot.ObservationState.READY,
                held("other-host", 1L).observationState);
    }

    /**
     * Leaving TERMINATING straight onto another host's stream: the leaving edge belongs to the
     * closed host, not to the host the model moved to in the same transition.
     */
    @Test public void leavingATerminationOntoAnotherHostClosesOnlyTheTerminatedHost() {
        m.enterHome(new Controller(), "session-a", "host-a", "child", 42, "game");
        dispatch("host-a");
        long otherInFlight = dispatch("host-b");
        assertTrue(m.markTerminating("session-a", "host-a", 42, "game"));

        m.enterHome(new Controller(), "session", "host-b", "child", 42, "game");

        assertNotNull(observe("host-b", ready("boot", otherInFlight)).accepted);
        assertEquals(SessionSnapshot.ObservationState.READY,
                held("host-b", 1L).observationState);
        assertEquals("the closed host reads as closing",
                SessionSnapshot.State.TERMINATING, held("host-a", 1L).state);
    }

    /**
     * A parent's close is a boundary of its execution profile only: a child on the parent's
     * Windows profile shares it, a child under its own execution profile does not.
     */
    @Test public void parentsCloseReachesOnlyProjectionsOfItsExecutionProfile() {
        m.call(new SessionEvent.ObservationDispatched("host", "kid", "kid"));
        dispatch("host");
        m.enterHome(new Controller(), "session", "host", "parent", 42, "game");
        assertTrue(m.markTerminating("session", "host", 42, "game"));
        clearRetainedTransport();

        assertEquals("a child on the parent's profile sees the close",
                SessionSnapshot.State.TERMINATING, held("host", 1L).state);
        assertEquals("a child under its own profile does not",
                SessionSnapshot.State.UNCERTAIN, SessionStateResolver.render(m.snapshot(),
                        "host", "kid", "kid", SessionStateResolver.LocalFacts.NONE, 1L).state);
    }

    @Test public void liveReconnectOfTheMarkedRecordIsReadyOnlyOnceTheMarkerYields() {
        Controller controller = new Controller();
        m.enterHome(controller, "other", "host", "child", 42, "game");
        SessionModel.Snapshot otherRecord = m.snapshot();
        clearRetainedTransport();
        m.enterHome(controller, "session", "host", "child", 42, "game");
        SessionModel.Snapshot beforeLoss = m.snapshot();
        // A state other than RECONNECT_REQUIRED is never relinquished.
        assertFalse(m.call(new SessionEvent.ReconnectRelinquished(beforeLoss)));
        assertEquals(SessionModel.State.HOME_LIVE, m.state());
        m.markReconnectRequired("session");

        // Resume metadata is not a live connection: the reconnect's evidence is not READY.
        long ordinal = owner.nextRequestOrdinal();
        assertNotNull(owner.accept(readyAtGeneration("boot", ordinal, 4L), m.snapshot(), 0L));
        assertEquals(SessionSnapshot.ObservationState.PENDING,
                owner.current(m.snapshot(), 1L).observationState);

        // A stale Game of another record changes nothing.
        assertFalse(m.call(new SessionEvent.ReconnectRelinquished(otherRecord)));
        assertEquals(SessionModel.State.RECONNECT_REQUIRED, m.state());
        assertEquals(SessionSnapshot.ObservationState.PENDING,
                owner.current(m.snapshot(), 2L).observationState);

        assertTrue(m.call(new SessionEvent.ReconnectRelinquished(m.snapshot())));
        assertEquals(SessionModel.State.NONE, m.state());
        assertEquals(SessionSnapshot.ObservationState.READY,
                owner.current(m.snapshot(), 3L).observationState);
    }

    /** The Gateway control generation is carried, never a correlation key. */
    @Test public void controlGenerationIsCarriedButNeverDecidesTheProjection() {
        Controller controller = new Controller();
        m.enterHome(controller, "session", "host", "child", 42, "game");
        assertTrue(m.bindControlGeneration(controller, "session", 3L));

        SessionSnapshot advanced = observe("host",
                readyAtGeneration("boot", dispatch("host"), 9L)).accepted;
        assertEquals(SessionSnapshot.ObservationState.READY, advanced.observationState);
        assertEquals(9L, advanced.controlGeneration);

        // Stale evidence is still rejected on its own correlators.
        long older = dispatch("host");
        long newer = dispatch("host");
        assertNull("a read dispatched before a newer one",
                observe("host", ready("boot", 2L, older)).accepted);
        assertNull("no newer producer revision",
                observe("host", ready("boot", 1L, newer)).accepted);
        SessionSnapshot otherStream = observe("host",
                readyForNextPlay("boot-b", dispatch("host"))).accepted;
        assertFalse("another stream session", otherStream.observationCorrelated);
        assertEquals(SessionSnapshot.ObservationState.PENDING, otherStream.observationState);
    }

    /**
     * TV 2026-10-14: after a cancel marked the retained stream RECONNECT_REQUIRED, a launch
     * the host turned into "resume" attached a new stream session. The marker must yield to
     * it, or every observation of the live stream stays uncorrelated.
     */
    @Test public void liveStreamOfANewSessionSupersedesTheMarkerOfItsHost() {
        Controller controller = new Controller();
        m.enterHome(controller, "session", "host", "parent", 42, "game");
        assertTrue(m.bindControlGeneration(controller, "session", 1L));
        m.markReconnectRequired("session");
        SessionModel.Snapshot marked = m.snapshot();
        long ordinal = owner.nextRequestOrdinal();
        assertNotNull(owner.accept(readyForNextPlay("boot", ordinal), m.snapshot(), 0L));
        assertEquals("observation_correlation_mismatch",
                owner.current(m.snapshot(), 1L).observationReason);

        assertFalse("another host's stream does not", marked.reconnectMarkerSupersededBy(
                "other-host", "parent"));
        assertFalse("another execution profile's stream does not",
                marked.reconnectMarkerSupersededBy("host", "child"));
        assertTrue(marked.reconnectMarkerSupersededBy("HOST", "parent"));

        assertTrue(m.call(new SessionEvent.ReconnectRelinquished(marked)));
        SessionSnapshot live = owner.current(m.snapshot(), 2L);
        assertTrue(live.observationCorrelated);
        assertEquals(SessionSnapshot.ObservationState.READY, live.observationState);
    }

    private static final long HOST_NOW = 1_700_000_000_000L;

    private static HostGatewayClient.SessionObservation running(
            long ordinal, long revision, long localNow, String gameId,
            long processStarted, long streamStarted) {
        return running(ordinal, revision, localNow, "session", gameId, processStarted,
                streamStarted);
    }

    /** A verified running game observed at host time {@code HOST_NOW + localNow}. */
    private static HostGatewayClient.SessionObservation running(
            long ordinal, long revision, long localNow, String session, String gameId,
            long processStarted, long streamStarted) {
        HostGatewayClient.SessionObservation seen = new HostGatewayClient.SessionObservation(
                "boot-a", revision, HOST_NOW + localNow, 5_000L, "running",
                HOST_NOW, "child", "parent", ordinal, "operation",
                session, 1L, 1L, "running", HostGatewayClient.Evidence.VERIFIED,
                "running", HostGatewayClient.Evidence.VERIFIED, gameId,
                HostGatewayClient.Evidence.VERIFIED, 42, TOKEN,
                HostGatewayClient.Evidence.VERIFIED, true, "game", 3, "target_window_ready",
                false, true, false);
        seen.processStartedUnixMs = processStarted;
        seen.streamStartedUnixMs = streamStarted;
        return seen;
    }

    private static HostGatewayClient.SessionObservation readyAtGeneration(
            String boot, long ordinal, long generation) {
        return new HostGatewayClient.SessionObservation(boot, 1L, 1_000L, 15_000L,
                "running", 1_000L, "child", "parent", ordinal, "operation",
                "session", generation, 1L, "running", HostGatewayClient.Evidence.VERIFIED,
                "running", HostGatewayClient.Evidence.VERIFIED, "game",
                HostGatewayClient.Evidence.VERIFIED, 42, TOKEN,
                HostGatewayClient.Evidence.VERIFIED, true, "game", 3, "target_window_ready",
                false, true, false);
    }

    private static HostGatewayClient.SessionObservation ready(
            String boot, long revision, long ordinal) {
        return observation(boot, revision, ordinal, "running-" + revision, "running",
                HostGatewayClient.Evidence.VERIFIED, "game",
                HostGatewayClient.Evidence.VERIFIED, 42, TOKEN,
                HostGatewayClient.Evidence.VERIFIED, true, "target_window_ready");
    }

    private static HostGatewayClient.SessionObservation pending(
            String boot, long revision, long ordinal, String episode) {
        return observation(boot, revision, ordinal, episode, "reconciling",
                HostGatewayClient.Evidence.PENDING, "",
                HostGatewayClient.Evidence.PENDING, 0, "",
                HostGatewayClient.Evidence.PENDING, false, "reconciling");
    }

    private static HostGatewayClient.SessionObservation idle(String boot, long ordinal) {
        return idle(boot, 1L, ordinal);
    }

    private static HostGatewayClient.SessionObservation idle(
            String boot, long revision, long ordinal) {
        return observation(boot, revision, ordinal, "idle", "idle",
                HostGatewayClient.Evidence.ABSENT, "",
                HostGatewayClient.Evidence.ABSENT, 0, "",
                HostGatewayClient.Evidence.ABSENT, false, "no_running_game");
    }

    private static HostGatewayClient.SessionObservation ready(String boot, long ordinal) {
        return observation(boot, 1L, ordinal, "running", "running",
                HostGatewayClient.Evidence.VERIFIED, "game",
                HostGatewayClient.Evidence.VERIFIED, 42, TOKEN,
                HostGatewayClient.Evidence.VERIFIED, true, "target_window_ready");
    }

    private static HostGatewayClient.SessionObservation readyForNextPlay(
            String boot, long ordinal) {
        return observation(boot, 1L, ordinal, "running-next", "running",
                HostGatewayClient.Evidence.VERIFIED, "game",
                HostGatewayClient.Evidence.VERIFIED, 42, TOKEN,
                HostGatewayClient.Evidence.VERIFIED, true, "target_window_ready",
                "operation-next", "session-next");
    }

    private static HostGatewayClient.SessionObservation observation(
            String boot, long revision, long ordinal, String episode, String gameState,
            HostGatewayClient.Evidence gameEvidence, String gameId,
            HostGatewayClient.Evidence processEvidence, int processId, String processToken,
            HostGatewayClient.Evidence windowEvidence, boolean windowReady, String reason) {
        return observation(boot, revision, ordinal, episode, gameState,
                gameEvidence, gameId, processEvidence, processId, processToken,
                windowEvidence, windowReady, reason, "operation", "session");
    }

    private static HostGatewayClient.SessionObservation observation(
            String boot, long revision, long ordinal, String episode, String gameState,
            HostGatewayClient.Evidence gameEvidence, String gameId,
            HostGatewayClient.Evidence processEvidence, int processId, String processToken,
            HostGatewayClient.Evidence windowEvidence, boolean windowReady, String reason,
            String operation, String session) {
        return new HostGatewayClient.SessionObservation(boot, revision, 1_000L, 15_000L,
                episode, 1_000L, "child", "parent", ordinal, operation,
                session, "session-next".equals(session) ? 5L : 1L, 1L,
                gameState, HostGatewayClient.Evidence.VERIFIED,
                gameState, gameEvidence, gameId, processEvidence, processId, processToken,
                windowEvidence, windowReady, "game", windowReady ? 3 : 0, reason,
                false, true, false);
    }

    private static final class Controller implements SessionModel.Controller {
        boolean disconnected;
        boolean terminated;
        @Override public boolean isRetainedTransportLive() { return true; }
        private final long fakeGeneration =
                com.limelight.session.SessionModelDriver.nextTransportGeneration();
        @Override public long transportGeneration() { return fakeGeneration; }
        @Override public String getExecutionProfileId() { return "parent"; }
        @Override public SessionModel.ParkOutcome parkRetainedTransport() { return SessionModel.ParkOutcome.PARKED; }
        @Override public void disconnectRetainedStream(
                SessionModel.TerminationCallback completion) {
            disconnected = true;
        }
        @Override public void terminateRetainedSession(
                String gameId, SessionModel.TerminationCallback completion) {
            terminated = true;
        }
        @Override public void switchGame(SessionModel.SwitchRequest request,
                                         SessionModel.SwitchCallback completion) { }
    }
}
