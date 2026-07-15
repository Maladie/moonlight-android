package com.limelight.console;

import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class ConsoleNvConnectionListenerTest {
    @Test public void routesLifecycleStatusAndMessages() {
        RecordingCallbacks callbacks = new RecordingCallbacks();
        ConsoleNvConnectionListener listener = new ConsoleNvConnectionListener(callbacks);

        listener.stageStarting("RTSP");
        listener.stageComplete("RTSP");
        listener.stageFailed("Video", 3, -7);
        listener.connectionStarted();
        listener.connectionStatusUpdate(2);
        listener.displayMessage("persistent");
        listener.displayTransientMessage("brief");
        listener.connectionTerminated(-9);

        assertEquals("RTSP", callbacks.startedStage);
        assertEquals("RTSP", callbacks.completedStage);
        assertEquals("Video", callbacks.failedStage);
        assertEquals(3, callbacks.portFlags);
        assertEquals(-7, callbacks.stageError);
        assertTrue(callbacks.connected);
        assertEquals(2, callbacks.connectionStatus);
        assertEquals("brief", callbacks.message);
        assertTrue(callbacks.transientMessage);
        assertEquals(-9, callbacks.terminationError);
    }

    @Test public void routesRendererAndControllerFeedback() {
        RecordingCallbacks callbacks = new RecordingCallbacks();
        ConsoleNvConnectionListener listener = new ConsoleNvConnectionListener(callbacks);
        byte[] metadata = new byte[] { 1, 2, 3 };

        listener.rumble((short) 1, (short) 2, (short) 3);
        listener.rumbleTriggers((short) 1, (short) 4, (short) 5);
        listener.setHdrMode(true, metadata);
        listener.setMotionEventState((short) 2, (byte) 6, (short) 120);
        listener.setControllerLED((short) 3, (byte) 7, (byte) 8, (byte) 9);

        assertEquals(1, callbacks.controller);
        assertEquals(2, callbacks.lowFrequency);
        assertEquals(3, callbacks.highFrequency);
        assertEquals(4, callbacks.leftTrigger);
        assertEquals(5, callbacks.rightTrigger);
        assertTrue(callbacks.hdrEnabled);
        assertArrayEquals(metadata, callbacks.hdrMetadata);
        assertEquals(2, callbacks.motionController);
        assertEquals(6, callbacks.motionType);
        assertEquals(120, callbacks.motionRate);
        assertEquals(3, callbacks.ledController);
        assertEquals(7, callbacks.red);
        assertEquals(8, callbacks.green);
        assertEquals(9, callbacks.blue);
    }

    private static final class RecordingCallbacks implements
            ConsoleNvConnectionListener.Callbacks {
        String startedStage;
        String completedStage;
        String failedStage;
        int portFlags;
        int stageError;
        boolean connected;
        int terminationError;
        int connectionStatus;
        String message;
        boolean transientMessage;
        short controller;
        short lowFrequency;
        short highFrequency;
        short leftTrigger;
        short rightTrigger;
        boolean hdrEnabled;
        byte[] hdrMetadata;
        short motionController;
        byte motionType;
        short motionRate;
        short ledController;
        byte red;
        byte green;
        byte blue;

        @Override public void onStageStarting(String stage) { startedStage = stage; }
        @Override public void onStageComplete(String stage) { completedStage = stage; }
        @Override public void onStageFailed(String stage, int flags, int error) {
            failedStage = stage;
            portFlags = flags;
            stageError = error;
        }
        @Override public void onConnectionStarted() { connected = true; }
        @Override public void onConnectionTerminated(int error) { terminationError = error; }
        @Override public void onConnectionStatusUpdate(int status) { connectionStatus = status; }
        @Override public void onMessage(String value, boolean transientValue) {
            message = value;
            transientMessage = transientValue;
        }
        @Override public void onRumble(short number, short low, short high) {
            controller = number;
            lowFrequency = low;
            highFrequency = high;
        }
        @Override public void onTriggerRumble(short number, short left, short right) {
            controller = number;
            leftTrigger = left;
            rightTrigger = right;
        }
        @Override public void onHdrMode(boolean enabled, byte[] metadata) {
            hdrEnabled = enabled;
            hdrMetadata = metadata;
        }
        @Override public void onMotionState(short number, byte type, short rate) {
            motionController = number;
            motionType = type;
            motionRate = rate;
        }
        @Override public void onControllerLed(short number, byte r, byte g, byte b) {
            ledController = number;
            red = r;
            green = g;
            blue = b;
        }
    }
}
