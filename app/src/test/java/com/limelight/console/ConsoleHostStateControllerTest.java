package com.limelight.console;

import static org.junit.Assert.assertFalse;
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
}
