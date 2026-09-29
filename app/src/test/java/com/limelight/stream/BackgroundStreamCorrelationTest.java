package com.limelight.stream;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class BackgroundStreamCorrelationTest {
    @Test public void staleCommandsCannotAffectNewSession() {
        BackgroundStreamCorrelation state = new BackgroundStreamCorrelation();
        state.establish("session-a", 1L);
        state.establish("session-b", 2L);

        assertFalse(state.end("session-a", 1L));
        assertFalse(state.resume("session-a", 1L));
        assertFalse(state.park("session-a", 1L));
        assertFalse(state.transportLost("session-a", 1L));
        assertTrue(state.matches("session-b", 2L));
        assertFalse(state.reconnectRequired());
    }

    @Test public void staleGenerationOfTheSameSessionIsRejected() {
        BackgroundStreamCorrelation state = new BackgroundStreamCorrelation();
        state.establish("session-a", 1L);
        state.establish("session-a", 2L);

        assertFalse(state.end("session-a", 1L));
        assertFalse(state.park("session-a", 1L));
        assertTrue(state.park("session-a", 2L));
        assertTrue(state.parked());
        assertTrue(state.resume("session-a", 2L));
        assertFalse(state.parked());
        assertTrue(state.end("session-a", 2L));
        assertEquals("", state.streamSessionId());
    }

    @Test public void currentTransportLossDoesNotLeakToReplacement() {
        BackgroundStreamCorrelation state = new BackgroundStreamCorrelation();
        state.establish("session-a", 1L);
        assertTrue(state.transportLost("session-a", 1L));
        assertTrue(state.reconnectRequired());
        assertFalse("lost transport cannot be parked", state.park("session-a", 1L));

        state.establish("session-b", 2L);

        assertFalse(state.reconnectRequired());
        assertEquals("session-b", state.streamSessionId());
    }

    @Test public void dismissEndsOnlyTheExactSession() {
        BackgroundStreamCorrelation state = new BackgroundStreamCorrelation();
        state.establish("session-a", 1L);
        assertFalse(state.dismiss("session-b"));
        assertTrue(state.dismiss("session-a"));
        assertEquals("", state.streamSessionId());
    }

    @Test public void notificationIdentityContainsExactSessionAndGeneration() {
        assertEquals("moonwaker://background-stream/end/session-a/3",
                BackgroundStreamCorrelation.intentIdentity("end", "session-a", 3L));
        assertFalse(BackgroundStreamCorrelation.intentIdentity("end", "session-a", 3L)
                .equals(BackgroundStreamCorrelation.intentIdentity("end", "session-a", 4L)));
    }
}
