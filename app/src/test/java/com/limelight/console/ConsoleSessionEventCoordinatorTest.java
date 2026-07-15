package com.limelight.console;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class ConsoleSessionEventCoordinatorTest {
    @Test public void startsLifecycleAndRuntimeTogether() {
        RecordingLifecycle lifecycle = new RecordingLifecycle();
        RecordingRuntimeListener runtime = new RecordingRuntimeListener();
        RecordingFeedback feedback = new RecordingFeedback();
        ConsoleSessionEventCoordinator coordinator =
                new ConsoleSessionEventCoordinator(lifecycle, runtime, feedback);

        coordinator.onStageStarting("RTSP");
        coordinator.onStageComplete("RTSP");
        coordinator.onConnectionStarted();

        assertEquals("Completed RTSP", feedback.status);
        assertTrue(lifecycle.started);
        assertEquals(1, runtime.connectedCount);
    }

    @Test public void stageFailureAndTerminationDeliverOneFailure() {
        RecordingLifecycle lifecycle = new RecordingLifecycle();
        RecordingRuntimeListener runtime = new RecordingRuntimeListener();
        ConsoleSessionEventCoordinator coordinator = new ConsoleSessionEventCoordinator(
                lifecycle, runtime, new RecordingFeedback());

        coordinator.onStageFailed("Video", 3, -7);
        coordinator.onConnectionTerminated(-9);
        coordinator.onConnectionStarted();

        assertTrue(lifecycle.failed);
        assertTrue(lifecycle.terminated);
        assertEquals(1, runtime.failureCount);
        assertEquals("Stage Video failed (-7)", runtime.reason);
        assertEquals(0, runtime.connectedCount);
    }

    @Test public void forwardsPeripheralFeedback() {
        RecordingFeedback feedback = new RecordingFeedback();
        ConsoleSessionEventCoordinator coordinator = new ConsoleSessionEventCoordinator(
                new RecordingLifecycle(), new RecordingRuntimeListener(), feedback);

        coordinator.onConnectionStatusUpdate(2);
        coordinator.onMessage("hello", true);
        coordinator.onRumble((short) 1, (short) 2, (short) 3);
        coordinator.onTriggerRumble((short) 1, (short) 4, (short) 5);
        coordinator.onHdrMode(true, new byte[] { 6 });
        coordinator.onMotionState((short) 2, (byte) 7, (short) 120);
        coordinator.onControllerLed((short) 3, (byte) 8, (byte) 9, (byte) 10);

        assertEquals(2, feedback.connectionStatus);
        assertEquals("hello", feedback.message);
        assertTrue(feedback.transientMessage);
        assertEquals(3, feedback.highFrequency);
        assertEquals(5, feedback.rightTrigger);
        assertTrue(feedback.hdrEnabled);
        assertEquals(120, feedback.motionRate);
        assertEquals(10, feedback.blue);
    }

    private static final class RecordingLifecycle implements
            ConsoleSessionEventCoordinator.Lifecycle {
        boolean started;
        boolean failed;
        boolean terminated;
        @Override public void onStarted() { started = true; }
        @Override public void onFailed() { failed = true; }
        @Override public void onTerminated() { terminated = true; }
    }

    private static final class RecordingRuntimeListener implements
            ConsoleResolvedStreamRuntime.Listener {
        int connectedCount;
        int failureCount;
        String reason;
        @Override public void onConnected() { connectedCount++; }
        @Override public void onConnectionFailed(String reason) {
            failureCount++;
            this.reason = reason;
        }
    }

    private static final class RecordingFeedback implements
            ConsoleSessionEventCoordinator.Feedback {
        String status;
        int connectionStatus;
        String message;
        boolean transientMessage;
        short highFrequency;
        short rightTrigger;
        boolean hdrEnabled;
        short motionRate;
        byte blue;

        @Override public void onStatus(String value) { status = value; }
        @Override public void onConnectionStatus(int value) { connectionStatus = value; }
        @Override public void onMessage(String value, boolean transientValue) {
            message = value;
            transientMessage = transientValue;
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
