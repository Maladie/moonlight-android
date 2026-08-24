package com.limelight.console;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class PlayniteTransitionGatewayTest {
    @Test public void suspendAcceptanceRequiresEchoedIdAndExactTarget() {
        PlayniteTransitionGateway.SuspendAcceptance acceptance =
                new PlayniteTransitionGateway.SuspendAcceptance(
                        true, "suspend-a", 42, "game");

        assertTrue(acceptance.matches("suspend-a", 42, "GAME"));
        assertFalse(acceptance.matches("suspend-b", 42, "game"));
        assertFalse(acceptance.matches("suspend-a", 7, "game"));
        assertFalse(acceptance.matches("suspend-a", 42, "other"));
    }

    @Test public void rejectedSuspendNeverMatches() {
        PlayniteTransitionGateway.SuspendAcceptance acceptance =
                new PlayniteTransitionGateway.SuspendAcceptance(
                        false, "suspend-a", 42, "game");

        assertFalse(acceptance.matches("suspend-a", 42, "game"));
    }
}
