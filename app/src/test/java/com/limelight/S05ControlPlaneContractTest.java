package com.limelight;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class S05ControlPlaneContractTest {
    @Test public void directSunshineEffectsExistOnlyInsideTheExactPermitLifetime()
            throws Exception {
        String connection = source("com/limelight/nvstream/NvConnection.java");
        String start = between(connection, "private boolean startApp(",
                "private boolean startAppEffect(");
        int permit = start.indexOf("controlBinding.client.acquire(");
        int effect = start.indexOf("startAppEffect(", permit);
        assertTrue(permit >= 0 && effect > permit);
        assertTrue(effect < start.indexOf("controlBinding.client.complete(", effect));
        assertTrue(start.contains("controlBinding.expectedGeneration, currentAppId"));

        String host = source("com/limelight/stream/HostSessionTermination.java");
        assertTrue(host.indexOf("getComputerDetails(true)")
                < host.indexOf("control.acquire("));
        assertTrue(host.indexOf("control.acquire(") < host.indexOf("provider.snapshot()"));
        assertTrue(host.indexOf("provider.stopGame(") < host.indexOf("http.quitApp()"));

        assertFalse(source("com/limelight/Game.java").contains(".quitApp("));
        assertFalse(source("com/limelight/console/ConsoleActivity.java")
                .contains(".quitApp("));
    }

    @Test public void semanticAcquireRejectionIsNotReconciledAsTransportLoss()
            throws Exception {
        String connection = source("com/limelight/nvstream/NvConnection.java");
        String start = between(connection, "private boolean startApp(",
                "private boolean startAppEffect(");
        int semantic = start.indexOf("catch (GatewayTransport.GatewayException");
        int transport = start.indexOf("catch (IOException unavailable)");
        int reconcile = start.indexOf("controlBinding.client.reconcile(");
        assertTrue(semantic >= 0 && semantic < transport && transport < reconcile);
        assertFalse(start.substring(semantic, transport).contains(".reconcile("));
        int complete = start.indexOf("controlBinding.client.complete(", reconcile);
        int completeSemantic = start.indexOf(
                "catch (GatewayTransport.GatewayException", complete);
        int completeTransport = start.indexOf("catch (IOException lostResponse)", complete);
        int completeReconcile = start.indexOf("controlBinding.client.reconcile(", complete);
        assertTrue(completeSemantic > complete && completeSemantic < completeTransport
                && completeTransport < completeReconcile);
    }

    @Test public void normalTerminalEntrypointsJoinOwnerButHardResetBypassesIt() throws Exception {
        String console = source("com/limelight/console/ConsoleActivity.java");
        String terminate = between(console,
                "private void requestTerminateSession(ComputerDetails host, String",
                "private boolean isPairingContextCurrent(");
        String close = between(console, "private void requestCloseHostStream(",
                "private void finishCloseHostStream(");
        String hard = between(console, "private void requestHardTerminateSession(",
                "private void finishHardTerminate(");
        assertTrue(terminate.contains("RetainedStreamSessionCoordinator.terminate("));
        assertTrue(close.contains("RetainedStreamSessionCoordinator.terminate("));
        assertFalse(hard.contains("RetainedStreamSessionCoordinator.terminate("));
        assertTrue(hard.contains("clearHardResetSessionState(host, profileKey)"));
        assertTrue(hard.contains("hardResetHost(gateway, childSessionId)"));
        assertFalse(close.contains("RetainedStreamSessionCoordinator.disconnect("));
    }

    @Test public void ordinaryQuitAcceptsAlreadyIdleProviderAndDoesNotPoisonBarrier()
            throws Exception {
        String host = source("com/limelight/stream/HostSessionTermination.java");

        assertTrue(host.contains("String exact = providerIdle ? \"\""));
        assertTrue(host.contains(
                "control.complete(gateway, permit, false, !effectResultUncertain)"));
        assertTrue(host.contains("\"control-full-quit-\" + UUID.randomUUID()"));
        assertTrue(host.contains(
                "return control.complete(gateway, permit, true).succeeded()"));
    }

    @Test public void terminalLocalEffectsDoNotDependOnViewsOrHostRpc() throws Exception {
        String game = source("com/limelight/Game.java");
        String privacy = between(game, "private void applyImmediateTerminalPrivacy(",
                "private void openTerminalConsoleHome(");
        String terminate = between(game, "private void terminateWholeSessionVerified(",
                "private void applyImmediateTerminalPrivacy(");
        assertTrue(privacy.contains("controllerHandler.setInputSuppressed(true)"));
        assertTrue(privacy.contains("if (overlayMenuView != null)"));
        assertTrue(privacy.contains("if (consoleLoadingView != null)"));
        assertTrue(terminate.contains("wholeSessionTermination.run("));
        assertTrue(terminate.contains("stopConnectionResult("));
        assertTrue(terminate.contains("HostSessionTermination.run("));
        int rejected = terminate.indexOf("Whole-session termination rejected");
        assertTrue(rejected >= 0);
        assertFalse(terminate.substring(rejected,
                terminate.indexOf("wholeSessionTermination.run(")).contains("return;"));
    }

    @Test public void childTerminalUsesCommonOwnerWhileGameOnlyRetainStaysDistinct()
            throws Exception {
        String game = source("com/limelight/Game.java");
        String terminal = between(game, "private void finishChildSession(",
                "private boolean childStateMatches(");
        String disconnect = between(game, "public void disconnectRetainedStream(",
                "public void terminateRetainedSession(");
        assertTrue(terminal.contains("terminateWholeSessionVerified(null)"));
        assertFalse(game.contains("terminateChildSession("));
        String awaitedEnd = between(game, "private ChildSessionClient.State endChildSessionAndWait(",
                "private void endChildSession(");
        assertTrue(awaitedEnd.contains("client.end("));
        assertTrue(awaitedEnd.contains("client.status("));
        assertTrue(awaitedEnd.contains("SystemClock.elapsedRealtime() + 30_000L"));
        assertTrue(awaitedEnd.contains("!ChildSessionLifecycle.terminal(state)"));
        assertTrue(disconnect.contains("stopConnectionResult("));
        assertFalse(disconnect.contains("HostSessionTermination"));
        assertTrue(source("com/limelight/console/ConsoleStreamTransitionCoordinator.java")
                .contains("callbacks.controlTransportAuthorized()"));
        String lifecycle = source("com/limelight/console/ChildSessionLifecycle.java");
        assertTrue(lifecycle.contains("ChildTerminalPolicy.action("));
        assertFalse(lifecycle.contains("WholeSessionTermination.Owner"));
        assertFalse(lifecycle.contains("TerminationActions"));
        assertFalse(game.contains("childHeartbeatRunnable"));
        assertFalse(game.contains("childCountdownRunnable"));
        assertFalse(game.contains("ChildTerminalPolicy.apply("));
        String close = between(game, "private void closeStreamWithPrivacy(",
                "private ");
        assertTrue(close.contains("terminateWholeSessionVerified(null)"));
        String common = between(game, "private void terminateWholeSessionVerified(\n"
                        + "            ProviderStopCallback completion, boolean providerAlreadyStopped)",
                "private void applyImmediateTerminalPrivacy(");
        assertTrue(common.contains("if (!wholeSessionTermination.begin())"));
        assertTrue(common.contains("stopChildMonitoring()"));
        assertTrue(game.contains("CHILD_HEARTBEAT_INTERVAL_MS = 30_000L"));
        assertTrue(game.contains("setSuspendSessionAvailable(true)"));
        assertFalse(game.contains("setSuspendSessionAvailable(!isChildSession())"));
        String overlay = source("com/limelight/ui/overlay/OverlayMenuView.java");
        assertFalse(overlay.contains("hostActionsAvailable"));
        int suspend = overlay.indexOf("if (suspendSessionAvailable)");
        int quit = overlay.indexOf("R.string.overlay_menu_quit_session", suspend);
        assertTrue(quit > suspend);
        assertTrue(overlay.substring(suspend, quit).contains("}"));
    }

    @Test public void capabilityAndPostQuitWarmupSuppressionAreProductionWired()
            throws Exception {
        String gateway = source("com/limelight/console/HostGatewayClient.java");
        String console = source("com/limelight/console/ConsoleActivity.java");
        assertTrue(gateway.contains("available(capabilities, \"control_operations_v1\")"));
        assertTrue(console.contains(
                "if (!hostGatewayClient.getCapabilities(connection).controlOperations)"));
        assertTrue(console.contains("EXTRA_SUPPRESS_AUTO_WARM_UP"));
        assertTrue(source("com/limelight/Game.java").contains(
                "home.putExtra(ConsoleActivity.EXTRA_SUPPRESS_AUTO_WARM_UP, true)"));
    }

    @Test public void acknowledgedRetainedHandoffUsesAtomicCommitAndExactCompensation() throws Exception {
        String game = source("com/limelight/Game.java");
        String transfer = between(game, "private void beginNewRetainedGameAttempt(",
                "private void releaseUnusedAllowance(");
        assertTrue(transfer.contains("RetainedControlHandoff.finish("));
        assertTrue(transfer.contains("RetainedStreamSessionCoordinator.commitReauthorization("));
        assertFalse(transfer.contains("RetainedStreamSessionCoordinator.bindActor("));
        assertTrue(transfer.contains("authorizationCompensated.compareAndSet(false, true)"));
        assertTrue(transfer.contains("authorization.streamSessionId, authorization.sunshineAppId"));
        assertTrue(transfer.contains("authorization.generation,"));
        assertTrue(transfer.contains("targetGateway, allowance.sessionId, \"cancelled\""));
        assertTrue(transfer.contains("RetainedStreamSessionCoordinator.discardReauthorization("));
    }

    @Test public void savedRecoveryRebindsActorWithinTheSameExecutionLifecycle()
            throws Exception {
        String console = source("com/limelight/console/ConsoleActivity.java");
        String reconnect = between(console,
                "private void reconnectSavedSessionForActor(",
                "private ChildSessionClient.State releasePersistedChildTimeAttachment(");
        assertTrue(reconnect.contains("targetExecution.equals(pending.executionProfileId)"));
        assertTrue(reconnect.contains("!intent.profileId.equals(pending.profileId)"));
        assertTrue(reconnect.contains("!pending.childSessionId.isEmpty() && !isChildIntent(intent)"));
        assertTrue(reconnect.contains(
                "releasePersistedChildTimeAttachment(\n                            pending, intent.profileId)"));
        assertTrue(reconnect.contains("startChildSession(intent, cancelled)"));
        assertTrue(reconnect.contains("failClosedActorHandoff(intent, pending, accepted)"));
        assertTrue(reconnect.contains("failClosedActorHandoff(intent, pending, binding)"));
        String resume = between(console, "private boolean startResumeActivity(",
                "private void failClosedActorHandoff(");
        assertTrue(resume.contains("Game.EXTRA_CONTROL_INTENT, \"reauthorize\""));
        assertTrue(resume.contains("Game.EXTRA_PROFILE_ID, intent.profileId"));
        assertTrue(resume.contains("Game.EXTRA_EXECUTION_PROFILE_ID"));
        assertTrue(resume.indexOf("if (actorHandoff)")
                < resume.indexOf("resume.removeExtra(Game.EXTRA_CHILD_SESSION_ID)"));
        String game = source("com/limelight/Game.java");
        assertTrue(game.contains(
                "launch|resume|reconnect|replacement|warm_up|reauthorize"));
        String connection = source("com/limelight/nvstream/NvConnection.java");
        String start = between(connection, "private boolean startApp(",
                "private boolean startAppEffect(");
        assertTrue(start.contains(
                "\"reauthorize\".equals(controlBinding.intent) ? \"reauthorize\""));
        assertTrue(start.contains("acquireBootstrapAfterReplay("));
        assertTrue(game.contains("markSessionEndedForExecutionIfMatches(this,"));
        assertTrue(console.contains("SuspendedSessionStore.recentlyEndedForExecution("));
        String compensation = between(console, "private void failClosedActorHandoff(",
                "private SessionOrchestrator.CloseResult switchRetainedProviderGame(");
        assertTrue(compensation.indexOf("releaseNewChildTimeAttachmentAndWait(newBinding)")
                < compensation.indexOf("terminateKnownControlPlane("));
        assertTrue(compensation.contains("client.end("));
        assertTrue(compensation.contains("client.status("));
        assertTrue(compensation.contains("SystemClock.elapsedRealtime() + 30_000L"));
        String known = between(console, "private long knownControlGeneration(",
                "private String knownProviderGameId(");
        assertTrue(known.contains("retained.executionProfileId"));
        assertTrue(known.contains("pending.executionProfileId"));
        assertFalse(known.contains("retained.profileId"));
        assertFalse(known.contains("pending.profileId"));
    }

    private static String source(String relative) throws Exception {
        Path path = Paths.get("src/main/java", relative);
        if (!Files.exists(path)) path = Paths.get("app/src/main/java", relative);
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }

    private static String between(String source, String start, String end) {
        int from = source.indexOf(start);
        int to = source.indexOf(end, from + start.length());
        assertTrue(from >= 0 && to > from);
        return source.substring(from, to);
    }
}
