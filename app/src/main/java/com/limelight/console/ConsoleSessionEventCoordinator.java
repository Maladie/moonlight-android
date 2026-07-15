package com.limelight.console;

import java.util.Objects;

/** Converts raw transport callbacks into one coherent Console session lifecycle. */
final class ConsoleSessionEventCoordinator implements ConsoleNvConnectionListener.Callbacks {
    interface Lifecycle {
        void onStarted();
        void onFailed();
        void onTerminated();
    }

    interface Feedback {
        void onStatus(String status);
        void onConnectionStatus(int status);
        void onMessage(String message, boolean transientMessage);
        void onRumble(short controller, short lowFrequency, short highFrequency);
        void onTriggerRumble(short controller, short leftTrigger, short rightTrigger);
        void onHdrMode(boolean enabled, byte[] metadata);
        void onMotionState(short controller, byte motionType, short reportRateHz);
        void onControllerLed(short controller, byte red, byte green, byte blue);
    }

    private final Lifecycle lifecycle;
    private final ConsoleResolvedStreamRuntime.Listener runtimeListener;
    private final Feedback feedback;
    private boolean terminalDelivered;

    ConsoleSessionEventCoordinator(Lifecycle lifecycle,
                                   ConsoleResolvedStreamRuntime.Listener runtimeListener,
                                   Feedback feedback) {
        this.lifecycle = Objects.requireNonNull(lifecycle, "lifecycle");
        this.runtimeListener = Objects.requireNonNull(runtimeListener, "runtimeListener");
        this.feedback = Objects.requireNonNull(feedback, "feedback");
    }

    @Override public synchronized void onStageStarting(String stage) {
        feedback.onStatus("Starting " + stage);
    }

    @Override public synchronized void onStageComplete(String stage) {
        feedback.onStatus("Completed " + stage);
    }

    @Override public synchronized void onStageFailed(
            String stage, int portFlags, int errorCode) {
        lifecycle.onFailed();
        failOnce("Stage " + stage + " failed (" + errorCode + ")");
    }

    @Override public synchronized void onConnectionStarted() {
        if (terminalDelivered) return;
        lifecycle.onStarted();
        runtimeListener.onConnected();
    }

    @Override public synchronized void onConnectionTerminated(int errorCode) {
        lifecycle.onTerminated();
        failOnce("Connection terminated (" + errorCode + ")");
    }

    @Override public synchronized void onConnectionStatusUpdate(int connectionStatus) {
        feedback.onConnectionStatus(connectionStatus);
    }

    @Override public synchronized void onMessage(String message, boolean transientMessage) {
        feedback.onMessage(message, transientMessage);
    }

    @Override public synchronized void onRumble(
            short controllerNumber, short lowFrequency, short highFrequency) {
        feedback.onRumble(controllerNumber, lowFrequency, highFrequency);
    }

    @Override public synchronized void onTriggerRumble(
            short controllerNumber, short leftTrigger, short rightTrigger) {
        feedback.onTriggerRumble(controllerNumber, leftTrigger, rightTrigger);
    }

    @Override public synchronized void onHdrMode(boolean enabled, byte[] metadata) {
        feedback.onHdrMode(enabled, metadata);
    }

    @Override public synchronized void onMotionState(
            short controllerNumber, byte motionType, short reportRateHz) {
        feedback.onMotionState(controllerNumber, motionType, reportRateHz);
    }

    @Override public synchronized void onControllerLed(
            short controllerNumber, byte red, byte green, byte blue) {
        feedback.onControllerLed(controllerNumber, red, green, blue);
    }

    private void failOnce(String reason) {
        if (terminalDelivered) return;
        terminalDelivered = true;
        runtimeListener.onConnectionFailed(reason);
    }
}
