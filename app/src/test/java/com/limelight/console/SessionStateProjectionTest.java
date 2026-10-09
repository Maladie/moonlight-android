package com.limelight.console;

import com.limelight.session.SessionEvent;
import com.limelight.session.SessionModel;
import com.limelight.session.SessionModelDriver;
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
    private static final SessionModelDriver m = new SessionModelDriver();
    private static final String TOKEN =
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    private SessionStateResolver.ProjectionOwner owner;

    @Before public void setUp() {
        SessionStateResolver.resetProjectionOwnersForTests();
        clearRetainedTransport();
        owner = SessionStateResolver.owner(m.model, "host", "child", "parent");
    }

    @After public void tearDown() {
        clearRetainedTransport();
        SessionStateResolver.resetProjectionOwnersForTests();
    }

    private static void clearRetainedTransport() {
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
                suspended, false, false, null, false, false, false, 0);

        SessionSnapshot snapshot = owner.current(
                m.snapshot(), local, 0L);

        assertEquals(SessionSnapshot.State.SUSPENDED, snapshot.state);
        assertEquals(SessionSnapshot.ObservationState.PENDING, snapshot.observationState);
        assertEquals("", snapshot.playniteGameId);
        assertEquals("suspend-a", snapshot.suspendId);
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

    @Test public void failedReadCannotPublishItsPreDispatchModelSnapshot() {
        SessionModel.Snapshot before = m.snapshot();
        long ordinal = owner.nextRequestOrdinal();
        m.enterHome(new Controller(), "session", "host", "child", 42, "game");
        owner.current(m.snapshot(), 16_000L);

        SessionSnapshot failed = owner.unavailable(ordinal, before, 16_000L, "timeout");

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

        SessionStateResolver.ProjectionOwner idleOwner = SessionStateResolver.owner(m.model, "other", "child", "parent");
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
        SessionStateResolver.diagnosticSink = (level, component, event, fields) -> {
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
        long ordinal = owner.nextRequestOrdinal();
        assertNotNull(owner.accept(ready("boot-fresh", ordinal),
                m.snapshot(), 1_000L));
        SessionStateResolver.owner(m.model, "other-host", "parent", "parent");
        recorded.clear();

        assertEquals(2, SessionStateResolver.sampleAll("phase-17:start", 1_500L));
        assertEquals(2, SessionStateResolver.sampleAll("phase-17:end", 2_000L));
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

        assertEquals(-1, SessionStateResolver.sampleAll("bad marker", 3_000L));
        assertEquals(-1, SessionStateResolver.sampleAll(null, 3_000L));
        assertEquals(4, recorded.size());

        // A sampling clock behind the publication clock is reported, not clamped to "fresh".
        assertEquals(2, SessionStateResolver.sampleAll("phase-17:skew", 900L));
        assertEquals(-100L, field(recorded.get(4), "published_age_ms"));
    }

    @Test public void sampleConfirmationReportsUndrainedWriterAndRejectsBadMarkers() {
        recordInto();
        assertTrue(SessionStateResolver.confirmSamples("phase-19:end"));
        Object[] confirmation = recorded.get(0);
        assertEquals("projection.sample_drained", confirmation[0]);
        assertEquals("phase-19:end", field(confirmation, "sample_marker"));
        assertEquals("not_drained", field(confirmation, "status"));
        assertEquals(-1L, field(confirmation, "dropped"));
        assertFalse(SessionStateResolver.confirmSamples("bad marker"));
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
                second, "", "operation", "session", 1L, 1L, "running",
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
                        "operation", "", "wrong-session"),
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

        assertTrue(m.bindControlGeneration(
                controller, "session", 5L));
        assertNull(m.beginDirectTermination(firstTransport));
        SessionModel.Snapshot exactRecovery =
                m.snapshot();
        SessionModel.DirectTermination recoveryToken =
                m.beginDirectTermination(exactRecovery);
        assertNotNull(recoveryToken);
        assertTrue(m.completeDirectTermination(
                recoveryToken, true));

        assertTrue(m.beginPreparing(controller,
                "session-next", "host", "child", 42, "game",
                "transition-next", 1L));
        assertTrue(m.bindControlGeneration(
                controller, "session-next", 5L));
        SessionModel.Snapshot nextTransport =
                m.snapshot();
        assertTrue(nextTransport.transportGeneration > firstTransport.transportGeneration);
        assertEquals("transition-next", nextTransport.transitionId);
        assertFalse(m.completeDirectTermination(
                recoveryToken, true));
        assertEquals("session-next",
                m.snapshot().streamSessionId);

        long nextOrdinal = owner.nextRequestOrdinal();
        assertNotNull(owner.accept(readyForNextPlay("boot-next", nextOrdinal),
                nextTransport, 16_001L));
        SessionSnapshot nextPlay = owner.current(nextTransport, 16_001L);
        assertEquals("operation-next", nextPlay.operationId);
        assertEquals("transition-next", nextPlay.transitionId);
        assertEquals(nextTransport.transportGeneration, nextPlay.transportGeneration);
        assertEquals(SessionSnapshot.ObservationState.READY, nextPlay.observationState);
        assertTrue(m.completePreparing(controller,
                "session-next", "host", 42, "transition-next", 1L));
    }

    @Test public void observationsBeforeTheLocalTerminationCannotPublishReadyAgain() {
        Controller controller = new Controller();
        m.enterHome(controller, "session", "host",
                "child", 42, "game");
        assertTrue(m.bindControlGeneration(
                controller, "session", 1L));
        long first = owner.nextRequestOrdinal();
        assertNotNull(owner.accept(ready("boot", first),
                m.snapshot(), 0L));
        assertEquals(SessionSnapshot.ObservationState.READY, owner.current(
                m.snapshot(), 1L).observationState);
        long inFlight = owner.nextRequestOrdinal();

        assertTrue(m.markTerminating(
                "session", "host", 42, "game"));
        assertEquals(SessionSnapshot.ObservationState.PENDING, owner.current(
                m.snapshot(), 2L).observationState);
        clearRetainedTransport();
        SessionModel.Snapshot ended = m.snapshot();

        // The stale READY (observed before the verified stop) neither returns as the
        // projection nor lets a request dispatched before the termination finish.
        assertEquals(SessionSnapshot.ObservationState.PENDING,
                owner.current(ended, 3L).observationState);
        assertNull(owner.accept(ready("boot", 2L, inFlight), ended, 4L));
        assertEquals(SessionSnapshot.ObservationState.PENDING,
                owner.current(ended, 5L).observationState);

        long afterwards = owner.nextRequestOrdinal();
        assertNotNull(owner.accept(idle("boot", 3L, afterwards), ended, 6L));
        assertEquals(SessionSnapshot.ObservationState.IDLE,
                owner.current(ended, 7L).observationState);
    }

    @Test public void terminationNobodySampledStillFencesTheOldObservation() {
        Controller controller = new Controller();
        m.enterHome(controller, "session", "host",
                "child", 42, "game");
        assertTrue(m.bindControlGeneration(
                controller, "session", 1L));
        long first = owner.nextRequestOrdinal();
        assertNotNull(owner.accept(ready("boot", first),
                m.snapshot(), 0L));
        long inFlight = owner.nextRequestOrdinal();

        // HOME_LIVE -> TERMINATING -> NONE with no owner call in between.
        assertTrue(m.markTerminating(
                "session", "host", 42, "game"));
        clearRetainedTransport();
        SessionModel.Snapshot ended = m.snapshot();

        assertEquals(SessionSnapshot.ObservationState.PENDING,
                owner.current(ended, 3L).observationState);
        assertNull(owner.accept(ready("boot", 2L, inFlight), ended, 4L));
        long afterwards = owner.nextRequestOrdinal();
        assertNotNull(owner.accept(idle("boot", 3L, afterwards), ended, 6L));
        assertEquals(SessionSnapshot.ObservationState.IDLE,
                owner.current(ended, 7L).observationState);
    }

    @Test public void verifiedCloseProjectsClosingUntilAFreshIdlePollIsAccepted() {
        // The snapshot SessionOrchestrator re-resolves right after a CLOSED result (MW-02).
        Controller controller = new Controller();
        m.enterHome(controller, "session", "host",
                "child", 42, "game");
        assertTrue(m.bindControlGeneration(
                controller, "session", 1L));
        long first = owner.nextRequestOrdinal();
        assertNotNull(owner.accept(ready("boot", first),
                m.snapshot(), 0L));
        assertTrue(m.markTerminating(
                "session", "host", 42, "game"));
        clearRetainedTransport();
        SessionModel.Snapshot ended = m.snapshot();

        // Closing, not "uncertain": the console greys the games until the host is free.
        assertEquals(SessionSnapshot.State.TERMINATING, owner.current(ended, 3L).state);
        long afterwards = owner.nextRequestOrdinal();
        assertNotNull(owner.accept(idle("boot", 3L, afterwards), ended, 6L));
        assertEquals(SessionSnapshot.State.NONE, owner.current(ended, 7L).state);
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

    @Test public void forgettingAHostDropsOnlyItsProjectionOwners() {
        SessionStateResolver.ProjectionOwner other =
                SessionStateResolver.owner(m.model, "other", "child", "parent");
        owner.beginLocalClose(1L, false);

        SessionStateResolver.forgetHost("HOST");

        SessionStateResolver.ProjectionOwner fresh =
                SessionStateResolver.owner(m.model, "host", "child", "parent");
        assertTrue(fresh != owner);
        assertEquals(SessionSnapshot.State.UNCERTAIN, fresh.current(m.snapshot(), 2L).state);
        assertTrue(other == SessionStateResolver.owner(m.model, "other", "child", "parent"));
    }

    @Test public void closingAfterACloseIsBoundedThenUncertainAgain() {
        Controller controller = new Controller();
        m.enterHome(controller, "session", "host",
                "child", 42, "game");
        assertTrue(m.bindControlGeneration(
                controller, "session", 1L));
        long first = owner.nextRequestOrdinal();
        assertNotNull(owner.accept(ready("boot", first),
                m.snapshot(), 0L));
        assertTrue(m.markTerminating(
                "session", "host", 42, "game"));
        clearRetainedTransport();
        SessionModel.Snapshot ended = m.snapshot();

        assertEquals(SessionSnapshot.State.TERMINATING, owner.current(ended, 100L).state);
        assertEquals(SessionSnapshot.State.TERMINATING, owner.current(ended,
                100L + SessionStateResolver.CLOSING_CONFIRMATION_MS - 1L).state);
        assertEquals("no free state in time: the ordinary uncertain path applies",
                SessionSnapshot.State.UNCERTAIN, owner.current(ended,
                100L + SessionStateResolver.CLOSING_CONFIRMATION_MS).state);
    }

    @Test public void renderedProjectionDoesNotStartTheClosingBound() {
        Controller controller = new Controller();
        m.enterHome(controller, "session", "host",
                "child", 42, "game");
        assertTrue(m.bindControlGeneration(
                controller, "session", 1L));
        long first = owner.nextRequestOrdinal();
        assertNotNull(owner.accept(ready("boot", first),
                m.snapshot(), 0L));
        assertTrue(m.markTerminating(
                "session", "host", 42, "game"));
        clearRetainedTransport();
        SessionModel.Snapshot ended = m.snapshot();
        owner.nextRequestOrdinal(); // Consumes the boundary and arms the closing bound.

        assertEquals(SessionSnapshot.State.TERMINATING,
                owner.rendered(ended, SessionStateResolver.LocalFacts.NONE, 100L).state);
        assertEquals("the bound starts at the first publication, not at a render",
                SessionSnapshot.State.TERMINATING, owner.current(ended,
                        100L + SessionStateResolver.CLOSING_CONFIRMATION_MS).state);
    }

    @Test public void responseCarryingASnapshotFromBeforeTheBoundaryIsRejected() {
        Controller controller = new Controller();
        m.enterHome(controller, "session", "host",
                "child", 42, "game");
        assertTrue(m.bindControlGeneration(
                controller, "session", 1L));
        long first = owner.nextRequestOrdinal();
        assertNotNull(owner.accept(ready("boot", first),
                m.snapshot(), 0L));

        // Dispatch, capture the snapshot, then both TERMINATING edges pass during the I/O.
        long inFlight = owner.nextRequestOrdinal();
        SessionModel.Snapshot beforeBoundary =
                m.snapshot();
        assertTrue(m.markTerminating(
                "session", "host", 42, "game"));
        clearRetainedTransport();

        // The very first owner call after the boundary carries the old snapshot.
        assertNull(owner.accept(ready("boot", 2L, inFlight), beforeBoundary, 4L));
        SessionSnapshot afterwards = owner.current(beforeBoundary, 5L);
        assertEquals(SessionSnapshot.ObservationState.PENDING, afterwards.observationState);
        assertFalse(afterwards.retainedTransport);
    }

    @Test public void aBoundaryPublishedDuringAcceptanceRetiresTheObservationNext() {
        Controller controller = new Controller();
        m.enterHome(controller, "session", "host",
                "child", 42, "game");
        assertTrue(m.bindControlGeneration(
                controller, "session", 1L));
        long first = owner.nextRequestOrdinal();
        SessionModel.Snapshot captured =
                m.snapshot();

        // The model thread never waits for a reader: a termination committed while the owner
        // publishes is ordered after that publication and retires it on the next projection.
        final boolean[] terminated = new boolean[1];
        SessionStateResolver.diagnosticSink = (level, component, event, fields) -> {
            if ("projection.published".equals(event) && !terminated[0]) {
                terminated[0] = true;
                m.markTerminating("session", "host", 42, "game");
            }
            return true;
        };

        assertNotNull(owner.accept(ready("boot", first), captured, 0L));

        assertTrue(terminated[0]);
        assertEquals(SessionModel.State.TERMINATING, m.state());
        assertEquals(SessionSnapshot.ObservationState.PENDING,
                owner.current(m.snapshot(), 1L).observationState);
    }

    @Test public void terminationRolledBackToReconnectAlsoFencesTheOldObservation() {
        Controller controller = new Controller();
        m.enterHome(controller, "session", "host",
                "child", 42, "game");
        assertTrue(m.bindControlGeneration(
                controller, "session", 1L));
        long first = owner.nextRequestOrdinal();
        assertNotNull(owner.accept(ready("boot", first),
                m.snapshot(), 0L));
        long inFlight = owner.nextRequestOrdinal();

        SessionModel.DirectTermination direct = m.beginDirectTermination(
                "session", "host", 42, "game");
        m.abandonDirectTermination(direct);
        SessionModel.Snapshot rolledBack =
                m.snapshot();

        assertEquals(SessionSnapshot.ObservationState.PENDING,
                owner.current(rolledBack, 3L).observationState);
        assertNull(owner.accept(ready("boot", 2L, inFlight), rolledBack, 4L));
    }

    @Test public void projectionCarriesTheRunningAppOfTheHostPollItWasGiven() {
        com.limelight.nvstream.http.ComputerDetails host =
                new com.limelight.nvstream.http.ComputerDetails();
        host.uuid = "host";
        host.runningGameId = 42;
        SessionStateResolver.LocalFacts local = SessionStateResolver.LocalFacts.forHost(
                host, "parent", null, false, null, false, false, false);

        assertEquals(42, owner.current(m.snapshot(), local, 0L)
                .hostRunningAppId);
        assertEquals(42, owner.rendered(m.snapshot(), local, 1L)
                .hostRunningAppId);
        assertEquals(0, owner.current(m.snapshot(), 2L)
                .hostRunningAppId);
    }

    @Test public void renderedProjectionOfAnOlderSnapshotPublishesNothing() {
        Controller controller = new Controller();
        m.enterHome(controller, "session", "host",
                "child", 42, "game");
        SessionModel.Snapshot delivered =
                m.snapshot();
        assertTrue(m.bindControlGeneration(
                controller, "session", 1L));
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
        Controller controller = new Controller();
        m.enterHome(controller, "session", "host",
                "child", 42, "game");
        assertTrue(m.bindControlGeneration(
                controller, "session", 1L));
        long ordinal = owner.nextRequestOrdinal();
        assertNotNull(owner.accept(ready("boot", ordinal),
                m.snapshot(), 0L));

        assertTrue(m.markTerminating(
                "session", "host", 42, "game"));
        clearRetainedTransport();
        SessionSnapshot rendered = owner.rendered(m.snapshot(),
                SessionStateResolver.LocalFacts.NONE, 3L);

        assertEquals(SessionSnapshot.ObservationState.PENDING, rendered.observationState);
        assertEquals("UNAVAILABLE", rendered.processEvidence);
        assertEquals("", rendered.playniteGameId);
    }

    @Test public void liveReconnectOfTheMarkedRecordReplacesItsStaleCorrelation() {
        Controller controller = new Controller();
        m.enterHome(controller, "other", "host", "child", 42, "game");
        SessionModel.Snapshot otherRecord = m.snapshot();
        clearRetainedTransport();
        m.enterHome(controller, "session", "host", "child", 42, "game");
        assertTrue(m.bindControlGeneration(controller, "session", 3L));
        SessionModel.Snapshot beforeLoss = m.snapshot();
        // A state other than RECONNECT_REQUIRED is never relinquished.
        assertFalse(m.call(new SessionEvent.ReconnectRelinquished(beforeLoss)));
        assertEquals(SessionModel.State.HOME_LIVE, m.state());
        m.markReconnectRequired("session");

        // The host bumped the generation for the reconnect acquire (3 -> 4).
        long ordinal = owner.nextRequestOrdinal();
        assertNotNull(owner.accept(readyAtGeneration("boot", ordinal, 4L), m.snapshot(), 0L));
        SessionSnapshot marked = owner.current(m.snapshot(), 1L);
        assertEquals(SessionSnapshot.ObservationState.PENDING, marked.observationState);
        assertEquals("observation_correlation_mismatch", marked.observationReason);

        // A stale Game of another record changes nothing.
        assertFalse(m.call(new SessionEvent.ReconnectRelinquished(otherRecord)));
        assertEquals(SessionModel.State.RECONNECT_REQUIRED, m.state());
        assertEquals("observation_correlation_mismatch",
                owner.current(m.snapshot(), 2L).observationReason);

        assertTrue(m.call(new SessionEvent.ReconnectRelinquished(m.snapshot())));
        assertEquals(SessionModel.State.NONE, m.state());
        assertEquals(SessionSnapshot.ObservationState.READY,
                owner.current(m.snapshot(), 3L).observationState);
    }

    @Test public void liveActorHandoffOfTheMarkedRecordReplacesItsStaleCorrelation() {
        // Saved by the parent actor; the child reconnects it through a host-authorized
        // "reauthorize" acquire on the same execution profile, which also advances 3 -> 4.
        Controller controller = new Controller();
        m.enterHome(controller, "session", "host", "parent", 42, "game");
        assertTrue(m.bindControlGeneration(controller, "session", 3L));
        m.markReconnectRequired("session");
        long ordinal = owner.nextRequestOrdinal();
        assertNotNull(owner.accept(readyAtGeneration("boot", ordinal, 4L), m.snapshot(), 0L));
        assertEquals("observation_correlation_mismatch",
                owner.current(m.snapshot(), 1L).observationReason);

        assertTrue(m.call(new SessionEvent.ReconnectRelinquished(m.snapshot())));
        assertEquals(SessionSnapshot.ObservationState.READY,
                owner.current(m.snapshot(), 2L).observationState);
    }

    private static HostGatewayClient.SessionObservation readyAtGeneration(
            String boot, long ordinal, long generation) {
        return new HostGatewayClient.SessionObservation(boot, 1L, 1_000L, 15_000L,
                "running", 1_000L, "child", "parent", ordinal, "", "operation",
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
                "operation-next", "transition-next", "session-next");
    }

    private static HostGatewayClient.SessionObservation observation(
            String boot, long revision, long ordinal, String episode, String gameState,
            HostGatewayClient.Evidence gameEvidence, String gameId,
            HostGatewayClient.Evidence processEvidence, int processId, String processToken,
            HostGatewayClient.Evidence windowEvidence, boolean windowReady, String reason) {
        return observation(boot, revision, ordinal, episode, gameState,
                gameEvidence, gameId, processEvidence, processId, processToken,
                windowEvidence, windowReady, reason, "operation", "", "session");
    }

    private static HostGatewayClient.SessionObservation observation(
            String boot, long revision, long ordinal, String episode, String gameState,
            HostGatewayClient.Evidence gameEvidence, String gameId,
            HostGatewayClient.Evidence processEvidence, int processId, String processToken,
            HostGatewayClient.Evidence windowEvidence, boolean windowReady, String reason,
            String operation, String transition, String session) {
        return new HostGatewayClient.SessionObservation(boot, revision, 1_000L, 15_000L,
                episode, 1_000L, "child", "parent", ordinal, transition, operation,
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
