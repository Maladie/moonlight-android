package com.limelight.session;

import com.limelight.gateway.GatewayConnection;

import org.junit.Test;

import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class SessionModelTest {
    private final SessionModelDriver m = new SessionModelDriver();
    private static final String SESSION_A = "session-a";

    /** A waiter for a plain closed/failed result; a game left on the host fails the test. */
    private static SessionModel.TerminationCallback finished(
            java.util.function.Consumer<Boolean> completion) {
        return new SessionModel.TerminationCallback() {
            @Override public void complete(boolean success) { completion.accept(success); }
            @Override public void gameLeftOnHost() { throw new AssertionError("game left"); }
        };
    }

    static class FakeController
            implements SessionModel.Controller {
        String origin;
        @Override public String getExecutionProfileId() { return origin; }
        boolean parkResult = true;
        boolean disconnected;
        boolean terminated;
        String terminatedGameId;
        int terminations;
        boolean completeTermination = true;
        boolean terminationResult = true;
        boolean live = true;
        SessionModel.TerminationCallback terminationCompletion;
        SessionModel.SwitchCallback switchCompletion;
        SessionModel.SwitchRequest switchRequest;
        SessionModel.Snapshot preparingPark;
        SessionModel.Snapshot preparingCancel;
        boolean preparingParkResult = true;
        boolean preparingCancelResult = true;
        int preparingCancels;
        int submittedPreparingFrames;
        boolean preparingFrameResult = true;
        int switches;

        @Override public boolean isRetainedTransportLive() { return live; }
        private final long fakeGeneration =
                com.limelight.session.SessionModelDriver.nextTransportGeneration();
        @Override public long transportGeneration() { return fakeGeneration; }
        @Override public SessionModel.ParkOutcome parkRetainedTransport() {
            return parkResult ? SessionModel.ParkOutcome.PARKED : SessionModel.ParkOutcome.STOPPED;
        }
        @Override public boolean parkPreparingTransport(
                SessionModel.Snapshot preparing) {
            preparingPark = preparing;
            return preparingParkResult;
        }
        @Override public boolean cancelPreparingTransport(
                SessionModel.Snapshot preparing) {
            preparingCancel = preparing;
            preparingCancels++;
            return preparingCancelResult;
        }
        @Override public boolean preparingHomeFrameSubmitted(
                SessionModel.Snapshot preparing) {
            submittedPreparingFrames++;
            return preparingFrameResult;
        }
        @Override public void disconnectRetainedStream(
                SessionModel.TerminationCallback completion) {
            disconnected = true;
            terminationCompletion = completion;
            if (completeTermination && completion != null) {
                completion.complete(terminationResult);
            }
        }
        @Override public void terminateRetainedSession(
                String gameId, SessionModel.TerminationCallback completion) {
            terminated = true;
            terminatedGameId = gameId;
            terminations++;
            terminationCompletion = completion;
            if (completeTermination && completion != null) {
                completion.complete(terminationResult);
            }
        }
        @Override public void switchGame(
                SessionModel.SwitchRequest request,
                SessionModel.SwitchCallback completion) {
            switches++;
            switchRequest = request;
            switchCompletion = completion;
        }
    }

    @Test public void transportGenerationIsTheOwnersAndIndependentOfRemoteControl() {
        FakeController owner = new FakeController();
        m.enterHome(owner, SESSION_A, "host", 7, "game-a");
        long first = m.snapshot().transportGeneration;
        assertTrue(first > 0L);
        assertTrue(m.bindControlGeneration(owner, SESSION_A, 77L));
        assertEquals(first, m.snapshot().transportGeneration);
        assertEquals(77L, m.snapshot().controlGeneration);
        m.enterHome(owner, SESSION_A, "host", 7, "game-a");
        assertEquals(first, m.snapshot().transportGeneration);
        m.clear();
        // A new stream is a new transport owner, with the generation StreamRuntime gave it.
        FakeController next = new FakeController();
        m.enterHome(next, "session-b", "host", 7, "game-b");
        assertEquals(next.transportGeneration(), m.snapshot().transportGeneration);
        assertTrue(m.snapshot().transportGeneration > first);
        assertEquals(0L, m.snapshot().controlGeneration);
    }

    @Test public void reauthorizationCommitsConnectionActorAndGenerationAtomically() {
        FakeController owner = new FakeController(); owner.origin = "parent";
        m.enterHome(owner, SESSION_A, "host", "child", 7, "");
        m.bindControlGeneration(owner, SESSION_A, 4);
        AtomicInteger connectionGeneration = new AtomicInteger(4);
        assertFalse(m.commitReauthorization(owner, SESSION_A,
                "parent", "parent", 4, 5, () -> false));
        assertEquals("child", m.snapshot().profileId);
        assertEquals(4, m.snapshot().controlGeneration);
        assertTrue(m.commitReauthorization(owner, SESSION_A,
                "parent", "parent", 4, 5, () -> connectionGeneration.compareAndSet(4, 5)));
        assertEquals("parent", m.snapshot().profileId);
        assertEquals(5, m.snapshot().controlGeneration);
        assertEquals(5, connectionGeneration.get());
        assertFalse(m.commitReauthorization(owner, SESSION_A,
                "parent", "child", 4, 6, () -> { fail("Stale commit reached connection"); return true; }));
        assertFalse(m.discardReauthorization(owner, SESSION_A, 3, 4));
        assertEquals(5, m.snapshot().controlGeneration);
        assertTrue(m.discardReauthorization(owner, SESSION_A, 4, 5));
        assertEquals(SessionModel.State.NONE, m.snapshot().state);
    }

    @Test public void providerTransferProofRequiresEveryRetainedFenceAndIsOneShot() {
        FakeController owner = new FakeController(); owner.origin = "parent";
        m.enterHome(
                owner, SESSION_A, "host", "child-a", 7, "game-a");
        assertTrue(m.bindControlGeneration(owner, SESSION_A, 4));
        assertNull(m.commitProviderTransferReauthorization(
                owner, SESSION_A, "parent", "child-b", "old", "next", "wrong-host",
                7, "game-a", token('a'), 71, 4, 5, 9, () -> true));
        assertEquals("child-a", m.snapshot().profileId);
        assertNull(m.commitProviderTransferReauthorization(
                owner, SESSION_A, "parent", "child-b", "old", "next", "host",
                8, "game-a", token('a'), 71, 4, 5, 9, () -> true));
        assertNull(m.commitProviderTransferReauthorization(
                owner, SESSION_A, "parent", "child-b", "old", "next", "host",
                7, "game-b", token('a'), 71, 4, 5, 9, () -> true));
        SessionModel.ProviderTransferAuthorization proof =
                m.commitProviderTransferReauthorization(
                        owner, SESSION_A, "parent", "child-b", "old", "next", "host",
                        7, "game-a", token('a'), 71, 4, 5, 9, () -> true);
        assertTrue(proof != null);
        assertEquals(9, proof.actorLeaseEpoch);
        assertEquals(71, proof.processId);
        assertEquals(token('a'), proof.processToken);
        assertTrue(m.consumeProviderTransferAuthorization(proof));
        assertFalse(m.consumeProviderTransferAuthorization(proof));
    }

    @Test public void ongoingTerminationCannotBeReauthorizedOrDiscardedByHandoff() {
        FakeController owner = new FakeController(); owner.origin = "parent";
        m.enterHome(owner, SESSION_A, "host", "parent", 7, "");
        m.bindControlGeneration(owner, SESSION_A, 4);
        assertTrue(m.markTerminating(SESSION_A, "host", 7, ""));
        assertFalse(m.commitReauthorization(owner, SESSION_A,
                "parent", "child", 4, 5, () -> { fail("Termination reached commit"); return true; }));
        assertFalse(m.discardReauthorization(owner, SESSION_A, 4, 5));
        assertEquals(SessionModel.State.TERMINATING,
                m.snapshot().state);
    }

    @Test public void replacedRetainedOwnerRejectsOldCommitAndCompensation() {
        FakeController oldOwner = new FakeController(); oldOwner.origin = "parent";
        FakeController parent = new FakeController(); parent.origin = "parent";
        m.enterHome(parent, SESSION_A, "host", "parent", 7, "");
        m.bindControlGeneration(parent, SESSION_A, 6);
        assertFalse(m.commitReauthorization(oldOwner, SESSION_A,
                "parent", "child", 4, 5, () -> { fail("Old owner reached connection"); return true; }));
        assertFalse(m.discardReauthorization(oldOwner, SESSION_A, 4, 5));
        assertEquals(6, m.snapshot().controlGeneration);
    }

    @Test public void familySwitchRetainsTransportButRebindsActorAndRejectsOtherOrigins() {
        FakeController owner = new FakeController();
        owner.origin = "parent";
        m.enterHome(owner, SESSION_A, "host", "parent", 7, "game-a");
        for (String actor : new String[] {"child-a", "child-b", "parent"}) {
            SessionModel.Snapshot before = m.snapshot();
            assertFalse(m.canSwitchOrigin("host", "other", 7));
            assertFalse(m.canSwitchOrigin("other-host", "parent", 7));
            assertEquals(SessionModel.SwitchResult.STARTED,
                    m.switchGame("host", "parent", actor,
                            !actor.equals("parent"), 7, "game-b", "Game", "Stream", "", () -> false,
                            (outcome, error) -> { }));
            assertEquals(actor, owner.switchRequest.targetProfileId);
            assertEquals("parent", owner.switchRequest.executionProfileId);
            assertTrue(m.bindActor(owner, SESSION_A, actor, "parent"));
            assertTrue(m.updateOwnedSwitchGame(owner,
                    owner.switchRequest, before.playniteGameId, "game-b"));
            owner.switchCompletion.complete(SessionModel.SwitchOutcome.REUSED, "");
            m.drain();
            m.finishSwitch(owner.switchRequest, owner);
            assertFalse(m.canResumeInstantly(before));
            assertEquals(actor, m.snapshot().profileId);
            assertEquals(SESSION_A, m.snapshot().streamSessionId);
            assertFalse(owner.disconnected);
            assertFalse(owner.terminated);
        }
    }

    @Test public void parkedFamilyTransportCanChangeGameWithoutDisconnect() {
        FakeController owner = new FakeController();
        owner.origin = "parent";
        m.enterHome(owner, SESSION_A, "host", "child-a", 7, "game-a");
        assertTrue(m.parkForBackground(SESSION_A));
        assertTrue(m.canSwitchOrigin("host", "parent", 7));
        assertEquals(SessionModel.SwitchResult.STARTED,
                m.switchGame("host", "parent", "child-b", true,
                        7, "game-b", "Game", "Stream", "", () -> false, (outcome, error) -> { }));
        assertTrue(m.bindActor(owner, SESSION_A, "child-b", "parent"));
        assertTrue(m.updateOwnedSwitchGame(owner,
                owner.switchRequest, "game-a", "game-b"));
        assertFalse(owner.disconnected);
    }

    @Test public void childAndParentReturnInstantlyFromDashboardAndBackground() {
        for (String profile : new String[] {"parent", "child"}) {
            m.clear();
            FakeController owner = new FakeController();
            m.enterHome(owner, SESSION_A,
                    "host", profile, 7, "steam:367520");
            assertTrue(m.canResumeInstantly());
            assertTrue(m.parkForBackground(SESSION_A));
            assertTrue(m.canResumeInstantly());
            assertEquals(profile, m.snapshot().profileId);
            assertFalse(owner.disconnected);
            assertFalse(owner.terminated);
            m.clearIfMatches(SESSION_A);
            assertFalse(m.canResumeInstantly());
        }
    }

    @Test public void exactSwitchSnapshotRejectsStaleTokenAndDeadOwner() {
        FakeController owner = new FakeController();
        owner.live = false;
        m.beginPreparing(owner, SESSION_A, "host", 7, "");
        SessionModel.Snapshot preparing = m.snapshot();
        assertTrue(m.canSwitchGame(preparing));
        m.clear();
        m.beginPreparing(owner, SESSION_A, "host", 7, "");
        assertFalse(m.canSwitchGame(preparing));
        m.clear();
        m.enterHome(owner, SESSION_A, "host", 7, "");
        SessionModel.Snapshot home = m.snapshot();
        assertFalse(m.canSwitchGame(home));
        owner.live = true;
        m.enterHome(owner, SESSION_A, "host", 7, "");
        home = m.snapshot();
        assertTrue(m.canSwitchGame(home));
        m.enterHome(owner, "other", "foreign", 8, "");
        assertFalse(m.canSwitchGame(home));
    }

    @Test public void snapshotSwitchEligibilityPreservesNonDefaultProfile() {
        for (String gameId : new String[] { "", "old" }) {
            m.clear();
            FakeController owner = new FakeController();
            String profile = "profile-a";
            assertTrue(m.beginPreparing(
                    owner, SESSION_A, "host", profile, 7, gameId) > 0L);
            SessionModel.Snapshot preparing =
                    m.snapshot();
            assertTrue(m.canSwitchGame(preparing));

            m.enterHome(
                    owner, SESSION_A, "host", profile, 7, gameId);
            SessionModel.Snapshot home =
                    m.snapshot();
            assertTrue(m.canSwitchGame(home));
        }
    }

    @Test public void snapshotSwitchEligibilityRejectsStaleProfile() {
        FakeController owner = new FakeController();
        m.enterHome(
                owner, SESSION_A, "host", "profile-a", 7, "old");
        SessionModel.Snapshot stale =
                m.snapshot();

        m.enterHome(
                owner, SESSION_A, "host", "profile-b", 7, "old");
        assertFalse(m.canSwitchGame(stale));
    }

    @Test public void ownedSwitchCanPublishAcceptedGameButDashboardCannotPreclearIt() {
        for (String oldGame : new String[] { "", "old" }) {
            m.clear();
            FakeController owner = new FakeController();
            m.enterHome(owner, SESSION_A, "host", 7, oldGame);
            assertEquals(SessionModel.SwitchResult.STARTED,
                    m.switchGame(
                            "host", 7, "new", "New", "MoonWaker Stream", "",
                            () -> false, null));
            assertFalse(m.updateOwnedSwitchGame(
                    new FakeController(), owner.switchRequest, oldGame, ""));
            assertTrue(m.updateOwnedSwitchGame(
                    owner, owner.switchRequest, oldGame, ""));
            assertTrue(m.updateOwnedSwitchGame(
                    owner, owner.switchRequest, "", "new"));
            assertEquals("new", m.snapshot().playniteGameId);
            assertEquals(1, owner.switches);
            assertFalse(owner.terminated);
            m.enterHome(
                    new FakeController(), "session-b", "other", 8, "other-game");
            assertFalse(m.updateOwnedSwitchGame(
                    owner, owner.switchRequest, "new", ""));
            assertEquals("other-game", m.snapshot().playniteGameId);
        }
    }

    @Test public void homeParksAndRemainsInstantlyResumable() {
        FakeController controller = new FakeController();
        m.enterHome(controller, SESSION_A, "host", 7, "game");

        assertTrue(m.parkForBackground(SESSION_A));
        assertEquals(SessionModel.State.PARKED_LIVE,
                m.state());
        assertTrue(m.canResumeInstantly());
        SessionModel.Snapshot parked =
                m.snapshot();
        assertTrue(m.canResumeInstantly(parked));

        m.enterHome(
                controller, "session-b", "other", 8, "other-game");
        assertFalse(m.canResumeInstantly(parked));
    }

    @Test public void externalPreparingCancelCapturesTheExactOwnerAndToken() {
        FakeController owner = new FakeController();
        assertTrue(m.beginPreparing(owner, SESSION_A, "host", 7, "") > 0L);
        SessionModel.Snapshot expected =
                m.snapshot();

        assertTrue(m.cancelPreparing(expected));
        assertEquals(1, owner.preparingCancels);
        assertEquals(expected, owner.preparingCancel);
        assertEquals(SessionModel.State.PREPARING,
                m.state());
    }

    @Test public void submittedHomeFrameReachesOnlyTheExactPreparingOwner() {
        FakeController owner = new FakeController();
        long operation = m.beginPreparing(owner, SESSION_A, "host", 7, "game");
        assertTrue(operation > 0L);
        SessionModel.Snapshot expected =
                m.snapshot();
        assertEquals(operation, expected.preparingOperation);

        assertTrue(m.preparingHomeFrameSubmitted(expected));
        assertEquals(1, owner.submittedPreparingFrames);
        assertFalse(m.preparingHomeFrameSubmitted(expected));
        assertEquals(1, owner.submittedPreparingFrames);
        assertTrue(m.isPreparingHomeFrameAccepted(
                SESSION_A, "host", 7, "game", operation));
        assertFalse(m.isPreparingHomeFrameAccepted(
                SESSION_A, "host", 7, "game", operation + 1L));
        assertTrue(m.completePreparing(
                owner, SESSION_A, "host", 7, operation));
        assertTrue(m.isPreparingHomeFrameAccepted(
                SESSION_A, "host", 7, "game", operation));
        m.enterHome(
                owner, SESSION_A, "host", 7, "game");
        assertFalse(m.isPreparingHomeFrameAccepted(
                SESSION_A, "host", 7, "game", operation));
    }

    /** Review S09 C5: a lost transport takes its preparing operation and accepted Home frame. */
    @Test public void lostTransportClearsThePreparingOperationAndItsHomeFrame() {
        for (int path = 0; path < 3; path++) {
            SessionModelDriver d = new SessionModelDriver();
            FakeController owner = new FakeController();
            long operation = d.beginPreparing(owner, SESSION_A, "host", 7, "");
            assertTrue(d.preparingHomeFrameSubmitted(d.snapshot()));
            assertTrue(d.completePreparing(owner, SESSION_A, "host", 7, operation));
            assertEquals(operation, d.snapshot().acceptedHomeOperation);
            if (path == 0) {
                d.markReconnectRequired(SESSION_A);
            } else if (path == 1) {
                assertTrue(d.markTransportUnsafe(owner, SESSION_A, "host", 7));
            } else {
                owner.live = false;
                d.call(new SessionEvent.ProbeTransport());
            }
            SessionModel.Snapshot lost = d.snapshot();
            assertEquals("path " + path, SessionModel.State.RECONNECT_REQUIRED, lost.state);
            assertEquals("path " + path, 0L, lost.preparingOperation);
            assertEquals("path " + path, 0L, lost.acceptedHomeOperation);
            assertFalse(d.isPreparingHomeFrameAccepted(SESSION_A, "host", 7, "", operation));
        }
    }

    /** Review S09 C5: a Home extra restored after process death never names a new operation. */
    @Test public void operationsContinueAfterTheSeedOfTheNewProcess() {
        long seed = 1_000_000L;
        SessionModel restarted = new SessionModel(Runnable::run, Runnable::run, () -> false,
                () -> 0L, (level, component, event, fields) -> true, new MemoryPreferences(),
                new MemoryPreferences(), new MemoryPreferences(), seed);
        long[] operation = {0L};
        restarted.dispatch(new SessionEvent.PreparingBegun(new FakeController(), SESSION_A,
                "host", GatewayConnection.DEFAULT_PROFILE_ID, 7, ""), value -> operation[0] = value);
        assertTrue(operation[0] > seed);
    }

    @Test public void completedPreparingStateDuringFrameCallbackCommitsExactOperation() {
        FakeController owner = new FakeController() {
            @Override public boolean preparingHomeFrameSubmitted(
                    SessionModel.Snapshot preparing) {
                assertTrue(m.completePreparing(
                        this, SESSION_A, "host", 7, preparing.preparingOperation));
                return true;
            }
        };
        long operation = m.beginPreparing(owner, SESSION_A, "host", 7, "game");
        SessionModel.Snapshot expected =
                m.snapshot();

        assertTrue(m.preparingHomeFrameSubmitted(expected));
        assertEquals(SessionModel.State.HOME_LIVE,
                m.state());
        assertEquals(operation, m.snapshot().preparingOperation);
        assertTrue(m.isPreparingHomeFrameAccepted(
                SESSION_A, "host", 7, "game", operation));
    }

    /** S09 C4: the Home frame of an older preparing operation never commits. */
    @Test public void staleHomeFrameCannotCommitAfterSameOwnerStartsNewOperation() {
        long[] replacement = {0L};
        FakeController owner = new FakeController() {
            @Override public boolean preparingHomeFrameSubmitted(
                    SessionModel.Snapshot preparing) {
                if (preparing.preparingOperation == replacement[0]) return true;
                assertTrue(m.cancelPreparing(
                        this, SESSION_A, "host", 7, preparing.preparingOperation));
                replacement[0] = m.beginPreparing(this, SESSION_A, "host", 7, "game");
                assertTrue(m.preparingHomeFrameSubmitted(m.snapshot()));
                return true;
            }
        };
        long first = m.beginPreparing(owner, SESSION_A, "host", 7, "game");
        SessionModel.Snapshot stale =
                m.snapshot();

        assertFalse(m.preparingHomeFrameSubmitted(stale));
        SessionModel.Snapshot current =
                m.snapshot();
        assertEquals(SessionModel.State.PREPARING, current.state);
        assertTrue(replacement[0] > first);
        assertEquals(replacement[0], current.preparingOperation);
        assertFalse(m.isPreparingHomeFrameAccepted(
                SESSION_A, "host", 7, "game", first));
        assertTrue(m.isPreparingHomeFrameAccepted(
                SESSION_A, "host", 7, "game", replacement[0]));
    }

    /** S09 C4: a Home recreated with an older operation's snapshot never reaches the owner. */
    @Test public void homeFrameOfAnOlderPreparingOperationIsRejected() {
        FakeController owner = new FakeController();
        long first = m.beginPreparing(owner, SESSION_A, "host", 7, "game");
        SessionModel.Snapshot stale = m.snapshot();
        assertTrue(m.cancelPreparing(owner, SESSION_A, "host", 7, first));
        long second = m.beginPreparing(owner, SESSION_A, "host", 7, "game");
        assertTrue(second > first);

        assertFalse(m.preparingHomeFrameSubmitted(stale));
        assertEquals(0, owner.submittedPreparingFrames);
        assertFalse(m.isPreparingHomeFrameAccepted(SESSION_A, "host", 7, "game", first));
        assertFalse(m.isPreparingHomeFrameAccepted(SESSION_A, "host", 7, "game", second));
        // The current operation's own Home frame is still accepted once.
        assertTrue(m.preparingHomeFrameSubmitted(m.snapshot()));
        assertTrue(m.isPreparingHomeFrameAccepted(SESSION_A, "host", 7, "game", second));
    }

    /** S09 C4: a switch result of an older preparing operation changes nothing newer. */
    @Test public void switchResultOfAnOlderPreparingOperationIsRejected() {
        FakeController owner = new FakeController();
        AtomicReference<SessionModel.SwitchOutcome> outcome = new AtomicReference<>();
        long first = m.beginPreparing(owner, SESSION_A, "host", 7, "old");
        assertEquals(SessionModel.SwitchResult.STARTED, m.switchGame("host", 7, "new", "New",
                "MoonWaker Stream", "", () -> false, (result, error) -> outcome.set(result)));
        SessionModel.SwitchRequest stale = owner.switchRequest;
        assertEquals(first, stale.preparingOperation);
        assertTrue(m.cancelPreparing(owner, SESSION_A, "host", 7, first));
        long second = m.beginPreparing(owner, SESSION_A, "host", 7, "old");

        owner.switchCompletion.complete(SessionModel.SwitchOutcome.REUSED, "");
        m.drain();

        assertNull(outcome.get());
        assertFalse(m.isPreparingSwitchOwned(SESSION_A, "host", 7, first));
        assertFalse(m.updatePreparingSwitch(owner, stale, "old", "new"));
        assertFalse(m.finishSwitch(stale, owner));
        assertTrue(m.isPreparing(owner, SESSION_A, "host", 7, second));
        assertEquals("old", m.snapshot().playniteGameId);
        assertTrue(m.canSwitchGame("host", 7));
        // The new operation's own switch is its own: the older operation never owns it.
        assertEquals(SessionModel.SwitchResult.STARTED, m.switchGame("host", 7, "new", "New",
                "MoonWaker Stream", "", () -> false, null));
        assertTrue(m.isPreparingSwitchOwned(SESSION_A, "host", 7, second));
        assertFalse(m.isPreparingSwitchOwned(SESSION_A, "host", 7, first));
    }

    /** S09 C4: a reconnect is a new preparation: a new operation; the lost one's proof is gone. */
    @Test public void reconnectGetsANewPreparingOperation() {
        FakeController lost = new FakeController();
        long first = m.beginPreparing(lost, SESSION_A, "host", 7, "game");
        assertTrue(m.preparingHomeFrameSubmitted(m.snapshot()));
        SessionModel.Snapshot beforeLoss = m.snapshot();
        assertTrue(m.markPreparingReconnectRequired(lost, SESSION_A, "host", 7, first));

        FakeController reconnect = new FakeController();
        long second = m.beginPreparing(reconnect, SESSION_A, "host", 7, "game");

        assertTrue(second > first);
        assertEquals(second, m.snapshot().preparingOperation);
        assertFalse(m.isPreparingHomeFrameAccepted(SESSION_A, "host", 7, "game", first));
        assertFalse(m.preparingHomeFrameSubmitted(beforeLoss));
        assertFalse(m.completePreparing(reconnect, SESSION_A, "host", 7, first));
        assertTrue(m.completePreparing(reconnect, SESSION_A, "host", 7, second));
    }

    /**
     * S09 C4: Back on the Home while its frame is still being claimed cancels the preparing
     * transport (CancelPreparingTransport); the late Home-frame result is then rejected.
     */
    @Test public void backDuringTheHomeFrameCancelsPreparing() {
        FakeController owner = new FakeController() {
            @Override public boolean preparingHomeFrameSubmitted(
                    SessionModel.Snapshot preparing) {
                super.preparingHomeFrameSubmitted(preparing);
                assertTrue(m.cancelPreparing(preparing));
                return true;
            }

            @Override public boolean cancelPreparingTransport(
                    SessionModel.Snapshot preparing) {
                super.cancelPreparingTransport(preparing);
                return m.cancelPreparing(
                        this, SESSION_A, "host", 7, preparing.preparingOperation);
            }
        };
        long operation = m.beginPreparing(owner, SESSION_A, "host", 7, "game");

        assertFalse(m.preparingHomeFrameSubmitted(m.snapshot()));

        assertEquals(1, owner.preparingCancels);
        assertEquals(operation, owner.preparingCancel.preparingOperation);
        assertEquals(SessionModel.State.NONE, m.state());
        assertFalse(m.isPreparingHomeFrameAccepted(SESSION_A, "host", 7, "game", operation));
    }

    @Test public void cancelledFrameClaimCannotBecomeAccepted() {
        FakeController owner = new FakeController() {
            @Override public boolean preparingHomeFrameSubmitted(
                    SessionModel.Snapshot preparing) {
                assertTrue(m.cancelPreparing(
                        this, SESSION_A, "host", 7, preparing.preparingOperation));
                return true;
            }
        };
        long operation = m.beginPreparing(owner, SESSION_A, "host", 7, "game");
        SessionModel.Snapshot expected =
                m.snapshot();

        assertFalse(m.preparingHomeFrameSubmitted(expected));
        assertEquals(SessionModel.State.NONE,
                m.state());
        assertFalse(m.isPreparingHomeFrameAccepted(
                SESSION_A, "host", 7, "game", operation));
    }

    @Test public void staleExternalCancelCannotReachANewerOwner() {
        FakeController oldOwner = new FakeController();
        long old = m.beginPreparing(oldOwner, SESSION_A, "host", 7, "");
        SessionModel.Snapshot stale =
                m.snapshot();
        assertTrue(m.cancelPreparing(
                oldOwner, SESSION_A, "host", 7, old));
        FakeController newOwner = new FakeController();
        assertTrue(m.beginPreparing(newOwner, "session-b", "host", 7, "") > 0L);

        assertFalse(m.cancelPreparing(stale));
        assertEquals(0, newOwner.preparingCancels);
        assertEquals("session-b", m.snapshot().streamSessionId);
    }

    @Test public void externalCancelNeverClosesLiveOrParkedSessions() {
        for (boolean parked : new boolean[] { false, true }) {
            FakeController owner = new FakeController();
            m.enterHome(owner, SESSION_A, "host", 7, "");
            if (parked) m.markParked(SESSION_A);
            SessionModel.Snapshot live =
                    m.snapshot();

            assertFalse(m.cancelPreparing(live));
            assertEquals(0, owner.preparingCancels);
            m.clear();
        }
    }

    @Test public void failedParkRequiresReconnect() {
        FakeController controller = new FakeController();
        controller.parkResult = false;
        m.enterHome(controller, SESSION_A, "host", 7, "game");

        assertFalse(m.parkForBackground(SESSION_A));
        assertEquals(SessionModel.State.RECONNECT_REQUIRED,
                m.state());
        assertFalse(m.canResumeInstantly());
    }

    @Test public void terminateCarriesTheGameTheRequesterNamed() {
        FakeController controller = new FakeController();
        m.enterHome(controller, SESSION_A, "host", 7, "");

        assertEquals(SessionModel.TerminationResult.STARTED, m.call(
                new SessionEvent.TerminateRequested(SESSION_A, "named", null)));
        assertEquals("named", controller.terminatedGameId);
    }

    @Test public void failedTerminationOfANamedGameLeavesTheSessionsOwnGame() {
        FakeController controller = new FakeController();
        controller.completeTermination = false;
        m.enterHome(controller, SESSION_A, "host", 7, "");

        assertEquals(SessionModel.TerminationResult.STARTED, m.call(
                new SessionEvent.TerminateRequested(SESSION_A, "named", null)));
        controller.terminationCompletion.complete(false);
        m.drain();

        assertEquals(SessionModel.State.HOME_LIVE, m.snapshot().state);
        assertEquals("", m.snapshot().playniteGameId);
    }

    @Test public void terminateInvokesOwnerAndRemovesResumeState() {
        FakeController controller = new FakeController();
        m.enterHome(controller, SESSION_A, "host", 7, "game");

        assertEquals(SessionModel.TerminationResult.STARTED,
                m.terminate(SESSION_A, null));
        assertTrue(controller.terminated);
        assertFalse(m.hasRetainedSession());
        assertFalse(m.canResumeInstantly());
    }


    @Test public void hardResetClearsEvenPreparingStateOnlyForTheExactHost() {
        FakeController controller = new FakeController();
        m.beginPreparing(
                controller, SESSION_A, "host", 7, "game");

        assertFalse(m.hardResetIfHostMatches("other"));
        assertEquals(SessionModel.State.PREPARING,
                m.state());
        assertTrue(m.hardResetIfHostMatches("HOST"));
        assertEquals(SessionModel.State.NONE,
                m.state());
        assertEquals(1, controller.preparingCancels);
        assertEquals(SESSION_A, controller.preparingCancel.streamSessionId);
        assertFalse(controller.disconnected);
    }

    @Test public void lostOwnerFallsBackToReconnect() {
        m.enterHome(
                new FakeController(), SESSION_A, "host", 7, "game");
        m.markReconnectRequired(SESSION_A);

        assertEquals(SessionModel.State.RECONNECT_REQUIRED,
                m.state());
        assertFalse(m.canResumeInstantly());
    }

    @Test public void deadOwnerCannotResumeSwitchOrTerminate() {
        FakeController controller = new FakeController();
        controller.live = false;
        m.enterHome(
                controller, SESSION_A, "host", 7, "game");

        assertFalse(m.canResumeInstantly());
        assertEquals(SessionModel.State.RECONNECT_REQUIRED,
                m.state());
        assertFalse(m.canSwitchGame("host", 7));
        assertEquals(SessionModel.TerminationResult.NO_CONTROLLER,
                m.terminate(SESSION_A, null));
        assertFalse(controller.terminated);
    }


    @Test public void failedTerminationRestoresNeutralizedLiveOwner() {
        FakeController controller = new FakeController();
        controller.completeTermination = false;
        m.enterHome(
                controller, SESSION_A, "host", 7, "game");
        assertEquals(SessionModel.TerminationResult.STARTED,
                m.terminate(SESSION_A, null));

        controller.terminationCompletion.complete(false);

        m.drain();

        SessionModel.Snapshot retained =
                m.snapshot();
        assertEquals(SessionModel.State.HOME_LIVE, retained.state);
        assertEquals("game", retained.playniteGameId);
        assertTrue(m.canResumeInstantly());
    }

    @Test public void snapshotKeepsRetainedGameIdentity() {
        m.enterHome(
                new FakeController(), SESSION_A, "host", 7, "game");
        SessionModel.Snapshot snapshot =
                m.snapshot();
        assertEquals(SESSION_A, snapshot.streamSessionId);
        assertEquals("host", snapshot.hostId);
        assertEquals(7, snapshot.appId);
        assertEquals("game", snapshot.playniteGameId);
    }

    @Test public void preparingRequiresFullCorrelationAndIsNotResumeReady() {
        FakeController owner = new FakeController();
        FakeController staleOwner = new FakeController();

        long operation = m.beginPreparing(owner, SESSION_A, "host", 7, "game");
        SessionModel.Snapshot snapshot =
                m.snapshot();
        assertEquals(SessionModel.State.PREPARING, snapshot.state);
        assertEquals("game", snapshot.playniteGameId);
        assertTrue(operation > 0L);
        assertEquals(operation, snapshot.preparingOperation);
        assertFalse(m.canResumeInstantly());
        assertTrue(m.parkForBackground(SESSION_A));
        assertFalse(m.clearIfMatches(SESSION_A));
        assertEquals(SessionModel.TerminationResult.NO_CONTROLLER,
                m.terminate(SESSION_A, null));

        assertFalse(m.completePreparing(
                staleOwner, SESSION_A, "host", 7, operation));
        assertFalse(m.completePreparing(
                owner, "stale-session", "host", 7, operation));
        assertFalse(m.completePreparing(
                owner, SESSION_A, "stale-host", 7, operation));
        assertFalse(m.completePreparing(
                owner, SESSION_A, "host", 8, operation));
        assertFalse(m.completePreparing(
                owner, SESSION_A, "host", 7, operation + 1L));
        assertTrue(m.completePreparing(
                owner, SESSION_A, "host", 7, operation));
        assertEquals(SessionModel.State.HOME_LIVE,
                m.state());
        assertTrue(m.canResumeInstantly());
    }

    @Test public void preparingCancelOnlyClearsTheExactOperation() {
        FakeController owner = new FakeController();
        long first = m.beginPreparing(owner, SESSION_A, "host", 7, "");

        assertFalse(m.cancelPreparing(
                owner, SESSION_A, "host", 7, first + 1L));
        assertTrue(m.cancelPreparing(
                owner, SESSION_A, "host", 7, first));
        long second = m.beginPreparing(owner, SESSION_A, "host", 7, "");
        assertTrue(second > first);
        assertFalse(m.cancelPreparing(
                owner, SESSION_A, "host", 7, first));
        assertTrue(m.isPreparing(
                owner, SESSION_A, "host", 7, second));
    }

    @Test public void preparingParkDelegatesTheExactTokenWithoutChangingState() {
        FakeController owner = new FakeController();
        long operation = m.beginPreparing(owner, SESSION_A, "host", 7, "game");

        assertTrue(m.parkForBackground(SESSION_A));

        assertEquals(SessionModel.State.PREPARING,
                m.state());
        assertEquals(SESSION_A, owner.preparingPark.streamSessionId);
        assertEquals("host", owner.preparingPark.hostId);
        assertEquals(7, owner.preparingPark.appId);
        assertEquals("game", owner.preparingPark.playniteGameId);
        assertEquals(operation, owner.preparingPark.preparingOperation);
    }

    @Test public void preparingReconnectRequiresTheFullTokenAndRejectsLateCompletion() {
        FakeController owner = new FakeController();
        long operation = m.beginPreparing(owner, SESSION_A, "host", 7, "");

        assertFalse(m.markPreparingReconnectRequired(
                owner, SESSION_A, "host", 7, operation + 1L));
        assertTrue(m.markPreparingReconnectRequired(
                owner, SESSION_A, "host", 7, operation));

        SessionModel.Snapshot snapshot =
                m.snapshot();
        assertEquals(SessionModel.State.RECONNECT_REQUIRED,
                snapshot.state);
        assertEquals(0L, snapshot.preparingOperation);
        assertFalse(m.completePreparing(
                owner, SESSION_A, "host", 7, operation));
    }

    /**
     * Owner decision 2026-10-14: a marker is resume metadata. A warm-up reconnects it on its
     * stream identity alone, and the host's current game (or none) replaces the marker's game.
     */
    @Test public void warmUpReconnectTakesTheHostsCurrentGameOverTheMarkersGame() {
        for (String current : new String[] {"game-b", ""}) {
            SessionModelDriver model = new SessionModelDriver();
            FakeController owner = new FakeController();
            model.enterHome(owner, SESSION_A, "host", 7, "game-a");
            model.markReconnectRequired(SESSION_A);

            assertTrue(model.beginPreparing(owner, SESSION_A, "host", 7, current) > 0L);
            SessionModel.Snapshot snapshot = model.snapshot();
            assertEquals(SessionModel.State.PREPARING, snapshot.state);
            assertEquals(current, snapshot.playniteGameId);
        }
    }

    @Test public void warmUpNeverReconnectsAMarkerOfAnotherStream() {
        String[][] others = {
                {"other-session", "host", GatewayConnection.DEFAULT_PROFILE_ID, "7"},
                {SESSION_A, "other-host", GatewayConnection.DEFAULT_PROFILE_ID, "7"},
                {SESSION_A, "host", "other-profile", "7"},
                {SESSION_A, "host", GatewayConnection.DEFAULT_PROFILE_ID, "8"}};
        for (String[] other : others) {
            SessionModelDriver model = new SessionModelDriver();
            FakeController owner = new FakeController();
            model.enterHome(owner, SESSION_A, "host", 7, "game-a");
            model.markReconnectRequired(SESSION_A);

            assertEquals(0L, model.beginPreparing(owner, other[0], other[1], other[2],
                    Integer.parseInt(other[3]), "game-a"));
            assertEquals(SessionModel.State.RECONNECT_REQUIRED, model.snapshot().state);
            assertEquals("game-a", model.snapshot().playniteGameId);
        }
    }

    /** Review finding 5: a child on a parent's Windows profile never inherits a host game. */
    @Test public void childWarmUpOnAParentsProfileNeverReconnectsAMarkerOfAnotherGame() {
        for (String actor : new String[] {"parent", "child"}) {
            SessionModelDriver model = new SessionModelDriver();
            FakeController owner = new FakeController();
            owner.origin = "parent";
            model.enterHome(owner, SESSION_A, "host", actor, 7, "");
            model.markReconnectRequired(SESSION_A);

            boolean own = actor.equals("parent");
            assertEquals(own, model.beginPreparing(owner, SESSION_A, "host", actor, 7,
                    "game-b") > 0L);
            assertEquals(own ? SessionModel.State.PREPARING
                    : SessionModel.State.RECONNECT_REQUIRED, model.snapshot().state);
            assertEquals(own ? "game-b" : "", model.snapshot().playniteGameId);
        }
    }

    @Test public void intentionalCloseClearsOnlyTheOwnersLiveRecord() {
        FakeController owner = new FakeController();
        m.enterHome(owner, SESSION_A, "host", 7, "");
        m.seedReconnectRecord(SESSION_A);

        assertTrue(m.closedIntentionally(owner, SESSION_A));

        assertEquals(SessionModel.State.NONE, m.state());
        assertFalse(m.hasReconnectRecord());
    }

    /** Review finding 1: End session on Home or a game switch keeps owning the session. */
    @Test public void intentionalCloseKeepsATerminationOrASwitchInFlight() {
        for (boolean terminating : new boolean[] {true, false}) {
            SessionModelDriver model = new SessionModelDriver();
            FakeController owner = new FakeController();
            owner.completeTermination = false;
            model.enterHome(owner, SESSION_A, "host", 7, "game-a");
            model.seedReconnectRecord(SESSION_A);
            if (terminating) {
                assertEquals(SessionModel.TerminationResult.STARTED,
                        model.terminate(SESSION_A, null));
            } else {
                assertEquals(SessionModel.SwitchResult.STARTED, model.switchGame("host", 7,
                        "game-b", "B", "B", "", () -> false, null));
            }
            SessionModel.State before = model.state();

            assertFalse(model.closedIntentionally(owner, SESSION_A));

            assertEquals(before, model.state());
            assertTrue(model.hasReconnectRecord());
        }
    }

    /** Review findings 3 and 4: a lost transport's marker and another owner's record stay. */
    @Test public void intentionalCloseKeepsAReconnectMarkerAndAnotherOwnersRecord() {
        FakeController owner = new FakeController();
        m.enterHome(owner, SESSION_A, "host", 7, "game-a");
        m.seedReconnectRecord(SESSION_A);
        m.post(new SessionEvent.TransportLost(owner, SESSION_A));

        assertFalse(m.closedIntentionally(owner, SESSION_A));
        assertEquals(SessionModel.State.RECONNECT_REQUIRED, m.state());
        assertTrue(m.hasReconnectRecord());

        FakeController reconnected = new FakeController();
        m.enterHome(reconnected, SESSION_A, "host", 7, "game-a");
        assertFalse(m.closedIntentionally(owner, SESSION_A));
        assertTrue(m.ownsLiveOrParkedSession(reconnected, SESSION_A, "host", 7));
        assertTrue(m.hasReconnectRecord());
    }

    @Test public void stalePreparingParkCannotMutateAReplacementAttempt() {
        FakeController replacement = new FakeController();
        long[] replaced = {0L};
        FakeController owner = new FakeController() {
            @Override public boolean parkPreparingTransport(
                    SessionModel.Snapshot preparing) {
                assertTrue(m.cancelPreparing(
                        this, SESSION_A, "host", 7, preparing.preparingOperation));
                replaced[0] = m.beginPreparing(replacement, "session-b", "host", 7, "");
                return true;
            }
        };
        assertTrue(m.beginPreparing(owner, SESSION_A, "host", 7, "") > 0L);

        assertTrue(m.parkForBackground(SESSION_A));

        assertTrue(m.isPreparing(
                replacement, "session-b", "host", 7, replaced[0]));
    }

    @Test public void preparingSwitchCarriesItsTokenWithoutRequiringLiveTransport() {
        FakeController owner = new FakeController();
        owner.live = false;
        long operation = m.beginPreparing(owner, SESSION_A, "host", 7, "old");

        assertTrue(m.canSwitchGame("host", 7));
        assertFalse(m.isPreparingSwitchOwned(
                SESSION_A, "host", 7, operation));
        assertEquals(SessionModel.SwitchResult.STARTED,
                m.switchGame(
                        "host", 7, "new", "New", "MoonWaker Stream", "",
                        () -> false, null));
        assertTrue(m.isPreparingSwitchOwned(
                SESSION_A, "host", 7, operation));
        assertFalse(m.isPreparingSwitchOwned(
                SESSION_A, "host", 7, operation + 1L));
        assertEquals(SessionModel.SwitchResult.NOT_ELIGIBLE,
                m.switchGame(
                        "host", 7, "new", "New", "MoonWaker Stream", "",
                        () -> false, null));
        assertEquals(1, owner.switches);
        assertTrue(owner.switchRequest.preparing);
        assertEquals("old", owner.switchRequest.oldGameId);
        assertEquals(operation, owner.switchRequest.preparingOperation);
        assertTrue(owner.switchRequest.operationId > 0L);
        owner.switchCompletion.complete(
                SessionModel.SwitchOutcome.REUSED, "");
        m.drain();
        assertTrue(m.isPreparingSwitchOwned(
                SESSION_A, "host", 7, operation));
        assertFalse(m.finishSwitch(owner.switchRequest, new FakeController()));
        assertTrue(m.finishSwitch(owner.switchRequest, owner));
        assertFalse(m.isPreparingSwitchOwned(
                SESSION_A, "host", 7, operation));
    }

    @Test public void preMutationSwitchFenceRequiresExactOwnerTransportAndOperation() {
        FakeController owner = new FakeController();
        owner.origin = "parent";
        m.enterHome(
                owner, SESSION_A, "host", "child", 7, "game");
        assertTrue(m.bindControlGeneration(
                owner, SESSION_A, 4L));
        assertEquals(SessionModel.SwitchResult.STARTED,
                m.switchGame(
                        "host", "parent", "parent", false, 7,
                        "game", "Game", "MoonWaker Stream", "",
                        () -> false, null));
        SessionModel.SwitchRequest request = owner.switchRequest;

        assertTrue(m.ownsSwitchBeforeMutation(owner, request));
        assertFalse(m.ownsSwitchBeforeMutation(new FakeController(), request));
        // The Gateway control generation is carried, never a decision key.
        assertTrue(m.bindControlGeneration(
                owner, SESSION_A, 5L));
        assertTrue(m.ownsSwitchBeforeMutation(owner, request));

        m.enterHome(
                new FakeController(), "session-b", "host", "child", 7, "game");
        assertFalse(m.ownsSwitchBeforeMutation(owner, request));
    }

    @Test public void stalePreparingSwitchCallbackCannotChangeANewerAttempt() {
        FakeController owner = new FakeController();
        AtomicReference<SessionModel.SwitchOutcome> outcome =
                new AtomicReference<>();
        long first = m.beginPreparing(owner, SESSION_A, "host", 7, "");
        assertEquals(SessionModel.SwitchResult.STARTED,
                m.switchGame(
                        "host", 7, "new", "New", "MoonWaker Stream", "",
                        () -> false, (result, error) -> outcome.set(result)));
        SessionModel.SwitchCallback stale = owner.switchCompletion;
        assertTrue(m.cancelPreparing(
                owner, SESSION_A, "host", 7, first));
        long second = m.beginPreparing(owner, SESSION_A, "host", 7, "");

        stale.complete(SessionModel.SwitchOutcome.FAILED, "stale");

        assertTrue(m.isPreparing(
                owner, SESSION_A, "host", 7, second));
        assertTrue(m.canSwitchGame("host", 7));
        assertNull(outcome.get());
    }

    @Test public void completedPreparingSwitchUsesTheExistingHomeFinish() {
        FakeController owner = new FakeController();
        long operation = m.beginPreparing(owner, SESSION_A, "host", 7, "");
        assertEquals(SessionModel.SwitchResult.STARTED,
                m.switchGame(
                        "host", 7, "new", "New", "MoonWaker Stream", "",
                        () -> false, null));
        assertTrue(m.completePreparing(
                owner, SESSION_A, "host", 7, operation));

        assertTrue(m.finishSwitch(owner.switchRequest, owner));
    }

    @Test public void reusedPreparingSwitchReleasesItsCapturedLockBeforeConnection() {
        FakeController owner = new FakeController();
        assertTrue(m.beginPreparing(owner, SESSION_A, "host", 7, "old") > 0L);
        assertEquals(SessionModel.SwitchResult.STARTED,
                m.switchGame(
                        "host", 7, "same", "Same", "MoonWaker Stream", "",
                        () -> false, null));

        owner.switchCompletion.complete(
                SessionModel.SwitchOutcome.REUSED, "");

        m.drain();
        assertFalse(m.canSwitchGame("host", 7));
        assertTrue(m.finishSwitch(owner.switchRequest, owner));
        assertTrue(m.canSwitchGame("host", 7));
        assertEquals(SessionModel.SwitchResult.STARTED,
                m.switchGame(
                        "host", 7, "next", "Next", "MoonWaker Stream", "",
                        () -> false, null));
    }

    @Test public void stalePreparingFinishCannotReleaseAReplacementSwitch() {
        FakeController owner = new FakeController();
        long first = m.beginPreparing(owner, SESSION_A, "host", 7, "");
        assertEquals(SessionModel.SwitchResult.STARTED,
                m.switchGame(
                        "host", 7, "old", "Old", "MoonWaker Stream", "",
                        () -> false, null));
        SessionModel.SwitchRequest stale = owner.switchRequest;
        assertTrue(m.cancelPreparing(
                owner, SESSION_A, "host", 7, first));
        assertTrue(m.beginPreparing(owner, SESSION_A, "host", 7, "") > first);
        assertEquals(SessionModel.SwitchResult.STARTED,
                m.switchGame(
                        "host", 7, "new", "New", "MoonWaker Stream", "",
                        () -> false, null));

        // Same owner and stream session: only the model's operation id tells them apart.
        assertFalse(m.finishSwitch(stale, owner));
        assertFalse(m.canSwitchGame("host", 7));
        assertTrue(m.finishSwitch(owner.switchRequest, owner));
    }

    /** K5.3b: the switch lock is released only by its own operation's finish. */
    @Test public void lateFinishOfAFailedSwitchCannotReleaseTheNextSwitch() {
        FakeController owner = new FakeController();
        m.enterHome(owner, SESSION_A, "host", 7, "game-a");
        assertEquals(SessionModel.SwitchResult.STARTED, m.switchGame(
                "host", 7, "game-b", "B", "MoonWaker Stream", "", () -> false, null));
        SessionModel.SwitchRequest failed = owner.switchRequest;
        owner.switchCompletion.complete(SessionModel.SwitchOutcome.FAILED, "failed");
        m.drain();
        assertEquals(SessionModel.SwitchResult.STARTED, m.switchGame(
                "host", 7, "game-c", "C", "MoonWaker Stream", "", () -> false, null));

        // Same owner, same stream session: the late finish is stale by its operation id.
        assertFalse(m.finishSwitch(failed, owner));
        assertFalse("the next switch keeps its lock", m.canSwitchGame("host", 7));
        assertTrue(m.finishSwitch(owner.switchRequest, owner));
        assertTrue(m.canSwitchGame("host", 7));
    }

    /**
     * K5.3b: a reconnect reuses the stream session id but is a new transport generation. A
     * recovery captured against the lost transport cannot act on the reconnected one, although
     * every other binding still matches.
     */
    @Test public void reconnectReusingTheSessionIdDoesNotLetTheLostTransportsRecoveryThrough() {
        FakeController lost = new FakeController();
        m.enterHome(lost, SESSION_A, "host", 7, "game");
        SessionModel.Snapshot beforeLoss = m.snapshot();
        m.markReconnectRequired(SESSION_A);
        FakeController reconnect = new FakeController();
        long operation = m.beginPreparing(reconnect, SESSION_A, "host", 7, "game");
        assertTrue(m.completePreparing(
                reconnect, SESSION_A, "host", 7, operation));
        assertEquals(reconnect.transportGeneration(), m.snapshot().transportGeneration);

        assertNull(m.beginDirectTermination(beforeLoss));
        assertEquals(SessionModel.State.HOME_LIVE, m.state());
        assertTrue(m.ownsLiveOrParkedSession(reconnect, SESSION_A, "host", 7));
    }

    @Test public void preparingSwitchUpdateRequiresTheFullCapturedToken() {
        FakeController owner = new FakeController();
        FakeController staleOwner = new FakeController();
        long operation = m.beginPreparing(owner, SESSION_A, "host", 7, "old");
        assertEquals(SessionModel.SwitchResult.STARTED,
                m.switchGame(
                        "host", 7, "new", "New", "MoonWaker Stream", "",
                        () -> false, null));
        SessionModel.SwitchRequest request = owner.switchRequest;

        assertFalse(m.updatePreparingSwitch(
                staleOwner, request, "old", ""));
        assertFalse(m.updatePreparingSwitch(
                owner, request, "other", ""));
        assertTrue(m.updatePreparingSwitch(
                owner, request, "old", ""));

        SessionModel.Snapshot updated =
                m.snapshot();
        assertEquals(SessionModel.State.PREPARING, updated.state);
        assertEquals("", updated.playniteGameId);
        assertEquals(operation, updated.preparingOperation);
        assertTrue(m.cancelPreparing(
                owner, SESSION_A, "host", 7, operation));
        long replacement = m.beginPreparing(staleOwner, SESSION_A, "host", 7, "");
        // The released switch's late update cannot retarget the replacement preparation.
        assertFalse(m.updatePreparingSwitch(
                owner, request, "", "new"));
        assertTrue(m.isPreparing(
                staleOwner, SESSION_A, "host", 7, replacement));
    }

    @Test public void preparingSwitchKeepsTheAcceptedHomeFrameOfItsOperation() {
        FakeController owner = new FakeController();
        long operation = m.beginPreparing(owner, SESSION_A, "host", 7, "old");
        assertTrue(m.preparingHomeFrameSubmitted(
                m.snapshot()));
        SessionModel.Snapshot ownerIdentity =
                m.snapshot();
        assertEquals(SessionModel.SwitchResult.STARTED,
                m.switchGame(
                        "host", ownerIdentity.executionProfileId, ownerIdentity.profileId,
                        false, 7, "old", "Old", "MoonWaker Stream", "", 19L,
                        () -> false, null));
        SessionModel.SwitchRequest request = owner.switchRequest;
        assertEquals(19L, request.orchestrationId);

        assertEquals(operation, request.preparingOperation);
        assertTrue(m.updatePreparingSwitch(
                owner, request, "old", "old"));

        assertTrue("the same operation keeps its accepted Home proof",
                m.isPreparingHomeFrameAccepted(
                        SESSION_A, "host", 7, "old", operation));
        assertFalse("a different operation never inherits it",
                m.isPreparingHomeFrameAccepted(
                        SESSION_A, "host", 7, "old", operation + 1L));
        assertFalse("a second Home frame is not accepted for the same operation",
                m.preparingHomeFrameSubmitted(
                        m.snapshot()));
    }

    @Test public void staleOwnerCannotMoveTheAcceptedHomeFrame() {
        FakeController owner = new FakeController();
        FakeController stale = new FakeController();
        long operation = m.beginPreparing(owner, SESSION_A, "host", 7, "old");
        assertTrue(m.preparingHomeFrameSubmitted(
                m.snapshot()));
        m.switchGame(
                "host", 7, "old", "Old", "MoonWaker Stream", "", () -> false, null);

        assertFalse(m.updatePreparingSwitch(
                stale, owner.switchRequest, "old", "new"));
        assertEquals("old", m.snapshot().playniteGameId);
        assertTrue(m.isPreparingHomeFrameAccepted(
                SESSION_A, "host", 7, "old", operation));
    }

    @Test public void repeatedTerminationDoesNotStartASecondRequest() {
        FakeController controller = new FakeController();
        controller.completeTermination = false;
        AtomicInteger first = new AtomicInteger();
        AtomicInteger second = new AtomicInteger();
        m.enterHome(controller, SESSION_A, "host", 7, "game");

        assertEquals(SessionModel.TerminationResult.STARTED,
                m.terminate(SESSION_A,
                        finished(success -> first.set(success ? 1 : -1))));
        assertEquals(SessionModel.TerminationResult.IN_PROGRESS,
                m.terminate(SESSION_A,
                        finished(success -> second.set(success ? 1 : -1))));
        assertEquals(SessionModel.State.TERMINATING,
                m.state());
        assertEquals(1, controller.terminations);
        assertEquals(0, first.get());
        assertEquals(0, second.get());

        controller.terminationCompletion.complete(true);

        m.drain();
        assertEquals(1, first.get());
        assertEquals(1, second.get());
        assertEquals(SessionModel.State.NONE,
                m.state());
    }

    @Test public void ownerCompletionClearsTerminatingAndPublishesSuccess() {
        FakeController controller = new FakeController();
        controller.completeTermination = false;
        AtomicReference<SessionModel.State> observed =
                new AtomicReference<>();
        m.enterHome(controller, SESSION_A, "host", 7, "game");

        m.terminate(SESSION_A,
                finished(success -> observed.set(m.state())));
        assertEquals(SessionModel.State.TERMINATING,
                m.state());
        controller.terminationCompletion.complete(true);
        m.drain();

        assertEquals(SessionModel.State.NONE, observed.get());
        assertEquals(SessionModel.State.NONE,
                m.state());
    }

    @Test public void oneThrowingTerminationJoinerDoesNotBlockTheNext() {
        FakeController controller = new FakeController();
        controller.completeTermination = false;
        AtomicInteger observed = new AtomicInteger();
        m.enterHome(controller, SESSION_A, "host", 7, "game");

        m.terminate(SESSION_A, finished(success -> {
            throw new IllegalStateException("observer failed");
        }));
        m.terminate(SESSION_A,
                finished(success -> observed.set(success ? 1 : -1)));
        controller.terminationCompletion.complete(true);
        m.drain();

        assertEquals(1, observed.get());
    }


    @Test public void directStreamQuitIsVisibleUntilMatchingQuitCompletes() {
        assertTrue(m.markTerminating(
                SESSION_A, "host", 7, "game"));

        SessionModel.Snapshot snapshot =
                m.snapshot();
        assertEquals(SessionModel.State.TERMINATING, snapshot.state);
        assertEquals("host", snapshot.hostId);
        assertEquals(7, snapshot.appId);

        assertFalse(m.clearIfMatches("other-session"));
        assertEquals(SessionModel.State.TERMINATING,
                m.state());
        assertFalse(m.clearAfterAuthoritativeEndIfMatches(
                SESSION_A));
        assertEquals(SessionModel.State.TERMINATING,
                m.state());
        assertTrue(m.clearIfMatches(SESSION_A));
        assertEquals(SessionModel.State.NONE,
                m.state());
    }

    @Test public void directWholeQuitRegistersAndCompletesCompatibleJoiners() {
        AtomicReference<Boolean> joined = new AtomicReference<>();
        SessionModel.DirectTermination direct =
                m.beginDirectTermination(
                        SESSION_A, "host", 7, "game");
        assertTrue(direct != null);

        assertEquals(SessionModel.TerminationResult.IN_PROGRESS,
                m.terminate(SESSION_A, finished(joined::set)));
        assertNull(joined.get());
        m.completeDirectTermination(direct, true);

        assertEquals(Boolean.TRUE, joined.get());
        assertEquals(SessionModel.State.NONE,
                m.state());
    }

    @Test public void directWholeQuitLeavingAGameReachesJoinersAsGameLeft() {
        AtomicInteger gameLeft = new AtomicInteger();
        SessionModel.TerminationCallback joiner = new SessionModel.TerminationCallback() {
            @Override public void complete(boolean success) { fail("plain result"); }
            @Override public void gameLeftOnHost() { gameLeft.incrementAndGet(); }
        };
        SessionModel.DirectTermination direct =
                m.beginDirectTermination(SESSION_A, "host", 7, "game");
        assertTrue(direct != null);
        assertEquals(SessionModel.TerminationResult.IN_PROGRESS,
                m.terminate(SESSION_A, joiner));

        assertTrue(m.completeDirectTermination(direct, true, true));

        assertEquals(1, gameLeft.get());
        assertEquals(SessionModel.State.NONE, m.state());
    }

    @Test public void failedDirectWholeQuitRestoresStateBeforeJoinersReturn() {
        FakeController controller = new FakeController();
        AtomicReference<Boolean> joined = new AtomicReference<>();
        m.enterHome(
                controller, SESSION_A, "host", 7, "game");
        SessionModel.DirectTermination direct =
                m.beginDirectTermination(
                        SESSION_A, "host", 7, "game");
        assertTrue(direct != null);
        m.terminate(SESSION_A, finished(joined::set));

        m.completeDirectTermination(direct, false);

        assertEquals(Boolean.FALSE, joined.get());
        assertEquals(SessionModel.State.HOME_LIVE,
                m.state());
        assertTrue(m.canResumeInstantly());
    }

    @Test public void staleDirectTokenCannotClearReusedSessionId() {
        SessionModel.DirectTermination old =
                m.beginDirectTermination(
                        SESSION_A, "host", 7, "old");
        assertTrue(old != null);
        assertTrue(m.clearIfMatches(SESSION_A));
        m.enterHome(
                new FakeController(), SESSION_A, "host", 8, "new");
        SessionModel.DirectTermination current =
                m.beginDirectTermination(
                        SESSION_A, "host", 8, "new");
        assertTrue(current != null);

        m.completeDirectTermination(old, true);

        assertEquals(SessionModel.State.TERMINATING,
                m.state());
        assertEquals(8, m.snapshot().appId);
        m.completeDirectTermination(current, true);
        assertEquals(SessionModel.State.NONE,
                m.state());
    }

    @Test public void staleDirectHostFailureCannotRestoreOverReusedExactIdentity() {
        FakeController oldOwner = new FakeController();
        m.enterHome(
                oldOwner, SESSION_A, "host", 7, "game");
        SessionModel.DirectTermination old =
                m.beginDirectTermination(
                        SESSION_A, "host", 7, "game");
        assertTrue(old != null);
        assertTrue(m.clearIfMatches(SESSION_A));

        FakeController newOwner = new FakeController();
        AtomicReference<Boolean> joined = new AtomicReference<>();
        m.enterHome(
                newOwner, SESSION_A, "host", 7, "game");
        SessionModel.DirectTermination current =
                m.beginDirectTermination(
                        SESSION_A, "host", 7, "game");
        assertTrue(current != null);
        m.terminate(SESSION_A, finished(joined::set));

        m.completeDirectTermination(old, false);

        assertNull(joined.get());
        assertEquals(SessionModel.State.TERMINATING,
                m.state());
        assertFalse(m.ownsLiveOrParkedSession(
                oldOwner, SESSION_A, "host", 7));
        assertFalse(m.ownsLiveOrParkedSession(
                newOwner, SESSION_A, "host", 7));
        m.completeDirectTermination(current, false);
        assertEquals(Boolean.FALSE, joined.get());
        assertEquals(SessionModel.State.HOME_LIVE,
                m.state());
        assertTrue(m.ownsLiveOrParkedSession(
                newOwner, SESSION_A, "host", 7));
        assertTrue(m.hardResetIfHostMatches("host"));
        assertTrue(newOwner.disconnected);
        assertFalse(oldOwner.disconnected);
    }

    @Test public void unsafeDirectTransportTerminalNeverRestoresLiveOwner() {
        {
            FakeController controller = new FakeController();
            AtomicReference<Boolean> joined = new AtomicReference<>();
            m.enterHome(
                    controller, SESSION_A, "host", 7, "game");
            SessionModel.DirectTermination direct =
                    m.beginDirectTermination(
                            SESSION_A, "host", 7, "game");
            m.terminate(SESSION_A, finished(joined::set));

            m.abandonDirectTermination(direct);

            assertEquals(Boolean.FALSE, joined.get());
            assertEquals(SessionModel.State.RECONNECT_REQUIRED,
                    m.state());
            assertFalse(m.canResumeInstantly());
            assertEquals(SESSION_A,
                    m.snapshot().streamSessionId);
            m.clear();
        }
    }

    @Test public void unsafeCoordinatorTransportTerminalNeverRestoresLiveOwner() {
        FakeController controller = new FakeController();
        controller.completeTermination = false;
        AtomicReference<Boolean> joined = new AtomicReference<>();
        m.enterHome(
                controller, SESSION_A, "host", 7, "game");
        m.terminate(SESSION_A, finished(joined::set));

        controller.terminationCompletion.transportUnsafe();

        m.drain();
        controller.terminationCompletion.complete(false);
        m.drain();

        assertEquals(Boolean.FALSE, joined.get());
        assertEquals(SessionModel.State.RECONNECT_REQUIRED,
                m.state());
        assertFalse(m.canResumeInstantly());
    }

    @Test public void gameLeftOnHostClearsTheClosedStreamAndReachesEveryWaiter() {
        FakeController controller = new FakeController();
        controller.completeTermination = false;
        AtomicInteger gameLeft = new AtomicInteger();
        SessionModel.TerminationCallback waiter = new SessionModel.TerminationCallback() {
            @Override public void complete(boolean success) { fail("plain result"); }
            @Override public void gameLeftOnHost() { gameLeft.incrementAndGet(); }
        };
        m.enterHome(controller, SESSION_A, "host", 7, "game");

        assertEquals(SessionModel.TerminationResult.STARTED, m.terminate(SESSION_A, waiter));
        assertEquals(SessionModel.TerminationResult.IN_PROGRESS,
                m.terminate(SESSION_A, waiter));
        controller.terminationCompletion.gameLeftOnHost();
        m.drain();

        assertEquals(2, gameLeft.get());
        // The stream closed: nothing is retained, so no tile offers a resume.
        assertEquals(SessionModel.State.NONE, m.state());
        assertFalse(m.hasRetainedSession());
    }

    @Test public void unsafeCoordinatorDisconnectNeverRestoresLiveOwner() {
        FakeController controller = new FakeController();
        controller.completeTermination = false;
        AtomicReference<Boolean> first = new AtomicReference<>();
        AtomicReference<Boolean> joined = new AtomicReference<>();
        m.enterHome(
                controller, SESSION_A, "host", 7, "game");

        assertEquals(SessionModel.TerminationResult.STARTED,
                m.terminate(SESSION_A, finished(first::set)));
        assertEquals(SessionModel.TerminationResult.IN_PROGRESS,
                m.terminate(SESSION_A, finished(joined::set)));
        controller.terminationCompletion.transportUnsafe();
        m.drain();
        controller.terminationCompletion.complete(false);
        m.drain();

        assertEquals(Boolean.FALSE, first.get());
        assertEquals(Boolean.FALSE, joined.get());
        assertEquals(SessionModel.State.RECONNECT_REQUIRED,
                m.state());
        assertFalse(m.canResumeInstantly());
    }

    @Test public void directStreamQuitCannotOverwriteAnotherSession() {
        m.enterHome(
                new FakeController(), "session-b", "other-host", 8, "other");

        assertFalse(m.markTerminating(
                SESSION_A, "host", 7, "game"));
        assertEquals("session-b",
                m.snapshot().streamSessionId);
        assertEquals(SessionModel.State.HOME_LIVE,
                m.state());
    }

    @Test public void staleParkResultCannotOverwriteNewSession() {
        FakeController old = new FakeController() {
            @Override public SessionModel.ParkOutcome parkRetainedTransport() {
                m.enterHome(
                        new FakeController(), "session-b", "host", 8, "other");
                return SessionModel.ParkOutcome.PARKED;
            }
        };
        m.enterHome(old, SESSION_A, "host", 7, "game");

        assertFalse(m.parkForBackground(SESSION_A));
        SessionModel.Snapshot snapshot =
                m.snapshot();
        assertEquals("session-b", snapshot.streamSessionId);
        assertEquals(SessionModel.State.HOME_LIVE, snapshot.state);
    }

    @Test public void staleTerminationCompletionCannotClearNewSession() {
        FakeController old = new FakeController();
        old.completeTermination = false;
        AtomicReference<Boolean> completed = new AtomicReference<>();
        m.enterHome(old, SESSION_A, "host", 7, "game");
        assertEquals(SessionModel.TerminationResult.STARTED,
                m.terminate(SESSION_A, finished(completed::set)));
        m.enterHome(
                new FakeController(), "session-b", "host", 8, "other");

        old.terminationCompletion.complete(true);

        m.drain();

        assertEquals(Boolean.FALSE, completed.get());
        assertEquals("session-b", m.snapshot().streamSessionId);
        assertEquals(SessionModel.State.HOME_LIVE,
                m.state());
    }

    @Test public void staleGameLeftOnHostCannotClearNewSession() {
        FakeController old = new FakeController();
        old.completeTermination = false;
        AtomicReference<Boolean> completed = new AtomicReference<>();
        m.enterHome(old, SESSION_A, "host", 7, "game");
        assertEquals(SessionModel.TerminationResult.STARTED,
                m.terminate(SESSION_A, finished(completed::set)));
        assertTrue(m.clearIfMatches(SESSION_A));
        m.enterHome(new FakeController(), SESSION_A, "host", 8, "other");

        old.terminationCompletion.gameLeftOnHost();
        m.drain();

        // The stale waiter gets a plain failure, never the game-left signal.
        assertEquals(Boolean.FALSE, completed.get());
        assertEquals(SessionModel.State.HOME_LIVE, m.state());
        assertEquals(8, m.snapshot().appId);
    }

    @Test public void terminationWithoutControllerDoesNotRemainWedged() {
        m.enterHome(
                new FakeController(), SESSION_A, "host", 7, "game");
        m.markReconnectRequired(SESSION_A);

        assertEquals(SessionModel.TerminationResult.NO_CONTROLLER,
                m.terminate(SESSION_A, null));
        assertEquals(SessionModel.State.RECONNECT_REQUIRED,
                m.state());
        assertEquals(SessionModel.TerminationResult.NO_CONTROLLER,
                m.terminate(SESSION_A, null));
    }

    @Test public void rejectedTerminationRestoresTheLiveOwner() {
        FakeController controller = new FakeController();
        controller.terminationResult = false;
        m.enterHome(controller, SESSION_A, "host", 7, "game");

        assertEquals(SessionModel.TerminationResult.STARTED,
                m.terminate(SESSION_A, null));

        assertEquals(SessionModel.State.HOME_LIVE,
                m.state());
        assertTrue(m.canResumeInstantly());
        assertTrue(m.hasRetainedSession());
    }

    @Test public void onlyOneCorrelatedSwitchCanBeInFlight() {
        FakeController controller = new FakeController();
        m.enterHome(
                controller, SESSION_A, "host", 7, "old");

        assertEquals(SessionModel.SwitchResult.STARTED,
                m.switchGame(
                        "host", 7, "new", "New", "MoonWaker Stream", "",
                        () -> false, null));
        assertEquals(SessionModel.SwitchResult.NOT_ELIGIBLE,
                m.switchGame(
                        "host", 7, "other", "Other", "MoonWaker Stream", "",
                        () -> false, null));
        assertEquals(1, controller.switches);

        controller.switchCompletion.complete(
                SessionModel.SwitchOutcome.REUSED, "");

        m.drain();
        assertFalse(m.canSwitchGame("host", 7));
        assertEquals(SessionModel.SwitchResult.NOT_ELIGIBLE,
                m.switchGame(
                        "host", 7, "other", "Other", "MoonWaker Stream", "",
                        () -> false, null));
        assertEquals(1, controller.switches);

        assertTrue(m.finishSwitch(controller.switchRequest, controller));
        assertTrue(m.canSwitchGame("host", 7));
    }


    @Test public void parkedTransportReusesOnlyTheSameHostAndApp() {
        FakeController controller = new FakeController();
        m.enterHome(
                controller, SESSION_A, "host", 7, "old");
        assertFalse(m.canSwitchGame("other", 7));
        assertFalse(m.canSwitchGame("host", 8));

        m.markParked(SESSION_A);

        assertTrue(m.canSwitchGame("host", 7));
    }

    @Test public void terminalCallbackReleasesSwitchButNotBeforeItArrives() {
        FakeController controller = new FakeController();
        m.enterHome(
                controller, SESSION_A, "host", 7, "old");
        assertEquals(SessionModel.SwitchResult.STARTED,
                m.switchGame(
                        "host", 7, "new", "New", "MoonWaker Stream", "",
                        () -> false, null));

        assertEquals(SessionModel.SwitchResult.NOT_ELIGIBLE,
                m.switchGame(
                        "host", 7, "other", "Other", "MoonWaker Stream", "",
                        () -> false, null));

        controller.switchCompletion.complete(
                SessionModel.SwitchOutcome.FAILED, "cleanup_failed");

        m.drain();
        assertTrue(m.canSwitchGame("host", 7));
    }

    @Test public void neutralLiveStreamCanSwitchToManagedGame() {
        FakeController controller = new FakeController();
        m.enterHome(
                controller, SESSION_A, "host", 7, "");

        assertTrue(m.canSwitchGame("host", 7));
        assertEquals(SessionModel.SwitchResult.STARTED,
                m.switchGame(
                        "host", 7, "new", "New", "MoonWaker Stream", "",
                        () -> false, null));
        assertEquals("", m.snapshot().playniteGameId);
        assertEquals(1, controller.switches);
    }

    @Test public void foregroundNaturalStopClaimsAnEmptyCoordinatorAsNeutralHome() {
        FakeController controller = new FakeController();

        assertTrue(m.retainNeutralAfterGameStopped(
                controller, SESSION_A, "host", 7, "old"));

        SessionModel.Snapshot retained =
                m.snapshot();
        assertEquals(SessionModel.State.HOME_LIVE, retained.state);
        assertEquals(SESSION_A, retained.streamSessionId);
        assertEquals("host", retained.hostId);
        assertEquals(7, retained.appId);
        assertEquals("", retained.playniteGameId);
        assertTrue(m.canSwitchGame("host", 7));
    }

    @Test public void duplicateNaturalStopKeepsTheSettledNeutralSession() {
        FakeController controller = new FakeController();
        assertTrue(m.retainNeutralAfterGameStopped(
                controller, SESSION_A, "host", 7, "old"));

        assertTrue(m.retainNeutralAfterGameStopped(
                controller, SESSION_A, "host", 7, "old"));

        SessionModel.Snapshot retained =
                m.snapshot();
        assertEquals(SessionModel.State.HOME_LIVE, retained.state);
        assertEquals(SESSION_A, retained.streamSessionId);
        assertEquals("", retained.playniteGameId);
        assertTrue(m.canSwitchGame("host", 7));
    }

    @Test public void dashboardStopCanPreclearExactGameBeforeOwnerSettlesNeutral() {
        FakeController controller = new FakeController();
        m.enterHome(
                controller, SESSION_A, "host", 7, "old");
        assertTrue(m.retainNeutralAfterGameStopped(
                controller, SESSION_A, "host", 7, "old"));
        assertFalse(m.retainNeutralAfterGameStopped(
                new FakeController(), SESSION_A, "host", 7, "old"));
        assertEquals("", m.snapshot().playniteGameId);
    }

    @Test public void parkedNaturalStopClearsOnlyTheExactGameAndKeepsParking() {
        FakeController controller = new FakeController();
        m.enterHome(
                controller, SESSION_A, "host", 7, "old");
        m.markParked(SESSION_A);

        assertTrue(m.retainNeutralAfterGameStopped(
                controller, SESSION_A, "host", 7, "old"));

        SessionModel.Snapshot retained =
                m.snapshot();
        assertEquals(SessionModel.State.PARKED_LIVE, retained.state);
        assertEquals(SESSION_A, retained.streamSessionId);
        assertEquals(7, retained.appId);
        assertEquals("", retained.playniteGameId);
    }

    @Test public void naturalStopCannotTakeOverAForeignRetainedSession() {
        FakeController foreign = new FakeController();
        m.enterHome(
                foreign, "session-b", "other-host", 8, "other");

        assertFalse(m.retainNeutralAfterGameStopped(
                new FakeController(), SESSION_A, "host", 7, "old"));

        SessionModel.Snapshot retained =
                m.snapshot();
        assertEquals("session-b", retained.streamSessionId);
        assertEquals("other-host", retained.hostId);
        assertEquals(8, retained.appId);
        assertEquals("other", retained.playniteGameId);
    }

    @Test public void naturalStopCannotMutateReconnectOrTerminatingSession() {
        FakeController controller = new FakeController();
        m.enterHome(
                controller, SESSION_A, "host", 7, "old");
        m.markReconnectRequired(SESSION_A);

        assertFalse(m.retainNeutralAfterGameStopped(
                controller, SESSION_A, "host", 7, "old"));
        assertEquals(SessionModel.State.RECONNECT_REQUIRED,
                m.state());
        assertEquals("old", m.snapshot().playniteGameId);

        m.clear();
        assertTrue(m.markTerminating(
                SESSION_A, "host", 7, "old"));
        assertFalse(m.retainNeutralAfterGameStopped(
                controller, SESSION_A, "host", 7, "old"));
        assertEquals(SessionModel.State.TERMINATING,
                m.state());
        assertEquals("old", m.snapshot().playniteGameId);
    }

    @Test public void replacedSessionSurvivesStaleSwitchCompletion() {
        FakeController old = new FakeController();
        AtomicReference<SessionModel.SwitchOutcome> outcome =
                new AtomicReference<>();
        m.enterHome(
                old, SESSION_A, "host", 7, "old");
        assertEquals(SessionModel.SwitchResult.STARTED,
                m.switchGame(
                        "host", 7, "new", "New", "MoonWaker Stream", "",
                        () -> false, (result, error) -> outcome.set(result)));

        m.enterHome(
                new FakeController(), "session-b", "host", 8, "other");
        old.switchCompletion.complete(
                SessionModel.SwitchOutcome.FAILED, "stale");
        m.drain();

        assertEquals(SessionModel.SwitchOutcome.FAILED, outcome.get());
        SessionModel.Snapshot retained =
                m.snapshot();
        assertEquals("session-b", retained.streamSessionId);
        assertEquals(8, retained.appId);
        assertEquals("other", retained.playniteGameId);
    }

    @Test public void controlGenerationBindsOnlyToTheExactLiveOwnerAndClears() {
        FakeController owner = new FakeController();
        m.enterHome(
                owner, SESSION_A, "host", 7, "game");
        assertTrue(m.bindControlGeneration(
                owner, SESSION_A, 11L));
        assertEquals(11L, m.snapshot().controlGeneration);

        m.enterHome(
                new FakeController(), "session-b", "host", 7, "game");
        assertFalse(m.bindControlGeneration(
                owner, SESSION_A, 12L));
        assertEquals(0L, m.snapshot().controlGeneration);
    }

    @Test public void exactPreparingOwnerCanBeginTerminalChildCleanup() {
        FakeController owner = new FakeController();
        assertTrue(m.beginPreparing(owner, SESSION_A, "host", 7, "game") > 0L);

        SessionModel.DirectTermination token =
                m.beginDirectTermination(
                        owner, SESSION_A, "host", 7, "game");

        assertTrue(token != null);
        assertEquals(SessionModel.State.TERMINATING,
                m.state());
        m.completeDirectTermination(token, true);
        assertEquals(SessionModel.State.NONE,
                m.state());
    }

    private static String token(char value) {
        return String.valueOf(value).repeat(64);
    }
}
