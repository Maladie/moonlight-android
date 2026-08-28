package com.limelight.console;

import com.limelight.console.transition.LaunchTransitionController;
import com.limelight.console.transition.LaunchTransitionSpec;
import com.limelight.console.transition.LaunchTransitionState;
import com.limelight.console.transition.LaunchTransitionType;

import org.junit.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ConsoleStreamTransitionCoordinatorTest {
    private static final String HOST = "host-1";
    private static final String GAME = "11111111-2222-3333-4444-555555555555";

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
        assertTrue(harness.states.contains(LaunchTransitionState.GAME_RUNNING));
        assertTrue(harness.controller.snapshot().overlayVisible);
        assertTrue(harness.controller.snapshot().inputBlocked);
        assertEquals("host locked", harness.controller.snapshot().detail);
    }

    @Test
    public void lockedSessionSkipsPlayniteFullscreenAndReadinessTimeout() {
        PlayniteTransitionGateway.Snapshot locked = snapshot(true, true, "idle", "", 0,
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
    public void subsequentEventsMapToController() {
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

        assertTrue(harness.states.contains(LaunchTransitionState.GAME_STARTING));
        assertTrue(harness.states.contains(LaunchTransitionState.GAME_PROCESS_RUNNING));
        assertTrue(harness.states.contains(LaunchTransitionState.GAME_WINDOW_STABILIZING));
        assertEquals(1, harness.callbacks.providerStopCalls);
        assertEquals(LaunchTransitionState.PLAYNITE_STOPPING,
                harness.controller.snapshot().state);
    }

    @Test
    public void timeoutDurationsRemainExact() {
        assertEquals(90_000L, timeout(LaunchTransitionState.PREPARING_SESSION));
        assertEquals(30_000L, timeout(LaunchTransitionState.CONNECTING_STREAM));
        assertEquals(15_000L, timeout(LaunchTransitionState.WAITING_FOR_VIDEO_SURFACE));
        assertEquals(45_000L, timeout(LaunchTransitionState.PLAYNITE_STARTING));
        assertEquals(45_000L, timeout(LaunchTransitionState.PLAYNITE_PROCESS_RUNNING));
        assertEquals(30_000L, timeout(LaunchTransitionState.PLAYNITE_FULLSCREEN_STARTING));
        assertEquals(120_000L, timeout(LaunchTransitionState.GAME_START_REQUESTED));
        assertEquals(120_000L, timeout(LaunchTransitionState.GAME_STARTING));
        assertEquals(120_000L, timeout(LaunchTransitionState.GAME_PROCESS_RUNNING));
        assertEquals(30_000L, timeout(LaunchTransitionState.GAME_WINDOW_STABILIZING));
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

        assertEquals(Arrays.asList(1_000L, 1_000L, 2_000L, 3_000L), sleeper.delays);
        assertEquals(LaunchTransitionState.ERROR, harness.controller.snapshot().state);
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
        LaunchTransitionController controller = started(LaunchTransitionType.GAME, null);
        ConsoleStreamTransitionCoordinator coordinator = new ConsoleStreamTransitionCoordinator(
                spec(LaunchTransitionType.GAME), controller, gateway, executor,
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
    public void existingGameConnectionDoesNotRestartGameOrActLikeInstallation() {
        FakeGateway gateway = new FakeGateway();
        QueuedScheduler scheduler = new QueuedScheduler();
        Harness harness = new Harness(LaunchTransitionType.GAME_CONNECTION, gateway,
                new FakeClock(), new InterruptingSleeper(), scheduler);

        harness.coordinator.start();
        harness.coordinator.onStreamConnected();

        assertFalse(harness.coordinator.isInstallationConfirmationStream());
        assertEquals(0, gateway.startGameCalls);
        assertEquals(0, gateway.focusInstallationCalls);
        assertTrue(scheduler.actions.isEmpty());
    }

    @Test
    public void providerRecordStartsExactlyOnceAfterStreamConnect() {
        LaunchTransitionSpec providerSpec = new LaunchTransitionSpec(
                "transition-provider", HOST, LaunchTransitionType.GAME, 42,
                "steam:289070", 1_000L);
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

        coordinator.onStreamConnected();
        coordinator.onStreamConnected();

        assertEquals(1, gateway.startGameCalls);
        assertEquals("steam:289070", gateway.startedGameId);
    }

    @Test
    public void cancelledProviderStartIsCompensatedAfterItReturns() throws Exception {
        LaunchTransitionSpec providerSpec = new LaunchTransitionSpec(
                "transition-provider", HOST, LaunchTransitionType.GAME, 42,
                "steam:289070", 1_000L);
        LaunchTransitionController controller = new LaunchTransitionController(null);
        controller.begin(providerSpec);
        controller.overlayRendered(providerSpec.id);
        CountDownLatch startEntered = new CountDownLatch(1);
        CountDownLatch releaseStart = new CountDownLatch(1);
        CountDownLatch stopCalled = new CountDownLatch(1);
        FakeGateway gateway = new FakeGateway() {
            @Override public void startGame(String gameId) throws IOException {
                super.startGame(gameId);
                startEntered.countDown();
                try {
                    releaseStart.await(2, TimeUnit.SECONDS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            }

            @Override public void stopGame(String gameId) {
                super.stopGame(gameId);
                stopCalled.countDown();
            }
        };
        ExecutorService executor = Executors.newFixedThreadPool(2);
        ConsoleStreamTransitionCoordinator coordinator = new ConsoleStreamTransitionCoordinator(
                providerSpec, controller, gateway, executor, new FakeClock(),
                new InterruptingSleeper(), (action, delay) -> { }, new FakeCallbacks());

        coordinator.start();
        coordinator.onStreamConnected();
        assertTrue(startEntered.await(2, TimeUnit.SECONDS));
        coordinator.cancel();
        releaseStart.countDown();

        assertTrue(stopCalled.await(2, TimeUnit.SECONDS));
        assertEquals(1, gateway.startGameCalls);
        assertEquals(1, gateway.stopGameCalls);
        assertEquals("steam:289070", gateway.stoppedGameId);
        coordinator.close();
        assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS));
    }

    @Test
    public void stoppedProviderStartFailureCannotMutateOldTransition() throws Exception {
        LaunchTransitionSpec providerSpec = new LaunchTransitionSpec(
                "transition-provider", HOST, LaunchTransitionType.GAME, 42,
                "steam:289070", 1_000L);
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
        coordinator.onStreamConnected();
        assertTrue(startEntered.await(2, TimeUnit.SECONDS));
        coordinator.stop();
        releaseStart.countDown();
        coordinator.close();

        assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS));
        assertEquals(before, controller.snapshot().state);
    }

    @Test
    public void providerStartIsNotStarvedByLongRunningObservation() throws Exception {
        LaunchTransitionSpec providerSpec = new LaunchTransitionSpec(
                "transition-provider", HOST, LaunchTransitionType.GAME, 42,
                "steam:289070", 1_000L);
        LaunchTransitionController controller = new LaunchTransitionController(null);
        controller.begin(providerSpec);
        controller.overlayRendered(providerSpec.id);
        CountDownLatch observationEntered = new CountDownLatch(1);
        CountDownLatch releaseObservation = new CountDownLatch(1);
        CountDownLatch startCalled = new CountDownLatch(1);
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
            }
        };
        ExecutorService executor = Executors.newFixedThreadPool(2);
        ConsoleStreamTransitionCoordinator coordinator = new ConsoleStreamTransitionCoordinator(
                providerSpec, controller, gateway, executor, new FakeClock(),
                new InterruptingSleeper(), (action, delay) -> { }, new FakeCallbacks());

        coordinator.start();
        assertTrue(observationEntered.await(2, TimeUnit.SECONDS));
        coordinator.onStreamConnected();
        assertTrue(startCalled.await(2, TimeUnit.SECONDS));
        assertEquals("steam:289070", gateway.startedGameId);

        releaseObservation.countDown();
        coordinator.close();
        assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS));
    }

    @Test
    public void providerLauncherInteractionKeepsRevealChoiceAvailable() {
        LaunchTransitionSpec providerSpec = new LaunchTransitionSpec(
                "transition-provider", HOST, LaunchTransitionType.GAME, 42,
                "steam:289070", 1_000L);
        LaunchTransitionController controller = new LaunchTransitionController(null);
        controller.begin(providerSpec);
        controller.overlayRendered(providerSpec.id);
        FakeGateway gateway = new FakeGateway();
        gateway.snapshots.add(new IOException("stop observation"));
        gateway.startError = new IOException("launcher_interaction_required");
        ConsoleStreamTransitionCoordinator coordinator =
                new ConsoleStreamTransitionCoordinator(
                        providerSpec, controller, gateway, new InlineExecutor(),
                        new FakeClock(), new InterruptingSleeper(), (action, delay) -> { },
                        new FakeCallbacks());
        coordinator.start();
        Thread.interrupted();

        coordinator.onStreamConnected();

        assertEquals(LaunchTransitionState.LAUNCHER_INTERACTION_REQUIRED,
                controller.snapshot().state);
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
                gameState, gameId, processId, windowReady, targetKind, stableSamples, reason);
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
            Thread.interrupted();
        }
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
        Runnable snapshotAction;
        FakeClock clock;
        boolean verifyResult;
        IOException verifyError;
        IOException startError;
        boolean focusGameError;

        @Override public PlayniteTransitionGateway.Snapshot snapshot() throws IOException {
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
            if (startError != null) throw startError;
        }

        @Override public void stopGame(String gameId) {
            stopGameCalls++;
            stoppedGameId = gameId;
        }
    }

    private static final class FakeCallbacks
            implements ConsoleStreamTransitionCoordinator.Callbacks {
        final List<String> pending = new ArrayList<>();
        final List<String> completed = new ArrayList<>();
        final List<String> cancelled = new ArrayList<>();
        final List<String> failed = new ArrayList<>();
        final List<String> attention = new ArrayList<>();
        String verification = "";
        int providerStopCalls;

        @Override public String gatewayUnavailableMessage() { return "gateway unavailable"; }
        @Override public String hostSessionLockedMessage() { return "host locked"; }
        @Override public String streamDisplayNotConfiguredMessage() { return "display missing"; }
        @Override public String readinessUnconfirmedMessage() { return "unconfirmed"; }
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
        @Override public void onProviderGameStopped() { providerStopCalls++; }
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
