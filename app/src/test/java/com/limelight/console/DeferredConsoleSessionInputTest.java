package com.limelight.console;

import android.view.KeyEvent;
import android.view.MotionEvent;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class DeferredConsoleSessionInputTest {
    @Test public void ignoresEventsUntilTransportInputIsReady() {
        DeferredConsoleSessionInput input = new DeferredConsoleSessionInput();

        assertFalse(input.handleKeyEvent(null));
        assertFalse(input.handleMotionEvent(null));
        input.onStatus("early");
    }

    @Test public void delegatesAllLifecycleAndFeedbackAfterBinding() {
        DeferredConsoleSessionInput input = new DeferredConsoleSessionInput();
        RecordingInput delegate = new RecordingInput();
        input.bind(delegate);

        input.enableSensors();
        input.disableSensors();
        input.onStatus("RTSP");
        input.onConnectionStatus(2);
        input.onMessage("hello", true);
        input.onRumble((short) 1, (short) 2, (short) 3);
        input.onTriggerRumble((short) 1, (short) 4, (short) 5);
        input.onHdrMode(true, new byte[] { 1 });
        input.onMotionState((short) 2, (byte) 6, (short) 120);
        input.onControllerLed((short) 3, (byte) 7, (byte) 8, (byte) 9);
        input.ensureControllersReported(false);
        input.close();

        assertTrue(delegate.sensorsEnabled);
        assertTrue(delegate.sensorsDisabled);
        assertEquals("RTSP", delegate.status);
        assertEquals(2, delegate.connectionStatus);
        assertEquals("hello", delegate.message);
        assertEquals(3, delegate.highFrequency);
        assertEquals(5, delegate.rightTrigger);
        assertTrue(delegate.hdrEnabled);
        assertEquals(120, delegate.motionRate);
        assertEquals(9, delegate.blue);
        assertFalse(delegate.announcedControllerArrival);
        assertTrue(delegate.controllersSynchronized);
        assertTrue(delegate.closed);
    }

    @Test public void bindingAfterCloseImmediatelyClosesDelegate() {
        DeferredConsoleSessionInput input = new DeferredConsoleSessionInput();
        RecordingInput delegate = new RecordingInput();
        input.close();

        input.bind(delegate);

        assertTrue(delegate.closed);
    }

    private static final class RecordingInput implements ConsoleSessionInput {
        boolean sensorsEnabled;
        boolean sensorsDisabled;
        boolean closed;
        String status;
        int connectionStatus;
        String message;
        short highFrequency;
        short rightTrigger;
        boolean hdrEnabled;
        short motionRate;
        byte blue;
        boolean controllersSynchronized;
        boolean announcedControllerArrival = true;

        @Override public boolean handleKeyEvent(KeyEvent event) { return true; }
        @Override public boolean handleMotionEvent(MotionEvent event) { return true; }
        @Override public void ensureControllersReported(boolean announceArrival) {
            controllersSynchronized = true;
            announcedControllerArrival = announceArrival;
        }
        @Override public void enableSensors() { sensorsEnabled = true; }
        @Override public void disableSensors() { sensorsDisabled = true; }
        @Override public void close() { closed = true; }
        @Override public void onStatus(String value) { status = value; }
        @Override public void onConnectionStatus(int value) { connectionStatus = value; }
        @Override public void onMessage(String value, boolean transientMessage) {
            message = value;
        }
        @Override public void onRumble(short controller, short low, short high) {
            highFrequency = high;
        }
        @Override public void onTriggerRumble(short controller, short left, short right) {
            rightTrigger = right;
        }
        @Override public void onHdrMode(boolean enabled, byte[] metadata) {
            hdrEnabled = enabled;
        }
        @Override public void onMotionState(short controller, byte type, short rate) {
            motionRate = rate;
        }
        @Override public void onControllerLed(short controller, byte r, byte g, byte b) {
            blue = b;
        }
    }
}
