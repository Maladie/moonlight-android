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
                ConsoleHostPresentation.state(host, true, true));
    }

    @Test
    public void wakeCapableOfflineHostRemainsOfflineWithoutSleepCommand() {
        ComputerDetails host = host(ComputerDetails.State.OFFLINE);
        host.macAddress = "00:11:22:33:44:55";

        assertEquals(ConsoleHostPresentation.State.OFFLINE,
                ConsoleHostPresentation.state(host, false, true));
        assertTrue(ConsoleHostPresentation.canWake(host));
    }

    @Test
    public void addressedOfflineHostRemainsOffline() {
        ComputerDetails host = host(ComputerDetails.State.OFFLINE);
        host.manualAddress = new ComputerDetails.AddressTuple("192.168.1.20", 47989);

        assertEquals(ConsoleHostPresentation.State.OFFLINE,
                ConsoleHostPresentation.state(host, false, true));
    }

    @Test
    public void rememberedStateIsCheckingUntilAPollAnswers() {
        ConsoleHostStateController controller = new ConsoleHostStateController(() -> 1L);
        // As loaded at start: the database keeps no pairing, the state is the remembered one.
        ComputerDetails loaded = host(ComputerDetails.State.UNKNOWN);
        loaded.uuid = "host";
        loaded.pairState = null;
        controller.remember("host", ComputerDetails.State.ONLINE);
        controller.observe(loaded, false);
        assertEquals(ComputerDetails.State.ONLINE, loaded.state);
        assertEquals(ConsoleHostPresentation.State.CHECKING, controller.state(loaded));

        ComputerDetails answered = host(ComputerDetails.State.ONLINE);
        answered.uuid = "host";
        controller.observe(answered, true);
        assertEquals(ConsoleHostPresentation.State.ONLINE, controller.state(answered));
        answered.pairState = PairingManager.PairState.NOT_PAIRED;
        assertEquals(ConsoleHostPresentation.State.UNPAIRED, controller.state(answered));

        // Offline since the start: pairing never reported, so unavailable rather than unpaired.
        loaded.state = ComputerDetails.State.OFFLINE;
        controller.observe(loaded, true);
        assertEquals(ConsoleHostPresentation.State.OFFLINE, controller.state(loaded));
    }

    @Test
    public void onlinePairedHostWithoutSessionIsOnline() {
        assertEquals(ConsoleHostPresentation.State.ONLINE,
                ConsoleHostPresentation.state(host(ComputerDetails.State.ONLINE), false, true));
    }

    @Test
    public void onlineSessionTakesPriority() {
        ComputerDetails host = host(ComputerDetails.State.ONLINE);
        host.pairState = PairingManager.PairState.PAIRED;
        host.runningGameId = 42;

        assertEquals(ConsoleHostPresentation.State.ACTIVE_SESSION,
                ConsoleHostPresentation.state(host, false, true));
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

    @Test
    public void onlyAnOnlineHostWithASavedSessionReadsAsPaused() {
        for (SessionSnapshot.State session : SessionSnapshot.State.values()) {
            for (ConsoleHostPresentation.State state : ConsoleHostPresentation.State.values()) {
                boolean online = state == ConsoleHostPresentation.State.ONLINE
                        || state == ConsoleHostPresentation.State.PROFILE_ATTENTION;
                assertEquals(session + " on " + state,
                        online && session == SessionSnapshot.State.RECONNECT_REQUIRED,
                        ConsoleHostPresentation.holdsPausedSession(state, session(session)));
            }
        }
    }

    private static SessionSnapshot session(SessionSnapshot.State state) {
        return new SessionSnapshot("host", "default", "default", state, 42, "", false,
                state == SessionSnapshot.State.SUSPENDED, false, false, false, "", false,
                SessionSnapshot.ObservationState.PENDING, false,
                "", 0L, 0L, 0L, 0L, 0L, "", "", "",
                "UNAVAILABLE", 0, "", "UNAVAILABLE", "test", 0, false);
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
