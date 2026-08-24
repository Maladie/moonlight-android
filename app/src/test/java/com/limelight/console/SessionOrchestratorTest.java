package com.limelight.console;

import com.limelight.console.transition.LaunchTransitionType;
import com.limelight.nvstream.http.NvApp;

import org.junit.Test;

import java.util.ArrayDeque;
import java.util.Queue;
import java.util.concurrent.Executor;
import java.util.function.BooleanSupplier;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

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

    @Test public void unverifiedSuspensionRejectsEveryTargetWithoutSideEffects() {
        Fake effects = new Fake();
        effects.snapshot = snapshot(SessionSnapshot.State.SUSPENDED_UNVERIFIED, 42, "game");
        orchestrator(effects).play(PlayIntent.playniteGame(
                "host", 77, "Other", false, "other", "other"));
        assertEquals(SessionOrchestrator.Rejection.SUSPENDED_UNVERIFIED, effects.rejection);
        assertEquals(0, effects.readiness); assertEquals(0, effects.launches); assertEquals(0, effects.closes);
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
                false, state == SessionSnapshot.State.SUSPENDED || state == SessionSnapshot.State.SUSPENDED_UNVERIFIED,
                false, false, false);
    }

    private static final class Fake implements SessionOrchestrator.Effects {
        boolean paired = true;
        SessionSnapshot snapshot = snapshot(SessionSnapshot.State.NONE, 0, "");
        SessionOrchestrator.Rejection rejection;
        Runnable confirmation;
        Runnable onReadiness;
        Runnable onClose;

        int returned;
        int reconnects;
        int readiness;

        int closes;
        int launches;
        LaunchTransitionType launchType;
        NvApp launchedTarget;
        String launchedSourceSuspendId;

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
        @Override public void showLoading(PlayIntent intent, LaunchTransitionType type,
                                          Runnable opaqueFrameReady) {
            opaqueFrameReady.run();
        }
        @Override public HostLaunchPreflight.Result preflight(
                PlayIntent intent, HostLaunchPreflight.Action action,
                BooleanSupplier cancelled) {
            readiness++;
            if (onReadiness != null) onReadiness.run();
            return cancelled.getAsBoolean() ? HostLaunchPreflight.Result.cancelled()
                    : HostLaunchPreflight.Result.ready(
                    new NvApp(intent.appName,
                            intent.sunshineAppId > 0 ? intent.sunshineAppId : 77,
                            intent.hdrSupported),
                    HostLaunchPreflight.TargetResolution.EXISTING);
        }
        @Override public boolean closePreviousSession(
                PlayIntent intent, NvApp target, BooleanSupplier cancelled) {
            closes++;
            if (onClose != null) onClose.run();
            snapshot = snapshot(SessionSnapshot.State.NONE, 0, "");
            return true;
        }
        @Override public void launch(PlayIntent intent, NvApp target,
                                     LaunchTransitionType type, String sourceSuspendId) {
            launches++;
            launchType = type;
            launchedTarget = target;
            launchedSourceSuspendId = sourceSuspendId;
        }
        @Override public void preflightFailed(HostLaunchPreflight.Failure failure) { }
        @Override public void orchestrationFailed() { }
        @Override public void previousSessionCloseFailed() { }
    }
}
