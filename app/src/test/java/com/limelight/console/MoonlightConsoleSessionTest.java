package com.limelight.console;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class MoonlightConsoleSessionTest {
    @Test public void attachesOnceAndKeepsSurfaceAcrossHomeAndStream() {
        RecordingConnection connection = new RecordingConnection();
        RecordingSurfaces surfaces = new RecordingSurfaces();
        MoonlightConsoleSession session = new MoonlightConsoleSession(connection, surfaces);

        session.connect();
        session.showHome();
        session.showStream();

        assertEquals(1, surfaces.attachCount);
        assertEquals(2, surfaces.bindCount);
        assertTrue(connection.connected);
        assertFalse(connection.preparedForStop);
    }

    @Test public void disconnectPreparesRendererAndDetachesAfterTransportStops() {
        RecordingConnection connection = new RecordingConnection();
        RecordingSurfaces surfaces = new RecordingSurfaces();
        MoonlightConsoleSession session = new MoonlightConsoleSession(connection, surfaces);

        session.connect();
        session.disconnect();
        session.disconnect();

        assertTrue(connection.preparedForStop);
        assertEquals(1, connection.disconnectCount);
        assertEquals(0, surfaces.detachCount);
        connection.afterStopped.run();
        assertEquals(1, surfaces.detachCount);
    }

    @Test public void ignoresSurfaceRequestsAfterDisconnect() {
        RecordingConnection connection = new RecordingConnection();
        RecordingSurfaces surfaces = new RecordingSurfaces();
        MoonlightConsoleSession session = new MoonlightConsoleSession(connection, surfaces);

        session.disconnect();
        session.showHome();
        session.showStream();

        assertEquals(0, surfaces.bindCount);
    }

    @Test(expected = IllegalStateException.class)
    public void cannotReconnectDisconnectedSession() {
        MoonlightConsoleSession session = new MoonlightConsoleSession(
                new RecordingConnection(), new RecordingSurfaces());
        session.disconnect();
        session.connect();
    }

    private static final class RecordingConnection implements
            MoonlightConsoleSession.Connection {
        boolean connected;
        boolean preparedForStop;
        int disconnectCount;
        Runnable afterStopped;

        @Override public void connect() { connected = true; }
        @Override public void prepareRendererForStop() { preparedForStop = true; }
        @Override public void disconnect(Runnable callback) {
            disconnectCount++;
            afterStopped = callback;
        }
    }

    private static final class RecordingSurfaces implements
            MoonlightConsoleSession.SurfaceOwnership {
        int attachCount;
        int bindCount;
        int detachCount;
        @Override public void attach() { attachCount++; }
        @Override public boolean bindConsoleSurface() {
            bindCount++;
            return true;
        }
        @Override public void detach() { detachCount++; }
    }
}
