package com.limelight.console;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.limelight.nvstream.http.ComputerDetails;

import org.junit.Test;

public class ConsoleHostStateControllerTest {
    @Test
    public void staleTimeoutCannotFinishNewWakeAttempt() {
        long[] now = {10L};
        ConsoleHostStateController controller = new ConsoleHostStateController(() -> now[0]);
        long first = controller.beginWaking("host");
        assertTrue(controller.finishWaking("host", first));

        now[0] = 20L;
        controller.beginWaking("host");
        assertFalse(controller.finishWaking("host", first));
        assertTrue(controller.isWaking("host"));
    }

    @Test
    public void onlineObservationClearsWakingState() {
        ConsoleHostStateController controller = new ConsoleHostStateController(() -> 1L);
        controller.beginWaking("host");
        ComputerDetails host = new ComputerDetails();
        host.uuid = "host";
        host.state = ComputerDetails.State.ONLINE;

        assertTrue(controller.observe(host));
        assertFalse(controller.isWaking("host"));
    }

    @Test
    public void pendingRefreshKeepsLastConfirmedState() {
        ConsoleHostStateController controller = new ConsoleHostStateController(() -> 1L);
        ComputerDetails online = host(ComputerDetails.State.ONLINE);
        controller.observe(online, true);

        ComputerDetails pending = host(ComputerDetails.State.UNKNOWN);
        controller.observe(pending, false);
        assertEquals(ComputerDetails.State.ONLINE, pending.state);

        controller.observe(host(ComputerDetails.State.OFFLINE), true);
        pending = host(ComputerDetails.State.UNKNOWN);
        controller.observe(pending, false);
        assertEquals(ComputerDetails.State.OFFLINE, pending.state);
    }

    private static ComputerDetails host(ComputerDetails.State state) {
        ComputerDetails host = new ComputerDetails();
        host.uuid = "host";
        host.state = state;
        return host;
    }
}
