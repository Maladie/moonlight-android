package com.limelight.stream;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class BackgroundStreamCorrelationTest {
    @Test public void staleCommandsCannotAffectNewSession() {
        BackgroundStreamCorrelation state = new BackgroundStreamCorrelation();
        state.park("session-a");
        state.park("session-b");

        assertFalse(state.end("session-a"));
        assertFalse(state.resume("session-a"));
        assertFalse(state.expire("session-a"));
        assertFalse(state.transportLost("session-a"));
        assertTrue(state.matches("session-b"));
        assertFalse(state.reconnectRequired());
    }

    @Test public void currentTransportLossDoesNotLeakToReplacement() {
        BackgroundStreamCorrelation state = new BackgroundStreamCorrelation();
        state.park("session-a");
        assertTrue(state.transportLost("session-a"));
        assertTrue(state.reconnectRequired());

        state.park("session-b");

        assertFalse(state.reconnectRequired());
        assertEquals("session-b", state.streamSessionId());
    }

    @Test public void notificationIdentityContainsExactSession() {
        assertEquals("moonwaker://background-stream/end/session-a",
                BackgroundStreamCorrelation.intentIdentity("end", "session-a"));
        assertFalse(BackgroundStreamCorrelation.intentIdentity("end", "session-a")
                .equals(BackgroundStreamCorrelation.intentIdentity("end", "session-b")));
    }
}
