package com.limelight.console;

import org.junit.Test;
import static org.junit.Assert.*;

public class StreamSurfaceHostTest {
    @Test public void backgroundSurfaceKeepsAttemptedSessionAlive() {
        StreamSurfaceHost host = new StreamSurfaceHost();
        host.onWindowSurfaceCreated();
        assertEquals(StreamSurfaceHost.LossAction.KEEP_SESSION_ON_BACKGROUND_SURFACE,
                host.onWindowSurfaceDestroyed(true, true));
        assertFalse(host.isWindowSurfaceAttached());
    }

    @Test public void noConnectionIgnoresSurfaceLoss() {
        StreamSurfaceHost host = new StreamSurfaceHost();
        host.onWindowSurfaceCreated();
        assertEquals(StreamSurfaceHost.LossAction.IGNORE,
                host.onWindowSurfaceDestroyed(false, false));
    }

    @Test(expected = IllegalStateException.class)
    public void duplicateDestroyIsRejected() {
        StreamSurfaceHost host = new StreamSurfaceHost();
        host.onWindowSurfaceCreated();
        host.onWindowSurfaceDestroyed(false, false);
        host.onWindowSurfaceDestroyed(false, false);
    }
}
