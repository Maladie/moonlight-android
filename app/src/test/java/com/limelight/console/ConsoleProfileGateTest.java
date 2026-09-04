package com.limelight.console;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class ConsoleProfileGateTest {
    @Test public void multipleProfilesWithoutAutomaticProfileShowGate() {
        HostGatewayStore.ProfileSelection selection = HostGatewayStore.projectProfiles(
                Arrays.asList(profile("basia"), profile("gry")), "basia", "");

        HostGatewayStore.ProfileGate gate =
                HostGatewayStore.resolveProfileGate(selection, "");

        assertTrue(gate.showGate);
        assertNull(gate.profile);
        assertFalse(gate.clearAutomatic);
    }

    @Test public void validAutomaticProfileEntersThatProfile() {
        HostGatewayStore.ProfileSelection selection = HostGatewayStore.projectProfiles(
                Arrays.asList(profile("basia"), profile("gry")), "basia", "");

        HostGatewayStore.ProfileGate gate =
                HostGatewayStore.resolveProfileGate(selection, "gry");

        assertFalse(gate.showGate);
        assertEquals("gry", gate.profile.id);
        assertFalse(gate.clearAutomatic);
    }

    @Test public void removedOrUnauthorizedAutomaticProfileIsClearedAndShowsGate() {
        HostGatewayStore.ProfileSelection selection = HostGatewayStore.projectProfiles(
                Arrays.asList(profile("basia"), profile("gry"),
                        profile("denied", false)), "basia", "");

        for (String automatic : Arrays.asList("removed", "denied")) {
            HostGatewayStore.ProfileGate gate =
                    HostGatewayStore.resolveProfileGate(selection, automatic);
            assertTrue(gate.showGate);
            assertNull(gate.profile);
            assertTrue(gate.clearAutomatic);
        }
    }

    @Test public void protectedAutomaticProfileIsClearedWhenObserved() {
        HostGatewayClient.IntegrationProfile protectedProfile =
                new HostGatewayClient.IntegrationProfile("protected", "protected",
                        false, false, false, false, false, false, false,
                        true, false, "active", "unavailable", true);
        HostGatewayStore.ProfileSelection selection = HostGatewayStore.projectProfiles(
                Arrays.asList(profile("open"), protectedProfile), "open", "");

        HostGatewayStore.ProfileGate gate =
                HostGatewayStore.resolveProfileGate(selection, "protected");

        assertTrue(gate.showGate);
        assertNull(gate.profile);
        assertTrue(gate.clearAutomatic);
        assertTrue(HostGatewayStore.shouldClearAutomaticProfile(
                selection.profiles, "protected"));
        assertFalse(HostGatewayStore.shouldClearAutomaticProfile(
                selection.profiles, "open"));
    }

    @Test public void singleUsableProfileEntersDirectly() {
        HostGatewayStore.ProfileSelection selection = HostGatewayStore.projectProfiles(
                Collections.singletonList(profile("basia")), "old", "");

        HostGatewayStore.ProfileGate gate =
                HostGatewayStore.resolveProfileGate(selection, "");

        assertFalse(gate.showGate);
        assertEquals("basia", gate.profile.id);
    }

    @Test public void hostActivationCannotLoadLibraryBeforeGateResolution() throws Exception {
        String source = new String(Files.readAllBytes(Paths.get(
                "src/main/java/com/limelight/console/ConsoleActivity.java")),
                StandardCharsets.UTF_8);
        String activation = between(source,
                "private void activateHostFromSelection(String uuid)",
                "private void resolveProfileGate(ComputerDetails host");
        String libraryLoad = between(source,
                "private void loadPlayniteForHost(ComputerDetails host)",
                "private void settleInitialLocalPresentation");
        String libraryRefresh = between(source,
                "private void requestPlayniteRefresh(ComputerDetails host, boolean manual)",
                "private void scheduleNextPlayniteRefresh");

        assertTrue(activation.contains("resolveProfileGate(host, true, true)"));
        assertFalse(activation.contains("selectHost("));
        assertTrue(libraryLoad.contains("profileGateHostUuid != null"));
        assertTrue(libraryRefresh.contains("profileGateHostUuid != null"));
    }

    @Test public void sameHostProfileChangeInvalidatesOldLibraryBeforeEntry() throws Exception {
        String source = new String(Files.readAllBytes(Paths.get(
                "src/main/java/com/limelight/console/ConsoleActivity.java")),
                StandardCharsets.UTF_8);
        String gate = between(source,
                "private void resolveProfileGate(ComputerDetails host, boolean focusApps,",
                "private void enterHostAfterProfileGate(");
        String change = between(source,
                "private boolean changeSelectedProfile(ComputerDetails host, String profileId)",
                "private void refreshHostProfiles(");

        assertEquals(3, occurrences(gate, "changeSelectedProfile(host,"));
        assertTrue(change.contains("profileGeneration.incrementAndGet()"));
        assertTrue(change.contains("cancelPlayniteRequest()"));
        assertTrue(change.contains("currentPlayniteGames = Collections.emptyList()"));
        assertTrue(change.contains("currentPlayniteHostUuid = null"));
        assertFalse(change.contains("loadPlayniteForHost("));
        assertFalse(change.contains("refreshHostProfiles("));
    }

    @Test public void everyProtectedSelectionPathWaitsForVerificationBeforePersisting()
            throws Exception {
        String source = new String(Files.readAllBytes(Paths.get(
                "src/main/java/com/limelight/console/ConsoleActivity.java")),
                StandardCharsets.UTF_8);
        String selection = between(source,
                "private void selectProfile(ComputerDetails host, String profileId)",
                "private void applySelectedProfile(");
        String selectorRefresh = between(source,
                "private void updateProfileSelector(ComputerDetails host)",
                "private void showProfileSelection(");
        String verification = between(source,
                "private void finishProfilePinVerification(",
                "private void renderPinCooldown(");

        assertTrue(selection.contains("if (profile.pinRequired)"));
        assertTrue(selection.indexOf("showPinEntry(") < selection.indexOf("applySelectedProfile("));
        assertFalse(selection.contains("changeSelectedProfile("));
        assertTrue(selectorRefresh.contains("!selection.selected.pinRequired"));
        assertTrue(verification.contains("gatewayError == null && transportError == null"));
        assertTrue(verification.contains("changeSelectedProfile(host, profileId)"));
        assertTrue(verification.contains("applySelectedProfile(host, profileId)"));
    }

    @Test public void pauseClearsOnlySecretAndKeepsPinScreenContext() throws Exception {
        String source = new String(Files.readAllBytes(Paths.get(
                "src/main/java/com/limelight/console/ConsoleActivity.java")),
                StandardCharsets.UTF_8);
        String pause = between(source, "protected void onPause()",
                "public void onTrimMemory(int level)");

        assertTrue(pause.contains("profileGateGeneration.incrementAndGet()"));
        assertTrue(pause.contains("pinEntry.clear()"));
        assertTrue(pause.contains("renderPinEntry(\"\")"));
        assertFalse(pause.contains("clearPinEntry()"));
    }

    private static HostGatewayClient.IntegrationProfile profile(String id) {
        return profile(id, true);
    }

    private static HostGatewayClient.IntegrationProfile profile(String id,
                                                                 boolean useProfile) {
        return new HostGatewayClient.IntegrationProfile(id, id,
                false, false, false, false, false, false, false,
                useProfile, false, "active", "unavailable");
    }

    private static String between(String source, String start, String end) {
        int from = source.indexOf(start);
        int to = source.indexOf(end, from + start.length());
        assertTrue(from >= 0);
        assertTrue(to > from);
        return source.substring(from, to);
    }

    private static int occurrences(String value, String needle) {
        int count = 0;
        for (int index = 0; (index = value.indexOf(needle, index)) >= 0;
             index += needle.length()) count++;
        return count;
    }
}
