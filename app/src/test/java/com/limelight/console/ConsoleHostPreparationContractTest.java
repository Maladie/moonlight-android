package com.limelight.console;

import com.limelight.SourceText;
import org.junit.Test;
import java.nio.file.Path;
import java.nio.file.Paths;
import static com.limelight.SourceText.assertOrdered;
import static org.junit.Assert.*;

public class ConsoleHostPreparationContractTest {
    @Test public void automaticPairingWaitsForBridgeAndReportsItsFailureStage() throws Exception {
        Path path = Paths.get("src/main/java/com/limelight/console/ConsoleActivity.java");
        String source = SourceText.read(path);
        String pairing = section(source, "private void beginAutomaticHostPairing(",
                "private void cancelPendingPairing(");

        assertFalse(pairing.contains("SystemClock.sleep(200L)"));
        assertTrue(pairing.contains("console_pair_stage_vibepollo"));
        assertTrue(pairing.contains("deepestPairingError(error)"));
        assertTrue(pairing.contains("pairFuture.get(12, TimeUnit.SECONDS)"));
    }

    @Test public void deepestPairingErrorPrefersTheActionableRootCause() {
        Exception root = new Exception("Vibepollo has no pending pairing session");
        Exception wrapper = new Exception("Gateway request failed", root);
        assertEquals("Vibepollo has no pending pairing session",
                ConsoleActivity.deepestPairingError(wrapper));
    }

    @Test public void savedPairOnlyAuthorizesPreparationAndBinderJoinIsConsumedOnce() throws Exception {
        Path path = Paths.get("src/main/java/com/limelight/console/ConsoleActivity.java");
        String source = SourceText.read(path);
        String prepare = section(source, "private void prepareSelectedHost(",
                "private void dispatchPendingHostPreparation()");
        assertTrue(prepare.contains("ConsoleActionCatalog.isRememberedPaired(host)"));
        // One pairing rule: host entry, Play and the warm-up all read the same one.
        assertTrue(section(source, "private void activateHostFromSelection(String uuid)",
                "private void resolveProfileGate(ComputerDetails host")
                .contains("} else if (!ConsoleActionCatalog.isRememberedPaired(host)) {"));
        assertTrue(source.contains("@Override public boolean isPaired(String hostId) {\n"
                + "                return ConsoleActionCatalog.isRememberedPaired("
                + "currentHost(hostId));"));
        assertFalse(source.contains("canPrepareHost"));
        assertOrdered(prepare, "if (managerBinder == null)",
                "sessionOrchestrator.prepareHost(host.uuid,");
        assertTrue(prepare.contains("pendingHostPreparation = host.uuid;\n                return;"));
        String dispatch = section(source, "private void dispatchPendingHostPreparation()",
                "private void armPendingWarmUpRelay()");
        assertOrdered(dispatch, "pendingHostPreparation = null;", "prepareSelectedHost(");
        assertTrue(dispatch.contains("!hostSelectionVisible && hostId.equals(selectedHostUuid)"));
        assertTrue(source.contains("managerBinder = binder;\n                    dispatchPendingHostPreparation();"));
        assertTrue(source.contains("private void selectHost(ComputerDetails host, boolean focusApps) {\n        pendingHostPreparation = null;"));
        assertTrue(source.contains("private void showHostSelection(String focusUuid) {\n        pendingHostPreparation = null;"));
        String pause = section(source, "protected void onPause()",
                "public void onTrimMemory");
        assertTrue(pause.contains("pendingHostPreparation = null;"));
        assertTrue(pause.contains("childEligibilityLaunchToken++"));
        String initial = section(source, "private void resolveInitialHostSelection()",
                "private void showHostSelection(");
        assertOrdered(initial, "if (!active || initialHostSelectionResolved) return;",
                "initialHostSelectionResolved = true;");
        assertTrue(source.contains("active = true;\n        if (initialHostsLoaded) resolveInitialHostSelection();"));
        assertOrdered(prepare, "if (!active) return;", "pendingHostPreparation = host.uuid;");
        assertTrue(dispatch.contains("if (active && hostId != null"));
        // Whether a prepared warm-up may run (another app, its fresh session) is the table's:
        // SessionTransitionTableTest warmUpNeverRunsBesideAnotherAppAndOwnsOnlyAnIdleHostsSession.
        String fresh = source.substring(source.indexOf("ready = refreshWarmUpHost(host)"));
        assertOrdered(fresh, "!ConsoleActionCatalog.isPaired(ready)",
                "new SessionOrchestrator.PreparedWarmUp(");
        assertFalse(fresh.substring(0, fresh.indexOf("new SessionOrchestrator.PreparedWarmUp("))
                .contains("streamerAppId"));
        // The ready projection's streamer app comes from an observation made after the answer.
        assertOrdered(fresh, "refreshObservationNow(ready, intent.profileId);",
                "SessionSnapshot readyProjection = resolveSessionSnapshot(ready, intent.profileId);");
        String retained = section(source, "@Override public NvApp verifiedRetainedTarget(",
                "@Override public List<NvApp> refreshApps(");
        assertTrue(retained.contains("request.action != HostLaunchPreflight.Action.SWITCH_RETAINED"));
        assertTrue(retained.contains("!retained.hostId.equalsIgnoreCase(request.hostId)"));
        assertTrue(retained.contains("retained.appId != request.appId"));
        assertTrue(retained.contains("!host.uuid.equalsIgnoreCase(retained.hostId)"));
        assertTrue(retained.contains("findById(loadApps(host, true), retained.appId)"));
        assertTrue(retained.contains("PlayniteTargetResolver.isNeutralStream(target)"));
        assertEquals(3, retained.split("canSwitchGame\\(retained\\)", -1).length);
    }

