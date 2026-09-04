package com.limelight.console;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.limelight.nvstream.http.ComputerDetails;
import com.limelight.nvstream.http.PairingManager;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

public class ConsoleHostPresentationTest {
    @Test
    public void reportsWakingBeforeOfflineClassification() {
        ComputerDetails host = host(ComputerDetails.State.OFFLINE);
        host.macAddress = "00:11:22:33:44:55";

        assertEquals(ConsoleHostPresentation.State.WAKING,
                ConsoleHostPresentation.state(host, true));
    }

    @Test
    public void wakeCapableOfflineHostRemainsOfflineWithoutSleepCommand() {
        ComputerDetails host = host(ComputerDetails.State.OFFLINE);
        host.macAddress = "00:11:22:33:44:55";

        assertEquals(ConsoleHostPresentation.State.OFFLINE,
                ConsoleHostPresentation.state(host, false));
        assertTrue(ConsoleHostPresentation.canWake(host));
    }

    @Test
    public void addressedOfflineHostRemainsOffline() {
        ComputerDetails host = host(ComputerDetails.State.OFFLINE);
        host.manualAddress = new ComputerDetails.AddressTuple("192.168.1.20", 47989);

        assertEquals(ConsoleHostPresentation.State.OFFLINE,
                ConsoleHostPresentation.state(host, false));
    }

    @Test
    public void unknownPairedHostIsOffline() {
        assertEquals(ConsoleHostPresentation.State.OFFLINE,
                ConsoleHostPresentation.state(host(ComputerDetails.State.UNKNOWN), false));
    }

    @Test
    public void onlinePairedHostWithoutSessionIsOnline() {
        assertEquals(ConsoleHostPresentation.State.ONLINE,
                ConsoleHostPresentation.state(host(ComputerDetails.State.ONLINE), false));
    }

    @Test
    public void onlineSessionTakesPriority() {
        ComputerDetails host = host(ComputerDetails.State.ONLINE);
        host.pairState = PairingManager.PairState.PAIRED;
        host.runningGameId = 42;

        assertEquals(ConsoleHostPresentation.State.ACTIVE_SESSION,
                ConsoleHostPresentation.state(host, false));
    }

    @Test
    public void selectedProfileMismatchNamesAuthorizedActiveProfile() {
        HostGatewayStore.ProfileSelection selection = HostGatewayStore.projectProfiles(
                Arrays.asList(profile("basia", "Basia", "other_user_active"),
                        profile("gry", "Gry", "active")), "basia", "");

        ConsoleHostPresentation.Profile result = ConsoleHostPresentation.profile(selection);

        assertEquals(ConsoleHostPresentation.ProfileState.OTHER_AUTHORIZED_ACTIVE,
                result.state);
        assertEquals("Basia", result.selectedName);
        assertEquals("Gry", result.activeName);
        assertEquals(ConsoleHostPresentation.State.PROFILE_ATTENTION,
                ConsoleHostPresentation.withProfile(ConsoleHostPresentation.State.ONLINE,
                        result));
        assertEquals(ConsoleHostPresentation.color(ConsoleHostPresentation.State.WAKING),
                ConsoleHostPresentation.color(
                        ConsoleHostPresentation.State.PROFILE_ATTENTION));
    }

    @Test
    public void selectedProfileMismatchDoesNotExposeUnavailableWindowsAccount() {
        HostGatewayStore.ProfileSelection selection = HostGatewayStore.projectProfiles(
                Collections.singletonList(profile("basia", "Basia", "other_user_active")),
                "basia", "");

        ConsoleHostPresentation.Profile result = ConsoleHostPresentation.profile(selection);

        assertEquals(ConsoleHostPresentation.ProfileState.OTHER_ACTIVE, result.state);
        assertEquals("", result.activeName);
    }

    @Test
    public void matchingProfileKeepsNormalOnlinePresentation() {
        ConsoleHostPresentation.Profile profile = ConsoleHostPresentation.profile(
                HostGatewayStore.projectProfiles(Collections.singletonList(
                        profile("basia", "Basia", "active")), "basia", ""));

        assertEquals(ConsoleHostPresentation.ProfileState.ACTIVE, profile.state);
        assertEquals(ConsoleHostPresentation.State.ONLINE,
                ConsoleHostPresentation.withProfile(ConsoleHostPresentation.State.ONLINE,
                        profile));
    }

    @Test
    public void unavailableSelectedProfileStatesProjectToAttentionTextKinds() {
        assertEquals(ConsoleHostPresentation.ProfileState.SIGN_IN_REQUIRED,
                projectedProfileState("signed_out"));
        assertEquals(ConsoleHostPresentation.ProfileState.SIGN_IN_REQUIRED,
                projectedProfileState("disconnected"));
        assertEquals(ConsoleHostPresentation.ProfileState.SIGN_IN_REQUIRED,
                projectedProfileState("locked"));
        assertEquals(ConsoleHostPresentation.ProfileState.UNKNOWN,
                projectedProfileState("unknown"));
    }

    @Test
    public void higherPriorityHostStatesIgnoreProfileAttention() {
        ConsoleHostPresentation.Profile profile = ConsoleHostPresentation.profile(
                HostGatewayStore.projectProfiles(Collections.singletonList(
                        profile("basia", "Basia", "locked")), "basia", ""));

        for (ConsoleHostPresentation.State state : Arrays.asList(
                ConsoleHostPresentation.State.ACTIVE_SESSION,
                ConsoleHostPresentation.State.WAKING,
                ConsoleHostPresentation.State.CONNECTING,
                ConsoleHostPresentation.State.ASLEEP,
                ConsoleHostPresentation.State.OFFLINE)) {
            assertEquals(state, ConsoleHostPresentation.withProfile(state, profile));
        }
    }

    private static HostGatewayClient.IntegrationProfile profile(
            String id, String name, String sessionState) {
        return new HostGatewayClient.IntegrationProfile(id, name,
                false, false, false, false, false, false, false,
                true, false, sessionState, "unavailable");
    }

    private static ConsoleHostPresentation.ProfileState projectedProfileState(
            String sessionState) {
        return ConsoleHostPresentation.profile(HostGatewayStore.projectProfiles(
                Collections.singletonList(profile("basia", "Basia", sessionState)),
                "basia", "")).state;
    }

    private static ComputerDetails host(ComputerDetails.State state) {
        ComputerDetails host = new ComputerDetails();
        host.state = state;
        host.pairState = PairingManager.PairState.PAIRED;
        return host;
    }
}
