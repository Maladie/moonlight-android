package com.limelight.console;

import com.limelight.session.SessionModel;
import com.limelight.session.SessionModelDriver;

import com.limelight.console.transition.LaunchTransitionType;
import com.limelight.nvstream.http.NvApp;

import org.junit.Test;

import java.util.ArrayDeque;
import java.util.Queue;
import java.util.concurrent.Executor;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class SessionOrchestratorTest {
    private final SessionModelDriver m = new SessionModelDriver();
    @Test public void sameOriginActorChangeReusesEvenWhenNewActorSeesIdleOrTheSameGame() {
        for (SessionSnapshot.State state : new SessionSnapshot.State[] {
                SessionSnapshot.State.NONE, SessionSnapshot.State.ACTIVE}) {
            Fake effects = new Fake();
            effects.snapshot = snapshot(state, state == SessionSnapshot.State.NONE ? 0 : 42,
                    state == SessionSnapshot.State.NONE ? "" : "game");
            effects.canSwitch = true;
            effects.closeResult = SessionOrchestrator.CloseResult.REUSED;
            orchestrator(effects).play(game());
            assertEquals(HostLaunchPreflight.Action.SWITCH_RETAINED, effects.preflightAction);
            assertEquals(1, effects.closes);
            assertEquals(1, effects.returned);
            assertEquals(0, effects.launches);
        }
    }

    @Test public void savedPairAllowsOnePreparationButNotOrdinaryPlay() {
        Fake effects = new Fake();
        effects.paired = false;
        effects.knownPreparationPair = true;
        SessionOrchestrator orchestrator = orchestrator(effects);
        assertTrue(orchestrator.prepareHost("host") > 0L);
        assertEquals(1, effects.prepareCalls);
        assertEquals(1, effects.preparedLaunches);
        orchestrator.play(game());
        assertEquals(SessionOrchestrator.Rejection.UNPAIRED, effects.rejection);
        assertEquals(0, effects.launches);
    }

    @Test public void warmUpGameClickHandsOffBeforeWorkerCompletes() {
        Fake effects = new Fake();
        effects.deferLoading = true;
        QueuedExecutor worker = new QueuedExecutor();
        QueuedDispatcher main = new QueuedDispatcher();
        SessionOrchestrator orchestrator = new SessionOrchestrator(effects, worker, main);

        orchestrator.prepareHost("host");
        orchestrator.play(game());

        assertEquals(1, effects.loadings);
        assertEquals(0, effects.readiness);
        worker.runNext();
        main.runNext();
        assertEquals(0, effects.preparedLaunches);
        effects.opaqueCallbacks.remove().run();
        main.runNext();
        assertEquals(1, effects.preparedLaunches);
        assertEquals("game", effects.preparedGame.playniteGameId);
        assertEquals(effects.prepareRequest, effects.preparedLaunchRequest);
    }

    @Test public void readyWarmUpWithoutSelectionLaunchesImmediatelyOnce() {
        Fake effects = new Fake();
        QueuedExecutor worker = new QueuedExecutor();
        QueuedDispatcher main = new QueuedDispatcher();
        SessionOrchestrator orchestrator = new SessionOrchestrator(effects, worker, main);

        orchestrator.prepareHost("host");
        worker.runNext();
        main.runNext();

        assertEquals(1, effects.preparedLaunches);
        assertNull(effects.preparedGame);
        orchestrator.play(game());
        assertEquals(1, effects.preparedLaunches);
        assertEquals(1, effects.loadings);
    }

    @Test public void queuedReadyThenClickWaitsForOpaqueAndCannotDoubleLaunch() {
        Fake effects = new Fake();
        effects.deferLoading = true;
        QueuedExecutor worker = new QueuedExecutor();
        QueuedDispatcher main = new QueuedDispatcher();
        SessionOrchestrator orchestrator = new SessionOrchestrator(effects, worker, main);

        orchestrator.prepareHost("host");
        worker.runNext();
        orchestrator.play(game());
        main.runNext();
        assertEquals(0, effects.preparedLaunches);

        effects.opaqueCallbacks.remove().run();
        main.runNext();
        assertEquals(1, effects.preparedLaunches);
        main.runAll();
        assertEquals(1, effects.preparedLaunches);
    }

    @Test public void handoffKeepsThePreparationGenerationCurrent() {
        Fake effects = new Fake();
        effects.deferLoading = true;
        QueuedExecutor worker = new QueuedExecutor();
        QueuedDispatcher main = new QueuedDispatcher();
        SessionOrchestrator orchestrator = new SessionOrchestrator(effects, worker, main);

        orchestrator.prepareHost("host");
        orchestrator.play(game());
        worker.runNext();
        main.runAll();
        effects.opaqueCallbacks.remove().run();
        main.runNext();

        assertEquals(1L, effects.prepareRequest);
        assertEquals(1L, effects.preparedLaunchRequest);
    }

    @Test public void ordinaryPlayAdvancesGenerationAfterPreparedLaunch() {
        Fake effects = new Fake();
        SessionOrchestrator orchestrator = orchestrator(effects);

        orchestrator.prepareHost("host");
        assertEquals(1L, effects.prepareRequest);
        orchestrator.play(game());
        orchestrator.prepareHost("host");

        assertEquals(3L, effects.prepareRequest);
    }

    @Test public void cancelledClosedAndStalePreparationResultsNeverLaunch() {
        for (int action = 0; action < 3; action++) {
            Fake effects = new Fake();
            QueuedExecutor worker = new QueuedExecutor();
            QueuedDispatcher main = new QueuedDispatcher();
            SessionOrchestrator orchestrator = new SessionOrchestrator(
                    effects, worker, main);
            orchestrator.prepareHost("host");
            worker.runNext();
            if (action == 0) orchestrator.cancel();
            else if (action == 1) orchestrator.close();
            else orchestrator.prepareHost("host");

            main.runNext();

            assertEquals(0, effects.preparedLaunches);
        }
    }

    @Test public void noLaunchPreparationClearsHandoffAndOrdinaryPlayContinues() {
        Fake effects = new Fake();
        effects.preparedWarmUp = null;
        QueuedExecutor worker = new QueuedExecutor();
        QueuedDispatcher main = new QueuedDispatcher();
        SessionOrchestrator orchestrator = new SessionOrchestrator(effects, worker, main);

        orchestrator.prepareHost("host");
        worker.runNext();
        main.runNext();
        orchestrator.play(game());

        assertEquals(1, effects.loadings);
        assertEquals(0, effects.preparedLaunches);
        assertEquals(1, worker.size());
    }

    @Test public void failedPreparationAfterGameHandoffShowsErrorAndRetryUsesGame() {
        Fake effects = new Fake();
        effects.preparedWarmUp = null;
        QueuedExecutor worker = new QueuedExecutor();
        QueuedDispatcher main = new QueuedDispatcher();
        SessionOrchestrator orchestrator = new SessionOrchestrator(effects, worker, main);

        orchestrator.prepareHost("host");
        orchestrator.play(game());
        worker.runNext();
        main.runAll();

        assertEquals(1, effects.preparationFailures);
        orchestrator.retry();
        assertEquals(2, effects.loadings);
        assertEquals(1, worker.size());
    }

    @Test public void preparationQueryAndCancelAreScopedToHost() {
        Fake effects = new Fake();
        QueuedExecutor worker = new QueuedExecutor();
        QueuedDispatcher main = new QueuedDispatcher();
        SessionOrchestrator orchestrator = new SessionOrchestrator(effects, worker, main);

        assertTrue(orchestrator.prepareHost("host") > 0L);
        assertTrue(orchestrator.hasPreparationForHost("host"));
        assertFalse(orchestrator.cancelPreparation("other"));
        assertTrue(orchestrator.hasPreparationForHost("host"));
        assertTrue(orchestrator.cancelPreparation("host"));
        assertFalse(orchestrator.hasPreparationForHost("host"));
    }

    @Test public void differentHostPlayDoesNotHandoffToWarmUp() {
        Fake effects = new Fake();
        QueuedExecutor worker = new QueuedExecutor();
        QueuedDispatcher main = new QueuedDispatcher();
        SessionOrchestrator orchestrator = new SessionOrchestrator(effects, worker, main);

        orchestrator.prepareHost("host");
        orchestrator.play(PlayIntent.playniteGame(
                "other", 42, "Game", false, "game", "game"));
        worker.runNext();
        main.runAll();

        assertEquals(1, effects.loadings);
        assertEquals(0, effects.preparedLaunches);
        assertTrue(effects.prepareCancelled.getAsBoolean());
    }

    @Test public void unavailableOrUnpairedHostNeverStartsPreparationWorker() {
        for (boolean unavailable : new boolean[] { true, false }) {
            Fake effects = new Fake();
            effects.available = !unavailable;
            effects.paired = unavailable;

            assertEquals(0L, orchestrator(effects).prepareHost("host"));
            assertEquals(0, effects.prepareCalls);
        }
    }

    @Test public void retainedAndPreparingNoLaunchResultsDoNotLeaveHandoffWaiting() {
        for (SessionSnapshot.State state : new SessionSnapshot.State[] {
                SessionSnapshot.State.ACTIVE,
                SessionSnapshot.State.PREPARING }) {
            Fake effects = new Fake();
            effects.snapshot = snapshot(state, 42, "");
            effects.preparedWarmUp = null;
            SessionOrchestrator orchestrator = orchestrator(effects);

            orchestrator.prepareHost("host");
            effects.snapshot = snapshot(SessionSnapshot.State.NONE, 0, "");
            orchestrator.play(game());

            assertEquals(1, effects.loadings);
            assertEquals(0, effects.preparedLaunches);
        }
    }

    @Test public void warmUpWakeDecisionUsesLocalBridgeAndMoonlightEvidence() {
        assertFalse(SessionOrchestrator.shouldSendWarmUpWake(
                SessionModel.State.HOME_LIVE, false,
                com.limelight.nvstream.http.ComputerDetails.State.OFFLINE));
        assertFalse(SessionOrchestrator.shouldSendWarmUpWake(
                SessionModel.State.PARKED_LIVE, false,
                com.limelight.nvstream.http.ComputerDetails.State.OFFLINE));
        assertFalse(SessionOrchestrator.shouldSendWarmUpWake(
                SessionModel.State.PREPARING, false,
                com.limelight.nvstream.http.ComputerDetails.State.OFFLINE));
        assertFalse(SessionOrchestrator.shouldSendWarmUpWake(
                SessionModel.State.TERMINATING, false,
                com.limelight.nvstream.http.ComputerDetails.State.OFFLINE));
        assertFalse(SessionOrchestrator.shouldSendWarmUpWake(
                SessionModel.State.NONE, true,
                com.limelight.nvstream.http.ComputerDetails.State.OFFLINE));
        assertFalse(SessionOrchestrator.shouldSendWarmUpWake(
                SessionModel.State.NONE, false,
                com.limelight.nvstream.http.ComputerDetails.State.ONLINE));
        assertFalse(SessionOrchestrator.shouldSendWarmUpWake(
                SessionModel.State.NONE, false,
                com.limelight.nvstream.http.ComputerDetails.State.UNKNOWN));
        assertTrue(SessionOrchestrator.shouldSendWarmUpWake(
                SessionModel.State.NONE, false,
                com.limelight.nvstream.http.ComputerDetails.State.OFFLINE));
        assertTrue(SessionOrchestrator.shouldSendWarmUpWake(
                SessionModel.State.RECONNECT_REQUIRED,
                false, com.limelight.nvstream.http.ComputerDetails.State.OFFLINE));
    }

    @Test public void warmUpWakeDecisionIsPureAndDoesNotOwnSendState() {
        assertTrue(SessionOrchestrator.shouldSendWarmUpWake(
                SessionModel.State.NONE, false,
                com.limelight.nvstream.http.ComputerDetails.State.OFFLINE));
        assertTrue(SessionOrchestrator.shouldSendWarmUpWake(
                SessionModel.State.NONE, false,
                com.limelight.nvstream.http.ComputerDetails.State.OFFLINE));
    }

    @Test public void matchingRetainedTargetReturnsWithoutReadinessOrLaunch() {
        Fake effects = new Fake();
        effects.snapshot = snapshot(SessionSnapshot.State.ACTIVE, 42,
                "game", true, "", false);

        orchestrator(effects).play(game());

        assertEquals(1, effects.returned);
        assertEquals(0, effects.readiness);
        assertEquals(0, effects.launches);
    }

    @Test public void differentRetainedTargetUsesSafeReplacementPath() {
        Fake effects = new Fake();
        effects.snapshot = snapshot(SessionSnapshot.State.ACTIVE, 42,
                "other", true, "", false);

        orchestrator(effects).play(game());

        assertNotNull(effects.confirmation);
        assertEquals(0, effects.closes);
        effects.confirmation.run();
        assertEquals(HostLaunchPreflight.Action.LAUNCH, effects.preflightAction);
    }

    @Test public void matchingReconnectUsesSavedIntentOnly() {
        Fake effects = new Fake();
        effects.snapshot = snapshot(SessionSnapshot.State.RECONNECT_REQUIRED, 42, "game");

        orchestrator(effects).play(game());

        assertEquals(1, effects.reconnects);
        assertEquals(1, effects.readiness);
        assertEquals(0, effects.launches);
    }

    @Test public void neutralWarmUpReconnectHandsOffGameWithoutReplacement() {
        Fake effects = new Fake();
        effects.warmUpCarriesGame = true;
        effects.snapshot = snapshot(SessionSnapshot.State.RECONNECT_REQUIRED,
                77, "", false, "", true);

        PlayIntent game = game();
        orchestrator(effects).play(game);

        assertEquals(SessionSnapshot.State.RECONNECT_REQUIRED, effects.snapshot.state);
        assertNull(effects.confirmation);
        assertEquals(HostLaunchPreflight.Action.RECONNECT, effects.preflightAction);
        assertEquals(1, effects.reconnects);
        assertSame(game, effects.reconnectIntent);
        assertEquals(0, effects.closes);
        assertEquals(0, effects.launches);
    }

    @Test public void gameVerifiedDuringOrphanPreflightAsksBeforeReplacement() {
        Fake effects = new Fake();
        effects.snapshot = snapshot(SessionSnapshot.State.RECONNECT_REQUIRED,
                77, "", false, "", true, SessionSnapshot.ObservationState.IDLE);
        effects.onReadiness = () -> effects.snapshot = snapshot(
                SessionSnapshot.State.RECONNECT_REQUIRED, 77, "other", false, "", true);

        orchestrator(effects).play(game());

        assertEquals(HostLaunchPreflight.Action.LAUNCH, effects.preflightAction);
        assertNotNull(effects.confirmation);
        assertEquals(0, effects.closes);
        assertEquals(0, effects.launches);
        assertEquals(0, effects.reconnects);
    }

    @Test public void hostConfirmedGameReconnectsNeutralTargetWithoutWarmUp() {
        Fake effects = new Fake();
        effects.snapshot = snapshot(SessionSnapshot.State.RECONNECT_REQUIRED,
                42, "game", false, "", true);

        orchestrator(effects).play(game());

        assertEquals(1, effects.reconnects);
        assertEquals(0, effects.closes);
        assertEquals(0, effects.launches);
    }

    @Test public void orphanedNeutralStreamWithoutWarmUpIsClosedThenGameLaunches() {
        Fake effects = new Fake();
        effects.snapshot = snapshot(SessionSnapshot.State.RECONNECT_REQUIRED,
                77, "", false, "", true, SessionSnapshot.ObservationState.IDLE);
        PlayIntent game = game();

        orchestrator(effects).play(game);

        assertNull(effects.confirmation);
        assertEquals(0, effects.refreshes);
        assertEquals(0, effects.reconnects);
        assertEquals(1, effects.closes);
        assertEquals(Boolean.TRUE, effects.allowDestructiveCloseSeen);
        assertEquals(1, effects.launches);
        assertEquals(42, effects.launchedTarget.getAppId());
        assertEquals(HostLaunchPreflight.Action.LAUNCH, effects.preflightAction);
    }

    /** Lab check 23: the projection went stale between the RECONNECT preflight and its pass. */
    @Test public void staleProjectionAfterReconnectPreflightRefreshesAndReconnects() {
        Fake effects = new Fake();
        SessionSnapshot ready = snapshot(SessionSnapshot.State.RECONNECT_REQUIRED,
                77, "game", false, "", true, SessionSnapshot.ObservationState.READY, true);
        effects.snapshot = ready;
        effects.onReadiness = () -> effects.snapshot = snapshot(
                SessionSnapshot.State.RECONNECT_REQUIRED, 77, "", false, "", true);
        effects.onRefresh = () -> effects.snapshot = ready;

        orchestrator(effects).play(game());

        assertEquals(HostLaunchPreflight.Action.RECONNECT, effects.preflightAction);
        assertEquals(1, effects.refreshes);
        assertEquals(1, effects.reconnects);
        assertNull(effects.confirmation);
        assertEquals(0, effects.closes);
        assertNull(effects.allowDestructiveCloseSeen);
        assertEquals(0, effects.launches);
        assertTrue(effects.failures.isEmpty());
    }

    @Test public void neutralStreamWithUnresolvedGameIsConfirmedNotClosed() {
        Fake effects = new Fake();
        effects.snapshot = snapshot(SessionSnapshot.State.RECONNECT_REQUIRED,
                77, "", false, "", true);
        SessionOrchestrator orchestrator = orchestrator(effects);

        orchestrator.play(game());

        assertEquals(1, effects.refreshes);
        assertNotNull(effects.confirmation);
        assertEquals(0, effects.closes);
        assertEquals(0, effects.reconnects);
        assertEquals(0, effects.launches);

        effects.confirmation.run();

        assertEquals(1, effects.refreshes);
        assertEquals(HostLaunchPreflight.Action.LAUNCH, effects.preflightAction);
        assertEquals(1, effects.closes);
        assertEquals(Boolean.TRUE, effects.allowDestructiveCloseSeen);
        assertEquals(1, effects.launches);
    }

    @Test public void cancelledNeutralWarmUpReconnectIgnoresStaleOpaqueFrame() {
        Fake effects = new Fake();
        effects.snapshot = snapshot(SessionSnapshot.State.RECONNECT_REQUIRED,
                77, "", false, "", true);
        effects.deferLoading = true;
        SessionOrchestrator orchestrator = orchestrator(effects);

        orchestrator.play(game());
        orchestrator.cancel();
        effects.opaqueCallbacks.remove().run();

        assertNull(effects.confirmation);
        assertEquals(0, effects.readiness);
        assertEquals(0, effects.reconnects);
    }

    @Test public void suspendedTargetReadiesAndConnectsGenerically() {
        Fake effects = new Fake();
        effects.snapshot = snapshot(SessionSnapshot.State.SUSPENDED, 42, "game");

        orchestrator(effects).play(game());

        assertEquals(1, effects.readiness);
        assertEquals(0, effects.closes);
        assertEquals(LaunchTransitionType.GENERIC, effects.launchType);
    }

    @Test public void activeTargetConnectsAfterPreflightWithoutQuit() {
        Fake effects = new Fake();
        effects.snapshot = snapshot(SessionSnapshot.State.ACTIVE, 42, "game");

        orchestrator(effects).play(game());

        assertEquals(1, effects.readiness);
        assertEquals(0, effects.closes);
        assertEquals(LaunchTransitionType.GENERIC, effects.launchType);
    }

    @Test public void suspendedTargetCarriesCanonicalSuspendCorrelation() {
        Fake effects = new Fake();
        effects.snapshot = snapshot(SessionSnapshot.State.SUSPENDED,
                42, "game", false, "suspend-a", false);

        orchestrator(effects).play(game());
        assertEquals(LaunchTransitionType.GENERIC, effects.launchType);
        assertEquals("suspend-a", effects.launchedSourceSuspendId);
    }

    @Test public void suspendedTargetConnectsDuringOnlineZeroPoll() {
        Fake effects = new Fake();
        effects.snapshot = snapshot(SessionSnapshot.State.SUSPENDED, 42, "game");

        orchestrator(effects).play(game());

        assertEquals(1, effects.readiness);
        assertEquals(1, effects.launches);
    }

    @Test public void noneDerivesFreshTransitionTypeFromTarget() {
        assertFreshType(game(), LaunchTransitionType.GAME, "");
        assertFreshType(PlayIntent.playniteFullscreen(
                "host", 42, "Playnite", false), LaunchTransitionType.PLAYNITE, "");
        assertFreshType(PlayIntent.sunshineApp(
                "host", 42, "App", false, "quick"), LaunchTransitionType.GENERIC, "");
    }

    @Test public void competingTargetWaitsForConfirmationBeforeClose() {
        Fake effects = competing();

        orchestrator(effects).play(game());

        assertNotNull(effects.confirmation);
        assertEquals(0, effects.closes);
        assertEquals(0, effects.launches);
    }

    @Test public void confirmedCompetingTargetClosesThenFreshLaunches() {
        Fake effects = competing();
        orchestrator(effects).play(game());

        effects.confirmation.run();

        assertEquals(1, effects.closes);
        assertEquals(1, effects.launches);
        assertEquals(LaunchTransitionType.GAME, effects.launchType);
        assertEquals(42, effects.launchedTarget.getAppId());
        assertTrue(effects.launchedOwnsFreshSunshineSession);
    }

    @Test public void confirmedNeutralRetainedSwitchReusesWithoutFreshLaunch() {
        Fake effects = competing();
        effects.canSwitch = true;
        effects.closeResult = SessionOrchestrator.CloseResult.REUSED;
        orchestrator(effects).play(game());

        assertNull(effects.confirmation);
        assertEquals(HostLaunchPreflight.Action.SWITCH_RETAINED,
                effects.preflightAction);
        assertEquals(Boolean.FALSE, effects.allowDestructiveCloseSeen);
        assertEquals(1, effects.closes);
        assertEquals(1, effects.returned);
        assertEquals(0, effects.launches);
        assertEquals(0, effects.refreshes);
        assertTrue(effects.failures.isEmpty());
    }

    @Test public void preparingObservedGameStillRequiresFreshExactBridgeState() {
        Fake effects = new Fake();
        effects.snapshot = snapshot(SessionSnapshot.State.PREPARING, 42, "old");
        effects.canSwitch = true;
        effects.closeResult = SessionOrchestrator.CloseResult.REUSED;

        orchestrator(effects).play(game());

        assertEquals(1, effects.refreshes);
        assertEquals(HostLaunchPreflight.Action.SWITCH_RETAINED,
                effects.preflightAction);
        assertEquals(1, effects.returned);
    }

    @Test public void retainedNeutralStreamLaunchesManagedGameWithoutClosingTransport() {
        Fake effects = new Fake();
        effects.snapshot = snapshot(SessionSnapshot.State.ACTIVE,
                42, "", true, "", false);
        effects.canSwitch = true;
        effects.closeResult = SessionOrchestrator.CloseResult.REUSED;
        orchestrator(effects).play(game());

        assertNull(effects.confirmation);
        assertEquals(HostLaunchPreflight.Action.SWITCH_RETAINED,
                effects.preflightAction);
        assertEquals(1, effects.returned);
        assertEquals(0, effects.launches);
    }

    @Test public void preparingWarmUpUsesOpaqueRetainedSwitchWithoutFreshLaunch() {
        Fake effects = new Fake();
        effects.snapshot = snapshot(SessionSnapshot.State.PREPARING, 42, "");
        effects.canSwitch = true;
        effects.closeResult = SessionOrchestrator.CloseResult.REUSED;

        orchestrator(effects).play(game());

        assertEquals(1, effects.loadings);
        assertEquals(HostLaunchPreflight.Action.SWITCH_RETAINED,
                effects.preflightAction);
        assertEquals(Boolean.FALSE, effects.allowDestructiveCloseSeen);
        assertEquals(1, effects.closes);
        assertEquals(1, effects.returned);
        assertEquals(0, effects.launches);
    }

    @Test public void detachedRetainedSwitchAttachesPresentationWithoutLaunchOrReturn() {
        Fake effects = new Fake();
        effects.snapshot = snapshot(SessionSnapshot.State.ACTIVE,
                42, "", true, "", false);
        effects.canSwitch = true;
        effects.closeResult = SessionOrchestrator.CloseResult.PRESENTATION_REQUIRED;

        orchestrator(effects).play(game());

        assertEquals(1, effects.attachedForSwitch);
        assertEquals(42, effects.attachedTarget.getAppId());
        assertEquals(1L, effects.attachedRequest);
        assertEquals(0, effects.returned);
        assertEquals(0, effects.launches);
        assertNull(effects.confirmation);
    }

    @Test public void stalePresentationRequiredResultDoesNothing() {
        Fake effects = new Fake();
        effects.snapshot = snapshot(SessionSnapshot.State.ACTIVE,
                42, "", true, "", false);
        effects.canSwitch = true;
        effects.closeResult = SessionOrchestrator.CloseResult.PRESENTATION_REQUIRED;
        effects.deferDispatch = true;
        SessionOrchestrator orchestrator = orchestrator(effects);
        orchestrator.play(game());
        orchestrator.play(game());
        effects.flushDispatch();

        assertEquals(1, effects.attachedForSwitch);
        assertEquals(2L, effects.attachedRequest);
        assertEquals(0, effects.launches);
    }

    @Test public void sharedSunshineAppWithDifferentGameStillRequiresReplacement() {
        Fake effects = new Fake();
        effects.snapshot = snapshot(SessionSnapshot.State.ACTIVE, 42, "other");
        orchestrator(effects).play(game());

        assertNotNull(effects.confirmation);
        assertEquals(0, effects.closes);
        effects.confirmation.run();
        assertEquals(1, effects.closes);
    }

    @Test public void competingTargetAppearingDuringReadinessRequestsConfirmation() {
        Fake effects = new Fake();
        effects.onReadiness = () -> effects.snapshot =
                snapshot(SessionSnapshot.State.ACTIVE, 7, "other");

        orchestrator(effects).play(game());

        assertNotNull(effects.confirmation);
        assertEquals(0, effects.closes);
        assertEquals(0, effects.launches);
    }

    @Test public void cancellationDuringReadinessSuppressesLaterEffects() {
        Fake effects = new Fake();
        SessionOrchestrator orchestrator = orchestrator(effects);
        effects.onReadiness = orchestrator::cancel;

        orchestrator.play(game());

        assertEquals(1, effects.readiness);
        assertEquals(0, effects.launches);
        assertNull(effects.rejection);
    }

    @Test public void cancellationDuringPreviousCloseSuppressesLaunch() {
        Fake effects = competing();
        SessionOrchestrator orchestrator = orchestrator(effects);
        effects.onClose = orchestrator::cancel;
        orchestrator.play(game());

        effects.confirmation.run();

        assertEquals(1, effects.closes);
        assertEquals(0, effects.launches);
    }

    @Test public void cancelledRetainedSwitchDoesNotLaunchReplacement() {
        Fake effects = competing();
        effects.canSwitch = true;
        effects.closeResult = SessionOrchestrator.CloseResult.CANCELLED;
        orchestrator(effects).play(game());

        assertNull(effects.confirmation);
        assertEquals(1, effects.closes);
        assertEquals(0, effects.launches);
        assertEquals(0, effects.returned);
    }

    @Test public void lostReuseEligibilityRequiresConfirmationBeforeDestructiveClose() {
        Fake effects = competing();
        effects.canSwitch = true;
        effects.closeResults.add(SessionOrchestrator.CloseResult.NEEDS_CONFIRMATION);
        effects.closeResults.add(SessionOrchestrator.CloseResult.CLOSED);

        orchestrator(effects).play(game());

        assertNotNull(effects.confirmation);
        assertEquals(1, effects.closes);
        assertEquals(Boolean.FALSE, effects.firstAllowDestructiveCloseSeen);
        assertEquals(1, effects.readiness);
        effects.confirmation.run();
        assertEquals(2, effects.closes);
        assertEquals(Boolean.TRUE, effects.allowDestructiveCloseSeen);
        assertEquals(1, effects.readiness);
        assertEquals(1, effects.launches);
    }

    @Test public void retryRepeatsSameIntentWithANewGeneration() {
        Fake effects = competing();
        SessionOrchestrator orchestrator = orchestrator(effects);
        orchestrator.play(game());
        Runnable staleConfirmation = effects.confirmation;
        effects.snapshot = snapshot(SessionSnapshot.State.NONE, 0, "");

        orchestrator.retry();
        staleConfirmation.run();

        assertEquals(1, effects.readiness);
        assertEquals(1, effects.launches);
        assertEquals(0, effects.closes);
    }

    @Test public void closeFailureDeliversTheCodeOfTheFailedClosePath() {
        for (SessionFailure reason : new SessionFailure[] {
                SessionFailure.CLOSE_HOST_UNAVAILABLE,
                SessionFailure.CLOSE_RETAINED_NOT_CLOSED,
                SessionFailure.CLOSE_RETAINED_TERMINATION_UNAVAILABLE,
                SessionFailure.CLOSE_RETAINED_INTERRUPTED,
                SessionFailure.CLOSE_RETAINED_TIMEOUT,
                SessionFailure.CLOSE_RETAINED_CHANGED,
                SessionFailure.CLOSE_RECONNECT_FALLBACK_NOT_CLOSED,
                SessionFailure.CLOSE_DIRECT_NOT_CLOSED,
                SessionFailure.SWITCH_NOT_ELIGIBLE,
                SessionFailure.SWITCH_INTERRUPTED,
                SessionFailure.SWITCH_TIMEOUT}) {
            Fake effects = competing();
            effects.closeFailure = reason.raise();
            orchestrator(effects).play(game());

            effects.confirmation.run();

            assertEquals(java.util.Collections.singletonList(reason), effects.failures);
            assertEquals(0, effects.launches);
        }
    }

    @Test public void retainedSwitchFailureIsClassifiedOnceFromTheStreamReason() {
        Fake effects = competing();
        effects.canSwitch = true;
        effects.closeFailure = SessionFailure.switchFailed("Gateway: game_stop_timeout");
        orchestrator(effects).play(game());

        assertEquals(java.util.Collections.singletonList(
                SessionFailure.SWITCH_PROVIDER_STOP_TIMEOUT), effects.failures);
        assertEquals(com.limelight.R.string.console_provider_stop_waiting_for_process,
                SessionFailure.SWITCH_PROVIDER_STOP_TIMEOUT.details);
        assertEquals(SessionFailure.SWITCH_REFUSED,
                SessionFailure.switchFailed("retained_switch_not_eligible").reason);
        assertEquals(SessionFailure.SWITCH_FAILED,
                SessionFailure.switchFailed("retained_switch_owner_changed").reason);
        assertEquals("retained_switch_owner_changed",
                SessionFailure.switchFailed("retained_switch_owner_changed").detail);
        assertEquals(SessionFailure.SWITCH_FAILED, SessionFailure.switchFailed("").reason);
    }

    @Test public void unexpectedCloseExceptionHasItsOwnCode() {
        Fake effects = competing();
        effects.closeFailure = new IllegalStateException("game_stop_timeout");
        orchestrator(effects).play(game());

        effects.confirmation.run();

        assertEquals(java.util.Collections.singletonList(
                SessionFailure.CLOSE_UNEXPECTED_ERROR), effects.failures);
    }

    @Test public void verifiedCloseLaunchesBeforeAFreshHostObservationArrives() {
        // UNCERTAIN is what the projection publishes until a poll after the close is accepted.
        Fake effects = competing();
        effects.afterClose = snapshot(SessionSnapshot.State.UNCERTAIN, 0, "");
        orchestrator(effects).play(game());

        effects.confirmation.run();

        assertEquals(1, effects.closes);
        assertEquals(1, effects.launches);
        assertTrue(effects.failures.isEmpty());
    }

    @Test public void closedButLocallyNotIdleSnapshotReportsItsOwnCode() {
        for (SessionSnapshot afterClose : new SessionSnapshot[] {
                snapshot(SessionSnapshot.State.ACTIVE, 7, "other"),
                snapshot(SessionSnapshot.State.SUSPENDED, 7, "other")}) {
            Fake effects = competing();
            effects.afterClose = afterClose;
            orchestrator(effects).play(game());

            effects.confirmation.run();

            assertEquals(1, effects.closes);
            assertEquals(java.util.Collections.singletonList(
                    SessionFailure.CLOSE_SNAPSHOT_NOT_IDLE), effects.failures);
            assertEquals(0, effects.launches);
        }
    }

    @Test public void recoveryRequiredObservationReportsItsOwnCode() {
        Fake effects = new Fake();
        effects.snapshot = snapshot(SessionSnapshot.State.ACTIVE, 7, "other",
                SessionSnapshot.ObservationState.RECOVERY_REQUIRED);

        orchestrator(effects).play(game());

        // Still uncorrelated after its one refresh: refused under the loading layer.
        assertEquals(1, effects.refreshes);
        assertEquals(java.util.Collections.singletonList(
                SessionFailure.UNCERTAIN_RECOVERY_REQUIRED), effects.failures);
        assertEquals(java.util.Collections.singletonList(1), effects.loadingsAtFailure);
    }

    @Test public void staleRecoveryRequiredRefreshesThenReconnects() {
        Fake effects = new Fake();
        // Play pressed in the hole between two host polls: stale evidence names no game.
        effects.snapshot = recovery(SessionSnapshot.State.RECONNECT_REQUIRED, "", false);
        effects.onRefresh = () -> effects.snapshot =
                recovery(SessionSnapshot.State.RECONNECT_REQUIRED, "game", true);

        orchestrator(effects).play(game());

        assertEquals(1, effects.loadings);
        assertEquals(1, effects.refreshes);
        assertEquals(HostLaunchPreflight.Action.RECONNECT, effects.preflightAction);
        assertEquals(1, effects.reconnects);
        assertEquals(0, effects.launches);
        assertTrue(effects.failures.isEmpty());
    }

    @Test public void recoveryEvidenceGoingStaleDuringPreflightIsRefreshedAgain() {
        Fake effects = new Fake();
        effects.snapshot = recovery(SessionSnapshot.State.RECONNECT_REQUIRED, "", false);
        effects.onRefresh = () -> effects.snapshot =
                recovery(SessionSnapshot.State.RECONNECT_REQUIRED, "game", true);
        effects.onReadiness = () -> effects.snapshot =
                recovery(SessionSnapshot.State.RECONNECT_REQUIRED, "", false);

        orchestrator(effects).play(game());

        assertEquals(1, effects.loadings);
        assertEquals(2, effects.refreshes);
        assertEquals(1, effects.readiness);
        assertEquals(1, effects.reconnects);
        assertTrue(effects.failures.isEmpty());
    }

    @Test public void correlatedRecoveryEvidenceNamingAnotherGameFailsVisibly() {
        Fake effects = new Fake();
        effects.snapshot = recovery(SessionSnapshot.State.RECONNECT_REQUIRED, "other", true);

        orchestrator(effects).play(game());

        assertEquals(java.util.Collections.singletonList(
                SessionFailure.UNCERTAIN_RECOVERY_REQUIRED), effects.failures);
        assertEquals(java.util.Collections.singletonList(1), effects.loadingsAtFailure);
        assertEquals(0, effects.refreshes);
        assertEquals(0, effects.reconnects);
        assertEquals(0, effects.launches);
        assertNull(effects.confirmation);
    }

    @Test public void refusedRefreshOfStaleRecoveryFailsVisiblyUnderLoadingLayer() {
        Fake effects = new Fake();
        effects.snapshot = recovery(SessionSnapshot.State.RECONNECT_REQUIRED, "", false);
        // What the host poll answers when it cannot run at all (host offline, poll busy).
        effects.refreshSuccess = false;

        orchestrator(effects).play(game());

        assertEquals(1, effects.refreshes);
        assertEquals(java.util.Collections.singletonList(
                SessionFailure.UNCERTAIN_REFRESH_FAILED), effects.failures);
        assertEquals(java.util.Collections.singletonList(1), effects.loadingsAtFailure);
        assertEquals(0, effects.reconnects);
        assertEquals(0, effects.launches);
    }

    @Test public void terminatingWithRecoveryRequiredRejectsBeforeAnyLoadingLayer() {
        Fake effects = new Fake();
        effects.snapshot = snapshot(SessionSnapshot.State.TERMINATING, 42, "other",
                SessionSnapshot.ObservationState.RECOVERY_REQUIRED);

        orchestrator(effects).play(game());

        assertEquals(SessionOrchestrator.Rejection.TERMINATING, effects.rejection);
        assertTrue(effects.failures.isEmpty());
        assertEquals(0, effects.loadings);
        assertEquals(0, effects.refreshes);
    }

    @Test public void terminatingSeenUnderLoadingLayerFailsVisibly() {
        Fake effects = new Fake();
        effects.snapshot = recovery(SessionSnapshot.State.RECONNECT_REQUIRED, "", false);
        effects.onRefresh = () -> effects.snapshot =
                snapshot(SessionSnapshot.State.TERMINATING, 42, "game");

        orchestrator(effects).play(game());

        assertEquals(java.util.Collections.singletonList(
                SessionFailure.UNCERTAIN_TERMINATING), effects.failures);
        assertEquals(java.util.Collections.singletonList(1), effects.loadingsAtFailure);
        assertNull(effects.rejection);
        assertEquals(0, effects.reconnects);
        assertEquals(0, effects.launches);
    }

    @Test public void retryWhileStillTerminatingRejectsUnderTheRaisedLoadingLayer() {
        Fake effects = new Fake();
        effects.snapshot = recovery(SessionSnapshot.State.RECONNECT_REQUIRED, "", false);
        effects.onRefresh = () -> effects.snapshot =
                snapshot(SessionSnapshot.State.TERMINATING, 42, "game");
        SessionOrchestrator orchestrator = orchestrator(effects);
        orchestrator.play(game());
        assertEquals(java.util.Collections.singletonList(
                SessionFailure.UNCERTAIN_TERMINATING), effects.failures);
        assertNull(effects.rejection);

        // Retry re-enters with the layer still up; the Activity's reject must show it there.
        orchestrator.retry();

        assertEquals(SessionOrchestrator.Rejection.TERMINATING, effects.rejection);
        assertEquals(1, effects.failures.size());
        assertEquals(1, effects.loadings);
        assertEquals(0, effects.reconnects);
        assertEquals(0, effects.launches);
    }

    @Test public void correlatedRecoveryNamingNoGameOnNeutralWarmUpReconnects() {
        Fake effects = new Fake();
        effects.warmUpCarriesGame = true;
        effects.snapshot = recovery(SessionSnapshot.State.RECONNECT_REQUIRED, "", true, true);

        orchestrator(effects).play(game());

        assertEquals(HostLaunchPreflight.Action.RECONNECT, effects.preflightAction);
        assertEquals(1, effects.reconnects);
        assertEquals(0, effects.refreshes);
        assertEquals(0, effects.closes);
        assertEquals(0, effects.launches);
        assertNull(effects.confirmation);
        assertTrue(effects.failures.isEmpty());
    }

    @Test public void correlatedRecoveryNamingNoGameOnNeutralStreamRefreshesThenAsks() {
        Fake effects = new Fake();
        effects.snapshot = recovery(SessionSnapshot.State.RECONNECT_REQUIRED, "", true, true);

        orchestrator(effects).play(game());

        // Recovery evidence naming no game does not prove the host idle.
        assertEquals(1, effects.refreshes);
        assertNotNull(effects.confirmation);
        assertEquals(0, effects.reconnects);
        assertEquals(0, effects.closes);
        assertNull(effects.allowDestructiveCloseSeen);
        assertEquals(0, effects.launches);
        assertTrue(effects.failures.isEmpty());
    }

    @Test public void correlatedRecoveryNamingNoGameOnNonNeutralStreamAsksBeforeReplacement() {
        Fake effects = new Fake();
        effects.snapshot = recovery(SessionSnapshot.State.RECONNECT_REQUIRED, "", true);

        orchestrator(effects).play(game());

        assertNotNull(effects.confirmation);
        assertEquals(0, effects.loadings);
        assertEquals(0, effects.refreshes);
        assertEquals(0, effects.reconnects);
        assertEquals(0, effects.closes);
        assertEquals(0, effects.launches);
        assertTrue(effects.failures.isEmpty());
    }

    @Test public void preparingWarmUpBranchesReportDistinctCodes() {
        Fake ineligible = new Fake();
        ineligible.snapshot = snapshot(SessionSnapshot.State.PREPARING, 42, "");
        orchestrator(ineligible).play(game());
        assertEquals(java.util.Collections.singletonList(
                SessionFailure.UNCERTAIN_PREPARING_NOT_SWITCHABLE), ineligible.failures);

        Fake refreshFailed = new Fake();
        refreshFailed.snapshot = snapshot(SessionSnapshot.State.PREPARING, 42, "old");
        refreshFailed.canSwitch = true;
        refreshFailed.refreshSuccess = false;
        orchestrator(refreshFailed).play(game());
        assertEquals(java.util.Collections.singletonList(
                SessionFailure.UNCERTAIN_PREPARING_REFRESH_FAILED), refreshFailed.failures);

        Fake ineligibleAfterRefresh = new Fake();
        ineligibleAfterRefresh.snapshot = snapshot(SessionSnapshot.State.PREPARING, 42, "old");
        orchestrator(ineligibleAfterRefresh).play(game());
        assertEquals(1, ineligibleAfterRefresh.refreshes);
        assertEquals(java.util.Collections.singletonList(
                SessionFailure.UNCERTAIN_PREPARING_NOT_SWITCHABLE_AFTER_REFRESH),
                ineligibleAfterRefresh.failures);
    }

    @Test public void refreshThatStillCannotResolveReportsItsOwnCode() {
        Fake effects = new Fake();
        effects.snapshot = snapshot(SessionSnapshot.State.UNCERTAIN, 0, "");

        orchestrator(effects).play(game());

        assertEquals(1, effects.refreshes);
        assertEquals(java.util.Collections.singletonList(
                SessionFailure.UNCERTAIN_AFTER_REFRESH), effects.failures);
        assertEquals(0, effects.readiness);
    }

    @Test public void evaluationLoopLimitReportsItsOwnCode() {
        Fake effects = new Fake();
        effects.snapshots.add(snapshot(SessionSnapshot.State.NONE, 0, ""));
        effects.snapshots.add(snapshot(SessionSnapshot.State.NONE, 0, ""));
        for (int pass = 2; pass <= 8; pass++) {
            effects.snapshots.add(snapshot(SessionSnapshot.State.NONE, 0, ""));
            effects.snapshots.add(snapshot(SessionSnapshot.State.ACTIVE, 7, "other"));
        }

        orchestrator(effects).play(PlayIntent.sunshineApp("host", 42, "App", false, ""));

        assertEquals(java.util.Collections.singletonList(
                SessionFailure.ORCHESTRATION_PASS_LIMIT), effects.failures);
        assertEquals(0, effects.launches);
    }

    @Test public void rejectedWorkerReportsItsOwnCode() {
        Fake effects = new Fake();
        SessionOrchestrator orchestrator = new SessionOrchestrator(effects, action -> {
            throw new java.util.concurrent.RejectedExecutionException();
        }, Runnable::run);

        orchestrator.play(PlayIntent.sunshineApp("host", 42, "App", false, ""));

        assertEquals(java.util.Collections.singletonList(
                SessionFailure.ORCHESTRATION_EXECUTOR_REJECTED), effects.failures);
        assertEquals(0, effects.readiness);
    }

    @Test public void reasonCodesAreUniqueStableTokensAndAttachIsNotACloseFailure() {
        java.util.Set<String> codes = new java.util.HashSet<>();
        for (SessionFailure reason : SessionFailure.values()) {
            assertTrue(reason.code, reason.code.matches("[a-z_]+\\.[a-z_]+"));
            assertTrue(reason.code, codes.add(reason.code));
        }
        for (SessionFailure attach : new SessionFailure[] {
                SessionFailure.ATTACH_RESUME_INTENT_MISSING,
                SessionFailure.ATTACH_START_ACTIVITY_FAILED}) {
            assertFalse(attach.details
                    == com.limelight.R.string.console_previous_session_close_failed);
            assertFalse(attach.title == com.limelight.R.string.applist_quit_fail);
        }
        assertEquals("retained_switch_failed",
                SessionFailure.safeDetail("retained_switch_failed"));
        assertEquals("", SessionFailure.safeDetail("Bearer abc123"));
        assertEquals("", SessionFailure.safeDetail("0123456789abcdef"));
    }

    @Test public void acceptingStaleConfirmationDoesNothing() {
        Fake effects = competing();
        SessionOrchestrator orchestrator = orchestrator(effects);
        orchestrator.play(game());
        Runnable stale = effects.confirmation;
        effects.snapshot = snapshot(SessionSnapshot.State.NONE, 0, "");

        orchestrator.play(game());
        stale.run();

        assertEquals(1, effects.launches);
        assertEquals(0, effects.closes);
    }

    @Test public void pairedOfflineRequestUsesReadiness() {
        Fake effects = new Fake();
        effects.paired = true;

        orchestrator(effects).play(PlayIntent.sunshineApp(
                "host", 42, "Quick", false, "quick-key"));

        assertEquals(1, effects.readiness);
        assertEquals(1, effects.launches);
    }

    @Test public void newlyMatchingActiveAfterReadinessUsesGenericConnection() {
        Fake effects = new Fake();
        effects.onReadiness = () -> effects.snapshot =
                snapshot(SessionSnapshot.State.ACTIVE, 42, "game");

        orchestrator(effects).play(game());

        assertEquals(LaunchTransitionType.GENERIC, effects.launchType);
        assertEquals(0, effects.closes);
    }

    @Test public void newlyMatchingSuspendedAfterReadinessRefocusesAndConnects() {
        Fake effects = new Fake();
        effects.onReadiness = () -> effects.snapshot =
                snapshot(SessionSnapshot.State.SUSPENDED, 42, "game");

        orchestrator(effects).play(game());
        assertEquals(LaunchTransitionType.GENERIC, effects.launchType);
    }

    @Test public void newlyMatchingReconnectAfterReadinessUsesSavedIntent() {
        Fake effects = new Fake();
        effects.onReadiness = () -> effects.snapshot =
                snapshot(SessionSnapshot.State.RECONNECT_REQUIRED, 42, "game");

        orchestrator(effects).play(game());

        assertEquals(1, effects.reconnects);
        assertEquals(0, effects.launches);
    }

    @Test public void terminatingRejectsWithoutReconnectCloseOrLaunch() {
        Fake effects = new Fake();
        effects.snapshot = snapshot(SessionSnapshot.State.TERMINATING, 42, "game");

        orchestrator(effects).play(game());

        assertEquals(SessionOrchestrator.Rejection.TERMINATING, effects.rejection);
        assertEquals(0, effects.reconnects);
        assertEquals(0, effects.closes);
        assertEquals(0, effects.launches);
    }

    @Test public void managedUnknownRefreshesOnceUnderOpaqueThenLaunches() {
        Fake effects = new Fake();
        effects.snapshot = snapshot(SessionSnapshot.State.UNCERTAIN, 0, "");
        effects.onRefresh = () -> effects.snapshot =
                snapshot(SessionSnapshot.State.NONE, 0, "");

        orchestrator(effects).play(game());

        assertEquals(1, effects.loadings);
        assertEquals(1, effects.refreshes);
        assertEquals(1, effects.readiness);
        assertEquals(1, effects.launches);
        assertNull(effects.rejection);
    }

    @Test public void failedUnknownRefreshShowsOverlayErrorAndRetryRefreshesAgain() {
        Fake effects = new Fake();
        effects.snapshot = snapshot(SessionSnapshot.State.UNCERTAIN, 0, "");
        effects.refreshSuccess = false;
        SessionOrchestrator orchestrator = orchestrator(effects);

        orchestrator.play(game());
        assertEquals(java.util.Collections.singletonList(
                SessionFailure.UNCERTAIN_REFRESH_FAILED), effects.failures);
        assertEquals(0, effects.readiness);
        assertEquals(0, effects.launches);

        effects.refreshSuccess = true;
        effects.onRefresh = () -> effects.snapshot =
                snapshot(SessionSnapshot.State.NONE, 0, "");
        orchestrator.retry();
        assertEquals(2, effects.refreshes);
        assertEquals(1, effects.launches);
    }

    @Test public void staleUnknownRefreshCannotContinueCancelledAttempt() {
        Fake effects = new Fake();
        effects.snapshot = snapshot(SessionSnapshot.State.UNCERTAIN, 0, "");
        effects.deferRefresh = true;
        SessionOrchestrator orchestrator = orchestrator(effects);
        orchestrator.play(game());
        Consumer<Boolean> stale = effects.deferredRefresh;

        orchestrator.cancel();
        effects.snapshot = snapshot(SessionSnapshot.State.NONE, 0, "");
        stale.accept(true);

        assertEquals(0, effects.readiness);
        assertEquals(0, effects.launches);
        assertTrue(effects.failures.isEmpty());
    }

    @Test public void manualSunshineTargetIsNotBlockedByUnknownBridge() {
        Fake effects = new Fake();
        effects.snapshot = snapshot(SessionSnapshot.State.UNCERTAIN, 42, "");

        orchestrator(effects).play(PlayIntent.sunshineApp(
                "host", 42, "Desktop", false, ""));

        assertEquals(1, effects.readiness);
        assertEquals(1, effects.launches);
        assertEquals(0, effects.refreshes);
        assertNull(effects.rejection);
    }

    @Test public void bridgeGameWithoutStreamConnectsWithoutFreshProviderLaunch() {
        Fake effects = new Fake();
        effects.snapshot = snapshot(SessionSnapshot.State.ACTIVE, 0, "game");

        orchestrator(effects).play(game());

        assertEquals(1, effects.readiness);
        assertEquals(LaunchTransitionType.GENERIC, effects.launchType);
        assertEquals(0, effects.closes);
    }

    @Test public void stalePreflightCompletionCannotLaunchOlderIntent() {
        Fake effects = new Fake();
        Queue<Runnable> callbacks = new ArrayDeque<>();
        SessionOrchestrator orchestrator = new SessionOrchestrator(
                effects, Runnable::run, callbacks::add);

        orchestrator.play(PlayIntent.sunshineApp("host", 42, "Old", false, ""));
        orchestrator.play(PlayIntent.sunshineApp("host", 77, "New", false, ""));
        callbacks.remove().run();
        callbacks.remove().run();

        assertEquals(1, effects.launches);
        assertEquals(77, effects.launchedTarget.getAppId());
    }

    private static void assertFreshType(PlayIntent intent, LaunchTransitionType type, String sourceSuspendId) {
        Fake effects = new Fake();
        orchestrator(effects).play(intent);
        assertEquals(type, effects.launchType);
        assertEquals(sourceSuspendId, effects.launchedSourceSuspendId);
    }

    private static Fake competing() {
        Fake effects = new Fake();
        effects.snapshot = snapshot(SessionSnapshot.State.ACTIVE, 7, "other");
        return effects;
    }

    private static SessionOrchestrator orchestrator(Fake effects) {
        Executor direct = Runnable::run;
        return new SessionOrchestrator(effects, direct, action -> {
            if (effects.deferDispatch) effects.dispatched.add(action);
            else action.run();
        });
    }

    private static PlayIntent game() {
        return PlayIntent.playniteGame("host", 42, "Game", false, "game", "game");
    }

    private static SessionSnapshot snapshot(SessionSnapshot.State state,
                                            int appId, String gameId) {
        return snapshot(state, appId, gameId, false, "", false);
    }

    private static SessionSnapshot snapshot(SessionSnapshot.State state,
                                            int appId, String gameId,
                                            boolean retained, String suspendId,
                                            boolean neutral) {
        return snapshot(state, appId, gameId, retained, suspendId, neutral,
                SessionSnapshot.ObservationState.PENDING);
    }

    private static SessionSnapshot snapshot(SessionSnapshot.State state, int appId,
                                            String gameId,
                                            SessionSnapshot.ObservationState observation) {
        return snapshot(state, appId, gameId, false, "", false, observation);
    }

    private static SessionSnapshot recovery(SessionSnapshot.State state, String gameId,
                                            boolean correlated) {
        return recovery(state, gameId, correlated, false);
    }

    private static SessionSnapshot recovery(SessionSnapshot.State state, String gameId,
                                            boolean correlated, boolean neutral) {
        return snapshot(state, 42, gameId, false, "", neutral,
                SessionSnapshot.ObservationState.RECOVERY_REQUIRED, correlated);
    }

    private static SessionSnapshot snapshot(SessionSnapshot.State state,
                                            int appId, String gameId,
                                            boolean retained, String suspendId,
                                            boolean neutral,
                                            SessionSnapshot.ObservationState observation) {
        return snapshot(state, appId, gameId, retained, suspendId, neutral, observation, false);
    }

    private static SessionSnapshot snapshot(SessionSnapshot.State state,
                                            int appId, String gameId,
                                            boolean retained, String suspendId,
                                            boolean neutral,
                                            SessionSnapshot.ObservationState observation,
                                            boolean correlated) {
        boolean suspended = state == SessionSnapshot.State.SUSPENDED;
        return new SessionSnapshot("host", "default", "default", state, appId, gameId,
                retained, suspended, false, false, false, suspendId, neutral,
                observation, correlated,
                "", 0L, 0L, 0L, 0L, 0L, "", "", "",
                "UNAVAILABLE", 0, "", "UNAVAILABLE", "test", 0);
    }

    private static final class Fake implements SessionOrchestrator.Effects {
        int attachedForSwitch;
        long attachedRequest;
        NvApp attachedTarget;
        boolean deferDispatch;
        final java.util.ArrayDeque<Runnable> dispatched = new java.util.ArrayDeque<>();

        void flushDispatch() {
            Runnable next;
            while ((next = dispatched.poll()) != null) next.run();
        }

        boolean available = true;
        boolean paired = true;
        boolean knownPreparationPair;
        SessionSnapshot snapshot = snapshot(SessionSnapshot.State.NONE, 0, "");
        final Queue<SessionSnapshot> snapshots = new ArrayDeque<>();
        SessionSnapshot afterClose = snapshot(SessionSnapshot.State.NONE, 0, "");
        final java.util.List<SessionFailure> failures = new java.util.ArrayList<>();
        final java.util.List<Integer> loadingsAtFailure = new java.util.ArrayList<>();
        SessionOrchestrator.Rejection rejection;
        Runnable confirmation;
        Runnable onReadiness;
        Runnable onClose;
        Exception closeFailure;
        SessionOrchestrator.CloseResult closeResult =
                SessionOrchestrator.CloseResult.CLOSED;
        final Queue<SessionOrchestrator.CloseResult> closeResults = new ArrayDeque<>();
        HostLaunchPreflight.Action preflightAction;
        boolean canSwitch;
        boolean refreshSuccess = true;
        boolean deferRefresh;
        Runnable onRefresh;
        Consumer<Boolean> deferredRefresh;
        Boolean firstAllowDestructiveCloseSeen;
        Boolean allowDestructiveCloseSeen;
        boolean deferLoading;
        final Queue<Runnable> opaqueCallbacks = new ArrayDeque<>();
        SessionOrchestrator.PreparedWarmUp preparedWarmUp =
                new SessionOrchestrator.PreparedWarmUp(
                        new NvApp("MoonWaker Stream", 77, false), "", null, false);
        BooleanSupplier prepareCancelled = () -> false;
        long prepareRequest;
        int preparedLaunches;
        long preparedLaunchRequest;
        PlayIntent preparedGame;
        int prepareCalls;
        int preparationFailures;

        int returned;
        int reconnects;
        PlayIntent reconnectIntent;
        boolean warmUpCarriesGame;
        int readiness;
        int loadings;
        int refreshes;

        int closes;
        int launches;
        LaunchTransitionType launchType;
        NvApp launchedTarget;
        String launchedSourceSuspendId;
        boolean launchedOwnsFreshSunshineSession;

        @Override public boolean isAvailable() { return available; }
        @Override public boolean isPaired(String hostId) { return paired; }
        @Override public boolean canPrepareHost(String hostId) {
            return paired || knownPreparationPair;
        }
        @Override public SessionSnapshot resolve(String hostId) {
            return snapshots.isEmpty() ? snapshot : snapshots.remove();
        }
        @Override public void returnToRetainedStream() { returned++; }
        @Override public void attachPresentationForSwitch(
                long orchestrationId, PlayIntent intent, NvApp target) {
            attachedForSwitch++;
            attachedRequest = orchestrationId;
            attachedTarget = target;
        }
        @Override public void reconnectSavedSession(PlayIntent intent) {
            reconnects++;
            reconnectIntent = intent;
        }
        @Override public boolean warmUpCarriesGame() { return warmUpCarriesGame; }
        @Override public void reject(SessionOrchestrator.Rejection reason) {
            rejection = reason;
        }
        @Override public void confirmReplacement(Runnable accepted) {
            confirmation = accepted;
        }
        @Override public boolean canAttemptRetainedSwitch(PlayIntent intent) {
            return canSwitch;
        }
        @Override public void showLoading(PlayIntent intent, LaunchTransitionType type,
                                          Runnable opaqueFrameReady) {
            loadings++;
            if (deferLoading) opaqueCallbacks.add(opaqueFrameReady);
            else opaqueFrameReady.run();
        }
        @Override public SessionOrchestrator.PreparedWarmUp prepareHost(
                PlayIntent intent, long request, BooleanSupplier cancelled) {
            prepareCalls++;
            prepareRequest = request;
            prepareCancelled = cancelled;
            return preparedWarmUp;
        }
        @Override public void launchPreparedHost(
                SessionOrchestrator.PreparedWarmUp prepared,
                PlayIntent pendingGame, long request) {
            preparedLaunches++;
            preparedLaunchRequest = request;
            preparedGame = pendingGame;
        }
        @Override public void preparationFailed() { preparationFailures++; }
        @Override public void refreshSession(
                PlayIntent intent, BooleanSupplier cancelled,
                Consumer<Boolean> completion) {
            refreshes++;
            if (onRefresh != null) onRefresh.run();
            if (deferRefresh) deferredRefresh = completion;
            else completion.accept(refreshSuccess);
        }
        @Override public HostLaunchPreflight.Result preflight(
                PlayIntent intent, HostLaunchPreflight.Action action,
                long orchestrationId,
                BooleanSupplier cancelled) {
            readiness++;
            preflightAction = action;
            if (onReadiness != null) onReadiness.run();
            return cancelled.getAsBoolean() ? HostLaunchPreflight.Result.cancelled()
                    : HostLaunchPreflight.Result.ready(
                    new NvApp(intent.appName,
                            intent.sunshineAppId > 0 ? intent.sunshineAppId : 77,
                            intent.hdrSupported),
                    HostLaunchPreflight.TargetResolution.EXISTING);
        }
        @Override public SessionOrchestrator.CloseResult closePreviousSession(
                PlayIntent intent, NvApp target, boolean allowDestructiveClose,
                BooleanSupplier cancelled) throws Exception {
            closes++;
            if (firstAllowDestructiveCloseSeen == null) {
                firstAllowDestructiveCloseSeen = allowDestructiveClose;
            }
            allowDestructiveCloseSeen = allowDestructiveClose;
            if (onClose != null) onClose.run();
            if (closeFailure != null) throw closeFailure;
            SessionOrchestrator.CloseResult result = closeResults.isEmpty()
                    ? closeResult : closeResults.remove();
            if (result == SessionOrchestrator.CloseResult.CLOSED) snapshot = afterClose;
            return result;
        }
        @Override public void launch(PlayIntent intent, NvApp target,
                                     LaunchTransitionType type, String sourceSuspendId,
                                     boolean ownsFreshSunshineSession) {
            launches++;
            launchType = type;
            launchedTarget = target;
            launchedSourceSuspendId = sourceSuspendId;
            launchedOwnsFreshSunshineSession = ownsFreshSunshineSession;
        }
        @Override public void preflightFailed(HostLaunchPreflight.Failure failure) { }
        @Override public void sessionFailed(PlayIntent intent, SessionFailure reason) {
            failures.add(reason);
            loadingsAtFailure.add(loadings);
        }
    }

    private static final class QueuedExecutor implements Executor {
        final Queue<Runnable> actions = new ArrayDeque<>();

        @Override public void execute(Runnable action) { actions.add(action); }
        void runNext() { actions.remove().run(); }
        int size() { return actions.size(); }
    }

    private static final class QueuedDispatcher implements SessionOrchestrator.Dispatcher {
        final Queue<Runnable> actions = new ArrayDeque<>();

        @Override public void post(Runnable action) { actions.add(action); }
        void runNext() { actions.remove().run(); }
        void runAll() { while (!actions.isEmpty()) runNext(); }
    }
}
