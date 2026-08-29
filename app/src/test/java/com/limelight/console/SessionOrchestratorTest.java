package com.limelight.console;

import com.limelight.console.transition.LaunchTransitionType;
import com.limelight.nvstream.http.NvApp;

import org.junit.Test;

import java.util.ArrayDeque;
import java.util.Queue;
import java.util.concurrent.Executor;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class SessionOrchestratorTest {
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
        boolean paired = true;
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

        @Override public boolean isAvailable() { return true; }
        @Override public boolean isPaired(String hostId) { return paired; }
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
            opaqueFrameReady.run();
        }
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
}
