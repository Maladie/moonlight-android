package com.limelight.console;

import com.limelight.gateway.GatewayConnection;

import org.junit.Test;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.Executor;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class GameOperationsControllerTest {
    private static final String HOST_A = "Host-A";
    private static final String HOST_B = "Host-B";
    private static final String GAME_ID = "00000001-0000-0000-0000-000000000000";

    @Test public void localInstallProjectsAsRequestingUntilHostExposesState() {
        Fixture fixture = new Fixture(Runnable::run);
        PlayniteLibraryGame idle = game(false, false, false, "", -1, false, "");

        GameOperationsController.Presentation requested = fixture.controller.requestInstall(
                HOST_A, idle, null, success -> {});

        assertEquals(GameOperationsController.Phase.REQUESTING_INSTALL, requested.phase);
        assertTrue(requested.installActive);
        assertEquals(GameOperationsController.Phase.DOWNLOADING,
                fixture.controller.presentation(HOST_A,
                        game(false, false, false, "downloading", 18, false, "")).phase);
    }

    @Test public void projectsHostInstallPhasesAttentionAndProgressDirectly() {
        Fixture fixture = new Fixture(Runnable::run);
        List<String> states = Arrays.asList(
                "preparing", "downloading", "installing", "attention_required");
        List<GameOperationsController.Phase> phases = Arrays.asList(
                GameOperationsController.Phase.PREPARING,
                GameOperationsController.Phase.DOWNLOADING,
                GameOperationsController.Phase.INSTALLING,
                GameOperationsController.Phase.ATTENTION_REQUIRED);

        for (int index = 0; index < states.size(); index++) {
            GameOperationsController.Presentation value = fixture.controller.presentation(
                    HOST_A, game(false, false, false, states.get(index), 42,
                            "attention_required".equals(states.get(index)), "launcher_prompt"));
            assertEquals(phases.get(index), value.phase);
            assertEquals(42, value.progress);
            assertTrue(value.installActive);
        }
        GameOperationsController.Presentation attention = fixture.controller.presentation(
                HOST_A, game(false, false, false, "attention_required", -1,
                        true, "launcher_prompt"));
        assertTrue(attention.attentionRequired);
        assertEquals("launcher_prompt", attention.attentionReason);
    }

    @Test public void uninstallProjectionIsDistinctFromInstallProjection() {
        Fixture fixture = new Fixture(Runnable::run);
        GameOperationsController.Presentation value = fixture.controller.presentation(
                HOST_A, game(true, false, true, "uninstalling", 9, false, ""));

        assertEquals(GameOperationsController.Phase.UNINSTALLING, value.phase);
        assertTrue(value.uninstallActive);
        assertFalse(value.installActive);
    }

    @Test public void failedHostOperationIsIdleAndNeverRequiresContinuation() {
        Fixture fixture = new Fixture(Runnable::run);
        GameOperationsController.Presentation value = fixture.controller.presentation(
                HOST_A, game(false, false, false, "failed", -1, false, ""));

        assertEquals(GameOperationsController.Phase.FAILED, value.phase);
        assertFalse(value.installActive);
        assertFalse(value.uninstallActive);
        assertFalse(value.attentionRequired);
        assertFalse(value.launchBlocked);
    }

    @Test public void installConfirmationOnlyComesFromInstalledSnapshot() {
        Fixture fixture = new Fixture(Runnable::run);
        fixture.controller.requestInstall(HOST_A,
                game(false, false, false, "", -1, false, ""), null, success -> {});

        assertTrue(fixture.controller.reconcile(HOST_A, Collections.singletonList(
                game(false, false, false, "", -1, false, ""))).isEmpty());
        List<GameOperationsController.Observation> observations = fixture.controller.reconcile(
                HOST_A, Collections.singletonList(
                        game(true, false, false, "completed", 100, false, "")));

        assertEquals(1, observations.size());
        assertEquals(GameOperationsController.ObservationType.INSTALL_CONFIRMED_BY_SNAPSHOT,
                observations.get(0).type);
    }

    @Test public void externalInstalledSnapshotDoesNotClaimLocalCompletion() {
        Fixture fixture = new Fixture(Runnable::run);
        assertTrue(fixture.controller.reconcile(HOST_A, Collections.singletonList(
                game(true, false, false, "completed", 100, false, ""))).isEmpty());
    }

    @Test public void inactiveAfterObservedInstallActivityEmitsPreciseObservation() {
        Fixture fixture = new Fixture(Runnable::run);
        fixture.controller.requestInstall(HOST_A,
                game(false, false, false, "", -1, false, ""), null, success -> {});
        assertTrue(fixture.controller.reconcile(HOST_A, Collections.singletonList(
                game(false, false, false, "downloading", 20, false, ""))).isEmpty());

        List<GameOperationsController.Observation> observations = fixture.controller.reconcile(
                HOST_A, Collections.singletonList(
                        game(false, false, false, "", -1, false, "")));

        assertEquals(GameOperationsController.ObservationType
                .INSTALL_NO_LONGER_ACTIVE_AFTER_OBSERVED_ACTIVITY,
                observations.get(0).type);
        assertTrue(fixture.controller.reconcile(HOST_A, Collections.singletonList(
                game(false, false, false, "", -1, false, ""))).isEmpty());
    }

    @Test public void missingGameDoesNotEmitTerminalObservation() {
        Fixture fixture = new Fixture(Runnable::run);
        fixture.controller.requestInstall(HOST_A,
                game(false, false, false, "", -1, false, ""), null, success -> {});
        fixture.controller.reconcile(HOST_A, Collections.singletonList(
                game(false, false, false, "installing", 20, false, "")));

        assertTrue(fixture.controller.reconcile(HOST_A, Collections.emptyList()).isEmpty());
        assertTrue(fixture.controller.hasActiveOperation(HOST_A, Collections.emptyList()));
    }

    @Test public void uninstallConfirmationComesFromInstalledFalseSnapshot() {
        Fixture fixture = new Fixture(Runnable::run);
        fixture.controller.requestUninstall(HOST_A,
                game(true, false, false, "", -1, false, ""), null, success -> {});

        assertTrue(fixture.controller.reconcile(HOST_A, Collections.singletonList(
                game(true, false, true, "uninstalling", 10, false, ""))).isEmpty());
        assertEquals(GameOperationsController.ObservationType.UNINSTALL_CONFIRMED_BY_SNAPSHOT,
                fixture.controller.reconcile(HOST_A, Collections.singletonList(
                        game(false, false, false, "completed", 100, false, "")))
                        .get(0).type);
    }

    @Test public void hostAndGameIdsDoNotCrossContaminateCorrelation() {
        Fixture fixture = new Fixture(Runnable::run);
        PlayniteLibraryGame idle = game(false, false, false, "", -1, false, "");
        fixture.controller.requestInstall("  HOST-A ", idle, null, success -> {});

        assertEquals(GameOperationsController.Phase.IDLE,
                fixture.controller.presentation(HOST_B, idle).phase);
        assertEquals(GameOperationsController.Phase.IDLE,
                fixture.controller.presentation(HOST_A, game(
                        "00000002-0000-0000-0000-000000000000", false, false,
                        false, "", -1, false, "")).phase);
        assertTrue(fixture.controller.reconcile(HOST_B, Collections.singletonList(
                game(true, false, false, "completed", 100, false, ""))).isEmpty());
        assertEquals(GameOperationsController.Phase.REQUESTING_INSTALL,
                fixture.controller.presentation("host-a", idle).phase);
    }

    @Test public void staleAsyncFailureCannotClearNewerRequestToken() {
        QueueExecutor executor = new QueueExecutor();
        Fixture fixture = new Fixture(executor);
        fixture.gateway.failNextInstall = true;
        PlayniteLibraryGame idle = game(false, false, false, "", -1, false, "");
        int[] callbacks = {0};
        fixture.controller.requestInstall(HOST_A, idle, null, success -> callbacks[0]++);
        fixture.controller.requestInstall(HOST_A, idle, null, success -> callbacks[0]++);

        executor.runNext();
        fixture.dispatcher.runAll();

        assertEquals(0, callbacks[0]);
        assertEquals(GameOperationsController.Phase.REQUESTING_INSTALL,
                fixture.controller.presentation(HOST_A, idle).phase);
    }

    @Test public void rejectedOperationClearsOptimisticStateAndAllowsImmediateRetry() {
        Fixture fixture = new Fixture(Runnable::run);
        fixture.gateway.failNextInstall = true;
        PlayniteLibraryGame idle = game(false, false, false, "", -1, false, "");
        boolean[] results = {true, false};

        fixture.controller.requestInstall(HOST_A, idle, null, success -> results[0] = success);
        fixture.dispatcher.runAll();

        assertFalse(results[0]);
        assertEquals(GameOperationsController.Phase.IDLE,
                fixture.controller.presentation(HOST_A, idle).phase);
        assertEquals(0, fixture.gateway.focusRequests);

        fixture.controller.requestInstall(HOST_A, idle, null, success -> results[1] = success);
        fixture.dispatcher.runAll();

        assertTrue(results[1]);
        assertEquals(GameOperationsController.Phase.REQUESTING_INSTALL,
                fixture.controller.presentation(HOST_A, idle).phase);
    }

    @Test public void closePreventsLateCallbacks() {
        QueueExecutor executor = new QueueExecutor();
        Fixture fixture = new Fixture(executor);
        int[] callbacks = {0};
        PlayniteLibraryGame idle = game(false, false, false, "", -1, false, "");
        fixture.controller.requestInstall(HOST_A, idle, null, success -> callbacks[0]++);
        executor.runNext();
        fixture.controller.close();
        fixture.dispatcher.runAll();

        assertEquals(0, callbacks[0]);
        assertEquals(GameOperationsController.Phase.IDLE,
                fixture.controller.presentation(HOST_A, idle).phase);
    }

    @Test public void confirmationBlockingPreservesExistingNarrowRules() {
        Fixture preparing = new Fixture(Runnable::run);
        assertTrue(preparing.controller.hasOperationAwaitingConfirmation(HOST_A,
                Collections.singletonList(game(false, false, false,
                        "preparing", -1, false, ""))));
        assertTrue(preparing.controller.hasOperationAwaitingConfirmation(HOST_A,
                Collections.singletonList(game(false, false, false,
                        "attention_required", -1, true, "prompt"))));

        Fixture local = new Fixture(Runnable::run);
        local.controller.requestInstall(HOST_A,
                game(false, false, false, "", -1, false, ""), null, success -> {});
        assertTrue(local.controller.hasOperationAwaitingConfirmation(HOST_A,
                Collections.singletonList(game(false, false, false, "", -1, false, ""))));
        assertFalse(local.controller.hasOperationAwaitingConfirmation(HOST_A,
                Collections.singletonList(game(false, false, false,
                        "downloading", 10, false, ""))));
    }

    @Test public void attentionContinuationChoosesDesktopOrFocus() {
        Fixture fixture = new Fixture(Runnable::run);
        for (String reason : Arrays.asList("secure_desktop", "epic_manual")) {
            assertEquals(GameOperationsController.Continuation.OPEN_INSTALLATION_DESKTOP,
                    fixture.controller.continuation(fixture.controller.presentation(HOST_A,
                            game(false, false, false, "attention_required", -1,
                                    true, reason))));
        }
        assertEquals(GameOperationsController.Continuation.FOCUS_INSTALLATION_WINDOW,
                fixture.controller.continuation(fixture.controller.presentation(HOST_A,
                        game(false, false, false, "attention_required", -1,
                                true, "launcher_prompt"))));
    }

    @Test public void focusSuccessAndFailureReturnThroughDispatcher() {
        Fixture fixture = new Fixture(Runnable::run);
        int[] result = {-1};
        fixture.controller.requestInstallationFocus(null, GAME_ID,
                success -> result[0] = success ? 1 : 0);
        assertEquals(-1, result[0]);
        fixture.dispatcher.runAll();
        assertEquals(1, result[0]);

        fixture.gateway.focusFails = true;
        fixture.controller.requestInstallationFocus(null, GAME_ID,
                success -> result[0] = success ? 1 : 0);
        fixture.dispatcher.runAll();
        assertEquals(0, result[0]);
    }

    @Test public void authoritativeUninstallSelectsFastRefreshWithoutLocalState() {
        Fixture fixture = new Fixture(Runnable::run);
        assertTrue(fixture.controller.hasActiveOperation(HOST_A,
                Collections.singletonList(game(true, false, true,
                        "", -1, false, ""))));
        assertTrue(fixture.controller.hasActiveOperation(HOST_A,
                Collections.singletonList(game(true, false, false,
                        "uninstalling", -1, false, ""))));
    }

    private static PlayniteLibraryGame game(boolean installed, boolean installing,
                                            boolean uninstalling, String state, int progress,
                                            boolean attention, String reason) {
        return game(GAME_ID, installed, installing, uninstalling, state, progress,
                attention, reason);
    }

    private static PlayniteLibraryGame game(String gameId, boolean installed,
                                            boolean installing, boolean uninstalling,
                                            String state, int progress, boolean attention,
                                            String reason) {
        return new PlayniteLibraryGame(gameId, "Game", installed, installing, false,
                0L, "", "", "", "", 0, "Steam", "", attention, reason,
                "Installer", "Launcher", state, progress, uninstalling);
    }

    private static final class Fixture {
        final FakeGateway gateway = new FakeGateway();
        final QueuedDispatcher dispatcher = new QueuedDispatcher();
        final GameOperationsController controller;

        Fixture(Executor executor) {
            controller = new GameOperationsController(gateway, executor, dispatcher);
        }
    }

    private static final class FakeGateway implements GameOperationsController.Gateway {
        boolean failNextInstall;
        boolean focusFails;
        int focusRequests;

        @Override public void install(GatewayConnection connection, String gameId)
                throws IOException {
            if (failNextInstall) {
                failNextInstall = false;
                throw new IOException("install failed");
            }
        }

        @Override public void uninstall(GatewayConnection connection, String gameId) {}

        @Override public void focusInstallation(GatewayConnection connection, String gameId)
                throws IOException {
            focusRequests++;
            if (focusFails) throw new IOException("focus failed");
        }
    }

    private static final class QueueExecutor implements Executor {
        private final Queue<Runnable> actions = new ArrayDeque<>();

        @Override public void execute(Runnable action) {
            actions.add(action);
        }

        void runNext() {
            actions.remove().run();
        }
    }

    private static final class QueuedDispatcher implements GameOperationsController.Dispatcher {
        private final Queue<Runnable> actions = new ArrayDeque<>();

        @Override public void post(Runnable action) {
            actions.add(action);
        }

        void runAll() {
            while (!actions.isEmpty()) actions.remove().run();
        }
    }
}
