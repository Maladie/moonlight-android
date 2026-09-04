package com.limelight.console;

import com.limelight.console.transition.LaunchTransitionType;
import com.limelight.nvstream.http.NvApp;
import com.limelight.stream.RetainedStreamSessionCoordinator;

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
import static org.junit.Assert.assertTrue;

public class SessionOrchestratorTest {
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
                RetainedStreamSessionCoordinator.State.HOME_LIVE, false,
                com.limelight.nvstream.http.ComputerDetails.State.OFFLINE));
        assertFalse(SessionOrchestrator.shouldSendWarmUpWake(
                RetainedStreamSessionCoordinator.State.PARKED_LIVE, false,
                com.limelight.nvstream.http.ComputerDetails.State.OFFLINE));
        assertFalse(SessionOrchestrator.shouldSendWarmUpWake(
                RetainedStreamSessionCoordinator.State.PREPARING, false,
                com.limelight.nvstream.http.ComputerDetails.State.OFFLINE));
        assertFalse(SessionOrchestrator.shouldSendWarmUpWake(
                RetainedStreamSessionCoordinator.State.TERMINATING, false,
                com.limelight.nvstream.http.ComputerDetails.State.OFFLINE));
        assertFalse(SessionOrchestrator.shouldSendWarmUpWake(
                RetainedStreamSessionCoordinator.State.NONE, true,
                com.limelight.nvstream.http.ComputerDetails.State.OFFLINE));
        assertFalse(SessionOrchestrator.shouldSendWarmUpWake(
                RetainedStreamSessionCoordinator.State.NONE, false,
                com.limelight.nvstream.http.ComputerDetails.State.ONLINE));
        assertFalse(SessionOrchestrator.shouldSendWarmUpWake(
                RetainedStreamSessionCoordinator.State.NONE, false,
                com.limelight.nvstream.http.ComputerDetails.State.UNKNOWN));
        assertTrue(SessionOrchestrator.shouldSendWarmUpWake(
                RetainedStreamSessionCoordinator.State.NONE, false,
                com.limelight.nvstream.http.ComputerDetails.State.OFFLINE));
        assertTrue(SessionOrchestrator.shouldSendWarmUpWake(
                RetainedStreamSessionCoordinator.State.RECONNECT_REQUIRED,
                false, com.limelight.nvstream.http.ComputerDetails.State.OFFLINE));
    }

    @Test public void warmUpWakeDecisionIsPureAndDoesNotOwnSendState() {
        assertTrue(SessionOrchestrator.shouldSendWarmUpWake(
                RetainedStreamSessionCoordinator.State.NONE, false,
                com.limelight.nvstream.http.ComputerDetails.State.OFFLINE));
        assertTrue(SessionOrchestrator.shouldSendWarmUpWake(
                RetainedStreamSessionCoordinator.State.NONE, false,
                com.limelight.nvstream.http.ComputerDetails.State.OFFLINE));
    }

    @Test public void matchingRetainedTargetReturnsWithoutReadinessOrLaunch() {
        Fake effects = new Fake();
        effects.snapshot = new SessionSnapshot("host", SessionSnapshot.State.ACTIVE, 42,
                "game", true, false, false, false, false);

        orchestrator(effects).play(game());

        assertEquals(1, effects.returned);
        assertEquals(0, effects.readiness);
        assertEquals(0, effects.launches);
    }

    @Test public void differentRetainedTargetUsesSafeReplacementPath() {
        Fake effects = new Fake();
        effects.snapshot = new SessionSnapshot("host", SessionSnapshot.State.ACTIVE, 42,
                "other", true, false, false, false, false);

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
        effects.snapshot = new SessionSnapshot("host", SessionSnapshot.State.SUSPENDED,
                42, "game", false, true, false, false, false, "suspend-a");

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
        assertEquals(0, effects.uncertainFailures);
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
        effects.snapshot = new SessionSnapshot("host", SessionSnapshot.State.ACTIVE,
                42, "", true, false, false, false, false);
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

    @Test public void previousCloseFailurePreservesProviderReason() {
        Fake effects = competing();
        effects.closeFailure = new RuntimeException("game_stop_timeout");
        orchestrator(effects).play(game());

        effects.confirmation.run();

        assertEquals("game_stop_timeout", effects.lastCloseFailure);
        assertEquals(0, effects.launches);
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
        assertEquals(1, effects.uncertainFailures);
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
        assertEquals(0, effects.uncertainFailures);
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
        return new SessionOrchestrator(effects, direct, Runnable::run);
    }

    private static PlayIntent game() {
        return PlayIntent.playniteGame("host", 42, "Game", false, "game", "game");
    }

    private static SessionSnapshot snapshot(SessionSnapshot.State state,
                                            int appId, String gameId) {
        return new SessionSnapshot("host", state, appId, gameId,
                false, state == SessionSnapshot.State.SUSPENDED,
                false, false, false);
    }

    private static final class Fake implements SessionOrchestrator.Effects {
        boolean available = true;
        boolean paired = true;
        boolean knownPreparationPair;
        SessionSnapshot snapshot = snapshot(SessionSnapshot.State.NONE, 0, "");
        SessionOrchestrator.Rejection rejection;
        Runnable confirmation;
        Runnable onReadiness;
        Runnable onClose;
        RuntimeException closeFailure;
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
        String lastCloseFailure;
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
        int readiness;
        int loadings;
        int refreshes;
        int uncertainFailures;

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
        @Override public SessionSnapshot resolve(String hostId) { return snapshot; }
        @Override public void returnToRetainedStream() { returned++; }
        @Override public void reconnectSavedSession() { reconnects++; }
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
                BooleanSupplier cancelled) {
            closes++;
            if (firstAllowDestructiveCloseSeen == null) {
                firstAllowDestructiveCloseSeen = allowDestructiveClose;
            }
            allowDestructiveCloseSeen = allowDestructiveClose;
            if (onClose != null) onClose.run();
            if (closeFailure != null) throw closeFailure;
            SessionOrchestrator.CloseResult result = closeResults.isEmpty()
                    ? closeResult : closeResults.remove();
            if (result == SessionOrchestrator.CloseResult.CLOSED) {
                snapshot = snapshot(SessionSnapshot.State.NONE, 0, "");
            }
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
        @Override public void orchestrationFailed() { }
        @Override public void uncertainSessionFailed() { uncertainFailures++; }
        @Override public void previousSessionCloseFailed(String reason) {
            lastCloseFailure = reason;
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
