package com.limelight.console;

import org.junit.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;

public class PlayniteLaunchOrchestratorTest {
    private static final String GAME = "840317c9-b9a4-4f72-be8e-807414e36a9b";

    @Test public void alreadyRunningGameIsNotStartedAgain() throws Exception {
        FakeBackend backend = new FakeBackend();
        backend.current = new HostGatewayClient.PlayniteCurrentGame("running", GAME, "Baba", 42);
        backend.readiness = new HostGatewayClient.PlayniteReadiness(
                true, "target_window_ready", "game", 3, 42, "DISPLAY15");
        CountDownLatch done = new CountDownLatch(1);
        PlayniteLaunchOrchestrator orchestrator = new PlayniteLaunchOrchestrator(backend, 500);
        orchestrator.launch(request(), listener(done));
        assertTrue(done.await(1, TimeUnit.SECONDS));
        assertEquals(0, backend.starts);
        orchestrator.close();
    }

    @Test public void newGameStartsOnceAndWaitsForReady() throws Exception {
        FakeBackend backend = new FakeBackend();
        backend.current = new HostGatewayClient.PlayniteCurrentGame("idle", "", "", 0);
        backend.readiness = new HostGatewayClient.PlayniteReadiness(
                true, "target_window_ready", "game", 3, 42, "DISPLAY15");
        CountDownLatch done = new CountDownLatch(1);
        PlayniteLaunchOrchestrator orchestrator = new PlayniteLaunchOrchestrator(backend, 500);
        orchestrator.launch(request(), listener(done));
        assertTrue(done.await(1, TimeUnit.SECONDS));
        assertEquals(1, backend.starts);
        orchestrator.close();
    }

    @Test public void transientReadinessFailureDoesNotDuplicateAcceptedLaunch() throws Exception {
        FakeBackend backend = new FakeBackend();
        backend.current = new HostGatewayClient.PlayniteCurrentGame("idle", "", "", 0);
        backend.readinessFailures = 1;
        backend.readiness = new HostGatewayClient.PlayniteReadiness(
                true, "target_window_ready", "game", 3, 42, "DISPLAY15");
        CountDownLatch done = new CountDownLatch(1);
        PlayniteLaunchOrchestrator orchestrator = new PlayniteLaunchOrchestrator(backend, 2_000);
        orchestrator.launch(request(), listener(done));
        assertTrue(done.await(3, TimeUnit.SECONDS));
        assertEquals(1, backend.starts);
        orchestrator.close();
    }

    private static LaunchOrchestrator.Request request() {
        return new LaunchOrchestrator.Request("host", "default", "playnite", GAME);
    }

    private static LaunchOrchestrator.Listener listener(CountDownLatch done) {
        return new LaunchOrchestrator.Listener() {
            @Override public void onStarting() { }
            @Override public void onRunning(LaunchOrchestrator.ReadinessSample sample) {
                if (sample.consecutiveStableSamples >= 3) done.countDown();
            }
            @Override public void onStopped() { }
            @Override public void onFailure(String safeMessage) { fail(safeMessage); }
        };
    }

    private static final class FakeBackend implements PlayniteLaunchOrchestrator.Backend {
        HostGatewayClient.PlayniteCurrentGame current;
        HostGatewayClient.PlayniteReadiness readiness;
        int readinessFailures;
        int starts;
        @Override public HostGatewayClient.PlayniteCurrentGame current() { return current; }
        @Override public void start(String gameId) { starts++; }
        @Override public void showFullscreen() { }
        @Override public HostGatewayClient.PlayniteReadiness readiness() throws Exception {
            if (readinessFailures-- > 0) throw new java.io.IOException("bridge restarted");
            return readiness;
        }
    }
}
