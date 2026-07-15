package com.limelight.console;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class HostWakePolicyTest {
    @Test public void onlineHostLaunchesWithoutWakeDelay() {
        HostWakePolicy.Step step = HostWakePolicy.evaluate(1_000, 2_000,
                true, true, 0);

        assertEquals(HostWakePolicy.Action.LAUNCH, step.action);
        assertEquals(0, step.delayMs);
    }

    @Test public void sleepingHostWakesImmediatelyThenAtFiveSecondIntervals() {
        assertEquals(HostWakePolicy.Action.SEND_WAKE,
                HostWakePolicy.evaluate(1_000, 1_000, false, true, 0).action);
        assertEquals(HostWakePolicy.Action.WAIT,
                HostWakePolicy.evaluate(1_000, 4_000, false, true, 1_000).action);
        assertEquals(HostWakePolicy.Action.SEND_WAKE,
                HostWakePolicy.evaluate(1_000, 6_000, false, true, 1_000).action);
    }

    @Test public void hostWithoutMacWaitsButNeverClaimsToSendWake() {
        HostWakePolicy.Step step = HostWakePolicy.evaluate(1_000, 2_000,
                false, false, 0);

        assertEquals(HostWakePolicy.Action.WAIT, step.action);
        assertEquals(1_200, step.delayMs);
        assertEquals("Waiting for a compatible streaming host", step.status);
    }

    @Test public void timeoutIsBoundedAtNinetySeconds() {
        assertEquals(HostWakePolicy.Action.WAIT,
                HostWakePolicy.evaluate(1_000, 90_999, false, false, 0).action);
        assertEquals(HostWakePolicy.Action.TIMEOUT,
                HostWakePolicy.evaluate(1_000, 91_000, false, true, 90_000).action);
    }
}
