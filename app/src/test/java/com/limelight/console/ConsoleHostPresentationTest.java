package com.limelight.console;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.limelight.nvstream.http.ComputerDetails;
import com.limelight.nvstream.http.PairingManager;

import org.junit.Test;

public class ConsoleHostPresentationTest {
    @Test
    public void reportsWakingBeforeOfflineClassification() {
        ComputerDetails host = host(ComputerDetails.State.OFFLINE);
        host.macAddress = "00:11:22:33:44:55";

        assertEquals(ConsoleHostPresentation.State.WAKING,
                ConsoleHostPresentation.state(host, true));
    }

    @Test
    public void wakeCapableOfflineHostIsAsleep() {
        ComputerDetails host = host(ComputerDetails.State.OFFLINE);
        host.macAddress = "00:11:22:33:44:55";

        assertEquals(ConsoleHostPresentation.State.ASLEEP,
                ConsoleHostPresentation.state(host, false));
        assertTrue(ConsoleHostPresentation.canWake(host));
    }

    @Test
    public void addressedHostWithoutWakeDataIsUnreachable() {
        ComputerDetails host = host(ComputerDetails.State.OFFLINE);
        host.manualAddress = new ComputerDetails.AddressTuple("192.168.1.20", 47989);

        assertEquals(ConsoleHostPresentation.State.UNREACHABLE,
                ConsoleHostPresentation.state(host, false));
    }

    @Test
    public void unknownHostIsConnecting() {
        assertEquals(ConsoleHostPresentation.State.CONNECTING,
                ConsoleHostPresentation.state(host(ComputerDetails.State.UNKNOWN), false));
    }

    @Test
    public void onlineSessionTakesPriority() {
        ComputerDetails host = host(ComputerDetails.State.ONLINE);
        host.pairState = PairingManager.PairState.PAIRED;
        host.runningGameId = 42;

        assertEquals(ConsoleHostPresentation.State.ACTIVE_SESSION,
                ConsoleHostPresentation.state(host, false));
    }

    private static ComputerDetails host(ComputerDetails.State state) {
        ComputerDetails host = new ComputerDetails();
        host.state = state;
        host.pairState = PairingManager.PairState.PAIRED;
        return host;
    }
}