    @Test public void interruptedNeutralWarmUpResumesWithThePendingGame() throws Exception {
        String console = source("src/main/java/com/limelight/console/ConsoleActivity.java");
        String reconnect = section(console,
                "@Override public void reconnectSavedSession(PlayIntent intent)",
                "@Override public void reject(");
        assertTrue(reconnect.contains("reconnectSavedSessionForActor(intent)"));
        String resume = section(console, "private String startResumeActivity(",
                "private void failClosedActorHandoff(");
        assertTrue(resume.contains("pending.neutralStreamTarget"));
        assertTrue(resume.contains("SessionModel.State.RECONNECT_REQUIRED"));
        assertTrue(resume.contains("retained.streamSessionId.equals(pending.streamSessionId)"));
        assertTrue(resume.contains(
                "if (warmUpCarriesPendingGame(tracksWarmUp(), warmUpClientHostId, pending, "
                        + "retained))\n            applyWarmUpIntent(resume,"));

        String game = source("src/main/java/com/limelight/Game.java");
        String home = section(game, "private void launchConsoleHome(",
                "private void showOverlayMenuWithBattery(");
        assertTrue(home.contains("EXTRA_WARM_UP_PENDING_REQUIRES_CONNECTOR"));
        assertTrue(home.contains("EXTRA_WARM_UP_PENDING_START_BEFORE_STREAM"));
    }

    @Test public void neutralReconnectWithoutWarmUpStartsTheStreamOrFailsVisibly()
            throws Exception {
        String console = source("src/main/java/com/limelight/console/ConsoleActivity.java");
        String reconnect = section(console, "private void reconnectSavedSessionForActor(",
                "private ChildSessionClient.State releasePersistedChildTimeAttachment(");
        String sameActor = section(reconnect, "if (!actorHandoff)", "BooleanSupplier cancelled");
        assertTrue(sameActor.contains("String refusal = savedRefusal != null ? savedRefusal"));
        assertTrue(sameActor.contains("if (refusal != null) sessionFailed("
                + "SessionFailure.RECONNECT_SAVED_SESSION_UNAVAILABLE"));
        assertFalse(reconnect.contains("pending.executionProfileId)) return;"));
        String handoff = section(reconnect, "mainHandler.post(", "} catch (");
        assertTrue(handoff.contains("SessionFailure.RECONNECT_ACTOR_HANDOFF_FAILED"));
        String failure = reconnect.substring(reconnect.indexOf("} catch ("));
        assertTrue(failure.contains("sessionFailed("));
        assertTrue(failure.contains("!cancelled.getAsBoolean()"));
        String resume = section(console, "private String startResumeActivity(",
                "private void failClosedActorHandoff(");
        assertFalse(resume.contains("warmUpRequest <= 0L"));
        assertOrdered(resume, "return \"retained_mismatch\"", "applyWarmUpIntent(",
                "startActivity(resume)");
        assertTrue(console.contains("return warmUpCarriesPendingGame(\n"
                + "                    tracksWarmUp(), warmUpClientHostId, s.records.pending, s); }"));
    }

    private static String source(String relative) throws Exception {
        Path path = Paths.get(relative);
        return SourceText.read(path);
    }

    private static String section(String source, String begin, String end) {
        int start = source.indexOf(begin);
        return source.substring(start, source.indexOf(end, start));
    }
}
