package com.limelight.console;

import com.limelight.nvstream.http.ComputerDetails;
import com.limelight.nvstream.http.NvApp;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class MoonlightConsoleResolvedStreamRuntimeTest {
    @Test public void connectsAndSwitchesPersistentSessionLayers() {
        RecordingFactory factory = new RecordingFactory();
        RecordingListener listener = new RecordingListener();
        MoonlightConsoleResolvedStreamRuntime runtime =
                new MoonlightConsoleResolvedStreamRuntime(factory);

        runtime.connect(parameters(), listener);
        factory.listener.onConnected();

        assertTrue(factory.session.streamShown);
        factory.session.streamShown = false;
        runtime.showHome();
        runtime.showStream();

        assertTrue(factory.session.connectCalled);
        assertEquals(1, listener.connectedCount);
        assertTrue(factory.session.homeShown);
        assertTrue(factory.session.streamShown);
        assertFalse(factory.session.disconnected);
    }

    @Test public void rejectsSecondSessionAndKeepsFirstActive() {
        RecordingFactory factory = new RecordingFactory();
        RecordingListener first = new RecordingListener();
        RecordingListener second = new RecordingListener();
        MoonlightConsoleResolvedStreamRuntime runtime =
                new MoonlightConsoleResolvedStreamRuntime(factory);

        runtime.connect(parameters(), first);
        runtime.connect(parameters(), second);

        assertEquals(1, factory.createCount);
        assertEquals("A stream session is already active", second.failure);
        assertFalse(factory.session.disconnected);
    }

    @Test public void cancellationDisconnectsAndSuppressesLateCallback() {
        RecordingFactory factory = new RecordingFactory();
        RecordingListener listener = new RecordingListener();
        MoonlightConsoleResolvedStreamRuntime runtime =
                new MoonlightConsoleResolvedStreamRuntime(factory);

        runtime.connect(parameters(), listener);
        runtime.cancelPendingConnection();
        factory.listener.onConnected();

        assertTrue(factory.session.disconnected);
        assertEquals(0, listener.connectedCount);
    }

    @Test public void failureDisconnectsAndAllowsRetry() {
        RecordingFactory factory = new RecordingFactory();
        RecordingListener first = new RecordingListener();
        MoonlightConsoleResolvedStreamRuntime runtime =
                new MoonlightConsoleResolvedStreamRuntime(factory);

        runtime.connect(parameters(), first);
        factory.listener.onConnectionFailed("network");
        runtime.connect(parameters(), new RecordingListener());

        assertTrue(factory.firstSessionDisconnected);
        assertEquals("network", first.failure);
        assertEquals(2, factory.createCount);
    }

    @Test public void closeDisconnectsAndRejectsFutureConnections() {
        RecordingFactory factory = new RecordingFactory();
        MoonlightConsoleResolvedStreamRuntime runtime =
                new MoonlightConsoleResolvedStreamRuntime(factory);
        runtime.connect(parameters(), new RecordingListener());
        runtime.close();
        RecordingListener afterClose = new RecordingListener();

        runtime.connect(parameters(), afterClose);

        assertTrue(factory.session.disconnected);
        assertEquals("Runtime is closed", afterClose.failure);
    }

    private static StreamLaunchParameters parameters() {
        ComputerDetails computer = new ComputerDetails();
        computer.uuid = "host-a";
        computer.name = "Host";
        computer.activeAddress = new ComputerDetails.AddressTuple("host", 47989);
        return StreamLaunchParameters.create(
                computer, new NvApp("Game", 7, false), "client", null, true);
    }

    private static final class RecordingFactory implements
            MoonlightConsoleResolvedStreamRuntime.SessionFactory {
        int createCount;
        RecordingSession session;
        ConsoleResolvedStreamRuntime.Listener listener;
        boolean firstSessionDisconnected;

        @Override public MoonlightConsoleResolvedStreamRuntime.Session create(
                StreamLaunchParameters parameters,
                ConsoleResolvedStreamRuntime.Listener listener) {
            if (session != null) {
                firstSessionDisconnected = session.disconnected;
            }
            createCount++;
            session = new RecordingSession();
            this.listener = listener;
            return session;
        }
    }

    private static final class RecordingSession implements
            MoonlightConsoleResolvedStreamRuntime.Session {
        boolean connectCalled;
        boolean disconnected;
        boolean streamShown;
        boolean homeShown;
        @Override public void connect() { connectCalled = true; }
        @Override public void disconnect() { disconnected = true; }
        @Override public void showStream() { streamShown = true; }
        @Override public void showHome() { homeShown = true; }
    }

    private static final class RecordingListener implements
            ConsoleResolvedStreamRuntime.Listener {
        int connectedCount;
        String failure;
        @Override public void onConnected() { connectedCount++; }
        @Override public void onConnectionFailed(String reason) { failure = reason; }
    }
}
