package com.limelight.console;

import com.limelight.session.SessionModel;
import com.limelight.session.SessionModelDriver;

import com.limelight.console.transition.LaunchTransitionController;
import com.limelight.console.transition.LaunchTransitionSpec;
import com.limelight.console.transition.LaunchTransitionState;
import com.limelight.console.transition.LaunchTransitionType;

import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ConsoleStreamTransitionCoordinatorTest {
    private static final SessionModelDriver m = new SessionModelDriver();
    private static final String HOST = "host-1";
    private static final String GAME = "11111111-2222-3333-4444-555555555555";
    private static final String PROCESS_TOKEN =
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";

    @Before
    public void resetProviderOwnership() throws Exception {
        ConsoleStreamTransitionCoordinator.resetProviderOwnershipForTests();
        resetRetainedStream();
    }

    @Test
    public void noGatewayTimesOutNonGenericTransition() {
        Harness harness = new Harness(LaunchTransitionType.GAME, null);

        harness.coordinator.start();

        assertEquals(LaunchTransitionState.TIMED_OUT, harness.controller.snapshot().state);
        assertEquals("gateway unavailable", harness.controller.snapshot().detail);
    }

    @Test
    public void connectorNotReadyDoesNotAdvanceController() {
        FakeGateway gateway = gatewayWith(snapshot(true, false, "idle", "", 0,
                false, "playnite", 0, ""));
        Harness harness = new Harness(LaunchTransitionType.PLAYNITE, gateway);
        LaunchTransitionState before = harness.controller.snapshot().state;

        harness.runObservation();

        assertEquals(before, harness.controller.snapshot().state);
    }

    @Test
    public void readySnapshotMapsProcessAndWindow() {
        FakeGateway gateway = gatewayWith(snapshot(true, true, "running", GAME, 42,
                true, "game", 0, ""));
        Harness harness = new Harness(LaunchTransitionType.GAME, gateway);

        harness.runObservation();

        assertEquals(LaunchTransitionState.GAME_READY, harness.controller.snapshot().state);
        assertFalse(harness.controller.snapshot().revealAuthorized);
    }

    @Test public void hostGuideDecisionIsDeliveredBeforeReadinessAndTracksProviderChanges() {
        FakeGateway gateway = gatewayWith(
                new PlayniteTransitionGateway.Snapshot(true, true, "running", GAME, 42,
                        true, "game", 4, "", true),
                new PlayniteTransitionGateway.Snapshot(true, true, "running", GAME, 42,
                        true, "game", 4, "", false));
        Harness harness = new Harness(LaunchTransitionType.GAME, gateway);
        harness.callbacks.onGuidePolicy = () -> {
            if (harness.callbacks.guideDecisions.size() == 1) {
                assertFalse(harness.states.contains(LaunchTransitionState.GAME_READY));
            }
        };

        harness.runObservation();

        assertEquals(Arrays.asList("transition-1:" + GAME + ":true",
                "transition-1:" + GAME + ":false"), harness.callbacks.guideDecisions);
    }

    @Test
    public void failedHostLaunchStopsWaitingForReadinessImmediately() {
        FakeGateway gateway = gatewayWith(snapshot(true, true, "failed", GAME, 0,
                false, "game", 0, "legendary_process_failed"));
        Harness harness = new Harness(LaunchTransitionType.GAME, gateway);

        harness.runObservation();

        assertEquals(LaunchTransitionState.ERROR, harness.controller.snapshot().state);
        assertEquals("unconfirmed", harness.controller.snapshot().detail);
    }

    @Test
    public void launcherAttentionIsProviderIndependentAndStopsReadinessTimeout() {
        FakeGateway gateway = gatewayWith(snapshot(true, true, "running", GAME, 42,
                false, "game", 0, "launcher_interaction_required"));
        Harness harness = new Harness(LaunchTransitionType.GAME, gateway);
        makeTransportReady(harness.controller);

        harness.runObservation();

        assertEquals(LaunchTransitionState.LAUNCHER_INTERACTION_REQUIRED,
                harness.controller.snapshot().state);
        assertEquals("reveal launcher", harness.controller.snapshot().detail);
        assertTrue(harness.controller.snapshot().manualRevealAvailable);
    }

    @Test
    public void lockedSessionUsesOneManualPrivacyGateUntilUnlocked() {
        PlayniteTransitionGateway.Snapshot locked = snapshot(true, true, "running", GAME, 42,
                false, "game", 0, "host_session_locked");
        PlayniteTransitionGateway.Snapshot unlocked = snapshot(true, true, "running", GAME, 42,
                false, "game", 0, "target_not_foreground");
        FakeGateway gateway = gatewayWith(locked, locked, unlocked, locked);
        Harness harness = new Harness(LaunchTransitionType.GAME, gateway);
        makeTransportReady(harness.controller);
        gateway.snapshotActions.add(() -> { });
        gateway.snapshotActions.add(() -> {
            harness.controller.videoFrameRendered("transition-1");
            assertTrue(harness.controller.snapshot().manualRevealAvailable);
            harness.controller.showStreamAnyway("transition-1");
            harness.controller.revealCompleted("transition-1");
        });
        gateway.snapshotActions.add(() -> {
            assertEquals(LaunchTransitionState.GAME_RUNNING, harness.controller.snapshot().state);
            assertFalse(harness.controller.snapshot().overlayVisible);
            assertFalse(harness.controller.snapshot().inputBlocked);
        });

        harness.runObservation();

        assertFalse(harness.states.contains(LaunchTransitionState.ERROR));
        assertFalse(harness.states.contains(LaunchTransitionState.TIMED_OUT));
        assertTrue(harness.states.contains(LaunchTransitionState.HOST_SESSION_LOCKED));
        assertTrue(harness.states.contains(LaunchTransitionState.GAME_RUNNING));
        assertFalse(harness.controller.snapshot().overlayVisible);
        assertFalse(harness.controller.snapshot().inputBlocked);
        assertEquals("host locked", harness.controller.snapshot().detail);
    }

    @Test
    public void wrongDisplayRecoversPrivacyWithSpecificReason() {
        FakeGateway gateway = gatewayWith(snapshotReadyGame(),
                snapshot(true, true, "running", GAME, 42,
                        false, "game", 0, "target_on_wrong_display"));
        Harness harness = new Harness(LaunchTransitionType.GAME, gateway);
        makeTransportReady(harness.controller);
        gateway.snapshotActions.add(() -> { });
        gateway.snapshotActions.add(() -> {
            harness.controller.videoFrameRendered("transition-1");
            harness.controller.revealCompleted("transition-1");
        });

        harness.runObservation();

        assertTrue(harness.controller.snapshot().overlayVisible);
        assertTrue(harness.controller.snapshot().inputBlocked);
        assertEquals("wrong display", harness.controller.snapshot().detail);
    }

    @Test
    public void lockedSessionSkipsPlayniteFullscreenAndReadinessTimeout() {
        PlayniteTransitionGateway.Snapshot locked = snapshot(true, false, "idle", "", 0,
                false, "playnite", 0, "host_session_locked");
        FakeGateway gateway = gatewayWith(locked, locked);
        FakeClock clock = new FakeClock();
        gateway.snapshotActions.add(() -> clock.value = 0L);
        gateway.snapshotActions.add(() -> clock.value = 31_000L);
        Harness harness = new Harness(LaunchTransitionType.PLAYNITE, gateway, clock,
                new InterruptingSleeper());

        harness.runObservation();

        assertEquals(0, gateway.fullscreenCalls);
        assertFalse(harness.states.contains(LaunchTransitionState.TIMED_OUT));
        assertFalse(harness.states.contains(LaunchTransitionState.ERROR));
        assertTrue(harness.controller.snapshot().overlayVisible);
        assertTrue(harness.controller.snapshot().inputBlocked);
    }

    @Test
    public void lockedProviderStartOffersManualRevealWithoutCompensatingStop() {
        LaunchTransitionSpec providerSpec = providerSpec("locked-start", "steam:289070");
        LaunchTransitionController controller = providerController(providerSpec);
        FakeGateway gateway = stoppingObservationGateway();
        gateway.startError = new IOException("host_session_locked");
        ConsoleStreamTransitionCoordinator coordinator = new ConsoleStreamTransitionCoordinator(
                providerSpec, controller, gateway, new InlineExecutor(), new FakeClock(),
                new InterruptingSleeper(), (action, delay) -> { }, new FakeCallbacks());

        coordinator.start();
        Thread.interrupted();
        controller.surfaceReady(providerSpec.id);
        controller.inputPipelineReady(providerSpec.id);
        controller.streamConnected(providerSpec.id);
        controller.videoFrameRendered(providerSpec.id);

        assertEquals("host locked", controller.snapshot().detail);
        assertEquals(LaunchTransitionState.HOST_SESSION_LOCKED,
                controller.snapshot().state);
        assertTrue(controller.snapshot().manualRevealAvailable);
        assertEquals(1, gateway.startGameCalls);
        coordinator.cancel();
        assertEquals(0, gateway.stopGameCalls);
    }

    @Test
    public void lockedSessionRequestsOneHostUnlockBeforeTheManualGate() {
        PlayniteTransitionGateway.Snapshot locked = snapshot(true, true, "running", GAME, 42,
                false, "game", 0, "host_session_locked");
        FakeGateway gateway = gatewayWith(locked, locked, locked);
        Harness harness = new Harness(LaunchTransitionType.GAME, gateway);
        gateway.onUnlock = () -> assertFalse(
                harness.states.contains(LaunchTransitionState.HOST_SESSION_LOCKED));

        harness.runObservation();

        assertEquals(1, gateway.unlockCalls);
        assertEquals(LaunchTransitionState.HOST_SESSION_LOCKED,
                harness.controller.snapshot().state);
        assertTrue(harness.controller.snapshot().overlayVisible);
        assertTrue(harness.controller.snapshot().inputBlocked);
    }

    @Test
    public void confirmedHostUnlockReReadsTheHostAndALaterLockUsesTheManualGate() {
        PlayniteTransitionGateway.Snapshot locked = snapshot(true, true, "running", GAME, 42,
                false, "game", 0, "host_session_locked");
        FakeGateway gateway = gatewayWith(locked, snapshotReadyGame(), locked);
        gateway.unlockResult = true;
        Harness harness = new Harness(LaunchTransitionType.GAME, gateway);
        gateway.snapshotActions.add(() -> { });
        gateway.snapshotActions.add(() -> {
            assertFalse(harness.states.contains(LaunchTransitionState.HOST_SESSION_LOCKED));
            // The privacy gate still waits for its own fresh frame.
            assertTrue(harness.controller.snapshot().overlayVisible);
        });
        gateway.snapshotActions.add(() ->
                assertEquals(LaunchTransitionState.GAME_READY,
                        harness.controller.snapshot().state));

        harness.runObservation();

        assertEquals(1, gateway.unlockCalls);
        assertEquals(LaunchTransitionState.HOST_SESSION_LOCKED,
                harness.controller.snapshot().state);
    }

    @Test
    public void hostUnlockStoppedTransitionCancelsPollingAndIgnoresTheResult() {
        PlayniteTransitionGateway.Snapshot locked = snapshot(true, true, "running", GAME, 42,
                false, "game", 0, "host_session_locked");
        FakeGateway gateway = gatewayWith(locked, snapshotReadyGame());
        gateway.unlockResult = true;
        Harness harness = new Harness(LaunchTransitionType.GAME, gateway);
        gateway.onUnlock = () -> {
            assertFalse(gateway.unlockCancelled.getAsBoolean());
            harness.coordinator.stop();
            assertTrue(gateway.unlockCancelled.getAsBoolean());
        };

        harness.runObservation();

        assertEquals(1, gateway.unlockCalls);
        assertEquals(1, gateway.snapshotIndex);
        assertFalse(harness.states.contains(LaunchTransitionState.HOST_SESSION_LOCKED));
        assertFalse(harness.states.contains(LaunchTransitionState.GAME_READY));
    }

    @Test
    public void stabilizingAndLostWindowKeepPrivacyGateClosed() {
        FakeGateway stabilizing = gatewayWith(snapshot(true, true, "running", GAME, 42,
                false, "game", 1, "stabilizing_target_window"));
        Harness first = new Harness(LaunchTransitionType.GAME, stabilizing);
        first.runObservation();
        assertEquals(LaunchTransitionState.GAME_WINDOW_STABILIZING,
                first.controller.snapshot().state);
        assertTrue(first.controller.snapshot().overlayVisible);

        FakeGateway lost = gatewayWith(snapshot(true, true, "running", GAME, 0,
                false, "game", 0, "target_not_foreground"));
        Harness second = new Harness(LaunchTransitionType.GAME, lost);
        makeGameRunning(second.controller);
        second.runObservation();
        assertEquals(LaunchTransitionState.GAME_WINDOW_STABILIZING,
                second.controller.snapshot().state);
        assertTrue(second.controller.snapshot().inputBlocked);
        assertEquals("window not active", second.controller.snapshot().detail);
    }

    @Test
    public void runningGameReportsTheSpecificReadinessBlocker() {
        Harness identity = new Harness(LaunchTransitionType.GAME,
                gatewayWith(snapshot(true, true, "running", GAME, 0,
                        false, "game", 0, "waiting_for_game_identity")));
        identity.runObservation();
        assertEquals(LaunchTransitionState.CONNECTING_STREAM,
                identity.controller.snapshot().state);
        assertTrue(identity.controller.snapshot().inputBlocked);

        Harness fullscreen = new Harness(LaunchTransitionType.GAME,
                gatewayWith(snapshot(true, true, "running", GAME, 42,
                        false, "game", 0, "target_not_fullscreen")));
        fullscreen.runObservation();
        assertEquals("window not fullscreen", fullscreen.controller.snapshot().detail);
    }

    @Test
    public void fullscreenRequestIsNotRepeatedAfterSuccess() {
        PlayniteTransitionGateway.Snapshot unready = snapshot(true, true, "idle", "", 0,
                false, "playnite", 0, "");
        FakeGateway gateway = gatewayWith(unready, unready);
        Harness harness = new Harness(LaunchTransitionType.PLAYNITE, gateway);

        harness.runObservation();

        assertEquals(1, gateway.fullscreenCalls);
    }

    @Test
    public void gameFocusIsLimitedAndSpacedByThreeSeconds() {
        PlayniteTransitionGateway.Snapshot background = snapshot(true, true, "running", GAME,
                42, false, "game", 0, "target_not_foreground");
        FakeGateway gateway = gatewayWith(background, background, background, background,
                background);
        FakeClock clock = new FakeClock();
        gateway.snapshotAction = () -> clock.value += 3_000L;
        gateway.focusGameError = true;
        Harness harness = new Harness(LaunchTransitionType.GAME, gateway, clock,
                new InterruptingSleeper());

        harness.runObservation();

        assertEquals(3, gateway.focusGameCalls);
        assertEquals(Arrays.asList(3_000L, 6_000L, 9_000L), gateway.focusTimes);
    }

    @Test
    public void runningGameFallbackReportsStopAndEndsProviderSession() {
        FakeGateway gateway = gatewayWith(
                snapshot(true, true, "running", GAME, 42, true, "game", 0, ""),
                snapshot(true, true, "idle", "", 0, true, "playnite", 0, ""));
        Harness harness = new Harness(LaunchTransitionType.GAME, gateway);

        harness.runObservation();

        assertEquals(LaunchTransitionState.GAME_STOPPING,
                harness.controller.snapshot().state);
        assertEquals(1, harness.callbacks.providerStopCalls);
    }

    @Test
    public void staleRunningSignalAfterStopCannotNotifyGameStopTwice() {
        FakeGateway gateway = gatewayWith(
                snapshotReadyGame(),
                snapshotReadyGame(),
                snapshot(true, true, "idle", "", 0, true, "playnite", 0, ""));
        gateway.events.add(events(1));
        gateway.events.add(events(3,
                event(2, "game-stopped", GAME, "Game"),
                event(3, "game-running", GAME, "Game")));
        Harness harness = new Harness(LaunchTransitionType.GAME, gateway);

        harness.runObservation();

        assertEquals(1, harness.callbacks.providerStopCalls);
        assertEquals(LaunchTransitionState.GAME_STOPPING,
                harness.controller.snapshot().state);
    }

    @Test
    public void firstBatchIgnoresStaleTransitionEventsAndAdvancesCursor() {
        FakeGateway gateway = gatewayWith(snapshotReadyGame(), snapshotReadyGame());
        gateway.events.add(events(7, event(7, "game-starting", GAME, "Game")));
        gateway.events.add(events(11));
        Harness harness = new Harness(LaunchTransitionType.GAME, gateway);

        harness.runObservation();

        assertFalse(gateway.awaitAfter.isEmpty());
        assertEquals(Arrays.asList(0L, 7L), gateway.awaitAfter);
        assertFalse(harness.states.contains(LaunchTransitionState.GAME_STARTING));
    }

    @Test
    public void firstBatchProcessesOnlyPendingTerminalInstallationEvents() {
        FakeGateway gateway = gatewayWith(snapshotReadyPlaynite());
        gateway.events.add(events(3,
                event(1, "game-installed", "installed", "Installed"),
                event(2, "game-installation-cancelled", "cancelled", "Cancelled"),
                event(3, "game-installation-failed", "failed", "Failed"),
                event(4, "game-installation-attention-required", "attention", "Attention")));
        Harness harness = new Harness(LaunchTransitionType.PLAYNITE, gateway);
        harness.callbacks.pending.addAll(Arrays.asList("installed", "cancelled", "failed",
                "attention"));

        harness.runObservation();

        assertEquals(Collections.singletonList("installed"), harness.callbacks.completed);
        assertEquals(Collections.singletonList("cancelled"), harness.callbacks.cancelled);
        assertEquals(Collections.singletonList("failed"), harness.callbacks.failed);
        assertTrue(harness.callbacks.attention.isEmpty());
        assertEquals(1, gateway.ensureTargetCalls);
    }

    @Test
    public void terminalInstallationFailureWithoutPendingMarkerIsIgnored() {
        FakeGateway gateway = gatewayWith(snapshotReadyPlaynite());
        gateway.events.add(events(1,
                event(1, "game-installation-failed", "failed", "Failed")));
        Harness harness = new Harness(LaunchTransitionType.PLAYNITE, gateway);

        harness.runObservation();

        assertTrue(harness.callbacks.failed.isEmpty());
    }

    @Test
    public void subsequentEventsCannotProveReadinessOrStopping() {
        FakeGateway gateway = gatewayWith(snapshotReadyGame(), snapshotReadyGame());
        gateway.events.add(events(1));
        gateway.events.add(events(8,
                event(2, "game-starting", GAME, "Game"),
                event(3, "game-running", GAME, "Game"),
                event(4, "privacy-gate-closed", GAME, "Game"),
                event(5, "game-stopping", GAME, "Game"),
                event(6, "game-stopped", "steam:other", "Other"),
                event(7, "game-stopped", GAME, "Game"),
                event(8, "bridge-disconnected", GAME, "Game")));
        Harness harness = new Harness(LaunchTransitionType.GAME, gateway);

        harness.runObservation();

        assertEquals(0, harness.callbacks.providerStopCalls);
        assertEquals(LaunchTransitionState.GAME_READY,
                harness.controller.snapshot().state);
    }

    @Test
    public void failedLaunchCleanupDoesNotCoverAnExplicitlyRevealedLauncher() {
        PlayniteTransitionGateway.Snapshot launcher = snapshot(
                true, true, "starting", GAME, 0,
                false, "game", 0, "launcher_interaction_required");
        FakeGateway gateway = gatewayWith(launcher, launcher);
        gateway.events.add(events(1));
        gateway.events.add(events(3,
                event(2, "game-stopping", GAME, "Game"),
                event(3, "game-stopped", GAME, "Game")));
        Harness harness = new Harness(LaunchTransitionType.GAME, gateway);
        makeTransportReady(harness.controller);
        gateway.snapshotActions.add(() -> { });
        gateway.snapshotActions.add(() -> {
            harness.controller.showStreamAnyway("transition-1");
            harness.controller.revealCompleted("transition-1");
        });

        harness.runObservation();

        assertFalse(harness.controller.snapshot().overlayVisible);
        assertFalse(harness.controller.snapshot().inputBlocked);
        assertEquals(0, harness.callbacks.providerStopCalls);
    }

    @Test
    public void timeoutDurationsRemainExact() {
        assertEquals(90_000L, timeout(LaunchTransitionState.PREPARING_SESSION));
        assertEquals(60_000L, timeout(LaunchTransitionState.CONNECTING_STREAM));
        assertEquals(15_000L, timeout(LaunchTransitionState.WAITING_FOR_VIDEO_SURFACE));
        assertEquals(45_000L, timeout(LaunchTransitionState.PLAYNITE_STARTING));
        assertEquals(45_000L, timeout(LaunchTransitionState.PLAYNITE_PROCESS_RUNNING));
        assertEquals(30_000L, timeout(LaunchTransitionState.PLAYNITE_FULLSCREEN_STARTING));
        assertEquals(120_000L, timeout(LaunchTransitionState.GAME_START_REQUESTED));
        assertEquals(120_000L, timeout(LaunchTransitionState.GAME_STARTING));
        assertEquals(120_000L, timeout(LaunchTransitionState.GAME_PROCESS_RUNNING));
        assertEquals(30_000L, timeout(LaunchTransitionState.GAME_WINDOW_STABILIZING));
        assertEquals(0L, timeout(LaunchTransitionState.HOST_SESSION_LOCKED));
        assertEquals(45_000L, timeout(LaunchTransitionState.PLAYNITE_RETURNING));
        assertEquals(15_000L, timeout(LaunchTransitionState.PLAYNITE_STOPPING));
        assertEquals(15_000L, timeout(LaunchTransitionState.CLOSING_STREAM));
        assertEquals(0L, timeout(LaunchTransitionState.GAME_READY));
    }

    @Test
    public void timeoutRestartsWhenObservedControllerStateChanges() {
        FakeClock clock = new FakeClock();
        List<Long> timeoutTimes = new ArrayList<>();
        LaunchTransitionController controller = new LaunchTransitionController(snapshot -> {
            if (snapshot.state == LaunchTransitionState.TIMED_OUT) {
                timeoutTimes.add(clock.value);
            }
        });
        controller.begin(spec(LaunchTransitionType.PLAYNITE));
        controller.overlayRendered("transition-1");
        FakeGateway gateway = gatewayWith(
                snapshot(true, false, "idle", "", 0, false, "playnite", 0, ""),
                snapshot(true, false, "idle", "", 0, false, "playnite", 0, ""),
                snapshot(true, false, "idle", "", 0, false, "playnite", 0, ""),
                snapshot(true, false, "idle", "", 0, false, "playnite", 0, ""),
                snapshot(true, false, "idle", "", 0, false, "playnite", 0, ""));
        gateway.snapshotActions.add(() -> clock.value = 0L);
        gateway.snapshotActions.add(() -> clock.value = 29_999L);
        gateway.snapshotActions.add(() -> {
            clock.value = 30_000L;
            controller.surfaceReady("transition-1");
        });
        gateway.snapshotActions.add(() -> clock.value = 44_999L);
        gateway.snapshotActions.add(() -> clock.value = 45_000L);
        ConsoleStreamTransitionCoordinator coordinator = new ConsoleStreamTransitionCoordinator(
                spec(LaunchTransitionType.PLAYNITE), controller, gateway,
                new InlineExecutor(), clock, new InterruptingSleeper(),
                (action, delay) -> { }, new FakeCallbacks());

        coordinator.start();
        Thread.interrupted();

        assertEquals(Collections.singletonList(45_000L), timeoutTimes);
    }

    @Test
    public void successfulIterationResetsFailureCount() {
        FakeGateway gateway = new FakeGateway();
        gateway.snapshots.add(new IOException("one"));
        gateway.snapshots.add(snapshotReadyPlaynite());
        gateway.snapshots.add(new IOException("one again"));
        gateway.snapshots.add(new IOException("two"));
        gateway.snapshots.add(new IOException("three"));
        ResetSleeper sleeper = new ResetSleeper();
        Harness harness = new Harness(LaunchTransitionType.PLAYNITE, gateway,
                new FakeClock(), sleeper);

        harness.runObservation();

        assertEquals(Arrays.asList(1_000L, 1_000L, 2_000L), sleeper.delays);
        assertEquals(LaunchTransitionState.TIMED_OUT, harness.controller.snapshot().state);
    }

    @Test
    public void temporaryObservationFailureDoesNotStopAnAcceptedGame() {
        LaunchTransitionSpec spec = providerSpec("slow-provider", "steam:289070");
        LaunchTransitionController controller = providerController(spec);
        FakeGateway gateway = new FakeGateway();
        gateway.snapshots.add(new IOException("one"));
        gateway.snapshots.add(new IOException("two"));
        gateway.snapshots.add(new IOException("three"));
        ConsoleStreamTransitionCoordinator coordinator = new ConsoleStreamTransitionCoordinator(
                spec, controller, gateway, new InlineExecutor(), new FakeClock(),
                new ResetSleeper(), (action, delay) -> { }, new FakeCallbacks());

        coordinator.start();
        Thread.interrupted();

        assertEquals(1, gateway.startGameCalls);
        assertEquals(0, gateway.stopGameCalls);
        assertEquals(LaunchTransitionState.TIMED_OUT, controller.snapshot().state);
        assertTrue(controller.snapshot().uncertain);
    }

    @Test
    public void stopSuppressesResultFromBlockingSnapshot() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ConsoleStreamTransitionCoordinator.Gateway gateway = new FakeGateway() {
            @Override public PlayniteTransitionGateway.Snapshot snapshot() {
                entered.countDown();
                boolean done = false;
                while (!done) {
                    try {
                        done = release.await(1, TimeUnit.SECONDS);
                    } catch (InterruptedException ignored) { }
                }
                return ConsoleStreamTransitionCoordinatorTest.snapshot(
                        true, true, "running", GAME, 42, true, "game", 0, "");
            }
        };
        ExecutorService executor = Executors.newSingleThreadExecutor();
        LaunchTransitionController controller = started(LaunchTransitionType.GAME_CONNECTION, null);
        ConsoleStreamTransitionCoordinator coordinator = new ConsoleStreamTransitionCoordinator(
                spec(LaunchTransitionType.GAME_CONNECTION), controller, gateway, executor,
                new FakeClock(), new InterruptingSleeper(), (action, delay) -> { },
                new FakeCallbacks());
        LaunchTransitionState before = controller.snapshot().state;

        coordinator.start();
        assertTrue(entered.await(2, TimeUnit.SECONDS));
        coordinator.stop();
        release.countDown();
        coordinator.close();
        assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS));

        assertEquals(before, controller.snapshot().state);
    }

    @Test
    public void stopLetsReadOnlyEventResponseFinishWithoutInterruptingIt() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicBoolean interrupted = new AtomicBoolean();
        ConsoleStreamTransitionCoordinator.Gateway gateway = new FakeGateway() {
            @Override public PlayniteTransitionGateway.Snapshot snapshot() {
                return ConsoleStreamTransitionCoordinatorTest.snapshot(
                        true, true, "running", GAME, 42, true, "game", 0, "");
            }
            @Override public PlayniteTransitionGateway.Events awaitEvents(
                    long after, String transitionId) {
                entered.countDown();
                try {
                    release.await(2, TimeUnit.SECONDS);
                } catch (InterruptedException error) {
                    interrupted.set(true);
                }
                return events(after);
            }
        };
        ExecutorService executor = Executors.newSingleThreadExecutor();
        ConsoleStreamTransitionCoordinator coordinator = new ConsoleStreamTransitionCoordinator(
                spec(LaunchTransitionType.GAME_CONNECTION),
                started(LaunchTransitionType.GAME_CONNECTION, null), gateway, executor,
                new FakeClock(), new InterruptingSleeper(), (action, delay) -> { },
                new FakeCallbacks());

        coordinator.start();
        assertTrue(entered.await(2, TimeUnit.SECONDS));
        coordinator.stop();
        coordinator.close();
        release.countDown();
        assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS));
        assertFalse(interrupted.get());
    }

    @Test
    public void installationConfirmationSchedulesExactFocusAttemptsAndStopsStaleOnes() {
        FakeGateway gateway = new FakeGateway();
        QueuedScheduler scheduler = new QueuedScheduler();
        Harness harness = new Harness(LaunchTransitionType.GENERIC, gateway,
                new FakeClock(), new InterruptingSleeper(), scheduler);
        harness.coordinator.start();

        harness.coordinator.onStreamConnected();

        assertEquals(Arrays.asList(0L, 1_500L, 4_000L), scheduler.delays);
        scheduler.actions.get(0).run();
        harness.coordinator.stop();
        scheduler.actions.get(1).run();
        scheduler.actions.get(2).run();
        assertEquals(1, gateway.focusInstallationCalls);
    }

    @Test
    public void existingGameConnectionObservesPrivacyWithoutRestartingGame() {
        FakeGateway gateway = gatewayWith(snapshot(true, true, "running", GAME, 42,
                false, "game", 0, "target_on_wrong_display"));
        QueuedScheduler scheduler = new QueuedScheduler();
        Harness harness = new Harness(LaunchTransitionType.GAME_CONNECTION, gateway,
                new FakeClock(), new InterruptingSleeper(), scheduler);

        harness.coordinator.start();
        harness.coordinator.onStreamConnected();

        assertFalse(harness.coordinator.isInstallationConfirmationStream());
        assertEquals(0, gateway.startGameCalls);
        assertEquals(0, gateway.focusInstallationCalls);
        assertTrue(scheduler.actions.isEmpty());
        assertTrue(harness.controller.snapshot().overlayVisible);
        assertEquals("wrong display", harness.controller.snapshot().detail);
    }

    @Test
    public void declaredProviderCapabilityStartsExactlyOnceBeforeStreamConnect() {
        LaunchTransitionSpec providerSpec = new LaunchTransitionSpec(
                "transition-provider", HOST, LaunchTransitionType.GAME, 42,
                "gog:Some_Game", 1_000L, true);
        LaunchTransitionController controller = new LaunchTransitionController(null);
        controller.begin(providerSpec);
        controller.overlayRendered(providerSpec.id);
        FakeGateway gateway = new FakeGateway();
        gateway.snapshots.add(new IOException("stop observation"));
        ConsoleStreamTransitionCoordinator coordinator =
                new ConsoleStreamTransitionCoordinator(
                        providerSpec, controller, gateway, new InlineExecutor(),
                        new FakeClock(), new InterruptingSleeper(), (action, delay) -> { },
                        new FakeCallbacks());
        coordinator.start();
        Thread.interrupted();

        assertEquals(1, gateway.startGameCalls);
        coordinator.onStreamConnected();
        coordinator.onStreamConnected();

        assertEquals(1, gateway.startGameCalls);
        assertEquals("gog:Some_Game", gateway.startedGameId);
    }

    @Test public void earlyProviderEffectWaitsForTransportControlPermit() {
        LaunchTransitionSpec providerSpec = new LaunchTransitionSpec(
                "transition-fenced", HOST, LaunchTransitionType.GAME, 42,
                "gog:Some_Game", 1_000L, true);
        LaunchTransitionController controller = new LaunchTransitionController(null);
        controller.begin(providerSpec);
        controller.overlayRendered(providerSpec.id);
        FakeGateway gateway = new FakeGateway();
        FakeCallbacks callbacks = new FakeCallbacks();
        callbacks.controlAuthorized = false;
        ConsoleStreamTransitionCoordinator coordinator =
                new ConsoleStreamTransitionCoordinator(providerSpec, controller, gateway,
                        new InlineExecutor(), new FakeClock(), new InterruptingSleeper(),
                        (action, delay) -> { }, callbacks);

        coordinator.start();
        assertEquals(0, gateway.startGameCalls);
        callbacks.controlAuthorized = true;
        coordinator.onStreamConnected();
        Thread.interrupted();
        assertEquals(1, gateway.startGameCalls);
    }

    @Test
    public void providerLaunchWaitsForControllerTransportWithoutOpeningPrivacyGate() {
        for (String gameId : Arrays.asList("epic:Cowbird", "playnite:" + GAME, GAME)) {
            ConsoleStreamTransitionCoordinator.resetProviderOwnershipForTests();
            LaunchTransitionSpec target = providerSpec("cold-" + gameId, gameId, false);
            LaunchTransitionController controller = providerController(target);
            FakeGateway gateway = stoppingObservationGateway();
            ConsoleStreamTransitionCoordinator coordinator = new ConsoleStreamTransitionCoordinator(
                    target, controller, gateway, new InlineExecutor(), new FakeClock(),
                    new InterruptingSleeper(), (action, delay) -> { }, new FakeCallbacks());

            coordinator.start();
            assertEquals(0, gateway.startGameCalls);
            assertTrue(controller.snapshot().inputBlocked);
            coordinator.onStreamConnected();
            Thread.interrupted();
            coordinator.onStreamConnected();

            assertEquals(1, gateway.startGameCalls);
            assertEquals(gameId, gateway.startedGameId);
            assertTrue(controller.snapshot().inputBlocked);
            assertFalse(controller.snapshot().revealAuthorized);
            coordinator.close();
        }
    }

    @Test
    public void cancelledColdNonSteamLaunchCannotStartOnLateConnection() {
        LaunchTransitionSpec target = providerSpec(
                "cold-cancel", "epic:Cowbird", false);
        FakeGateway gateway = stoppingObservationGateway();
        ConsoleStreamTransitionCoordinator coordinator = new ConsoleStreamTransitionCoordinator(
                target, providerController(target), gateway, new InlineExecutor(), new FakeClock(),
                new InterruptingSleeper(), (action, delay) -> { }, new FakeCallbacks());

        coordinator.start();
        coordinator.cancel();
        coordinator.onStreamConnected();

        assertEquals(0, gateway.startGameCalls);
        assertEquals(0, gateway.stopGameCalls);
        coordinator.close();
    }

    @Test
    public void cancelledProviderStartWithoutObservedIdentityCannotStopUnknownProcess()
            throws Exception {
        LaunchTransitionSpec providerSpec = new LaunchTransitionSpec(
                "transition-provider", HOST, LaunchTransitionType.GAME, 42,
                "steam:289070", 1_000L, true);
        LaunchTransitionController controller = new LaunchTransitionController(null);
        controller.begin(providerSpec);
        controller.overlayRendered(providerSpec.id);
        CountDownLatch startEntered = new CountDownLatch(1);
        CountDownLatch releaseStart = new CountDownLatch(1);
        CountDownLatch cleanupCalled = new CountDownLatch(1);
        FakeGateway gateway = new FakeGateway() {
            @Override public PlayniteTransitionGateway.Snapshot snapshot()
                    throws IOException {
                if (releaseStart.getCount() != 0) throw new IOException("start pending");
                return new PlayniteTransitionGateway.Snapshot(
                        true, true, "running", "steam:289070", 42,
                        true, "game", 1, "", false, PROCESS_TOKEN);
            }

            @Override public void startGame(String gameId) throws IOException {
                super.startGame(gameId);
                startEntered.countDown();
                try {
                    releaseStart.await(2, TimeUnit.SECONDS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            }

        };
        ExecutorService executor = Executors.newSingleThreadExecutor();
        ExecutorService providerExecutor = Executors.newSingleThreadExecutor();
        FakeCallbacks callbacks = new FakeCallbacks() {
            @Override public void onProviderGameCleanupComplete(
                    String transitionId, String gameId, boolean success) {
                super.onProviderGameCleanupComplete(transitionId, gameId, success);
                cleanupCalled.countDown();
            }
        };
        ConsoleStreamTransitionCoordinator coordinator = new ConsoleStreamTransitionCoordinator(
                providerSpec, controller, gateway, executor, providerExecutor,
                new FakeClock(), 0L, new InterruptingSleeper(),
                (action, delay) -> { }, callbacks);

        coordinator.start();
        assertTrue(startEntered.await(2, TimeUnit.SECONDS));
        coordinator.cancel();
        assertFalse(cleanupCalled.await(100, TimeUnit.MILLISECONDS));
        releaseStart.countDown();

        assertTrue(cleanupCalled.await(2, TimeUnit.SECONDS));
        assertEquals(1, gateway.startGameCalls);
        assertEquals(0, gateway.stopGameCalls);
        assertFalse(callbacks.lastCleanupSuccess);
        assertEquals(0, callbacks.providerStartAcceptedCalls);
        coordinator.close();
        providerExecutor.shutdownNow();
        assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS));
        assertTrue(providerExecutor.awaitTermination(2, TimeUnit.SECONDS));
    }

    @Test
    public void stoppedProviderStartFailureCannotMutateOldTransition() throws Exception {
        LaunchTransitionSpec providerSpec = new LaunchTransitionSpec(
                "transition-provider", HOST, LaunchTransitionType.GAME, 42,
                "steam:289070", 1_000L, true);
        LaunchTransitionController controller = new LaunchTransitionController(null);
        controller.begin(providerSpec);
        controller.overlayRendered(providerSpec.id);
        LaunchTransitionState before = controller.snapshot().state;
        CountDownLatch startEntered = new CountDownLatch(1);
        CountDownLatch releaseStart = new CountDownLatch(1);
        FakeGateway gateway = new FakeGateway() {
            @Override public void startGame(String gameId) throws IOException {
                super.startGame(gameId);
                startEntered.countDown();
                try {
                    releaseStart.await(2, TimeUnit.SECONDS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
                throw new IOException("stale failure");
            }
        };
        ExecutorService executor = Executors.newFixedThreadPool(2);
        ConsoleStreamTransitionCoordinator coordinator = new ConsoleStreamTransitionCoordinator(
                providerSpec, controller, gateway, executor, new FakeClock(),
                new InterruptingSleeper(), (action, delay) -> { }, new FakeCallbacks());

        coordinator.start();
        assertTrue(startEntered.await(2, TimeUnit.SECONDS));
        coordinator.stop();
        releaseStart.countDown();
        coordinator.close();

        assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS));
        assertEquals(before, controller.snapshot().state);
    }

    @Test
    public void providerStartAndStreamConnectionCanProgressTogether() throws Exception {
        LaunchTransitionSpec providerSpec = new LaunchTransitionSpec(
                "transition-provider", HOST, LaunchTransitionType.GAME, 42,
                "steam:289070", 1_000L, true);
        LaunchTransitionController controller = new LaunchTransitionController(null);
        controller.begin(providerSpec);
        controller.overlayRendered(providerSpec.id);
        CountDownLatch observationEntered = new CountDownLatch(1);
        CountDownLatch releaseObservation = new CountDownLatch(1);
        CountDownLatch startCalled = new CountDownLatch(1);
        CountDownLatch releaseStart = new CountDownLatch(1);
        FakeGateway gateway = new FakeGateway() {
            @Override public PlayniteTransitionGateway.Snapshot snapshot() {
                observationEntered.countDown();
                try {
                    releaseObservation.await(2, TimeUnit.SECONDS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
                return ConsoleStreamTransitionCoordinatorTest.snapshot(
                        true, true, "idle", GAME, 0, false, "playnite", 0, "");
            }

            @Override public void startGame(String gameId) throws IOException {
                super.startGame(gameId);
                startCalled.countDown();
                try {
                    releaseStart.await(2, TimeUnit.SECONDS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
        };
        ExecutorService executor = Executors.newFixedThreadPool(2);
        ConsoleStreamTransitionCoordinator coordinator = new ConsoleStreamTransitionCoordinator(
                providerSpec, controller, gateway, executor, new FakeClock(),
                new InterruptingSleeper(), (action, delay) -> { }, new FakeCallbacks());

        coordinator.start();
        assertTrue(startCalled.await(2, TimeUnit.SECONDS));
        assertFalse(observationEntered.await(100, TimeUnit.MILLISECONDS));
        controller.streamConnected(providerSpec.id);
        coordinator.onStreamConnected();
        assertEquals(LaunchTransitionState.GAME_START_REQUESTED,
                controller.snapshot().state);
        assertEquals(1, gateway.startGameCalls);
        assertEquals("steam:289070", gateway.startedGameId);
        assertEquals(0, gateway.snapshotIndex);
        controller.surfaceReady(providerSpec.id);
        controller.inputPipelineReady(providerSpec.id);
        controller.videoFrameRendered(providerSpec.id);
        assertFalse(controller.snapshot().revealAuthorized);

        releaseStart.countDown();
        assertTrue(observationEntered.await(2, TimeUnit.SECONDS));
        releaseObservation.countDown();
        coordinator.close();
        assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS));
    }

    @Test
    public void providerLauncherInteractionKeepsRevealChoiceAvailable() {
        LaunchTransitionSpec providerSpec = new LaunchTransitionSpec(
                "transition-provider", HOST, LaunchTransitionType.GAME, 42,
                "steam:289070", 1_000L, true);
        LaunchTransitionController controller = new LaunchTransitionController(null);
        controller.begin(providerSpec);
        controller.overlayRendered(providerSpec.id);
        FakeGateway gateway = new FakeGateway();
        gateway.snapshots.add(new IOException("stop observation"));
        gateway.startError = new IOException("launcher_interaction_required");
        FakeCallbacks callbacks = new FakeCallbacks();
        ConsoleStreamTransitionCoordinator coordinator =
                new ConsoleStreamTransitionCoordinator(
                        providerSpec, controller, gateway, new InlineExecutor(),
                        new FakeClock(), new InterruptingSleeper(), (action, delay) -> { },
                        callbacks);
        coordinator.start();
        Thread.interrupted();

        assertEquals(LaunchTransitionState.LAUNCHER_INTERACTION_REQUIRED,
                controller.snapshot().state);
        assertEquals(0, callbacks.providerStartAcceptedCalls);
        assertEquals(1, callbacks.providerStartFailedCalls);

        controller.surfaceReady(providerSpec.id);
        controller.streamConnected(providerSpec.id);
        controller.videoFrameRendered(providerSpec.id);
        assertEquals(LaunchTransitionState.LAUNCHER_INTERACTION_REQUIRED,
                controller.snapshot().state);
        assertEquals(1, gateway.startGameCalls);
        assertEquals(0, callbacks.providerStartAcceptedCalls);

        coordinator.cancel();
        assertEquals(0, gateway.stopGameCalls);
    }

    @Test
    public void cancellationBeforeQueuedProviderStartPreventsStart() {
        LaunchTransitionSpec providerSpec = providerSpec("old", "steam:289070");
        LaunchTransitionController controller = providerController(providerSpec);
        FakeGateway gateway = stoppingObservationGateway();
        QueuedExecutor provider = new QueuedExecutor();
        ConsoleStreamTransitionCoordinator coordinator = coordinator(
                providerSpec, controller, gateway, provider);

        coordinator.start();
        Thread.interrupted();
        coordinator.cancel();
        provider.runAll();

        assertEquals(0, gateway.startGameCalls);
        assertEquals(0, gateway.stopGameCalls);
    }

    @Test
    public void streamFailureAfterEarlyStartStopsOwnedGame() {
        LaunchTransitionSpec providerSpec = providerSpec("failed-stream", "steam:289070");
        LaunchTransitionController controller = providerController(providerSpec);
        FakeGateway gateway = stoppingObservationGateway();
        ConsoleStreamTransitionCoordinator coordinator = new ConsoleStreamTransitionCoordinator(
                providerSpec, controller, gateway, new InlineExecutor(), new FakeClock(),
                new InterruptingSleeper(), (action, delay) -> { }, new FakeCallbacks());

        coordinator.start();
        Thread.interrupted();
        coordinator.onStreamFailed();

        assertEquals(1, gateway.startGameCalls);
        assertEquals(1, gateway.stopGameCalls);
        assertEquals("steam:289070", gateway.stoppedGameId);
    }

    @Test
    public void observerFailureAfterRevealCannotStopCommittedProviderGame() {
        LaunchTransitionSpec providerSpec = providerSpec("revealed", "steam:289070");
        FakeGateway gateway = stoppingObservationGateway();
        ConsoleStreamTransitionCoordinator coordinator = new ConsoleStreamTransitionCoordinator(
                providerSpec, providerController(providerSpec), gateway,
                new InlineExecutor(), new FakeClock(), new InterruptingSleeper(),
                (action, delay) -> { }, new FakeCallbacks());
        coordinator.start();
        Thread.interrupted();

        coordinator.commitProviderLaunch();
        coordinator.onStreamFailed();
        coordinator.cancel();

        assertEquals(1, gateway.startGameCalls);
        assertEquals(0, gateway.stopGameCalls);
    }

    @Test
    public void repeatedFailureAndCancelSignalsStopOwnedGameOnlyOnce() {
        LaunchTransitionSpec providerSpec = providerSpec("repeated-failure", "steam:289070");
        LaunchTransitionController controller = providerController(providerSpec);
        FakeGateway gateway = stoppingObservationGateway();
        ConsoleStreamTransitionCoordinator coordinator = new ConsoleStreamTransitionCoordinator(
                providerSpec, controller, gateway, new InlineExecutor(), new FakeClock(),
                new InterruptingSleeper(), (action, delay) -> { }, new FakeCallbacks());

        coordinator.start();
        Thread.interrupted();
        coordinator.onStreamFailed();
        coordinator.onStreamFailed();
        coordinator.cancel();

        assertEquals(1, gateway.startGameCalls);
        assertEquals(1, gateway.stopGameCalls);
    }

    @Test
    public void cleanupCannotStopNewProcessOfSameGameAfterOwnedTokenChanges() {
        String replacementToken =
                "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";
        LaunchTransitionSpec spec = providerSpec("same-game-replacement", "steam:289070");
        FakeGateway gateway = new FakeGateway();
        gateway.snapshots.add(new PlayniteTransitionGateway.Snapshot(
                true, true, "running", "steam:289070", 42,
                true, "game", 1, "", false, PROCESS_TOKEN));
        gateway.snapshots.add(new IOException("stop observation"));
        gateway.snapshots.add(new PlayniteTransitionGateway.Snapshot(
                true, true, "running", "steam:289070", 43,
                true, "game", 1, "", false, replacementToken));
        FakeCallbacks callbacks = new FakeCallbacks();
        ConsoleStreamTransitionCoordinator coordinator =
                new ConsoleStreamTransitionCoordinator(
                        spec, providerController(spec), gateway, new InlineExecutor(),
                        new FakeClock(), new InterruptingSleeper(),
                        (action, delay) -> { }, callbacks);

        coordinator.start();
        Thread.interrupted();
        coordinator.onStreamFailed();

        assertEquals(0, gateway.stopGameCalls);
        assertEquals(1, callbacks.cleanupCalls);
        assertFalse(callbacks.lastCleanupSuccess);
    }

    @Test
    public void ownershipTransferCannotRaceClaimedCleanupOfSameProcess() throws Exception {
        CountDownLatch observationFinished = new CountDownLatch(1);
        CountDownLatch cleanupSnapshotEntered = new CountDownLatch(1);
        CountDownLatch releaseCleanupSnapshot = new CountDownLatch(1);
        CountDownLatch stopCalled = new CountDownLatch(1);
        FakeGateway gateway = new FakeGateway() {
            int calls;

            @Override public synchronized PlayniteTransitionGateway.Snapshot snapshot()
                    throws IOException {
                calls++;
                if (calls == 1) {
                    return new PlayniteTransitionGateway.Snapshot(
                            true, true, "running", "steam:289070", 42,
                            true, "game", 1, "", false, PROCESS_TOKEN);
                }
                if (calls == 2) {
                    observationFinished.countDown();
                    throw new IOException("stop observation");
                }
                cleanupSnapshotEntered.countDown();
                try {
                    releaseCleanupSnapshot.await(2, TimeUnit.SECONDS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IOException("cleanup interrupted", interrupted);
                }
                return new PlayniteTransitionGateway.Snapshot(
                        true, true, "running", "steam:289070", 42,
                        true, "game", 1, "", false, PROCESS_TOKEN);
            }

            @Override public void stopGame(String gameId, String processToken) {
                super.stopGame(gameId, processToken);
                stopCalled.countDown();
            }
        };
        LaunchTransitionSpec ownerSpec = providerSpec("owner", "steam:289070");
        LaunchTransitionSpec replacementSpec = new LaunchTransitionSpec(
                "replacement", HOST, LaunchTransitionType.GAME_CONNECTION, 42,
                "steam:289070", 1_000L);
        ExecutorService provider = Executors.newSingleThreadExecutor();
        ConsoleStreamTransitionCoordinator owner = new ConsoleStreamTransitionCoordinator(
                ownerSpec, providerController(ownerSpec), gateway, new InlineExecutor(), provider,
                new FakeClock(), 0L, new InterruptingSleeper(),
                (action, delay) -> { }, new FakeCallbacks());
        ConsoleStreamTransitionCoordinator replacement =
                new ConsoleStreamTransitionCoordinator(
                        replacementSpec, providerController(replacementSpec), gateway,
                        new InlineExecutor(), new FakeClock(), new InterruptingSleeper(),
                        (action, delay) -> { }, new FakeCallbacks());

        owner.start();
        assertTrue(observationFinished.await(2, TimeUnit.SECONDS));
        owner.onStreamFailed();
        assertTrue(cleanupSnapshotEntered.await(2, TimeUnit.SECONDS));
        assertFalse(owner.transferProviderOwnershipTo(replacement));
        releaseCleanupSnapshot.countDown();

        assertTrue(stopCalled.await(2, TimeUnit.SECONDS));
        assertEquals(1, gateway.stopGameCalls);
        owner.close();
        replacement.close();
        provider.shutdownNow();
        assertTrue(provider.awaitTermination(2, TimeUnit.SECONDS));
    }

    @Test
    public void staleAttemptCleanupRunsBeforeNewAttemptStart() {
        List<String> actions = new ArrayList<>();
        FakeGateway gateway = stoppingObservationGateway();
        gateway.actions = actions;
        QueuedExecutor provider = new QueuedExecutor();
        LaunchTransitionSpec oldSpec = providerSpec("old", "steam:old");
        LaunchTransitionSpec newSpec = providerSpec("new", "steam:new");
        ConsoleStreamTransitionCoordinator oldCoordinator = coordinator(
                oldSpec, providerController(oldSpec), gateway, provider);
        ConsoleStreamTransitionCoordinator newCoordinator = coordinator(
                newSpec, providerController(newSpec), gateway, provider);

        oldCoordinator.start();
        Thread.interrupted();
        provider.runNext();
        oldCoordinator.onStreamFailed();
        newCoordinator.start();
        Thread.interrupted();
        provider.runAll();

        assertEquals(Arrays.asList(
                "start:steam:old", "stop:steam:old", "start:steam:new"), actions);
    }

    @Test
    public void recreatedPresentationJoinsTheOneInFlightStartWithoutAccepting()
            throws Exception {
        for (boolean succeed : new boolean[]{true, false}) {
            ConsoleStreamTransitionCoordinator.resetProviderOwnershipForTests();
            LaunchTransitionSpec spec = providerSpec("recreated-" + succeed, "steam:289070");
            CountDownLatch startEntered = new CountDownLatch(1);
            CountDownLatch releaseStart = new CountDownLatch(1);
            FakeGateway gateway = new FakeGateway() {
                @Override public PlayniteTransitionGateway.Snapshot snapshot()
                        throws IOException {
                    throw new IOException("observation not under test");
                }

                @Override public void startGame(String gameId) throws IOException {
                    super.startGame(gameId);
                    startEntered.countDown();
                    try {
                        releaseStart.await(2, TimeUnit.SECONDS);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    }
                    if (!succeed) throw new IOException("failed");
                }
            };
            ExecutorService oldProvider = Executors.newSingleThreadExecutor();
            ExecutorService newProvider = Executors.newSingleThreadExecutor();
            FakeCallbacks oldCallbacks = new FakeCallbacks();
            FakeCallbacks newCallbacks = new FakeCallbacks();
            ConsoleStreamTransitionCoordinator old = new ConsoleStreamTransitionCoordinator(
                    spec, providerController(spec), gateway,
                    Executors.newSingleThreadExecutor(), oldProvider, new FakeClock(), 0L,
                    new InterruptingSleeper(), (action, delay) -> { }, oldCallbacks);
            old.start();
            assertTrue(startEntered.await(2, TimeUnit.SECONDS));

            old.detachForRecreation();
            ConsoleStreamTransitionCoordinator adopter = new ConsoleStreamTransitionCoordinator(
                    spec, providerController(spec), gateway,
                    Executors.newSingleThreadExecutor(), newProvider, new FakeClock(), 0L,
                    new InterruptingSleeper(), (action, delay) -> { }, newCallbacks);
            assertTrue(adopter.adoptRecreatedProviderOwnership());
            assertEquals(ConsoleStreamTransitionCoordinator.OwnerState.STARTING,
                    adopter.ownerState());
            adopter.start();
            adopter.onStreamConnected();
            newProvider.submit(() -> { }).get(2, TimeUnit.SECONDS);
            assertEquals("STARTING is not a started provider", 0,
                    newCallbacks.providerStartAcceptedCalls);

            releaseStart.countDown();
            oldProvider.submit(() -> { }).get(2, TimeUnit.SECONDS);

            assertEquals("exactly one external start", 1, gateway.startGameCalls);
            assertEquals(0, oldCallbacks.providerStartAcceptedCalls);
            assertEquals(succeed ? 1 : 0, newCallbacks.providerStartAcceptedCalls);
            assertEquals(succeed ? 0 : 1, newCallbacks.providerStartFailedCalls);
            assertEquals(succeed ? ConsoleStreamTransitionCoordinator.OwnerState.STARTED
                    : ConsoleStreamTransitionCoordinator.OwnerState.NONE, adopter.ownerState());
            assertEquals(0, gateway.stopGameCalls);
            adopter.close();
            oldProvider.shutdownNow();
            newProvider.shutdownNow();
        }
    }

    @Test
    public void detachedOwnerWithoutObservedProcessIsNeverReportedStopped() throws Exception {
        ConsoleStreamTransitionCoordinator.resetProviderOwnershipForTests();
        LaunchTransitionSpec spec = providerSpec("detached-end", "steam:289070");
        CountDownLatch startEntered = new CountDownLatch(1);
        CountDownLatch releaseStart = new CountDownLatch(1);
        FakeGateway gateway = new FakeGateway() {
            @Override public PlayniteTransitionGateway.Snapshot snapshot() throws IOException {
                throw new IOException("unobservable");
            }

            @Override public void startGame(String gameId) throws IOException {
                super.startGame(gameId);
                startEntered.countDown();
                try {
                    releaseStart.await(2, TimeUnit.SECONDS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
        };
        ExecutorService provider = Executors.newSingleThreadExecutor();
        FakeCallbacks callbacks = new FakeCallbacks();
        ConsoleStreamTransitionCoordinator old = new ConsoleStreamTransitionCoordinator(
                spec, providerController(spec), gateway, Executors.newSingleThreadExecutor(),
                provider, new FakeClock(), 0L, new InterruptingSleeper(),
                (action, delay) -> { }, callbacks);
        old.start();
        assertTrue(startEntered.await(2, TimeUnit.SECONDS));
        old.detachForRecreation();

        ConsoleStreamTransitionCoordinator.endDetachedOwner(HOST);  // runtime ended, no adopter
        releaseStart.countDown();
        provider.submit(() -> { }).get(2, TimeUnit.SECONDS);
        provider.submit(() -> { }).get(2, TimeUnit.SECONDS);

        assertEquals(1, gateway.startGameCalls);
        assertEquals("no identity bound by observation: no stop", 0, gateway.stopGameCalls);
        assertEquals(1, callbacks.cleanupCalls);
        assertFalse(callbacks.lastCleanupSuccess);
        assertEquals(0, callbacks.providerStartAcceptedCalls);
        provider.shutdownNow();
    }

    @Test
    public void holderlessOwnerStopsTheObservableExactProcessOnce() throws Exception {
        ConsoleStreamTransitionCoordinator.resetProviderOwnershipForTests();
        LaunchTransitionSpec spec = providerSpec("detached-exact", "steam:289070");
        CountDownLatch startEntered = new CountDownLatch(1);
        CountDownLatch releaseStart = new CountDownLatch(1);
        FakeGateway gateway = new FakeGateway() {
            @Override public PlayniteTransitionGateway.Snapshot snapshot() throws IOException {
                if (releaseStart.getCount() != 0) throw new IOException("start pending");
                return new PlayniteTransitionGateway.Snapshot(
                        true, true, "running", "steam:289070", 42,
                        true, "game", 1, "", false, PROCESS_TOKEN);
            }

            @Override public void startGame(String gameId) throws IOException {
                super.startGame(gameId);
                startEntered.countDown();
                try {
                    releaseStart.await(2, TimeUnit.SECONDS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
        };
        ExecutorService provider = Executors.newSingleThreadExecutor();
        FakeCallbacks callbacks = new FakeCallbacks();
        ConsoleStreamTransitionCoordinator old = new ConsoleStreamTransitionCoordinator(
                spec, providerController(spec), gateway, Executors.newSingleThreadExecutor(),
                provider, new FakeClock(), 0L, new InterruptingSleeper(),
                (action, delay) -> { }, callbacks);
        old.start();
        assertTrue(startEntered.await(2, TimeUnit.SECONDS));
        old.detachForRecreation();

        ConsoleStreamTransitionCoordinator.endDetachedOwner(HOST);
        releaseStart.countDown();
        provider.submit(() -> { }).get(2, TimeUnit.SECONDS);
        provider.submit(() -> { }).get(2, TimeUnit.SECONDS);

        assertEquals(1, gateway.startGameCalls);
        assertEquals("exact observable process stopped once", 1, gateway.stopGameCalls);
        assertEquals(PROCESS_TOKEN, gateway.stoppedProcessToken);
        assertEquals(1, callbacks.cleanupCalls);
        assertTrue(callbacks.lastCleanupSuccess);
        ConsoleStreamTransitionCoordinator probe = new ConsoleStreamTransitionCoordinator(
                spec, providerController(spec), gateway, new InlineExecutor(), new FakeClock(),
                new InterruptingSleeper(), (action, delay) -> { }, new FakeCallbacks());
        assertEquals("owner retired", ConsoleStreamTransitionCoordinator.OwnerState.NONE,
                probe.ownerState());
        provider.shutdownNow();
    }

    @Test
    public void detachForRecreationReleasesTheOldObservationPool() throws Exception {
        ConsoleStreamTransitionCoordinator.resetProviderOwnershipForTests();
        LaunchTransitionSpec spec = providerSpec("detached-pool", "steam:289070");
        ExecutorService observation = Executors.newFixedThreadPool(2);
        ConsoleStreamTransitionCoordinator old = new ConsoleStreamTransitionCoordinator(
                spec, providerController(spec), stoppingObservationGateway(), observation,
                new InlineExecutor(), new FakeClock(), 0L, new InterruptingSleeper(),
                (action, delay) -> { }, new FakeCallbacks());
        old.start();

        old.detachForRecreation();

        assertTrue(observation.awaitTermination(2, TimeUnit.SECONDS));
    }

    @Test
    public void queuedButNeverInvokedStartIsAdoptedAndStartedExactlyOnce() {
        for (boolean succeed : new boolean[]{true, false}) {
            ConsoleStreamTransitionCoordinator.resetProviderOwnershipForTests();
            LaunchTransitionSpec spec = providerSpec("queued-" + succeed, "steam:289070");
            FakeGateway gateway = stoppingObservationGateway();
            if (!succeed) gateway.startError = new IOException("failed");
            java.util.ArrayDeque<Runnable> oldQueue = new java.util.ArrayDeque<>();
            java.util.ArrayDeque<Runnable> newQueue = new java.util.ArrayDeque<>();
            FakeCallbacks oldCallbacks = new FakeCallbacks();
            FakeCallbacks newCallbacks = new FakeCallbacks();
            ConsoleStreamTransitionCoordinator old = new ConsoleStreamTransitionCoordinator(
                    spec, providerController(spec), gateway, Executors.newSingleThreadExecutor(),
                    oldQueue::add, new FakeClock(), 0L, new InterruptingSleeper(),
                    (action, delay) -> { }, oldCallbacks);
            old.start();                              // queued, not yet run
            assertEquals(1, oldQueue.size());
            assertEquals(ConsoleStreamTransitionCoordinator.OwnerState.PENDING,
                    old.ownerState());

            old.detachForRecreation();
            ConsoleStreamTransitionCoordinator adopter = new ConsoleStreamTransitionCoordinator(
                    spec, providerController(spec), gateway, Executors.newSingleThreadExecutor(),
                    newQueue::add, new FakeClock(), 0L, new InterruptingSleeper(),
                    (action, delay) -> { }, newCallbacks);
            assertTrue(adopter.adoptRecreatedProviderOwnership());
            adopter.start();
            while (!oldQueue.isEmpty()) oldQueue.poll().run();   // stale worker
            assertEquals("the detached worker never invokes the start", 0,
                    gateway.startGameCalls);
            while (!newQueue.isEmpty()) newQueue.poll().run();
            Thread.interrupted();

            assertEquals("exactly one external start", 1, gateway.startGameCalls);
            assertEquals(succeed ? 1 : 0, newCallbacks.providerStartAcceptedCalls);
            assertEquals(succeed ? 0 : 1, newCallbacks.providerStartFailedCalls);
            assertEquals(0, oldCallbacks.providerStartAcceptedCalls
                    + oldCallbacks.providerStartFailedCalls);
            adopter.close();
        }
    }

    @Test
    public void mismatchedSpecCannotAdoptAQueuedReservation() {
        ConsoleStreamTransitionCoordinator.resetProviderOwnershipForTests();
        LaunchTransitionSpec spec = providerSpec("queued-exact", "steam:289070");
        java.util.ArrayDeque<Runnable> queue = new java.util.ArrayDeque<>();
        FakeGateway gateway = stoppingObservationGateway();
        ConsoleStreamTransitionCoordinator old = new ConsoleStreamTransitionCoordinator(
                spec, providerController(spec), gateway, Executors.newSingleThreadExecutor(),
                queue::add, new FakeClock(), 0L, new InterruptingSleeper(),
                (action, delay) -> { }, new FakeCallbacks());
        old.start();
        old.detachForRecreation();
        LaunchTransitionSpec other = providerSpec("queued-other", "steam:289070");
        ConsoleStreamTransitionCoordinator stale = new ConsoleStreamTransitionCoordinator(
                other, providerController(other), gateway, Executors.newSingleThreadExecutor(),
                queue::add, new FakeClock(), 0L, new InterruptingSleeper(),
                (action, delay) -> { }, new FakeCallbacks());

        assertFalse(stale.adoptRecreatedProviderOwnership());
        while (!queue.isEmpty()) queue.poll().run();
        assertEquals(0, gateway.startGameCalls);
    }

    @Test
    public void endedRuntimeReleasesANeverInvokedReservationWithoutStopping() {
        ConsoleStreamTransitionCoordinator.resetProviderOwnershipForTests();
        LaunchTransitionSpec spec = providerSpec("queued-end", "steam:289070");
        java.util.ArrayDeque<Runnable> queue = new java.util.ArrayDeque<>();
        FakeGateway gateway = stoppingObservationGateway();
        ConsoleStreamTransitionCoordinator old = new ConsoleStreamTransitionCoordinator(
                spec, providerController(spec), gateway, Executors.newSingleThreadExecutor(),
                queue::add, new FakeClock(), 0L, new InterruptingSleeper(),
                (action, delay) -> { }, new FakeCallbacks());
        old.start();
        old.detachForRecreation();

        ConsoleStreamTransitionCoordinator.endDetachedOwner(HOST);
        while (!queue.isEmpty()) queue.poll().run();

        assertEquals(0, gateway.startGameCalls);
        assertEquals(0, gateway.stopGameCalls);
        assertEquals(ConsoleStreamTransitionCoordinator.OwnerState.NONE, old.ownerState());
    }

    @Test
    public void cleanupWinningBeforeReservationLeavesNoOwnerAndNextAttemptStarts() {
        ConsoleStreamTransitionCoordinator.resetProviderOwnershipForTests();
        LaunchTransitionSpec spec = providerSpec("cleanup-first", "steam:289070", false);
        FakeGateway gateway = stoppingObservationGateway();
        java.util.ArrayDeque<Runnable> queue = new java.util.ArrayDeque<>();
        ConsoleStreamTransitionCoordinator cancelled = new ConsoleStreamTransitionCoordinator(
                spec, providerController(spec), gateway, Executors.newSingleThreadExecutor(),
                queue::add, new FakeClock(), 0L, new InterruptingSleeper(),
                (action, delay) -> { }, new FakeCallbacks());
        cancelled.start();
        long epoch = cancelled.currentEpochForTests();

        // Interleaving: the start request passed its first check, then cleanup completed.
        cancelled.onStreamFailed();
        assertFalse(cancelled.reservePendingStart(epoch));
        while (!queue.isEmpty()) queue.poll().run();

        assertEquals(ConsoleStreamTransitionCoordinator.OwnerState.NONE, cancelled.ownerState());
        assertEquals(0, gateway.startGameCalls);
        assertEquals(0, gateway.stopGameCalls);

        LaunchTransitionSpec next = providerSpec("cleanup-next", "steam:289070");
        FakeCallbacks nextCallbacks = new FakeCallbacks();
        ConsoleStreamTransitionCoordinator attempt = new ConsoleStreamTransitionCoordinator(
                next, providerController(next), gateway, Executors.newSingleThreadExecutor(),
                queue::add, new FakeClock(), 0L, new InterruptingSleeper(),
                (action, delay) -> { }, nextCallbacks);
        attempt.start();
        while (!queue.isEmpty()) queue.poll().run();
        Thread.interrupted();

        assertEquals("the next exact attempt starts once", 1, gateway.startGameCalls);
        assertEquals(1, nextCallbacks.providerStartAcceptedCalls);
        attempt.close();
    }

    @Test
    public void failedProviderStartCannotBecomeAutomaticallyReady() {
        LaunchTransitionSpec providerSpec = providerSpec("failed-start", "steam:289070");
        LaunchTransitionController controller = providerController(providerSpec);
        FakeGateway gateway = stoppingObservationGateway();
        gateway.startError = new IOException("failed");
        ConsoleStreamTransitionCoordinator coordinator = new ConsoleStreamTransitionCoordinator(
                providerSpec, controller, gateway, new InlineExecutor(), new FakeClock(),
                new InterruptingSleeper(), (action, delay) -> { }, new FakeCallbacks());

        coordinator.start();
        Thread.interrupted();

        assertEquals(LaunchTransitionState.ERROR, controller.snapshot().state);
        assertFalse(controller.snapshot().revealAuthorized);
        assertEquals(0, gateway.stopGameCalls);
        assertEquals(0, gateway.snapshotIndex);
    }

    @Test
    public void silentStreamTimeoutStopsEarlyProviderGame() {
        LaunchTransitionSpec providerSpec = providerSpec("silent-stream", "steam:289070");
        LaunchTransitionController controller = providerController(providerSpec);
        FakeGateway gateway = stoppingObservationGateway();
        QueuedExecutor provider = new QueuedExecutor();
        QueuedScheduler scheduler = new QueuedScheduler();
        ConsoleStreamTransitionCoordinator coordinator = new ConsoleStreamTransitionCoordinator(
                providerSpec, controller, gateway, new InlineExecutor(), provider,
                new FakeClock(), 0L, new InterruptingSleeper(), scheduler,
                new FakeCallbacks());

        coordinator.start();
        provider.runNext();
        Thread.interrupted();
        assertEquals(Collections.singletonList(60_000L), scheduler.delays);

        scheduler.actions.get(0).run();
        provider.runAll();

        assertEquals(LaunchTransitionState.TIMED_OUT, controller.snapshot().state);
        assertEquals(1, gateway.startGameCalls);
        assertEquals(1, gateway.stopGameCalls);
    }

    @Test
    public void lateOldCleanupCannotStopNewOwnerOnSameHost() {
        List<String> actions = new ArrayList<>();
        FakeGateway gateway = stoppingObservationGateway();
        gateway.actions = actions;
        QueuedExecutor provider = new QueuedExecutor();
        LaunchTransitionSpec oldSpec = providerSpec("old", "steam:same");
        LaunchTransitionSpec newSpec = providerSpec("new", "steam:same");
        ConsoleStreamTransitionCoordinator oldCoordinator = coordinator(
                oldSpec, providerController(oldSpec), gateway, provider);
        ConsoleStreamTransitionCoordinator newCoordinator = coordinator(
                newSpec, providerController(newSpec), gateway, provider);

        oldCoordinator.start();
        provider.runNext();
        Thread.interrupted();
        newCoordinator.start();
        provider.runNext();
        Thread.interrupted();
        oldCoordinator.onStreamFailed();
        provider.runAll();

        assertEquals(Arrays.asList("start:steam:same", "start:steam:same"), actions);
        assertEquals(0, gateway.stopGameCalls);
    }

    @Test
    public void skippedCleanupForNewerOwnerCompletesOnceAsFailure() {
        FakeGateway gateway = stoppingObservationGateway();
        LaunchTransitionSpec oldSpec = providerSpec("old", "steam:same");
        LaunchTransitionSpec newSpec = providerSpec("new", "steam:same");
        FakeCallbacks oldCallbacks = new FakeCallbacks();
        ConsoleStreamTransitionCoordinator oldCoordinator =
                new ConsoleStreamTransitionCoordinator(oldSpec,
                        providerController(oldSpec), gateway, new InlineExecutor(),
                        new FakeClock(), new InterruptingSleeper(),
                        (action, delay) -> { }, oldCallbacks);
        ConsoleStreamTransitionCoordinator newCoordinator =
                new ConsoleStreamTransitionCoordinator(newSpec,
                        providerController(newSpec), gateway, new InlineExecutor(),
                        new FakeClock(), new InterruptingSleeper(),
                        (action, delay) -> { }, new FakeCallbacks());
        oldCoordinator.start();
        Thread.interrupted();
        newCoordinator.start();
        Thread.interrupted();

        oldCoordinator.onStreamFailed();
        oldCoordinator.cancel();

        assertEquals(0, gateway.stopGameCalls);
        assertEquals(1, oldCallbacks.cleanupCalls);
        assertFalse(oldCallbacks.lastCleanupSuccess);
    }

    @Test
    public void reconstructedCoordinatorReusesSameAttemptWithoutStartingAgain() {
        LaunchTransitionSpec providerSpec = providerSpec("same-attempt", "steam:289070");
        FakeGateway gateway = stoppingObservationGateway();
        QueuedExecutor provider = new QueuedExecutor();
        ConsoleStreamTransitionCoordinator first = coordinator(
                providerSpec, providerController(providerSpec), gateway, provider);
        ConsoleStreamTransitionCoordinator reconstructed = coordinator(
                providerSpec, providerController(providerSpec), gateway, provider);

        first.start();
        provider.runNext();
        Thread.interrupted();
        reconstructed.start();
        provider.runNext();
        Thread.interrupted();

        assertEquals(1, gateway.startGameCalls);
        assertEquals(0, gateway.stopGameCalls);
    }

    @Test
    public void reconstructedObservationOnlyAttemptCanNeverStopProviderOwner() {
        LaunchTransitionSpec game = providerSpec("same-attempt", "steam:289070");
        LaunchTransitionSpec observation = new LaunchTransitionSpec(
                game.id, game.hostId, LaunchTransitionType.GAME_CONNECTION,
                game.sunshineAppId, game.playniteGameId, game.createdAtMillis);
        FakeGateway gateway = stoppingObservationGateway();
        ConsoleStreamTransitionCoordinator owner = new ConsoleStreamTransitionCoordinator(
                game, providerController(game), gateway, new InlineExecutor(),
                new FakeClock(), new InterruptingSleeper(), (action, delay) -> { },
                new FakeCallbacks());
        ConsoleStreamTransitionCoordinator observer = new ConsoleStreamTransitionCoordinator(
                observation, providerController(observation), gateway, new InlineExecutor(),
                new FakeClock(), new InterruptingSleeper(), (action, delay) -> { },
                new FakeCallbacks());
        owner.start();
        Thread.interrupted();
        observer.start();
        Thread.interrupted();

        observer.onStreamFailed();
        observer.cancel();

        assertEquals(1, gateway.startGameCalls);
        assertEquals(0, gateway.stopGameCalls);
    }

    @Test
    public void detachForSwitchReleasesExactOwnerWithoutProviderStop() {
        LaunchTransitionSpec oldSpec = providerSpec("old-switch", "steam:old");
        FakeGateway gateway = stoppingObservationGateway();
        ConsoleStreamTransitionCoordinator coordinator = new ConsoleStreamTransitionCoordinator(
                oldSpec, providerController(oldSpec), gateway, new InlineExecutor(),
                new FakeClock(), new InterruptingSleeper(), (action, delay) -> { },
                new FakeCallbacks());
        coordinator.start();
        Thread.interrupted();

        assertTrue(coordinator.detachForSwitch());
        assertEquals(1, gateway.startGameCalls);
        assertEquals(0, gateway.stopGameCalls);
    }

    @Test
    public void confirmedStopCanDetachAfterExactOwnerWasAlreadyCleared() {
        LaunchTransitionSpec spec = providerSpec("confirmed-stop", "steam:old");
        FakeGateway gateway = stoppingObservationGateway();
        ConsoleStreamTransitionCoordinator coordinator = new ConsoleStreamTransitionCoordinator(
                spec, providerController(spec), gateway, new InlineExecutor(),
                new FakeClock(), new InterruptingSleeper(), (action, delay) -> { },
                new FakeCallbacks());
        coordinator.start();
        Thread.interrupted();
        ConsoleStreamTransitionCoordinator.resetProviderOwnershipForTests();

        assertTrue(coordinator.detachAfterConfirmedGameStop());
        assertEquals(1, gateway.startGameCalls);
        assertEquals(0, gateway.stopGameCalls);
    }

    @Test
    public void confirmedStopCannotDetachANewerProviderOwner() {
        LaunchTransitionSpec newer = providerSpec("newer", "steam:new");
        LaunchTransitionSpec stale = providerSpec("stale", "steam:old");
        FakeGateway gateway = stoppingObservationGateway();
        ConsoleStreamTransitionCoordinator newerCoordinator =
                new ConsoleStreamTransitionCoordinator(
                        newer, providerController(newer), gateway, new InlineExecutor(),
                        new FakeClock(), new InterruptingSleeper(), (action, delay) -> { },
                        new FakeCallbacks());
        ConsoleStreamTransitionCoordinator staleCoordinator =
                new ConsoleStreamTransitionCoordinator(
                        stale, providerController(stale), gateway, new InlineExecutor(),
                        new FakeClock(), new InterruptingSleeper(), (action, delay) -> { },
                        new FakeCallbacks());
        newerCoordinator.start();
        Thread.interrupted();

        assertFalse(staleCoordinator.detachAfterConfirmedGameStop());
        assertTrue(newerCoordinator.detachForSwitch());
        assertEquals(1, gateway.startGameCalls);
        assertEquals(0, gateway.stopGameCalls);
    }

    @Test
    public void differentActorCannotDetachCancelOrTransferExactProviderOwner() {
        FakeGateway gateway = stoppingObservationGateway();
        LaunchTransitionSpec ownerSpec = providerSpec(
                "same-owner", "child-a", "steam:old");
        ConsoleStreamTransitionCoordinator owner = new ConsoleStreamTransitionCoordinator(
                ownerSpec, providerController(ownerSpec), gateway, new InlineExecutor(),
                new FakeClock(), new InterruptingSleeper(), (action, delay) -> { },
                new FakeCallbacks());
        owner.start();
        Thread.interrupted();

        LaunchTransitionSpec staleSpec = providerSpec(
                "same-owner", "child-b", "steam:old");
        LaunchTransitionSpec replacementSpec = providerSpec(
                "replacement", "child-b", "steam:old");
        ConsoleStreamTransitionCoordinator stale = new ConsoleStreamTransitionCoordinator(
                staleSpec, providerController(staleSpec), gateway, new InlineExecutor(),
                new FakeClock(), new InterruptingSleeper(), (action, delay) -> { },
                new FakeCallbacks());
        ConsoleStreamTransitionCoordinator replacement = new ConsoleStreamTransitionCoordinator(
                replacementSpec, providerController(replacementSpec), gateway,
                new InlineExecutor(), new FakeClock(), new InterruptingSleeper(),
                (action, delay) -> { }, new FakeCallbacks());

        assertFalse(stale.detachForSwitch());
        assertFalse(stale.detachAfterConfirmedGameStop());
        assertFalse(stale.transferProviderOwnershipTo(replacement));
        stale.onStreamFailed();
        stale.cancel();

        assertEquals(1, gateway.startGameCalls);
        assertEquals(0, gateway.stopGameCalls);
        assertFalse(owner.transferProviderOwnershipTo(replacement));
        assertTrue(owner.detachForSwitch());
    }

    @Test
    public void authorizedFamilyActorTransfersKeepExactProviderProcessAndStream()
            throws Exception {
        for (String[] actors : new String[][] {
                {"parent", "child-a"}, {"child-a", "parent"}, {"child-a", "child-b"}}) {
            ConsoleStreamTransitionCoordinator.resetProviderOwnershipForTests();
            resetRetainedStream();
            FakeGateway gateway = stoppingObservationGateway();
            LaunchTransitionSpec oldSpec = providerSpec("old-" + actors[0], actors[0], GAME);
            LaunchTransitionSpec nextSpec = new LaunchTransitionSpec(
                    "next-" + actors[1], HOST, actors[1], LaunchTransitionType.GAME_CONNECTION,
                    42, GAME, 2L, false);
            ConsoleStreamTransitionCoordinator oldCoordinator = new ConsoleStreamTransitionCoordinator(
                    oldSpec, providerController(oldSpec), gateway, new InlineExecutor(),
                    new FakeClock(), new InterruptingSleeper(), (action, delay) -> { },
                    new FakeCallbacks());
            ConsoleStreamTransitionCoordinator replacement =
                    new ConsoleStreamTransitionCoordinator(
                            nextSpec, providerController(nextSpec), gateway, new InlineExecutor(),
                            new FakeClock(), new InterruptingSleeper(), (action, delay) -> { },
                            new FakeCallbacks());
            oldCoordinator.start();
            Thread.interrupted();
            ConsoleStreamTransitionCoordinator.ProviderProcessIdentity process =
                    oldCoordinator.providerProcessIdentityForTransfer();
            assertTrue(process != null);
            FakeRetainedController retained = new FakeRetainedController("parent");
            m.enterHome(
                    retained, "stream-1", HOST, actors[0], 42, GAME);
            assertTrue(m.bindControlGeneration(
                    retained, "stream-1", 4));
            SessionModel.ProviderTransferAuthorization authorization =
                    m.commitProviderTransferReauthorization(
                            retained, "stream-1", "parent", actors[1], oldSpec.id, nextSpec.id,
                            HOST, 42, GAME, process.processToken, process.processId,
                            4, 5, 9, () -> true);

            assertTrue(authorization != null);
            assertTrue(oldCoordinator.transferProviderOwnershipTo(replacement, authorization, m.model));
            assertFalse(oldCoordinator.transferProviderOwnershipTo(replacement, authorization, m.model));
            SessionModel.Snapshot snapshot =
                    m.snapshot();
            assertEquals("stream-1", snapshot.streamSessionId);
            assertEquals("parent", snapshot.executionProfileId);
            assertEquals(42, snapshot.appId);
            assertEquals(GAME, snapshot.playniteGameId);
            assertEquals(actors[1], snapshot.profileId);
            assertEquals(5, snapshot.controlGeneration);
            assertEquals(PROCESS_TOKEN, process.processToken);
            assertEquals(42, process.processId);
            assertEquals(1, gateway.startGameCalls);
            assertEquals(0, gateway.stopGameCalls);
            assertEquals(0, retained.disconnectCalls);
            assertEquals(0, retained.terminateCalls);
            assertTrue(replacement.detachForSwitch());
        }
    }

    @Test
    public void authenticatedSnapshotCanBindOnlyExactUnclaimedProviderIdentity()
            throws Exception {
        LaunchTransitionSpec observation = new LaunchTransitionSpec(
                "snapshot-fallback", HOST, "child-a", LaunchTransitionType.GAME_CONNECTION,
                42, GAME, 1L, false);
        ConsoleStreamTransitionCoordinator coordinator = new ConsoleStreamTransitionCoordinator(
                observation, providerController(observation), new FakeGateway(),
                new InlineExecutor(), new FakeClock(), new InterruptingSleeper(),
                (action, delay) -> { }, new FakeCallbacks());
        assertTrue(coordinator.adoptDetachedProviderOwnership());
        assertTrue(coordinator.providerProcessIdentityForTransfer() == null);

        PlayniteTransitionGateway.Snapshot wrongGame = new PlayniteTransitionGateway.Snapshot(
                true, true, "running", "steam:other", 42,
                true, "game", 1, "", false, PROCESS_TOKEN);
        PlayniteTransitionGateway.Snapshot malformedToken = new PlayniteTransitionGateway.Snapshot(
                true, true, "running", GAME, 42,
                true, "game", 1, "", false, "not-a-process-token");
        PlayniteTransitionGateway.Snapshot missingPid = new PlayniteTransitionGateway.Snapshot(
                true, true, "running", GAME, 0,
                true, "game", 1, "", false, PROCESS_TOKEN);
        assertTrue(resolveSnapshotIdentity(coordinator, wrongGame) == null);
        assertTrue(resolveSnapshotIdentity(coordinator, malformedToken) == null);
        assertTrue(resolveSnapshotIdentity(coordinator, missingPid) == null);

        PlayniteTransitionGateway.Snapshot exact = new PlayniteTransitionGateway.Snapshot(
                true, true, "running", GAME, 42,
                true, "game", 1, "", false, PROCESS_TOKEN);
        ConsoleStreamTransitionCoordinator.ProviderProcessIdentity identity =
                resolveSnapshotIdentity(coordinator, exact);
        assertTrue(identity != null);
        assertEquals(PROCESS_TOKEN, identity.processToken);
        assertEquals(42, identity.processId);
        assertEquals(PROCESS_TOKEN, coordinator.providerProcessIdentityForTransfer().processToken);
        assertEquals(42, coordinator.providerProcessIdentityForTransfer().processId);
    }

    private static ConsoleStreamTransitionCoordinator.ProviderProcessIdentity
            resolveSnapshotIdentity(ConsoleStreamTransitionCoordinator coordinator,
                                    PlayniteTransitionGateway.Snapshot snapshot)
            throws Exception {
        java.lang.reflect.Method resolve = ConsoleStreamTransitionCoordinator.class
                .getDeclaredMethod("providerProcessIdentityForTransfer",
                        PlayniteTransitionGateway.Snapshot.class);
        resolve.setAccessible(true);
        return (ConsoleStreamTransitionCoordinator.ProviderProcessIdentity)
                resolve.invoke(coordinator, snapshot);
    }

    @Test
    public void authorizedActorTransferRejectsWrongExactProcessIdentity() {
        FakeGateway gateway = stoppingObservationGateway();
        LaunchTransitionSpec oldSpec = providerSpec("old", "parent", GAME);
        LaunchTransitionSpec nextSpec = providerSpec("next", "child", GAME);
        ConsoleStreamTransitionCoordinator oldCoordinator = new ConsoleStreamTransitionCoordinator(
                oldSpec, providerController(oldSpec), gateway, new InlineExecutor(),
                new FakeClock(), new InterruptingSleeper(), (action, delay) -> { },
                new FakeCallbacks());
        ConsoleStreamTransitionCoordinator replacement = new ConsoleStreamTransitionCoordinator(
                nextSpec, providerController(nextSpec), gateway, new InlineExecutor(),
                new FakeClock(), new InterruptingSleeper(), (action, delay) -> { },
                new FakeCallbacks());
        oldCoordinator.start();
        Thread.interrupted();
        FakeRetainedController retained = new FakeRetainedController("parent");
        m.enterHome(retained, "stream-1", HOST,
                "parent", 42, GAME);
        assertTrue(m.bindControlGeneration(retained, "stream-1", 4));
        SessionModel.ProviderTransferAuthorization authorization =
                m.commitProviderTransferReauthorization(
                        retained, "stream-1", "parent", "child", oldSpec.id, nextSpec.id,
                        HOST, 42, GAME,
                        "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
                        42, 4, 5, 9, () -> true);

        assertTrue(authorization != null);
        assertFalse(oldCoordinator.transferProviderOwnershipTo(replacement, authorization, m.model));
        assertEquals(1, gateway.startGameCalls);
        assertEquals(0, gateway.stopGameCalls);
    }

    @Test
    public void sameActorObservationTransfersExactOwnerWithoutProviderAction() {
        FakeGateway gateway = stoppingObservationGateway();
        LaunchTransitionSpec oldSpec = new LaunchTransitionSpec(
                "old-child-observer", HOST, "child-a", LaunchTransitionType.GAME_CONNECTION,
                42, "steam:old", 1L, false);
        LaunchTransitionSpec newSpec = new LaunchTransitionSpec(
                "new-child-observer", HOST, "child-a", LaunchTransitionType.GAME_CONNECTION,
                42, "steam:old", 2L, false);
        ConsoleStreamTransitionCoordinator oldObserver = new ConsoleStreamTransitionCoordinator(
                oldSpec, providerController(oldSpec), gateway, new InlineExecutor(),
                new FakeClock(), new InterruptingSleeper(), (action, delay) -> { },
                new FakeCallbacks());
        ConsoleStreamTransitionCoordinator newObserver = new ConsoleStreamTransitionCoordinator(
                newSpec, providerController(newSpec), gateway, new InlineExecutor(),
                new FakeClock(), new InterruptingSleeper(), (action, delay) -> { },
                new FakeCallbacks());

        assertTrue(oldObserver.adoptDetachedProviderOwnership());
        assertTrue(oldObserver.transferProviderOwnershipTo(newObserver));
        newObserver.start();
        Thread.interrupted();
        assertTrue(newObserver.detachForSwitch());

        assertEquals(0, gateway.startGameCalls);
        assertEquals(0, gateway.stopGameCalls);
    }

    @Test
    public void differentActorOwnerStillBlocksAdoptOnSameHost() {
        LaunchTransitionSpec ownerSpec = new LaunchTransitionSpec(
                "owner-child-a", HOST, "child-a", LaunchTransitionType.GAME_CONNECTION,
                42, "steam:old", 1L, false);
        LaunchTransitionSpec otherSpec = new LaunchTransitionSpec(
                "owner-child-b", HOST, "child-b", LaunchTransitionType.GAME_CONNECTION,
                42, "steam:old", 2L, false);
        FakeGateway gateway = new FakeGateway();
        ConsoleStreamTransitionCoordinator owner = new ConsoleStreamTransitionCoordinator(
                ownerSpec, providerController(ownerSpec), gateway, new InlineExecutor(),
                new FakeClock(), new InterruptingSleeper(), (action, delay) -> { },
                new FakeCallbacks());
        ConsoleStreamTransitionCoordinator other = new ConsoleStreamTransitionCoordinator(
                otherSpec, providerController(otherSpec), gateway, new InlineExecutor(),
                new FakeClock(), new InterruptingSleeper(), (action, delay) -> { },
                new FakeCallbacks());

        assertTrue(owner.adoptDetachedProviderOwnership());
        assertFalse(other.adoptDetachedProviderOwnership());
        assertTrue(owner.detachForSwitch());
    }

    @Test
    public void failedSwitchRecoveryCannotReplaceNewerProviderOwner() {
        FakeGateway gateway = stoppingObservationGateway();
        LaunchTransitionSpec oldSpec = providerSpec("old-switch", "steam:old");
        LaunchTransitionSpec newSpec = providerSpec("new-switch", "steam:new");
        ConsoleStreamTransitionCoordinator oldCoordinator = new ConsoleStreamTransitionCoordinator(
                oldSpec, providerController(oldSpec), gateway, new InlineExecutor(),
                new FakeClock(), new InterruptingSleeper(), (action, delay) -> { },
                new FakeCallbacks());
        ConsoleStreamTransitionCoordinator newCoordinator = new ConsoleStreamTransitionCoordinator(
                newSpec, providerController(newSpec), gateway, new InlineExecutor(),
                new FakeClock(), new InterruptingSleeper(), (action, delay) -> { },
                new FakeCallbacks());
        oldCoordinator.start();
        Thread.interrupted();
        assertTrue(oldCoordinator.detachForSwitch());
        newCoordinator.start();
        Thread.interrupted();

        assertFalse(oldCoordinator.adoptDetachedProviderOwnership());
        oldCoordinator.cancel();

        assertEquals(2, gateway.startGameCalls);
        assertEquals(0, gateway.stopGameCalls);
    }

    @Test
    public void observationRetryTransfersExactOwnerWithoutProviderAction() {
        FakeGateway gateway = stoppingObservationGateway();
        LaunchTransitionSpec oldSpec = new LaunchTransitionSpec(
                "old-observer", HOST, LaunchTransitionType.GAME_CONNECTION,
                42, "steam:old", 1L);
        LaunchTransitionSpec newSpec = new LaunchTransitionSpec(
                "new-observer", HOST, LaunchTransitionType.GAME_CONNECTION,
                42, "steam:old", 2L);
        ConsoleStreamTransitionCoordinator oldObserver = new ConsoleStreamTransitionCoordinator(
                oldSpec, providerController(oldSpec), gateway, new InlineExecutor(),
                new FakeClock(), new InterruptingSleeper(), (action, delay) -> { },
                new FakeCallbacks());
        ConsoleStreamTransitionCoordinator newObserver = new ConsoleStreamTransitionCoordinator(
                newSpec, providerController(newSpec), gateway, new InlineExecutor(),
                new FakeClock(), new InterruptingSleeper(), (action, delay) -> { },
                new FakeCallbacks());
        assertTrue(oldObserver.adoptDetachedProviderOwnership());

        assertTrue(oldObserver.transferProviderOwnershipTo(newObserver));
        newObserver.start();
        Thread.interrupted();
        assertTrue(newObserver.detachForSwitch());

        assertEquals(0, gateway.startGameCalls);
        assertEquals(0, gateway.stopGameCalls);
    }

    @Test
    public void reconstructedObservationWithoutStaticOwnerCanDetachSafely() {
        FakeGateway gateway = stoppingObservationGateway();
        LaunchTransitionSpec observation = new LaunchTransitionSpec(
                "restored-observer", HOST, LaunchTransitionType.GAME_CONNECTION,
                42, "steam:old", 1L);
        ConsoleStreamTransitionCoordinator coordinator = new ConsoleStreamTransitionCoordinator(
                observation, providerController(observation), gateway,
                new InlineExecutor(), new FakeClock(), new InterruptingSleeper(),
                (action, delay) -> { }, new FakeCallbacks());
        coordinator.start();
        Thread.interrupted();

        assertTrue(coordinator.detachForSwitch());
        assertEquals(0, gateway.startGameCalls);
        assertEquals(0, gateway.stopGameCalls);
    }

    @Test
    public void neutralObservationWithoutOwnerCanDetachSafely() {
        FakeGateway gateway = stoppingObservationGateway();
        LaunchTransitionSpec observation = new LaunchTransitionSpec(
                "neutral-observer", HOST, LaunchTransitionType.GAME_CONNECTION,
                42, "", 1L);
        ConsoleStreamTransitionCoordinator coordinator = new ConsoleStreamTransitionCoordinator(
                observation, providerController(observation), gateway,
                new InlineExecutor(), new FakeClock(), new InterruptingSleeper(),
                (action, delay) -> { }, new FakeCallbacks());
        coordinator.start();
        Thread.interrupted();

        assertTrue(coordinator.detachForSwitch());
        assertEquals(0, gateway.startGameCalls);
        assertEquals(0, gateway.stopGameCalls);
    }

    @Test
    public void observationRetryCannotOverwriteNewerOwner() {
        FakeGateway gateway = stoppingObservationGateway();
        LaunchTransitionSpec oldSpec = new LaunchTransitionSpec(
                "old-observer", HOST, LaunchTransitionType.GAME_CONNECTION,
                42, "steam:old", 1L);
        LaunchTransitionSpec replacementSpec = new LaunchTransitionSpec(
                "replacement", HOST, LaunchTransitionType.GAME_CONNECTION,
                42, "steam:old", 2L);
        LaunchTransitionSpec newerSpec = providerSpec("newer", "steam:new");
        ConsoleStreamTransitionCoordinator oldObserver = new ConsoleStreamTransitionCoordinator(
                oldSpec, providerController(oldSpec), gateway, new InlineExecutor(),
                new FakeClock(), new InterruptingSleeper(), (action, delay) -> { },
                new FakeCallbacks());
        ConsoleStreamTransitionCoordinator replacement = new ConsoleStreamTransitionCoordinator(
                replacementSpec, providerController(replacementSpec), gateway,
                new InlineExecutor(), new FakeClock(), new InterruptingSleeper(),
                (action, delay) -> { }, new FakeCallbacks());
        ConsoleStreamTransitionCoordinator newer = new ConsoleStreamTransitionCoordinator(
                newerSpec, providerController(newerSpec), gateway, new InlineExecutor(),
                new FakeClock(), new InterruptingSleeper(), (action, delay) -> { },
                new FakeCallbacks());
        assertTrue(oldObserver.adoptDetachedProviderOwnership());
        newer.start();
        Thread.interrupted();

        assertFalse(oldObserver.transferProviderOwnershipTo(replacement));
        assertEquals(1, gateway.startGameCalls);
        assertEquals(0, gateway.stopGameCalls);
    }

    @Test
    public void runningProviderGameDoesNotCloseStreamUntilItActuallyStops() {
        FakeGateway gateway = gatewayWith(snapshotReadyGame());
        Harness harness = new Harness(LaunchTransitionType.GAME, gateway);

        harness.runObservation();

        assertEquals(0, harness.callbacks.providerStopCalls);
        assertEquals(0, gateway.stopGameCalls);
    }

    @Test
    public void rejectedIdleSnapshotCannotStopCurrentGamePresentation() {
        FakeGateway gateway = gatewayWith(snapshotReadyGame(),
                snapshot(false, true, "idle", GAME, 0,
                        false, "game", 0, "stale_observation"));
        Harness harness = new Harness(LaunchTransitionType.GAME, gateway);

        harness.runObservation();

        assertEquals(0, harness.callbacks.providerStopCalls);
    }

    @Test
    public void verificationReportsAllOutcomes() {
        assertVerification(true, "verified");
        assertVerification(false, "still");

        FakeGateway failing = new FakeGateway();
        failing.verifyError = new IOException("failed");
        Harness harness = new Harness(LaunchTransitionType.GENERIC, failing);
        harness.coordinator.start();
        harness.coordinator.verifyInstallation();
        assertEquals("failed", harness.callbacks.verification);

        Harness unavailable = new Harness(LaunchTransitionType.GENERIC, null);
        unavailable.coordinator.start();
        unavailable.coordinator.verifyInstallation();
        assertEquals("failed", unavailable.callbacks.verification);
    }

    private static void assertVerification(boolean result, String expected) {
        FakeGateway gateway = new FakeGateway();
        gateway.verifyResult = result;
        Harness harness = new Harness(LaunchTransitionType.GENERIC, gateway);
        harness.coordinator.start();
        harness.coordinator.verifyInstallation();
        assertEquals(expected, harness.callbacks.verification);
    }

    private static LaunchTransitionSpec providerSpec(String transitionId, String gameId) {
        return providerSpec(transitionId, gameId, true);
    }

    private static LaunchTransitionSpec providerSpec(String transitionId, String gameId,
                                                     boolean startBeforeStream) {
        return new LaunchTransitionSpec(
                transitionId, HOST, LaunchTransitionType.GAME, 42, gameId, 1_000L,
                startBeforeStream);
    }

    private static LaunchTransitionSpec providerSpec(String transitionId, String profileId,
                                                     String gameId) {
        return new LaunchTransitionSpec(
                transitionId, HOST, profileId, LaunchTransitionType.GAME, 42, gameId,
                1_000L, true);
    }

    private static LaunchTransitionController providerController(LaunchTransitionSpec spec) {
        LaunchTransitionController controller = new LaunchTransitionController(null);
        controller.begin(spec);
        controller.overlayRendered(spec.id);
        return controller;
    }

    private static FakeGateway stoppingObservationGateway() {
        FakeGateway gateway = new FakeGateway();
        gateway.observeStartedProcessThenStop = true;
        return gateway;
    }

    private static ConsoleStreamTransitionCoordinator coordinator(
            LaunchTransitionSpec spec, LaunchTransitionController controller,
            FakeGateway gateway, Executor providerExecutor) {
        return new ConsoleStreamTransitionCoordinator(
                spec, controller, gateway, new InlineExecutor(), providerExecutor,
                new FakeClock(), 0L, new InterruptingSleeper(),
                (action, delay) -> { }, new FakeCallbacks());
    }

    private static long timeout(LaunchTransitionState state) {
        return ConsoleStreamTransitionCoordinator.timeoutFor(state);
    }

    private static LaunchTransitionSpec spec(LaunchTransitionType type) {
        return new LaunchTransitionSpec("transition-1", HOST, type, 42,
                type == LaunchTransitionType.PLAYNITE ? "" : GAME, 1_000L);
    }

    private static LaunchTransitionController started(
            LaunchTransitionType type, List<LaunchTransitionState> states) {
        LaunchTransitionController controller = new LaunchTransitionController(snapshot -> {
            if (states != null) states.add(snapshot.state);
        });
        controller.begin(spec(type));
        controller.overlayRendered("transition-1");
        return controller;
    }

    private static void makeGameRunning(LaunchTransitionController controller) {
        controller.surfaceReady("transition-1");
        controller.inputPipelineReady("transition-1");
        controller.streamConnected("transition-1");
        controller.gatewayConnected("transition-1", HOST);
        controller.targetProcessRunning("transition-1", HOST,
                LaunchTransitionType.GAME, GAME);
        controller.targetWindowReady("transition-1", HOST,
                LaunchTransitionType.GAME, GAME);
        controller.videoFrameRendered("transition-1");
        controller.revealCompleted("transition-1");
        assertEquals(LaunchTransitionState.GAME_RUNNING, controller.snapshot().state);
    }

    private static void makeTransportReady(LaunchTransitionController controller) {
        controller.surfaceReady("transition-1");
        controller.inputPipelineReady("transition-1");
        controller.streamConnected("transition-1");
        controller.videoFrameRendered("transition-1");
    }

    private static PlayniteTransitionGateway.Snapshot snapshotReadyPlaynite() {
        return snapshot(true, true, "idle", "", 10, true, "playnite", 0, "");
    }

    private static PlayniteTransitionGateway.Snapshot snapshotReadyGame() {
        return snapshot(true, true, "running", GAME, 10, true, "game", 0, "");
    }

    private static PlayniteTransitionGateway.Snapshot snapshot(boolean gatewayReady,
            boolean connectorReady, String gameState, String gameId, int processId,
            boolean windowReady, String targetKind, int stableSamples, String reason) {
        return new PlayniteTransitionGateway.Snapshot(gatewayReady, connectorReady,
                gameState, gameId, processId, windowReady, targetKind, stableSamples, reason,
                false, processId > 0 ? PROCESS_TOKEN : "");
    }

    private static PlayniteTransitionGateway.Event event(
            long sequence, String name, String gameId, String gameName) {
        return new PlayniteTransitionGateway.Event(sequence, name, gameId, gameName);
    }

    private static PlayniteTransitionGateway.Events events(
            long latest, PlayniteTransitionGateway.Event... values) {
        return new PlayniteTransitionGateway.Events(Arrays.asList(values), latest);
    }

    private static FakeGateway gatewayWith(PlayniteTransitionGateway.Snapshot... snapshots) {
        FakeGateway gateway = new FakeGateway();
        gateway.snapshots.addAll(Arrays.asList(snapshots));
        return gateway;
    }

    private static final class Harness {
        final List<LaunchTransitionState> states = new ArrayList<>();
        final LaunchTransitionController controller;
        final FakeCallbacks callbacks = new FakeCallbacks();
        final ConsoleStreamTransitionCoordinator coordinator;

        Harness(LaunchTransitionType type, ConsoleStreamTransitionCoordinator.Gateway gateway) {
            this(type, gateway, new FakeClock(), new InterruptingSleeper());
        }

        Harness(LaunchTransitionType type, ConsoleStreamTransitionCoordinator.Gateway gateway,
                FakeClock clock, ConsoleStreamTransitionCoordinator.Sleeper sleeper) {
            this(type, gateway, clock, sleeper, (action, delay) -> { });
        }

        Harness(LaunchTransitionType type, ConsoleStreamTransitionCoordinator.Gateway gateway,
                FakeClock clock, ConsoleStreamTransitionCoordinator.Sleeper sleeper,
                ConsoleStreamTransitionCoordinator.Scheduler scheduler) {
            controller = started(type, states);
            if (gateway instanceof FakeGateway) ((FakeGateway) gateway).clock = clock;
            coordinator = new ConsoleStreamTransitionCoordinator(spec(type), controller, gateway,
                    new InlineExecutor(), clock, sleeper, scheduler, callbacks);
        }

        void runObservation() {
            coordinator.start();
            coordinator.onStreamConnected();
            Thread.interrupted();
        }
    }

    private static final class FakeRetainedController
            implements SessionModel.Controller {
        private final String executionProfileId;
        private int disconnectCalls;
        private int terminateCalls;

        FakeRetainedController(String executionProfileId) {
            this.executionProfileId = executionProfileId;
        }

        @Override public boolean isRetainedTransportLive() { return true; }
        @Override public String getExecutionProfileId() { return executionProfileId; }
        @Override public SessionModel.ParkOutcome parkRetainedTransport() { return SessionModel.ParkOutcome.PARKED; }
        @Override public void disconnectRetainedStream(
                SessionModel.TerminationCallback completion) {
            disconnectCalls++;
            completion.complete(true);
        }
        @Override public void terminateRetainedSession(
                SessionModel.TerminationCallback completion) {
            terminateCalls++;
            completion.complete(true);
        }
        @Override public void switchGame(SessionModel.SwitchRequest request,
                                         SessionModel.SwitchCallback completion) { }
    }

    private static void resetRetainedStream() throws Exception {
        m.clear();
    }

    private static class FakeGateway implements ConsoleStreamTransitionCoordinator.Gateway {
        final List<Object> snapshots = new ArrayList<>();
        final List<PlayniteTransitionGateway.Events> events = new ArrayList<>();
        final List<Long> awaitAfter = new ArrayList<>();
        final List<Long> focusTimes = new ArrayList<>();
        final List<Runnable> snapshotActions = new ArrayList<>();
        int snapshotIndex;
        int eventIndex;
        int fullscreenCalls;
        int focusGameCalls;
        int focusInstallationCalls;
        int ensureTargetCalls;
        int startGameCalls;
        int stopGameCalls;
        String startedGameId = "";
        String stoppedGameId = "";
        String stoppedProcessToken = "";
        List<String> actions;
        Runnable snapshotAction;
        FakeClock clock;
        boolean verifyResult;
        IOException verifyError;
        IOException startError;
        boolean focusGameError;
        boolean observeStartedProcessThenStop;
        int unlockCalls;
        boolean unlockResult;
        Runnable onUnlock;
        java.util.function.BooleanSupplier unlockCancelled;

        @Override public boolean unlockHostSession(java.util.function.BooleanSupplier cancelled) {
            unlockCalls++;
            unlockCancelled = cancelled;
            if (onUnlock != null) onUnlock.run();
            return unlockResult;
        }

        @Override public PlayniteTransitionGateway.Snapshot snapshot() throws IOException {
            if (observeStartedProcessThenStop) {
                int index = snapshotIndex++;
                if (index % 3 != 1) {
                    return new PlayniteTransitionGateway.Snapshot(
                            true, true, "running", startedGameId, 42,
                            true, "game", 1, "", false, PROCESS_TOKEN);
                }
                throw new IOException("stop observation");
            }
            if (snapshotAction != null) snapshotAction.run();
            if (snapshotIndex < snapshotActions.size()) snapshotActions.get(snapshotIndex).run();
            if (snapshotIndex >= snapshots.size()) throw new IOException("done");
            Object value = snapshots.get(snapshotIndex++);
            if (value instanceof IOException) throw (IOException) value;
            if (value instanceof RuntimeException) throw (RuntimeException) value;
            return (PlayniteTransitionGateway.Snapshot) value;
        }

        @Override public PlayniteTransitionGateway.Events awaitEvents(
                long after, String transitionId) {
            awaitAfter.add(after);
            return eventIndex < events.size() ? events.get(eventIndex++) : events(after);
        }

        @Override public void showFullscreen() {
            fullscreenCalls++;
        }

        @Override public void focusGame() throws IOException {
            focusGameCalls++;
            if (clock != null) focusTimes.add(clock.value);
            if (focusGameError) throw new IOException("focus failed");
        }

        @Override public void focusInstallation(String gameId) {
            focusInstallationCalls++;
        }

        @Override public boolean verifyInstallation(String gameId) throws IOException {
            if (verifyError != null) throw verifyError;
            return verifyResult;
        }

        @Override public void ensureInstalledGameTarget(String gameId, String gameName) {
            ensureTargetCalls++;
        }

        @Override public void startGame(String gameId) throws IOException {
            startGameCalls++;
            startedGameId = gameId;
            if (actions != null) actions.add("start:" + gameId);
            if (startError != null) throw startError;
        }

        @Override public void stopGame(String gameId, String processToken) {
            stopGameCalls++;
            stoppedGameId = gameId;
            stoppedProcessToken = processToken;
            if (actions != null) actions.add("stop:" + gameId);
        }
    }

    private static class FakeCallbacks
            implements ConsoleStreamTransitionCoordinator.Callbacks {
        final List<String> guideDecisions = new ArrayList<>();
        Runnable onGuidePolicy;
        @Override public void onHostGuidePolicy(String transitionId, String gameId, boolean allowed) {
            guideDecisions.add(transitionId + ":" + gameId + ":" + allowed);
            if (onGuidePolicy != null) onGuidePolicy.run();
        }

        final List<String> pending = new ArrayList<>();
        final List<String> completed = new ArrayList<>();
        final List<String> cancelled = new ArrayList<>();
        final List<String> failed = new ArrayList<>();
        final List<String> attention = new ArrayList<>();
        String verification = "";
        int providerStopCalls;
        int providerStartAcceptedCalls;
        int providerStartFailedCalls;
        int cleanupCalls;
        boolean lastCleanupSuccess;
        boolean controlAuthorized = true;

        @Override public boolean controlTransportAuthorized() {
            return controlAuthorized;
        }

        @Override public String gatewayUnavailableMessage() { return "gateway unavailable"; }
        @Override public String hostSessionLockedMessage() { return "host locked"; }
        @Override public String streamDisplayNotConfiguredMessage() { return "display missing"; }
        @Override public String targetWrongDisplayMessage() { return "wrong display"; }
        @Override public String readinessUnconfirmedMessage() { return "unconfirmed"; }
        @Override public String gameIdentityPendingMessage() {
            return "waiting for process details";
        }
        @Override public String gameWindowPendingMessage() { return "waiting for game window"; }
        @Override public String gameWindowNotFullscreenMessage() {
            return "window not fullscreen";
        }
        @Override public String gameWindowNotForegroundMessage() { return "window not active"; }
        @Override public String launcherInteractionRequiredMessage() { return "reveal launcher"; }
        @Override public String windowStabilizingMessage() { return "stabilizing"; }
        @Override public boolean isPendingInstallation(String hostId, String gameId) {
            return pending.contains(gameId);
        }
        @Override public String pendingInstallationName(String hostId, String gameId) {
            return "pending " + gameId;
        }
        @Override public void onInstallationCompleted(
                String hostId, String gameId, String gameName) { completed.add(gameId); }
        @Override public void onInstallationCancelled(
                String hostId, String gameId, String gameName) { cancelled.add(gameId); }
        @Override public void onInstallationFailed(
                String hostId, String gameId, String gameName) { failed.add(gameId); }
        @Override public void onInstallationAttentionRequired(
                String hostId, String gameId, String gameName) { attention.add(gameId); }
        @Override public void onProviderGameStopped(
                String transitionId, String gameId) { providerStopCalls++; }
        @Override public void onProviderGameStartAccepted(
                String transitionId, String gameId) { providerStartAcceptedCalls++; }
        @Override public void onProviderGameStartFailed(
                String transitionId, String gameId,
                ConsoleStreamTransitionCoordinator.ProviderStartFailure failure) {
            providerStartFailedCalls++;
        }
        @Override public void onProviderGameCleanupComplete(
                String transitionId, String gameId, boolean success) {
            cleanupCalls++;
            lastCleanupSuccess = success;
        }
        @Override public void onInstallationVerified() { verification = "verified"; }
        @Override public void onInstallationStillNeedsConfirmation() { verification = "still"; }
        @Override public void onInstallationVerificationFailed() { verification = "failed"; }
    }

    private static final class FakeClock
            implements ConsoleStreamTransitionCoordinator.MonotonicClock {
        long value;
        @Override public long now() { return value; }
    }

    private static class InterruptingSleeper
            implements ConsoleStreamTransitionCoordinator.Sleeper {
        @Override public void sleep(long delayMs) throws InterruptedException {
            throw new InterruptedException("end test observation");
        }
    }

    private static final class ResetSleeper extends InterruptingSleeper {
        final List<Long> delays = new ArrayList<>();
        @Override public void sleep(long delayMs) throws InterruptedException {
            delays.add(delayMs);
            if (delays.size() >= 4) throw new InterruptedException("done");
        }
    }

    private static final class QueuedScheduler
            implements ConsoleStreamTransitionCoordinator.Scheduler {
        final List<Long> delays = new ArrayList<>();
        final List<Runnable> actions = new ArrayList<>();
        @Override public void postDelayed(Runnable action, long delayMs) {
            delays.add(delayMs);
            actions.add(action);
        }
    }

    private static final class QueuedExecutor implements Executor {
        final List<Runnable> actions = new ArrayList<>();
        @Override public void execute(Runnable action) { actions.add(action); }
        void runNext() { actions.remove(0).run(); }
        void runAll() { while (!actions.isEmpty()) runNext(); }
    }

    private static final class InlineExecutor extends AbstractExecutorService {
        private boolean shutdown;
        @Override public void shutdown() { shutdown = true; }
        @Override public List<Runnable> shutdownNow() {
            shutdown = true;
            return Collections.emptyList();
        }
        @Override public boolean isShutdown() { return shutdown; }
        @Override public boolean isTerminated() { return shutdown; }
        @Override public boolean awaitTermination(long timeout, TimeUnit unit) {
            return shutdown;
        }
        @Override public void execute(Runnable command) {
            if (shutdown) throw new IllegalStateException("shutdown");
            command.run();
        }
    }
}
