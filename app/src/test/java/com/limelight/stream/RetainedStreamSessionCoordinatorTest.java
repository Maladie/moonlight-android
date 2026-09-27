package com.limelight.stream;

import org.junit.After;
import org.junit.Test;

import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class RetainedStreamSessionCoordinatorTest {
    private static final String SESSION_A = "session-a";

    private static class FakeController
            implements RetainedStreamSessionCoordinator.Controller {
        String origin;
        @Override public String getExecutionProfileId() { return origin; }
        boolean parkResult = true;
        boolean disconnected;
        boolean terminated;
        int terminations;
        boolean completeTermination = true;
        boolean terminationResult = true;
        boolean live = true;
        RetainedStreamSessionCoordinator.TerminationCallback terminationCompletion;
        RetainedStreamSessionCoordinator.SwitchCallback switchCompletion;
        RetainedStreamSessionCoordinator.SwitchRequest switchRequest;
        RetainedStreamSessionCoordinator.Snapshot preparingPark;
        RetainedStreamSessionCoordinator.Snapshot preparingCancel;
        boolean preparingParkResult = true;
        boolean preparingCancelResult = true;
        int preparingCancels;
        int submittedPreparingFrames;
        boolean preparingFrameResult = true;
        int switches;

        @Override public boolean isRetainedTransportLive() { return live; }
        @Override public boolean parkRetainedTransport() { return parkResult; }
        @Override public boolean parkPreparingTransport(
                RetainedStreamSessionCoordinator.Snapshot preparing) {
            preparingPark = preparing;
            return preparingParkResult;
        }
        @Override public boolean cancelPreparingTransport(
                RetainedStreamSessionCoordinator.Snapshot preparing) {
            preparingCancel = preparing;
            preparingCancels++;
            return preparingCancelResult;
        }
        @Override public boolean preparingHomeFrameSubmitted(
                RetainedStreamSessionCoordinator.Snapshot preparing) {
            submittedPreparingFrames++;
            return preparingFrameResult;
        }
        @Override public void disconnectRetainedStream(
                RetainedStreamSessionCoordinator.TerminationCallback completion) {
            disconnected = true;
            terminationCompletion = completion;
            if (completeTermination && completion != null) {
                completion.complete(terminationResult);
            }
        }
        @Override public void terminateRetainedSession(
                RetainedStreamSessionCoordinator.TerminationCallback completion) {
            terminated = true;
            terminations++;
            terminationCompletion = completion;
            if (completeTermination && completion != null) {
                completion.complete(terminationResult);
            }
        }
        @Override public void switchGame(
                RetainedStreamSessionCoordinator.SwitchRequest request,
                RetainedStreamSessionCoordinator.SwitchCallback completion) {
            switches++;
            switchRequest = request;
            switchCompletion = completion;
        }
    }

    @Test public void transportGenerationIsMonotonicAndIndependentOfRemoteControl() {
        FakeController owner = new FakeController();
        RetainedStreamSessionCoordinator.enterHome(owner, SESSION_A, "host", 7, "game-a");
        long first = RetainedStreamSessionCoordinator.snapshot().transportGeneration;
        assertTrue(first > 0L);
        assertTrue(RetainedStreamSessionCoordinator.bindControlGeneration(owner, SESSION_A, 77L));
        assertEquals(first, RetainedStreamSessionCoordinator.snapshot().transportGeneration);
        assertEquals(77L, RetainedStreamSessionCoordinator.snapshot().controlGeneration);
        RetainedStreamSessionCoordinator.enterHome(owner, SESSION_A, "host", 7, "game-a");
        assertEquals(first, RetainedStreamSessionCoordinator.snapshot().transportGeneration);
        RetainedStreamSessionCoordinator.clear();
        RetainedStreamSessionCoordinator.enterHome(owner, "session-b", "host", 7, "game-b");
        assertTrue(RetainedStreamSessionCoordinator.snapshot().transportGeneration > first);
        assertEquals(0L, RetainedStreamSessionCoordinator.snapshot().controlGeneration);
    }

    @Test public void reauthorizationCommitsConnectionActorAndGenerationAtomically() {
        FakeController owner = new FakeController(); owner.origin = "parent";
        RetainedStreamSessionCoordinator.enterHome(owner, SESSION_A, "host", "child", 7, "");
        RetainedStreamSessionCoordinator.bindControlGeneration(owner, SESSION_A, 4);
        AtomicInteger connectionGeneration = new AtomicInteger(4);
        assertFalse(RetainedStreamSessionCoordinator.commitReauthorization(owner, SESSION_A,
                "parent", "parent", 4, 5, () -> false));
        assertEquals("child", RetainedStreamSessionCoordinator.snapshot().profileId);
        assertEquals(4, RetainedStreamSessionCoordinator.snapshot().controlGeneration);
        assertTrue(RetainedStreamSessionCoordinator.commitReauthorization(owner, SESSION_A,
                "parent", "parent", 4, 5, () -> connectionGeneration.compareAndSet(4, 5)));
        assertEquals("parent", RetainedStreamSessionCoordinator.snapshot().profileId);
        assertEquals(5, RetainedStreamSessionCoordinator.snapshot().controlGeneration);
        assertEquals(5, connectionGeneration.get());
        assertFalse(RetainedStreamSessionCoordinator.commitReauthorization(owner, SESSION_A,
                "parent", "child", 4, 6, () -> { fail("Stale commit reached connection"); return true; }));
        assertFalse(RetainedStreamSessionCoordinator.discardReauthorization(owner, SESSION_A, 3, 4));
        assertEquals(5, RetainedStreamSessionCoordinator.snapshot().controlGeneration);
        assertTrue(RetainedStreamSessionCoordinator.discardReauthorization(owner, SESSION_A, 4, 5));
        assertEquals(RetainedStreamSessionCoordinator.State.NONE, RetainedStreamSessionCoordinator.snapshot().state);
    }

    @Test public void providerTransferProofRequiresEveryRetainedFenceAndIsOneShot() {
        FakeController owner = new FakeController(); owner.origin = "parent";
        RetainedStreamSessionCoordinator.enterHome(
                owner, SESSION_A, "host", "child-a", 7, "game-a");
        assertTrue(RetainedStreamSessionCoordinator.bindControlGeneration(owner, SESSION_A, 4));
        assertNull(RetainedStreamSessionCoordinator.commitProviderTransferReauthorization(
                owner, SESSION_A, "parent", "child-b", "old", "next", "wrong-host",
                7, "game-a", token('a'), 71, 4, 5, 9, () -> true));
        assertEquals("child-a", RetainedStreamSessionCoordinator.snapshot().profileId);
        assertNull(RetainedStreamSessionCoordinator.commitProviderTransferReauthorization(
                owner, SESSION_A, "parent", "child-b", "old", "next", "host",
                8, "game-a", token('a'), 71, 4, 5, 9, () -> true));
        assertNull(RetainedStreamSessionCoordinator.commitProviderTransferReauthorization(
                owner, SESSION_A, "parent", "child-b", "old", "next", "host",
                7, "game-b", token('a'), 71, 4, 5, 9, () -> true));
        RetainedStreamSessionCoordinator.ProviderTransferAuthorization proof =
                RetainedStreamSessionCoordinator.commitProviderTransferReauthorization(
                        owner, SESSION_A, "parent", "child-b", "old", "next", "host",
                        7, "game-a", token('a'), 71, 4, 5, 9, () -> true);
        assertTrue(proof != null);
        assertEquals(9, proof.actorLeaseEpoch);
        assertEquals(71, proof.processId);
        assertEquals(token('a'), proof.processToken);
        assertTrue(RetainedStreamSessionCoordinator.consumeProviderTransferAuthorization(proof));
        assertFalse(RetainedStreamSessionCoordinator.consumeProviderTransferAuthorization(proof));
    }

    @Test public void ongoingTerminationCannotBeReauthorizedOrDiscardedByHandoff() {
        FakeController owner = new FakeController(); owner.origin = "parent";
        RetainedStreamSessionCoordinator.enterHome(owner, SESSION_A, "host", "parent", 7, "");
        RetainedStreamSessionCoordinator.bindControlGeneration(owner, SESSION_A, 4);
        assertTrue(RetainedStreamSessionCoordinator.markTerminating(SESSION_A, "host", 7, ""));
        assertFalse(RetainedStreamSessionCoordinator.commitReauthorization(owner, SESSION_A,
                "parent", "child", 4, 5, () -> { fail("Termination reached commit"); return true; }));
        assertFalse(RetainedStreamSessionCoordinator.discardReauthorization(owner, SESSION_A, 4, 5));
        assertEquals(RetainedStreamSessionCoordinator.State.TERMINATING,
                RetainedStreamSessionCoordinator.snapshot().state);
    }

    @Test public void replacedRetainedOwnerRejectsOldCommitAndCompensation() {
        FakeController oldOwner = new FakeController(); oldOwner.origin = "parent";
        FakeController parent = new FakeController(); parent.origin = "parent";
        RetainedStreamSessionCoordinator.enterHome(parent, SESSION_A, "host", "parent", 7, "");
        RetainedStreamSessionCoordinator.bindControlGeneration(parent, SESSION_A, 6);
        assertFalse(RetainedStreamSessionCoordinator.commitReauthorization(oldOwner, SESSION_A,
                "parent", "child", 4, 5, () -> { fail("Old owner reached connection"); return true; }));
        assertFalse(RetainedStreamSessionCoordinator.discardReauthorization(oldOwner, SESSION_A, 4, 5));
        assertEquals(6, RetainedStreamSessionCoordinator.snapshot().controlGeneration);
    }

    @Test public void familySwitchRetainsTransportButRebindsActorAndRejectsOtherOrigins() {
        FakeController owner = new FakeController();
        owner.origin = "parent";
        RetainedStreamSessionCoordinator.enterHome(owner, SESSION_A, "host", "parent", 7, "game-a");
        for (String actor : new String[] {"child-a", "child-b", "parent"}) {
            RetainedStreamSessionCoordinator.Snapshot before = RetainedStreamSessionCoordinator.snapshot();
            assertFalse(RetainedStreamSessionCoordinator.canSwitchOrigin("host", "other", 7));
            assertFalse(RetainedStreamSessionCoordinator.canSwitchOrigin("other-host", "parent", 7));
            assertEquals(RetainedStreamSessionCoordinator.SwitchResult.STARTED,
                    RetainedStreamSessionCoordinator.switchGame("host", "parent", actor,
                            !actor.equals("parent"), 7, "game-b", "Game", "Stream", "", () -> false,
                            (outcome, error) -> { }));
            assertEquals(actor, owner.switchRequest.targetProfileId);
            assertEquals("parent", owner.switchRequest.executionProfileId);
            assertTrue(RetainedStreamSessionCoordinator.bindActor(owner, SESSION_A, actor, "parent"));
            assertTrue(RetainedStreamSessionCoordinator.updateOwnedSwitchGame(owner,
                    owner.switchRequest, before.playniteGameId, "game-b"));
            owner.switchCompletion.complete(RetainedStreamSessionCoordinator.SwitchOutcome.REUSED, "");
            RetainedStreamSessionCoordinator.finishSwitch(SESSION_A, owner);
            assertFalse(RetainedStreamSessionCoordinator.canResumeInstantly(before));
            assertEquals(actor, RetainedStreamSessionCoordinator.snapshot().profileId);
            assertEquals(SESSION_A, RetainedStreamSessionCoordinator.snapshot().streamSessionId);
            assertFalse(owner.disconnected);
            assertFalse(owner.terminated);
        }
    }

    @Test public void parkedFamilyTransportCanChangeGameWithoutDisconnect() {
        FakeController owner = new FakeController();
        owner.origin = "parent";
        RetainedStreamSessionCoordinator.enterHome(owner, SESSION_A, "host", "child-a", 7, "game-a");
        assertTrue(RetainedStreamSessionCoordinator.parkForBackground(SESSION_A));
        assertTrue(RetainedStreamSessionCoordinator.canSwitchOrigin("host", "parent", 7));
        assertEquals(RetainedStreamSessionCoordinator.SwitchResult.STARTED,
                RetainedStreamSessionCoordinator.switchGame("host", "parent", "child-b", true,
                        7, "game-b", "Game", "Stream", "", () -> false, (outcome, error) -> { }));
        assertTrue(RetainedStreamSessionCoordinator.bindActor(owner, SESSION_A, "child-b", "parent"));
        assertTrue(RetainedStreamSessionCoordinator.updateOwnedSwitchGame(owner,
                owner.switchRequest, "game-a", "game-b"));
        assertFalse(owner.disconnected);
    }

    @After public void reset() {
        RetainedStreamSessionCoordinator.clear();
    }

    @Test public void childAndParentReturnInstantlyFromDashboardAndBackground() {
        for (String profile : new String[] {"parent", "child"}) {
            RetainedStreamSessionCoordinator.clear();
            FakeController owner = new FakeController();
            RetainedStreamSessionCoordinator.enterHome(owner, SESSION_A,
                    "host", profile, 7, "steam:367520");
            assertTrue(RetainedStreamSessionCoordinator.canResumeInstantly());
            assertTrue(RetainedStreamSessionCoordinator.parkForBackground(SESSION_A));
            assertTrue(RetainedStreamSessionCoordinator.canResumeInstantly());
            assertEquals(profile, RetainedStreamSessionCoordinator.snapshot().profileId);
            assertFalse(owner.disconnected);
            assertFalse(owner.terminated);
            RetainedStreamSessionCoordinator.clearIfMatches(SESSION_A);
            assertFalse(RetainedStreamSessionCoordinator.canResumeInstantly());
        }
    }

    @Test public void exactSwitchSnapshotRejectsStaleTokenAndDeadOwner() {
        FakeController owner = new FakeController();
        owner.live = false;
        RetainedStreamSessionCoordinator.beginPreparing(owner, SESSION_A, "host", 7, "", "a", 1);
        RetainedStreamSessionCoordinator.Snapshot preparing = RetainedStreamSessionCoordinator.snapshot();
        assertTrue(RetainedStreamSessionCoordinator.canSwitchGame(preparing));
        RetainedStreamSessionCoordinator.clear();
        RetainedStreamSessionCoordinator.beginPreparing(owner, SESSION_A, "host", 7, "", "b", 2);
        assertFalse(RetainedStreamSessionCoordinator.canSwitchGame(preparing));
        RetainedStreamSessionCoordinator.clear();
        RetainedStreamSessionCoordinator.enterHome(owner, SESSION_A, "host", 7, "");
        RetainedStreamSessionCoordinator.Snapshot home = RetainedStreamSessionCoordinator.snapshot();
        assertFalse(RetainedStreamSessionCoordinator.canSwitchGame(home));
        owner.live = true;
        RetainedStreamSessionCoordinator.enterHome(owner, SESSION_A, "host", 7, "");
        home = RetainedStreamSessionCoordinator.snapshot();
        assertTrue(RetainedStreamSessionCoordinator.canSwitchGame(home));
        RetainedStreamSessionCoordinator.enterHome(owner, "other", "foreign", 8, "");
        assertFalse(RetainedStreamSessionCoordinator.canSwitchGame(home));
    }

    @Test public void snapshotSwitchEligibilityPreservesNonDefaultProfile() {
        for (String gameId : new String[] { "", "old" }) {
            RetainedStreamSessionCoordinator.clear();
            FakeController owner = new FakeController();
            String profile = "profile-a";
            assertTrue(RetainedStreamSessionCoordinator.beginPreparing(
                    owner, SESSION_A, "host", profile, 7, gameId,
                    "transition-a", 1L));
            RetainedStreamSessionCoordinator.Snapshot preparing =
                    RetainedStreamSessionCoordinator.snapshot();
            assertTrue(RetainedStreamSessionCoordinator.canSwitchGame(preparing));

            RetainedStreamSessionCoordinator.enterHome(
                    owner, SESSION_A, "host", profile, 7, gameId);
            RetainedStreamSessionCoordinator.Snapshot home =
                    RetainedStreamSessionCoordinator.snapshot();
            assertTrue(RetainedStreamSessionCoordinator.canSwitchGame(home));
        }
    }

    @Test public void snapshotSwitchEligibilityRejectsStaleProfile() {
        FakeController owner = new FakeController();
        RetainedStreamSessionCoordinator.enterHome(
                owner, SESSION_A, "host", "profile-a", 7, "old");
        RetainedStreamSessionCoordinator.Snapshot stale =
                RetainedStreamSessionCoordinator.snapshot();

        RetainedStreamSessionCoordinator.enterHome(
                owner, SESSION_A, "host", "profile-b", 7, "old");
        assertFalse(RetainedStreamSessionCoordinator.canSwitchGame(stale));
    }

    @Test public void ownedSwitchCanPublishAcceptedGameButDashboardCannotPreclearIt() {
        for (String oldGame : new String[] { "", "old" }) {
            RetainedStreamSessionCoordinator.clear();
            FakeController owner = new FakeController();
            RetainedStreamSessionCoordinator.enterHome(owner, SESSION_A, "host", 7, oldGame);
            assertEquals(RetainedStreamSessionCoordinator.SwitchResult.STARTED,
                    RetainedStreamSessionCoordinator.switchGame(
                            "host", 7, "new", "New", "MoonWaker Stream", "",
                            () -> false, null));
            assertFalse(RetainedStreamSessionCoordinator.updateGameIfMatches(
                    SESSION_A, "host", 7, oldGame, ""));
            assertFalse(RetainedStreamSessionCoordinator.updateOwnedSwitchGame(
                    new FakeController(), owner.switchRequest, oldGame, ""));
            assertTrue(RetainedStreamSessionCoordinator.updateOwnedSwitchGame(
                    owner, owner.switchRequest, oldGame, ""));
            assertTrue(RetainedStreamSessionCoordinator.updateOwnedSwitchGame(
                    owner, owner.switchRequest, "", "new"));
            assertEquals("new", RetainedStreamSessionCoordinator.snapshot().playniteGameId);
            assertEquals(1, owner.switches);
            assertFalse(owner.terminated);
            RetainedStreamSessionCoordinator.enterHome(
                    new FakeController(), "session-b", "other", 8, "other-game");
            assertFalse(RetainedStreamSessionCoordinator.updateOwnedSwitchGame(
                    owner, owner.switchRequest, "new", ""));
            assertEquals("other-game", RetainedStreamSessionCoordinator.snapshot().playniteGameId);
        }
    }

    @Test public void homeParksAndRemainsInstantlyResumable() {
        FakeController controller = new FakeController();
        RetainedStreamSessionCoordinator.enterHome(controller, SESSION_A, "host", 7, "game");

        assertTrue(RetainedStreamSessionCoordinator.parkForBackground(SESSION_A));
        assertEquals(RetainedStreamSessionCoordinator.State.PARKED_LIVE,
                RetainedStreamSessionCoordinator.state());
        assertTrue(RetainedStreamSessionCoordinator.canResumeInstantly());
        RetainedStreamSessionCoordinator.Snapshot parked =
                RetainedStreamSessionCoordinator.snapshot();
        assertTrue(RetainedStreamSessionCoordinator.canResumeInstantly(parked));

        RetainedStreamSessionCoordinator.enterHome(
                controller, "session-b", "other", 8, "other-game");
        assertFalse(RetainedStreamSessionCoordinator.canResumeInstantly(parked));
    }

    @Test public void externalPreparingCancelCapturesTheExactOwnerAndToken() {
        FakeController owner = new FakeController();
        assertTrue(RetainedStreamSessionCoordinator.beginPreparing(
                owner, SESSION_A, "host", 7, "", "transition-a", 11L));
        RetainedStreamSessionCoordinator.Snapshot expected =
                RetainedStreamSessionCoordinator.snapshot();

        assertTrue(RetainedStreamSessionCoordinator.cancelPreparing(expected));
        assertEquals(1, owner.preparingCancels);
        assertEquals(expected, owner.preparingCancel);
        assertEquals(RetainedStreamSessionCoordinator.State.PREPARING,
                RetainedStreamSessionCoordinator.state());
    }

    @Test public void submittedHomeFrameReachesOnlyTheExactPreparingOwner() {
        FakeController owner = new FakeController();
        assertTrue(RetainedStreamSessionCoordinator.beginPreparing(
                owner, SESSION_A, "host", 7, "game", "transition-a", 11L));
        RetainedStreamSessionCoordinator.Snapshot expected =
                RetainedStreamSessionCoordinator.snapshot();

        assertTrue(RetainedStreamSessionCoordinator.preparingHomeFrameSubmitted(expected));
        assertEquals(1, owner.submittedPreparingFrames);
        assertFalse(RetainedStreamSessionCoordinator.preparingHomeFrameSubmitted(expected));
        assertEquals(1, owner.submittedPreparingFrames);
        assertTrue(RetainedStreamSessionCoordinator.isPreparingHomeFrameAccepted(
                SESSION_A, "host", 7, "game", "transition-a", 11L));
        assertFalse(RetainedStreamSessionCoordinator.isPreparingHomeFrameAccepted(
                SESSION_A, "host", 7, "game", "transition-a", 12L));
        assertTrue(RetainedStreamSessionCoordinator.completePreparing(
                owner, SESSION_A, "host", 7, "transition-a", 11L));
        assertTrue(RetainedStreamSessionCoordinator.isPreparingHomeFrameAccepted(
                SESSION_A, "host", 7, "game", "transition-a", 11L));
        RetainedStreamSessionCoordinator.enterHome(
                owner, SESSION_A, "host", 7, "game");
        assertFalse(RetainedStreamSessionCoordinator.isPreparingHomeFrameAccepted(
                SESSION_A, "host", 7, "game", "transition-a", 11L));
    }

    @Test public void cancelledFrameClaimCannotBecomeAccepted() {
        FakeController owner = new FakeController() {
            @Override public boolean preparingHomeFrameSubmitted(
                    RetainedStreamSessionCoordinator.Snapshot preparing) {
                assertTrue(RetainedStreamSessionCoordinator.cancelPreparing(
                        this, SESSION_A, "host", 7, "transition-a", 11L));
                return true;
            }
        };
        assertTrue(RetainedStreamSessionCoordinator.beginPreparing(
                owner, SESSION_A, "host", 7, "game", "transition-a", 11L));
        RetainedStreamSessionCoordinator.Snapshot expected =
                RetainedStreamSessionCoordinator.snapshot();

        assertFalse(RetainedStreamSessionCoordinator.preparingHomeFrameSubmitted(expected));
        assertEquals(RetainedStreamSessionCoordinator.State.NONE,
                RetainedStreamSessionCoordinator.state());
        assertFalse(RetainedStreamSessionCoordinator.isPreparingHomeFrameAccepted(
                SESSION_A, "host", 7, "game", "transition-a", 11L));
    }

    @Test public void staleExternalCancelCannotReachANewerOwner() {
        FakeController oldOwner = new FakeController();
        assertTrue(RetainedStreamSessionCoordinator.beginPreparing(
                oldOwner, SESSION_A, "host", 7, "", "transition-a", 11L));
        RetainedStreamSessionCoordinator.Snapshot stale =
                RetainedStreamSessionCoordinator.snapshot();
        assertTrue(RetainedStreamSessionCoordinator.cancelPreparing(
                oldOwner, SESSION_A, "host", 7, "transition-a", 11L));
        FakeController newOwner = new FakeController();
        assertTrue(RetainedStreamSessionCoordinator.beginPreparing(
                newOwner, "session-b", "host", 7, "", "transition-b", 12L));

        assertFalse(RetainedStreamSessionCoordinator.cancelPreparing(stale));
        assertEquals(0, newOwner.preparingCancels);
        assertEquals("session-b", RetainedStreamSessionCoordinator.snapshot().streamSessionId);
    }

    @Test public void externalCancelNeverClosesLiveOrParkedSessions() {
        for (boolean parked : new boolean[] { false, true }) {
            FakeController owner = new FakeController();
            RetainedStreamSessionCoordinator.enterHome(owner, SESSION_A, "host", 7, "");
            if (parked) RetainedStreamSessionCoordinator.markParked(SESSION_A);
            RetainedStreamSessionCoordinator.Snapshot live =
                    RetainedStreamSessionCoordinator.snapshot();

            assertFalse(RetainedStreamSessionCoordinator.cancelPreparing(live));
            assertEquals(0, owner.preparingCancels);
            RetainedStreamSessionCoordinator.clear();
        }
    }

    @Test public void failedParkRequiresReconnect() {
        FakeController controller = new FakeController();
        controller.parkResult = false;
        RetainedStreamSessionCoordinator.enterHome(controller, SESSION_A, "host", 7, "game");

        assertFalse(RetainedStreamSessionCoordinator.parkForBackground(SESSION_A));
        assertEquals(RetainedStreamSessionCoordinator.State.RECONNECT_REQUIRED,
                RetainedStreamSessionCoordinator.state());
        assertFalse(RetainedStreamSessionCoordinator.canResumeInstantly());
    }

    @Test public void terminateInvokesOwnerAndRemovesResumeState() {
        FakeController controller = new FakeController();
        RetainedStreamSessionCoordinator.enterHome(controller, SESSION_A, "host", 7, "game");

        assertEquals(RetainedStreamSessionCoordinator.TerminationResult.STARTED,
                RetainedStreamSessionCoordinator.terminate(SESSION_A, null));
        assertTrue(controller.terminated);
        assertFalse(RetainedStreamSessionCoordinator.hasRetainedSession());
        assertFalse(RetainedStreamSessionCoordinator.canResumeInstantly());
    }

    @Test public void disconnectInvokesStreamOnlyOwnerPath() {
        FakeController controller = new FakeController();
        RetainedStreamSessionCoordinator.enterHome(controller, SESSION_A, "host", 7, "game");

        assertEquals(RetainedStreamSessionCoordinator.TerminationResult.STARTED,
                RetainedStreamSessionCoordinator.disconnect(SESSION_A, null));
        assertTrue(controller.disconnected);
        assertFalse(controller.terminated);
        assertFalse(RetainedStreamSessionCoordinator.hasRetainedSession());
    }

    @Test public void hardResetClearsEvenPreparingStateOnlyForTheExactHost() {
        FakeController controller = new FakeController();
        RetainedStreamSessionCoordinator.beginPreparing(
                controller, SESSION_A, "host", 7, "game", "transition", 1L);

        assertFalse(RetainedStreamSessionCoordinator.hardResetIfHostMatches("other"));
        assertEquals(RetainedStreamSessionCoordinator.State.PREPARING,
                RetainedStreamSessionCoordinator.state());
        assertTrue(RetainedStreamSessionCoordinator.hardResetIfHostMatches("HOST"));
        assertEquals(RetainedStreamSessionCoordinator.State.NONE,
                RetainedStreamSessionCoordinator.state());
        assertEquals(1, controller.preparingCancels);
        assertEquals(SESSION_A, controller.preparingCancel.streamSessionId);
        assertFalse(controller.disconnected);
    }

    @Test public void lostOwnerFallsBackToReconnect() {
        RetainedStreamSessionCoordinator.enterHome(
                new FakeController(), SESSION_A, "host", 7, "game");
        RetainedStreamSessionCoordinator.markReconnectRequired(SESSION_A);

        assertEquals(RetainedStreamSessionCoordinator.State.RECONNECT_REQUIRED,
                RetainedStreamSessionCoordinator.state());
        assertFalse(RetainedStreamSessionCoordinator.canResumeInstantly());
    }

    @Test public void deadOwnerCannotResumeSwitchOrTerminate() {
        FakeController controller = new FakeController();
        controller.live = false;
        RetainedStreamSessionCoordinator.enterHome(
                controller, SESSION_A, "host", 7, "game");

        assertFalse(RetainedStreamSessionCoordinator.canResumeInstantly());
        assertEquals(RetainedStreamSessionCoordinator.State.RECONNECT_REQUIRED,
                RetainedStreamSessionCoordinator.state());
        assertFalse(RetainedStreamSessionCoordinator.canSwitchGame("host", 7));
        assertEquals(RetainedStreamSessionCoordinator.TerminationResult.NO_CONTROLLER,
                RetainedStreamSessionCoordinator.terminate(SESSION_A, null));
        assertFalse(controller.terminated);
    }

    @Test public void deadRetainedReplacementRestoresReconnectAfterFailedClose() {
        FakeController controller = new FakeController();
        RetainedStreamSessionCoordinator.enterHome(
                controller, SESSION_A, "host", 7, "game");
        RetainedStreamSessionCoordinator.markReconnectRequired(SESSION_A);

        assertTrue(RetainedStreamSessionCoordinator.markTerminating(
                SESSION_A, "host", 7, "game"));
        assertFalse(RetainedStreamSessionCoordinator.restoreReconnectIfTerminating(
                SESSION_A, "other-host", 7, "game"));
        assertEquals(RetainedStreamSessionCoordinator.State.TERMINATING,
                RetainedStreamSessionCoordinator.state());
        assertTrue(RetainedStreamSessionCoordinator.restoreReconnectIfTerminating(
                SESSION_A, "host", 7, "game"));

        RetainedStreamSessionCoordinator.Snapshot retained =
                RetainedStreamSessionCoordinator.snapshot();
        assertEquals(RetainedStreamSessionCoordinator.State.RECONNECT_REQUIRED,
                retained.state);
        assertEquals(SESSION_A, retained.streamSessionId);
        assertEquals("host", retained.hostId);
        assertEquals(7, retained.appId);
        assertEquals("game", retained.playniteGameId);
    }

    @Test public void failedTerminationRestoresNeutralizedLiveOwner() {
        FakeController controller = new FakeController();
        controller.completeTermination = false;
        RetainedStreamSessionCoordinator.enterHome(
                controller, SESSION_A, "host", 7, "game");
        assertEquals(RetainedStreamSessionCoordinator.TerminationResult.STARTED,
                RetainedStreamSessionCoordinator.terminate(SESSION_A, null));
        assertTrue(RetainedStreamSessionCoordinator.clearTerminatingGameIfMatches(
                SESSION_A, "host", 7, "game"));

        controller.terminationCompletion.complete(false);

        RetainedStreamSessionCoordinator.Snapshot retained =
                RetainedStreamSessionCoordinator.snapshot();
        assertEquals(RetainedStreamSessionCoordinator.State.HOME_LIVE, retained.state);
        assertEquals("", retained.playniteGameId);
        assertTrue(RetainedStreamSessionCoordinator.canResumeInstantly());
    }

    @Test public void snapshotKeepsRetainedGameIdentity() {
        RetainedStreamSessionCoordinator.enterHome(
                new FakeController(), SESSION_A, "host", 7, "game");
        RetainedStreamSessionCoordinator.Snapshot snapshot =
                RetainedStreamSessionCoordinator.snapshot();
        assertEquals(SESSION_A, snapshot.streamSessionId);
        assertEquals("host", snapshot.hostId);
        assertEquals(7, snapshot.appId);
        assertEquals("game", snapshot.playniteGameId);
    }

    @Test public void preparingRequiresFullCorrelationAndIsNotResumeReady() {
        FakeController owner = new FakeController();
        FakeController staleOwner = new FakeController();

        assertTrue(RetainedStreamSessionCoordinator.beginPreparing(
                owner, SESSION_A, "host", 7, "game", "transition-a", 1L));
        RetainedStreamSessionCoordinator.Snapshot snapshot =
                RetainedStreamSessionCoordinator.snapshot();
        assertEquals(RetainedStreamSessionCoordinator.State.PREPARING, snapshot.state);
        assertEquals("game", snapshot.playniteGameId);
        assertEquals("transition-a", snapshot.transitionId);
        assertEquals(1L, snapshot.attempt);
        assertFalse(RetainedStreamSessionCoordinator.canResumeInstantly());
        assertTrue(RetainedStreamSessionCoordinator.parkForBackground(SESSION_A));
        assertFalse(RetainedStreamSessionCoordinator.clearIfMatches(SESSION_A));
        assertEquals(RetainedStreamSessionCoordinator.TerminationResult.NO_CONTROLLER,
                RetainedStreamSessionCoordinator.terminate(SESSION_A, null));

        assertFalse(RetainedStreamSessionCoordinator.completePreparing(
                staleOwner, SESSION_A, "host", 7, "transition-a", 1L));
        assertFalse(RetainedStreamSessionCoordinator.completePreparing(
                owner, "stale-session", "host", 7, "transition-a", 1L));
        assertFalse(RetainedStreamSessionCoordinator.completePreparing(
                owner, SESSION_A, "stale-host", 7, "transition-a", 1L));
        assertFalse(RetainedStreamSessionCoordinator.completePreparing(
                owner, SESSION_A, "host", 8, "transition-a", 1L));
        assertFalse(RetainedStreamSessionCoordinator.completePreparing(
                owner, SESSION_A, "host", 7, "stale-transition", 1L));
        assertFalse(RetainedStreamSessionCoordinator.completePreparing(
                owner, SESSION_A, "host", 7, "transition-a", 2L));
        assertTrue(RetainedStreamSessionCoordinator.completePreparing(
                owner, SESSION_A, "host", 7, "transition-a", 1L));
        assertEquals(RetainedStreamSessionCoordinator.State.HOME_LIVE,
                RetainedStreamSessionCoordinator.state());
        assertEquals("", RetainedStreamSessionCoordinator.snapshot().transitionId);
        assertEquals(0L, RetainedStreamSessionCoordinator.snapshot().attempt);
        assertTrue(RetainedStreamSessionCoordinator.canResumeInstantly());
    }

    @Test public void preparingCancelOnlyClearsTheExactAttempt() {
        FakeController owner = new FakeController();
        assertTrue(RetainedStreamSessionCoordinator.beginPreparing(
                owner, SESSION_A, "host", 7, "", "transition-a", 1L));

        assertFalse(RetainedStreamSessionCoordinator.cancelPreparing(
                owner, SESSION_A, "host", 7, "transition-a", 2L));
        assertTrue(RetainedStreamSessionCoordinator.cancelPreparing(
                owner, SESSION_A, "host", 7, "transition-a", 1L));
        assertTrue(RetainedStreamSessionCoordinator.beginPreparing(
                owner, SESSION_A, "host", 7, "", "transition-b", 2L));
        assertFalse(RetainedStreamSessionCoordinator.cancelPreparing(
                owner, SESSION_A, "host", 7, "transition-a", 1L));
        assertTrue(RetainedStreamSessionCoordinator.isPreparing(
                owner, SESSION_A, "host", 7, "transition-b", 2L));
    }

    @Test public void preparingParkDelegatesTheExactTokenWithoutChangingState() {
        FakeController owner = new FakeController();
        assertTrue(RetainedStreamSessionCoordinator.beginPreparing(
                owner, SESSION_A, "host", 7, "game", "transition-a", 1L));

        assertTrue(RetainedStreamSessionCoordinator.parkForBackground(SESSION_A));

        assertEquals(RetainedStreamSessionCoordinator.State.PREPARING,
                RetainedStreamSessionCoordinator.state());
        assertEquals(SESSION_A, owner.preparingPark.streamSessionId);
        assertEquals("host", owner.preparingPark.hostId);
        assertEquals(7, owner.preparingPark.appId);
        assertEquals("game", owner.preparingPark.playniteGameId);
        assertEquals("transition-a", owner.preparingPark.transitionId);
        assertEquals(1L, owner.preparingPark.attempt);
    }

    @Test public void preparingReconnectRequiresTheFullTokenAndRejectsLateCompletion() {
        FakeController owner = new FakeController();
        assertTrue(RetainedStreamSessionCoordinator.beginPreparing(
                owner, SESSION_A, "host", 7, "", "transition-a", 1L));

        assertFalse(RetainedStreamSessionCoordinator.markPreparingReconnectRequired(
                owner, SESSION_A, "host", 7, "transition-a", 2L));
        assertTrue(RetainedStreamSessionCoordinator.markPreparingReconnectRequired(
                owner, SESSION_A, "host", 7, "transition-a", 1L));

        RetainedStreamSessionCoordinator.Snapshot snapshot =
                RetainedStreamSessionCoordinator.snapshot();
        assertEquals(RetainedStreamSessionCoordinator.State.RECONNECT_REQUIRED,
                snapshot.state);
        assertEquals("", snapshot.transitionId);
        assertEquals(0L, snapshot.attempt);
        assertFalse(RetainedStreamSessionCoordinator.completePreparing(
                owner, SESSION_A, "host", 7, "transition-a", 1L));
    }

    @Test public void stalePreparingParkCannotMutateAReplacementAttempt() {
        FakeController replacement = new FakeController();
        FakeController owner = new FakeController() {
            @Override public boolean parkPreparingTransport(
                    RetainedStreamSessionCoordinator.Snapshot preparing) {
                assertTrue(RetainedStreamSessionCoordinator.cancelPreparing(
                        this, SESSION_A, "host", 7, "transition-a", 1L));
                assertTrue(RetainedStreamSessionCoordinator.beginPreparing(
                        replacement, "session-b", "host", 7, "",
                        "transition-b", 2L));
                return true;
            }
        };
        assertTrue(RetainedStreamSessionCoordinator.beginPreparing(
                owner, SESSION_A, "host", 7, "", "transition-a", 1L));

        assertTrue(RetainedStreamSessionCoordinator.parkForBackground(SESSION_A));

        assertTrue(RetainedStreamSessionCoordinator.isPreparing(
                replacement, "session-b", "host", 7, "transition-b", 2L));
    }

    @Test public void preparingSwitchCarriesItsTokenWithoutRequiringLiveTransport() {
        FakeController owner = new FakeController();
        owner.live = false;
        assertTrue(RetainedStreamSessionCoordinator.beginPreparing(
                owner, SESSION_A, "host", 7, "old", "transition-a", 3L));

        assertTrue(RetainedStreamSessionCoordinator.canSwitchGame("host", 7));
        assertFalse(RetainedStreamSessionCoordinator.isPreparingSwitchOwned(
                SESSION_A, "host", 7, "transition-a", 3L));
        assertEquals(RetainedStreamSessionCoordinator.SwitchResult.STARTED,
                RetainedStreamSessionCoordinator.switchGame(
                        "host", 7, "new", "New", "MoonWaker Stream", "",
                        () -> false, null));
        assertTrue(RetainedStreamSessionCoordinator.isPreparingSwitchOwned(
                SESSION_A, "host", 7, "transition-a", 3L));
        assertFalse(RetainedStreamSessionCoordinator.isPreparingSwitchOwned(
                SESSION_A, "host", 7, "transition-a", 4L));
        assertEquals(RetainedStreamSessionCoordinator.SwitchResult.NOT_ELIGIBLE,
                RetainedStreamSessionCoordinator.switchGame(
                        "host", 7, "new", "New", "MoonWaker Stream", "",
                        () -> false, null));
        assertEquals(1, owner.switches);
        assertTrue(owner.switchRequest.preparing);
        assertEquals("old", owner.switchRequest.oldGameId);
        assertEquals("transition-a", owner.switchRequest.transitionId);
        assertEquals(3L, owner.switchRequest.attempt);
        owner.switchCompletion.complete(
                RetainedStreamSessionCoordinator.SwitchOutcome.REUSED, "");
        assertTrue(RetainedStreamSessionCoordinator.isPreparingSwitchOwned(
                SESSION_A, "host", 7, "transition-a", 3L));
        assertFalse(RetainedStreamSessionCoordinator.finishSwitch(SESSION_A, owner));
        assertTrue(RetainedStreamSessionCoordinator.finishSwitch(
                SESSION_A, "transition-a", 3L, owner));
        assertFalse(RetainedStreamSessionCoordinator.isPreparingSwitchOwned(
                SESSION_A, "host", 7, "transition-a", 3L));
    }

    @Test public void preMutationSwitchFenceRequiresExactOwnerActorAndGeneration() {
        FakeController owner = new FakeController();
        owner.origin = "parent";
        RetainedStreamSessionCoordinator.enterHome(
                owner, SESSION_A, "host", "child", 7, "game");
        assertTrue(RetainedStreamSessionCoordinator.bindControlGeneration(
                owner, SESSION_A, 4L));
        assertEquals(RetainedStreamSessionCoordinator.SwitchResult.STARTED,
                RetainedStreamSessionCoordinator.switchGame(
                        "host", "parent", "parent", false, 7,
                        "game", "Game", "MoonWaker Stream", "",
                        () -> false, null));
        RetainedStreamSessionCoordinator.SwitchRequest request = owner.switchRequest;

        assertTrue(RetainedStreamSessionCoordinator.ownsSwitchBeforeMutation(
                owner, request, 4L));
        assertFalse(RetainedStreamSessionCoordinator.ownsSwitchBeforeMutation(
                new FakeController(), request, 4L));
        assertFalse(RetainedStreamSessionCoordinator.ownsSwitchBeforeMutation(
                owner, request, 5L));

        assertTrue(RetainedStreamSessionCoordinator.bindControlGeneration(
                owner, SESSION_A, 5L));
        assertFalse(RetainedStreamSessionCoordinator.ownsSwitchBeforeMutation(
                owner, request, 4L));
        RetainedStreamSessionCoordinator.enterHome(
                new FakeController(), "session-b", "host", "child", 7, "game");
        assertFalse(RetainedStreamSessionCoordinator.ownsSwitchBeforeMutation(
                owner, request, 5L));
    }

    @Test public void stalePreparingSwitchCallbackCannotChangeANewerAttempt() {
        FakeController owner = new FakeController();
        AtomicReference<RetainedStreamSessionCoordinator.SwitchOutcome> outcome =
                new AtomicReference<>();
        assertTrue(RetainedStreamSessionCoordinator.beginPreparing(
                owner, SESSION_A, "host", 7, "", "transition-a", 1L));
        assertEquals(RetainedStreamSessionCoordinator.SwitchResult.STARTED,
                RetainedStreamSessionCoordinator.switchGame(
                        "host", 7, "new", "New", "MoonWaker Stream", "",
                        () -> false, (result, error) -> outcome.set(result)));
        RetainedStreamSessionCoordinator.SwitchCallback stale = owner.switchCompletion;
        assertTrue(RetainedStreamSessionCoordinator.cancelPreparing(
                owner, SESSION_A, "host", 7, "transition-a", 1L));
        assertTrue(RetainedStreamSessionCoordinator.beginPreparing(
                owner, SESSION_A, "host", 7, "", "transition-b", 2L));

        stale.complete(RetainedStreamSessionCoordinator.SwitchOutcome.FAILED, "stale");

        assertTrue(RetainedStreamSessionCoordinator.isPreparing(
                owner, SESSION_A, "host", 7, "transition-b", 2L));
        assertTrue(RetainedStreamSessionCoordinator.canSwitchGame("host", 7));
        assertNull(outcome.get());
    }

    @Test public void completedPreparingSwitchUsesTheExistingHomeFinish() {
        FakeController owner = new FakeController();
        assertTrue(RetainedStreamSessionCoordinator.beginPreparing(
                owner, SESSION_A, "host", 7, "", "transition-a", 1L));
        assertEquals(RetainedStreamSessionCoordinator.SwitchResult.STARTED,
                RetainedStreamSessionCoordinator.switchGame(
                        "host", 7, "new", "New", "MoonWaker Stream", "",
                        () -> false, null));
        assertTrue(RetainedStreamSessionCoordinator.completePreparing(
                owner, SESSION_A, "host", 7, "transition-a", 1L));

        assertTrue(RetainedStreamSessionCoordinator.finishSwitch(SESSION_A, owner));
    }

    @Test public void reusedPreparingSwitchReleasesItsCapturedLockBeforeConnection() {
        FakeController owner = new FakeController();
        assertTrue(RetainedStreamSessionCoordinator.beginPreparing(
                owner, SESSION_A, "host", 7, "old", "transition-a", 3L));
        assertEquals(RetainedStreamSessionCoordinator.SwitchResult.STARTED,
                RetainedStreamSessionCoordinator.switchGame(
                        "host", 7, "same", "Same", "MoonWaker Stream", "",
                        () -> false, null));

        owner.switchCompletion.complete(
                RetainedStreamSessionCoordinator.SwitchOutcome.REUSED, "");
        assertFalse(RetainedStreamSessionCoordinator.canSwitchGame("host", 7));
        assertTrue(RetainedStreamSessionCoordinator.finishSwitch(
                SESSION_A, "transition-a", 3L, owner));
        assertTrue(RetainedStreamSessionCoordinator.canSwitchGame("host", 7));
        assertEquals(RetainedStreamSessionCoordinator.SwitchResult.STARTED,
                RetainedStreamSessionCoordinator.switchGame(
                        "host", 7, "next", "Next", "MoonWaker Stream", "",
                        () -> false, null));
    }

    @Test public void stalePreparingFinishCannotReleaseAReplacementSwitch() {
        FakeController owner = new FakeController();
        assertTrue(RetainedStreamSessionCoordinator.beginPreparing(
                owner, SESSION_A, "host", 7, "", "transition-a", 1L));
        assertEquals(RetainedStreamSessionCoordinator.SwitchResult.STARTED,
                RetainedStreamSessionCoordinator.switchGame(
                        "host", 7, "old", "Old", "MoonWaker Stream", "",
                        () -> false, null));
        assertTrue(RetainedStreamSessionCoordinator.cancelPreparing(
                owner, SESSION_A, "host", 7, "transition-a", 1L));
        assertTrue(RetainedStreamSessionCoordinator.beginPreparing(
                owner, SESSION_A, "host", 7, "", "transition-b", 2L));
        assertEquals(RetainedStreamSessionCoordinator.SwitchResult.STARTED,
                RetainedStreamSessionCoordinator.switchGame(
                        "host", 7, "new", "New", "MoonWaker Stream", "",
                        () -> false, null));

        assertFalse(RetainedStreamSessionCoordinator.finishSwitch(
                SESSION_A, "transition-a", 1L, owner));
        assertFalse(RetainedStreamSessionCoordinator.canSwitchGame("host", 7));
        assertTrue(RetainedStreamSessionCoordinator.finishSwitch(
                SESSION_A, "transition-b", 2L, owner));
    }

    @Test public void preparingSwitchUpdateRequiresTheFullCapturedToken() {
        FakeController owner = new FakeController();
        FakeController staleOwner = new FakeController();
        assertTrue(RetainedStreamSessionCoordinator.beginPreparing(
                owner, SESSION_A, "host", 7, "old", "transition-a", 3L));
        assertEquals(RetainedStreamSessionCoordinator.SwitchResult.STARTED,
                RetainedStreamSessionCoordinator.switchGame(
                        "host", 7, "new", "New", "MoonWaker Stream", "",
                        () -> false, null));
        RetainedStreamSessionCoordinator.SwitchRequest request = owner.switchRequest;

        assertFalse(RetainedStreamSessionCoordinator.updatePreparingSwitch(
                staleOwner, request, "transition-a", "old", "", "transition-b"));
        assertFalse(RetainedStreamSessionCoordinator.updatePreparingSwitch(
                owner, request, "stale", "old", "", "transition-b"));
        assertFalse(RetainedStreamSessionCoordinator.updatePreparingSwitch(
                owner, request, "transition-a", "other", "", "transition-b"));
        assertTrue(RetainedStreamSessionCoordinator.updatePreparingSwitch(
                owner, request, "transition-a", "old", "", "transition-b"));

        RetainedStreamSessionCoordinator.Snapshot updated =
                RetainedStreamSessionCoordinator.snapshot();
        assertEquals(RetainedStreamSessionCoordinator.State.PREPARING, updated.state);
        assertEquals("", updated.playniteGameId);
        assertEquals("transition-b", updated.transitionId);
        assertEquals(3L, updated.attempt);
        assertFalse(RetainedStreamSessionCoordinator.updatePreparingSwitch(
                owner, request, "transition-a", "", "new", "transition-c"));
        assertTrue(RetainedStreamSessionCoordinator.cancelPreparing(
                owner, SESSION_A, "host", 7, "transition-b", 3L));
        assertTrue(RetainedStreamSessionCoordinator.beginPreparing(
                staleOwner, SESSION_A, "host", 7, "", "transition-c", 4L));
        assertFalse(RetainedStreamSessionCoordinator.updatePreparingSwitch(
                owner, request, "transition-b", "", "new", "transition-d"));
        assertTrue(RetainedStreamSessionCoordinator.isPreparing(
                staleOwner, SESSION_A, "host", 7, "transition-c", 4L));
    }

    @Test public void repeatedTerminationDoesNotStartASecondRequest() {
        FakeController controller = new FakeController();
        controller.completeTermination = false;
        AtomicInteger first = new AtomicInteger();
        AtomicInteger second = new AtomicInteger();
        RetainedStreamSessionCoordinator.enterHome(controller, SESSION_A, "host", 7, "game");

        assertEquals(RetainedStreamSessionCoordinator.TerminationResult.STARTED,
                RetainedStreamSessionCoordinator.terminate(SESSION_A,
                        success -> first.set(success ? 1 : -1)));
        assertEquals(RetainedStreamSessionCoordinator.TerminationResult.IN_PROGRESS,
                RetainedStreamSessionCoordinator.terminate(SESSION_A,
                        success -> second.set(success ? 1 : -1)));
        assertEquals(RetainedStreamSessionCoordinator.State.TERMINATING,
                RetainedStreamSessionCoordinator.state());
        assertEquals(1, controller.terminations);
        assertEquals(0, first.get());
        assertEquals(0, second.get());

        controller.terminationCompletion.complete(true);
        assertEquals(1, first.get());
        assertEquals(1, second.get());
        assertEquals(RetainedStreamSessionCoordinator.State.NONE,
                RetainedStreamSessionCoordinator.state());
    }

    @Test public void ownerCompletionClearsTerminatingAndPublishesSuccess() {
        FakeController controller = new FakeController();
        controller.completeTermination = false;
        AtomicReference<RetainedStreamSessionCoordinator.State> observed =
                new AtomicReference<>();
        RetainedStreamSessionCoordinator.enterHome(controller, SESSION_A, "host", 7, "game");

        RetainedStreamSessionCoordinator.terminate(SESSION_A,
                success -> observed.set(RetainedStreamSessionCoordinator.state()));
        assertEquals(RetainedStreamSessionCoordinator.State.TERMINATING,
                RetainedStreamSessionCoordinator.state());
        controller.terminationCompletion.complete(true);

        assertEquals(RetainedStreamSessionCoordinator.State.NONE, observed.get());
        assertEquals(RetainedStreamSessionCoordinator.State.NONE,
                RetainedStreamSessionCoordinator.state());
    }

    @Test public void oneThrowingTerminationJoinerDoesNotBlockTheNext() {
        FakeController controller = new FakeController();
        controller.completeTermination = false;
        AtomicInteger observed = new AtomicInteger();
        RetainedStreamSessionCoordinator.enterHome(controller, SESSION_A, "host", 7, "game");

        RetainedStreamSessionCoordinator.terminate(SESSION_A, success -> {
            throw new IllegalStateException("observer failed");
        });
        RetainedStreamSessionCoordinator.terminate(SESSION_A,
                success -> observed.set(success ? 1 : -1));
        controller.terminationCompletion.complete(true);

        assertEquals(1, observed.get());
    }

    @Test public void mixedTerminationKindsFailClosedWithoutASecondEffect() {
        FakeController disconnect = new FakeController();
        disconnect.completeTermination = false;
        AtomicReference<Boolean> disconnected = new AtomicReference<>();
        AtomicReference<Boolean> terminateConflict = new AtomicReference<>();
        RetainedStreamSessionCoordinator.enterHome(
                disconnect, SESSION_A, "host", 7, "game");

        assertEquals(RetainedStreamSessionCoordinator.TerminationResult.STARTED,
                RetainedStreamSessionCoordinator.disconnect(SESSION_A, disconnected::set));
        assertEquals(RetainedStreamSessionCoordinator.TerminationResult.IN_PROGRESS,
                RetainedStreamSessionCoordinator.terminate(
                        SESSION_A, terminateConflict::set));
        assertEquals(Boolean.FALSE, terminateConflict.get());
        assertFalse(disconnect.terminated);
        disconnect.terminationCompletion.complete(true);
        assertEquals(Boolean.TRUE, disconnected.get());

        FakeController terminate = new FakeController();
        terminate.completeTermination = false;
        AtomicReference<Boolean> terminated = new AtomicReference<>();
        AtomicReference<Boolean> disconnectConflict = new AtomicReference<>();
        RetainedStreamSessionCoordinator.enterHome(
                terminate, SESSION_A, "host", 7, "game");
        RetainedStreamSessionCoordinator.terminate(SESSION_A, terminated::set);
        RetainedStreamSessionCoordinator.disconnect(
                SESSION_A, disconnectConflict::set);
        assertEquals(Boolean.FALSE, disconnectConflict.get());
        assertFalse(terminate.disconnected);
        terminate.terminationCompletion.complete(true);
        assertEquals(Boolean.TRUE, terminated.get());
    }

    @Test public void directStreamQuitIsVisibleUntilMatchingQuitCompletes() {
        assertTrue(RetainedStreamSessionCoordinator.markTerminating(
                SESSION_A, "host", 7, "game"));

        RetainedStreamSessionCoordinator.Snapshot snapshot =
                RetainedStreamSessionCoordinator.snapshot();
        assertEquals(RetainedStreamSessionCoordinator.State.TERMINATING, snapshot.state);
        assertEquals("host", snapshot.hostId);
        assertEquals(7, snapshot.appId);

        assertFalse(RetainedStreamSessionCoordinator.clearIfMatches("other-session"));
        assertEquals(RetainedStreamSessionCoordinator.State.TERMINATING,
                RetainedStreamSessionCoordinator.state());
        assertFalse(RetainedStreamSessionCoordinator.clearAfterAuthoritativeEndIfMatches(
                SESSION_A));
        assertEquals(RetainedStreamSessionCoordinator.State.TERMINATING,
                RetainedStreamSessionCoordinator.state());
        assertTrue(RetainedStreamSessionCoordinator.clearIfMatches(SESSION_A));
        assertEquals(RetainedStreamSessionCoordinator.State.NONE,
                RetainedStreamSessionCoordinator.state());
    }

    @Test public void directWholeQuitRegistersAndCompletesCompatibleJoiners() {
        AtomicReference<Boolean> joined = new AtomicReference<>();
        RetainedStreamSessionCoordinator.DirectTermination direct =
                RetainedStreamSessionCoordinator.beginDirectTermination(
                        SESSION_A, "host", 7, "game");
        assertTrue(direct != null);

        assertEquals(RetainedStreamSessionCoordinator.TerminationResult.IN_PROGRESS,
                RetainedStreamSessionCoordinator.terminate(SESSION_A, joined::set));
        assertNull(joined.get());
        RetainedStreamSessionCoordinator.completeDirectTermination(direct, true);

        assertEquals(Boolean.TRUE, joined.get());
        assertEquals(RetainedStreamSessionCoordinator.State.NONE,
                RetainedStreamSessionCoordinator.state());
    }

    @Test public void failedDirectWholeQuitRestoresStateBeforeJoinersReturn() {
        FakeController controller = new FakeController();
        AtomicReference<Boolean> joined = new AtomicReference<>();
        RetainedStreamSessionCoordinator.enterHome(
                controller, SESSION_A, "host", 7, "game");
        RetainedStreamSessionCoordinator.DirectTermination direct =
                RetainedStreamSessionCoordinator.beginDirectTermination(
                        SESSION_A, "host", 7, "game");
        assertTrue(direct != null);
        RetainedStreamSessionCoordinator.terminate(SESSION_A, joined::set);

        RetainedStreamSessionCoordinator.completeDirectTermination(direct, false);

        assertEquals(Boolean.FALSE, joined.get());
        assertEquals(RetainedStreamSessionCoordinator.State.HOME_LIVE,
                RetainedStreamSessionCoordinator.state());
        assertTrue(RetainedStreamSessionCoordinator.canResumeInstantly());
    }

    @Test public void staleDirectTokenCannotClearReusedSessionId() {
        RetainedStreamSessionCoordinator.DirectTermination old =
                RetainedStreamSessionCoordinator.beginDirectTermination(
                        SESSION_A, "host", 7, "old");
        assertTrue(old != null);
        assertTrue(RetainedStreamSessionCoordinator.clearIfMatches(SESSION_A));
        RetainedStreamSessionCoordinator.enterHome(
                new FakeController(), SESSION_A, "host", 8, "new");
        RetainedStreamSessionCoordinator.DirectTermination current =
                RetainedStreamSessionCoordinator.beginDirectTermination(
                        SESSION_A, "host", 8, "new");
        assertTrue(current != null);

        RetainedStreamSessionCoordinator.completeDirectTermination(old, true);

        assertEquals(RetainedStreamSessionCoordinator.State.TERMINATING,
                RetainedStreamSessionCoordinator.state());
        assertEquals(8, RetainedStreamSessionCoordinator.snapshot().appId);
        RetainedStreamSessionCoordinator.completeDirectTermination(current, true);
        assertEquals(RetainedStreamSessionCoordinator.State.NONE,
                RetainedStreamSessionCoordinator.state());
    }

    @Test public void staleDirectHostFailureCannotRestoreOverReusedExactIdentity() {
        FakeController oldOwner = new FakeController();
        RetainedStreamSessionCoordinator.enterHome(
                oldOwner, SESSION_A, "host", 7, "game");
        RetainedStreamSessionCoordinator.DirectTermination old =
                RetainedStreamSessionCoordinator.beginDirectTermination(
                        SESSION_A, "host", 7, "game");
        assertTrue(old != null);
        assertTrue(RetainedStreamSessionCoordinator.clearIfMatches(SESSION_A));

        FakeController newOwner = new FakeController();
        AtomicReference<Boolean> joined = new AtomicReference<>();
        RetainedStreamSessionCoordinator.enterHome(
                newOwner, SESSION_A, "host", 7, "game");
        RetainedStreamSessionCoordinator.DirectTermination current =
                RetainedStreamSessionCoordinator.beginDirectTermination(
                        SESSION_A, "host", 7, "game");
        assertTrue(current != null);
        RetainedStreamSessionCoordinator.terminate(SESSION_A, joined::set);

        RetainedStreamSessionCoordinator.completeDirectTermination(old, false);

        assertNull(joined.get());
        assertEquals(RetainedStreamSessionCoordinator.State.TERMINATING,
                RetainedStreamSessionCoordinator.state());
        assertFalse(RetainedStreamSessionCoordinator.ownsLiveOrParkedSession(
                oldOwner, SESSION_A, "host", 7));
        assertFalse(RetainedStreamSessionCoordinator.ownsLiveOrParkedSession(
                newOwner, SESSION_A, "host", 7));
        RetainedStreamSessionCoordinator.completeDirectTermination(current, false);
        assertEquals(Boolean.FALSE, joined.get());
        assertEquals(RetainedStreamSessionCoordinator.State.HOME_LIVE,
                RetainedStreamSessionCoordinator.state());
        assertTrue(RetainedStreamSessionCoordinator.ownsLiveOrParkedSession(
                newOwner, SESSION_A, "host", 7));
        RetainedStreamSessionCoordinator.disconnect(SESSION_A, null);
        assertTrue(newOwner.disconnected);
        assertFalse(oldOwner.disconnected);
    }

    @Test public void unsafeDirectTransportTerminalNeverRestoresLiveOwner() {
        for (TransportStopCoordinator.Result result : new TransportStopCoordinator.Result[] {
                TransportStopCoordinator.Result.FAILED,
                TransportStopCoordinator.Result.CANCELLED}) {
            FakeController controller = new FakeController();
            AtomicReference<Boolean> joined = new AtomicReference<>();
            RetainedStreamSessionCoordinator.enterHome(
                    controller, SESSION_A, "host", 7, "game");
            RetainedStreamSessionCoordinator.DirectTermination direct =
                    RetainedStreamSessionCoordinator.beginDirectTermination(
                            SESSION_A, "host", 7, "game");
            RetainedStreamSessionCoordinator.terminate(SESSION_A, joined::set);

            RetainedStreamSessionCoordinator.completeDirectTransportStop(direct, result);

            assertEquals(Boolean.FALSE, joined.get());
            assertEquals(RetainedStreamSessionCoordinator.State.RECONNECT_REQUIRED,
                    RetainedStreamSessionCoordinator.state());
            assertFalse(RetainedStreamSessionCoordinator.canResumeInstantly());
            assertEquals(SESSION_A,
                    RetainedStreamSessionCoordinator.snapshot().streamSessionId);
            RetainedStreamSessionCoordinator.clear();
        }
    }

    @Test public void unsafeCoordinatorTransportTerminalNeverRestoresLiveOwner() {
        FakeController controller = new FakeController();
        controller.completeTermination = false;
        AtomicReference<Boolean> joined = new AtomicReference<>();
        RetainedStreamSessionCoordinator.enterHome(
                controller, SESSION_A, "host", 7, "game");
        RetainedStreamSessionCoordinator.terminate(SESSION_A, joined::set);

        controller.terminationCompletion.transportUnsafe();
        controller.terminationCompletion.complete(false);

        assertEquals(Boolean.FALSE, joined.get());
        assertEquals(RetainedStreamSessionCoordinator.State.RECONNECT_REQUIRED,
                RetainedStreamSessionCoordinator.state());
        assertFalse(RetainedStreamSessionCoordinator.canResumeInstantly());
    }

    @Test public void unsafeCoordinatorDisconnectNeverRestoresLiveOwner() {
        FakeController controller = new FakeController();
        controller.completeTermination = false;
        AtomicReference<Boolean> first = new AtomicReference<>();
        AtomicReference<Boolean> joined = new AtomicReference<>();
        RetainedStreamSessionCoordinator.enterHome(
                controller, SESSION_A, "host", 7, "game");

        assertEquals(RetainedStreamSessionCoordinator.TerminationResult.STARTED,
                RetainedStreamSessionCoordinator.disconnect(SESSION_A, first::set));
        assertEquals(RetainedStreamSessionCoordinator.TerminationResult.IN_PROGRESS,
                RetainedStreamSessionCoordinator.disconnect(SESSION_A, joined::set));
        controller.terminationCompletion.transportUnsafe();
        controller.terminationCompletion.complete(false);

        assertEquals(Boolean.FALSE, first.get());
        assertEquals(Boolean.FALSE, joined.get());
        assertEquals(RetainedStreamSessionCoordinator.State.RECONNECT_REQUIRED,
                RetainedStreamSessionCoordinator.state());
        assertFalse(RetainedStreamSessionCoordinator.canResumeInstantly());
    }

    @Test public void directStreamQuitCannotOverwriteAnotherSession() {
        RetainedStreamSessionCoordinator.enterHome(
                new FakeController(), "session-b", "other-host", 8, "other");

        assertFalse(RetainedStreamSessionCoordinator.markTerminating(
                SESSION_A, "host", 7, "game"));
        assertEquals("session-b",
                RetainedStreamSessionCoordinator.snapshot().streamSessionId);
        assertEquals(RetainedStreamSessionCoordinator.State.HOME_LIVE,
                RetainedStreamSessionCoordinator.state());
    }

    @Test public void staleParkResultCannotOverwriteNewSession() {
        FakeController old = new FakeController() {
            @Override public boolean parkRetainedTransport() {
                RetainedStreamSessionCoordinator.enterHome(
                        new FakeController(), "session-b", "host", 8, "other");
                return true;
            }
        };
        RetainedStreamSessionCoordinator.enterHome(old, SESSION_A, "host", 7, "game");

        assertFalse(RetainedStreamSessionCoordinator.parkForBackground(SESSION_A));
        RetainedStreamSessionCoordinator.Snapshot snapshot =
                RetainedStreamSessionCoordinator.snapshot();
        assertEquals("session-b", snapshot.streamSessionId);
        assertEquals(RetainedStreamSessionCoordinator.State.HOME_LIVE, snapshot.state);
    }

    @Test public void staleTerminationCompletionCannotClearNewSession() {
        FakeController old = new FakeController();
        old.completeTermination = false;
        AtomicReference<Boolean> completed = new AtomicReference<>();
        RetainedStreamSessionCoordinator.enterHome(old, SESSION_A, "host", 7, "game");
        assertEquals(RetainedStreamSessionCoordinator.TerminationResult.STARTED,
                RetainedStreamSessionCoordinator.terminate(SESSION_A, completed::set));
        RetainedStreamSessionCoordinator.enterHome(
                new FakeController(), "session-b", "host", 8, "other");

        old.terminationCompletion.complete(true);

        assertEquals(Boolean.FALSE, completed.get());
        assertEquals("session-b", RetainedStreamSessionCoordinator.snapshot().streamSessionId);
        assertEquals(RetainedStreamSessionCoordinator.State.HOME_LIVE,
                RetainedStreamSessionCoordinator.state());
    }

    @Test public void terminationWithoutControllerDoesNotRemainWedged() {
        RetainedStreamSessionCoordinator.enterHome(
                new FakeController(), SESSION_A, "host", 7, "game");
        RetainedStreamSessionCoordinator.markReconnectRequired(SESSION_A);

        assertEquals(RetainedStreamSessionCoordinator.TerminationResult.NO_CONTROLLER,
                RetainedStreamSessionCoordinator.terminate(SESSION_A, null));
        assertEquals(RetainedStreamSessionCoordinator.State.RECONNECT_REQUIRED,
                RetainedStreamSessionCoordinator.state());
        assertEquals(RetainedStreamSessionCoordinator.TerminationResult.NO_CONTROLLER,
                RetainedStreamSessionCoordinator.terminate(SESSION_A, null));
    }

    @Test public void rejectedTerminationRestoresTheLiveOwner() {
        FakeController controller = new FakeController();
        controller.terminationResult = false;
        RetainedStreamSessionCoordinator.enterHome(controller, SESSION_A, "host", 7, "game");

        assertEquals(RetainedStreamSessionCoordinator.TerminationResult.STARTED,
                RetainedStreamSessionCoordinator.terminate(SESSION_A, null));

        assertEquals(RetainedStreamSessionCoordinator.State.HOME_LIVE,
                RetainedStreamSessionCoordinator.state());
        assertTrue(RetainedStreamSessionCoordinator.canResumeInstantly());
        assertTrue(RetainedStreamSessionCoordinator.hasRetainedSession());
    }

    @Test public void onlyOneCorrelatedSwitchCanBeInFlight() {
        FakeController controller = new FakeController();
        RetainedStreamSessionCoordinator.enterHome(
                controller, SESSION_A, "host", 7, "old");

        assertEquals(RetainedStreamSessionCoordinator.SwitchResult.STARTED,
                RetainedStreamSessionCoordinator.switchGame(
                        "host", 7, "new", "New", "MoonWaker Stream", "",
                        () -> false, null));
        assertEquals(RetainedStreamSessionCoordinator.SwitchResult.NOT_ELIGIBLE,
                RetainedStreamSessionCoordinator.switchGame(
                        "host", 7, "other", "Other", "MoonWaker Stream", "",
                        () -> false, null));
        assertEquals(1, controller.switches);

        controller.switchCompletion.complete(
                RetainedStreamSessionCoordinator.SwitchOutcome.REUSED, "");
        assertFalse(RetainedStreamSessionCoordinator.canSwitchGame("host", 7));
        assertEquals(RetainedStreamSessionCoordinator.SwitchResult.NOT_ELIGIBLE,
                RetainedStreamSessionCoordinator.switchGame(
                        "host", 7, "other", "Other", "MoonWaker Stream", "",
                        () -> false, null));
        assertEquals(1, controller.switches);

        assertTrue(RetainedStreamSessionCoordinator.finishSwitch(SESSION_A, controller));
        assertTrue(RetainedStreamSessionCoordinator.canSwitchGame("host", 7));
    }

    @Test public void staleSwitchCannotRewriteNewerSessionIdentity() {
        FakeController controller = new FakeController();
        RetainedStreamSessionCoordinator.enterHome(
                controller, SESSION_A, "host", 7, "old");
        assertTrue(RetainedStreamSessionCoordinator.updateGameIfMatches(
                SESSION_A, "host", 7, "old", "new"));

        RetainedStreamSessionCoordinator.enterHome(
                new FakeController(), "session-b", "host", 7, "other");

        assertFalse(RetainedStreamSessionCoordinator.updateGameIfMatches(
                SESSION_A, "host", 7, "new", "stale"));
        assertEquals("other", RetainedStreamSessionCoordinator.snapshot().playniteGameId);
    }

    @Test public void parkedTransportReusesOnlyTheSameHostAndApp() {
        FakeController controller = new FakeController();
        RetainedStreamSessionCoordinator.enterHome(
                controller, SESSION_A, "host", 7, "old");
        assertFalse(RetainedStreamSessionCoordinator.canSwitchGame("other", 7));
        assertFalse(RetainedStreamSessionCoordinator.canSwitchGame("host", 8));

        RetainedStreamSessionCoordinator.markParked(SESSION_A);

        assertTrue(RetainedStreamSessionCoordinator.canSwitchGame("host", 7));
    }

    @Test public void terminalCallbackReleasesSwitchButNotBeforeItArrives() {
        FakeController controller = new FakeController();
        RetainedStreamSessionCoordinator.enterHome(
                controller, SESSION_A, "host", 7, "old");
        assertEquals(RetainedStreamSessionCoordinator.SwitchResult.STARTED,
                RetainedStreamSessionCoordinator.switchGame(
                        "host", 7, "new", "New", "MoonWaker Stream", "",
                        () -> false, null));

        assertEquals(RetainedStreamSessionCoordinator.SwitchResult.NOT_ELIGIBLE,
                RetainedStreamSessionCoordinator.switchGame(
                        "host", 7, "other", "Other", "MoonWaker Stream", "",
                        () -> false, null));

        controller.switchCompletion.complete(
                RetainedStreamSessionCoordinator.SwitchOutcome.FAILED, "cleanup_failed");
        assertTrue(RetainedStreamSessionCoordinator.canSwitchGame("host", 7));
    }

    @Test public void neutralLiveStreamCanSwitchToManagedGame() {
        FakeController controller = new FakeController();
        RetainedStreamSessionCoordinator.enterHome(
                controller, SESSION_A, "host", 7, "");

        assertTrue(RetainedStreamSessionCoordinator.canSwitchGame("host", 7));
        assertEquals(RetainedStreamSessionCoordinator.SwitchResult.STARTED,
                RetainedStreamSessionCoordinator.switchGame(
                        "host", 7, "new", "New", "MoonWaker Stream", "",
                        () -> false, null));
        assertEquals("", RetainedStreamSessionCoordinator.snapshot().playniteGameId);
        assertEquals(1, controller.switches);
    }

    @Test public void foregroundNaturalStopClaimsAnEmptyCoordinatorAsNeutralHome() {
        FakeController controller = new FakeController();

        assertTrue(RetainedStreamSessionCoordinator.retainNeutralAfterGameStopped(
                controller, SESSION_A, "host", 7, "old"));

        RetainedStreamSessionCoordinator.Snapshot retained =
                RetainedStreamSessionCoordinator.snapshot();
        assertEquals(RetainedStreamSessionCoordinator.State.HOME_LIVE, retained.state);
        assertEquals(SESSION_A, retained.streamSessionId);
        assertEquals("host", retained.hostId);
        assertEquals(7, retained.appId);
        assertEquals("", retained.playniteGameId);
        assertTrue(RetainedStreamSessionCoordinator.canSwitchGame("host", 7));
    }

    @Test public void duplicateNaturalStopKeepsTheSettledNeutralSession() {
        FakeController controller = new FakeController();
        assertTrue(RetainedStreamSessionCoordinator.retainNeutralAfterGameStopped(
                controller, SESSION_A, "host", 7, "old"));

        assertTrue(RetainedStreamSessionCoordinator.retainNeutralAfterGameStopped(
                controller, SESSION_A, "host", 7, "old"));

        RetainedStreamSessionCoordinator.Snapshot retained =
                RetainedStreamSessionCoordinator.snapshot();
        assertEquals(RetainedStreamSessionCoordinator.State.HOME_LIVE, retained.state);
        assertEquals(SESSION_A, retained.streamSessionId);
        assertEquals("", retained.playniteGameId);
        assertTrue(RetainedStreamSessionCoordinator.canSwitchGame("host", 7));
    }

    @Test public void dashboardStopCanPreclearExactGameBeforeOwnerSettlesNeutral() {
        FakeController controller = new FakeController();
        RetainedStreamSessionCoordinator.enterHome(
                controller, SESSION_A, "host", 7, "old");
        assertTrue(RetainedStreamSessionCoordinator.updateGameIfMatches(
                SESSION_A, "host", 7, "old", ""));

        assertTrue(RetainedStreamSessionCoordinator.retainNeutralAfterGameStopped(
                controller, SESSION_A, "host", 7, "old"));
        assertFalse(RetainedStreamSessionCoordinator.retainNeutralAfterGameStopped(
                new FakeController(), SESSION_A, "host", 7, "old"));
        assertEquals("", RetainedStreamSessionCoordinator.snapshot().playniteGameId);
    }

    @Test public void parkedNaturalStopClearsOnlyTheExactGameAndKeepsParking() {
        FakeController controller = new FakeController();
        RetainedStreamSessionCoordinator.enterHome(
                controller, SESSION_A, "host", 7, "old");
        RetainedStreamSessionCoordinator.markParked(SESSION_A);

        assertTrue(RetainedStreamSessionCoordinator.retainNeutralAfterGameStopped(
                controller, SESSION_A, "host", 7, "old"));

        RetainedStreamSessionCoordinator.Snapshot retained =
                RetainedStreamSessionCoordinator.snapshot();
        assertEquals(RetainedStreamSessionCoordinator.State.PARKED_LIVE, retained.state);
        assertEquals(SESSION_A, retained.streamSessionId);
        assertEquals(7, retained.appId);
        assertEquals("", retained.playniteGameId);
    }

    @Test public void naturalStopCannotTakeOverAForeignRetainedSession() {
        FakeController foreign = new FakeController();
        RetainedStreamSessionCoordinator.enterHome(
                foreign, "session-b", "other-host", 8, "other");

        assertFalse(RetainedStreamSessionCoordinator.retainNeutralAfterGameStopped(
                new FakeController(), SESSION_A, "host", 7, "old"));

        RetainedStreamSessionCoordinator.Snapshot retained =
                RetainedStreamSessionCoordinator.snapshot();
        assertEquals("session-b", retained.streamSessionId);
        assertEquals("other-host", retained.hostId);
        assertEquals(8, retained.appId);
        assertEquals("other", retained.playniteGameId);
    }

    @Test public void naturalStopCannotMutateReconnectOrTerminatingSession() {
        FakeController controller = new FakeController();
        RetainedStreamSessionCoordinator.enterHome(
                controller, SESSION_A, "host", 7, "old");
        RetainedStreamSessionCoordinator.markReconnectRequired(SESSION_A);

        assertFalse(RetainedStreamSessionCoordinator.retainNeutralAfterGameStopped(
                controller, SESSION_A, "host", 7, "old"));
        assertEquals(RetainedStreamSessionCoordinator.State.RECONNECT_REQUIRED,
                RetainedStreamSessionCoordinator.state());
        assertEquals("old", RetainedStreamSessionCoordinator.snapshot().playniteGameId);

        RetainedStreamSessionCoordinator.clear();
        assertTrue(RetainedStreamSessionCoordinator.markTerminating(
                SESSION_A, "host", 7, "old"));
        assertFalse(RetainedStreamSessionCoordinator.retainNeutralAfterGameStopped(
                controller, SESSION_A, "host", 7, "old"));
        assertEquals(RetainedStreamSessionCoordinator.State.TERMINATING,
                RetainedStreamSessionCoordinator.state());
        assertEquals("old", RetainedStreamSessionCoordinator.snapshot().playniteGameId);
    }

    @Test public void replacedSessionSurvivesStaleSwitchCompletion() {
        FakeController old = new FakeController();
        AtomicReference<RetainedStreamSessionCoordinator.SwitchOutcome> outcome =
                new AtomicReference<>();
        RetainedStreamSessionCoordinator.enterHome(
                old, SESSION_A, "host", 7, "old");
        assertEquals(RetainedStreamSessionCoordinator.SwitchResult.STARTED,
                RetainedStreamSessionCoordinator.switchGame(
                        "host", 7, "new", "New", "MoonWaker Stream", "",
                        () -> false, (result, error) -> outcome.set(result)));

        RetainedStreamSessionCoordinator.enterHome(
                new FakeController(), "session-b", "host", 8, "other");
        old.switchCompletion.complete(
                RetainedStreamSessionCoordinator.SwitchOutcome.FAILED, "stale");

        assertEquals(RetainedStreamSessionCoordinator.SwitchOutcome.FAILED, outcome.get());
        RetainedStreamSessionCoordinator.Snapshot retained =
                RetainedStreamSessionCoordinator.snapshot();
        assertEquals("session-b", retained.streamSessionId);
        assertEquals(8, retained.appId);
        assertEquals("other", retained.playniteGameId);
    }

    @Test public void controlGenerationBindsOnlyToTheExactLiveOwnerAndClears() {
        FakeController owner = new FakeController();
        RetainedStreamSessionCoordinator.enterHome(
                owner, SESSION_A, "host", 7, "game");
        assertTrue(RetainedStreamSessionCoordinator.bindControlGeneration(
                owner, SESSION_A, 11L));
        assertEquals(11L, RetainedStreamSessionCoordinator.snapshot().controlGeneration);

        RetainedStreamSessionCoordinator.enterHome(
                new FakeController(), "session-b", "host", 7, "game");
        assertFalse(RetainedStreamSessionCoordinator.bindControlGeneration(
                owner, SESSION_A, 12L));
        assertEquals(0L, RetainedStreamSessionCoordinator.snapshot().controlGeneration);
    }

    @Test public void exactPreparingOwnerCanBeginTerminalChildCleanup() {
        FakeController owner = new FakeController();
        assertTrue(RetainedStreamSessionCoordinator.beginPreparing(
                owner, SESSION_A, "host", 7, "game", "transition", 1L));

        RetainedStreamSessionCoordinator.DirectTermination token =
                RetainedStreamSessionCoordinator.beginDirectTermination(
                        owner, SESSION_A, "host", 7, "game");

        assertTrue(token != null);
        assertEquals(RetainedStreamSessionCoordinator.State.TERMINATING,
                RetainedStreamSessionCoordinator.state());
        RetainedStreamSessionCoordinator.completeDirectTermination(token, true);
        assertEquals(RetainedStreamSessionCoordinator.State.NONE,
                RetainedStreamSessionCoordinator.state());
    }

    private static String token(char value) {
        return String.valueOf(value).repeat(64);
    }
}
