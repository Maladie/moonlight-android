package com.limelight.console;

import org.junit.Test;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import static org.junit.Assert.*;

public class ConsoleHostPreparationContractTest {
    @Test public void savedPairOnlyAuthorizesPreparationAndBinderJoinIsConsumedOnce() throws Exception {
        Path path = Paths.get("src/main/java/com/limelight/console/ConsoleActivity.java");
        if (!Files.exists(path)) path = Paths.get("app").resolve(path);
        String source = new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
        String prepare = section(source, "private void prepareSelectedHost(",
                "private void dispatchPendingHostPreparation()");
        assertTrue(prepare.contains("ConsoleActionCatalog.canPrepareHost(host)"));
        assertTrue(prepare.indexOf("if (managerBinder == null)")
                < prepare.indexOf("sessionOrchestrator.prepareHost(host.uuid,"));
        assertTrue(prepare.contains("pendingHostPreparation = host.uuid;\n                return;"));
        String dispatch = section(source, "private void dispatchPendingHostPreparation()",
                "private void armPendingWarmUpRelay()");
        assertTrue(dispatch.indexOf("pendingHostPreparation = null;")
                < dispatch.indexOf("prepareSelectedHost("));
        assertTrue(dispatch.contains("!hostSelectionVisible && hostId.equals(selectedHostUuid)"));
        assertTrue(source.contains("managerBinder = binder;\n                    dispatchPendingHostPreparation();"));
        assertTrue(source.contains("private void selectHost(ComputerDetails host, boolean focusApps) {\n        pendingHostPreparation = null;"));
        assertTrue(source.contains("private void showHostSelection(String focusUuid) {\n        pendingHostPreparation = null;"));
        assertTrue(source.contains("protected void onPause() {\n        pendingHostPreparation = null;"));
        String initial = section(source, "private void resolveInitialHostSelection()",
                "private void showHostSelection(");
        assertTrue(initial.indexOf("if (!active || initialHostSelectionResolved) return;")
                < initial.indexOf("initialHostSelectionResolved = true;"));
        assertTrue(source.contains("active = true;\n        if (initialHostsLoaded) resolveInitialHostSelection();"));
        assertTrue(prepare.indexOf("if (!active) return;")
                < prepare.indexOf("pendingHostPreparation = host.uuid;"));
        assertTrue(dispatch.contains("if (active && hostId != null"));
        String fresh = source.substring(source.indexOf("ready = refreshWarmUpHost(ready)"));
        assertTrue(fresh.indexOf("!ConsoleActionCatalog.isPaired(ready)")
                < fresh.indexOf("new SessionOrchestrator.PreparedWarmUp("));
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

    private static String section(String source, String begin, String end) {
        int start = source.indexOf(begin);
        return source.substring(start, source.indexOf(end, start));
    }
}
