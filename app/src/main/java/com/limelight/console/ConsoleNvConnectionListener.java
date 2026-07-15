package com.limelight.console;

import com.limelight.nvstream.NvConnectionListener;

import java.util.Objects;

/** Routes Moonlight transport callbacks into the in-Activity Console runtime. */
final class ConsoleNvConnectionListener implements NvConnectionListener {
    interface Callbacks {
        void onStageStarting(String stage);
        void onStageComplete(String stage);
        void onStageFailed(String stage, int portFlags, int errorCode);
        void onConnectionStarted();
        void onConnectionTerminated(int errorCode);
        void onConnectionStatusUpdate(int connectionStatus);
        void onMessage(String message, boolean transientMessage);
        void onRumble(short controllerNumber, short lowFrequency, short highFrequency);
        void onTriggerRumble(short controllerNumber, short leftTrigger, short rightTrigger);
        void onHdrMode(boolean enabled, byte[] metadata);
        void onMotionState(short controllerNumber, byte motionType, short reportRateHz);
        void onControllerLed(short controllerNumber, byte red, byte green, byte blue);
    }

    private final Callbacks callbacks;

    ConsoleNvConnectionListener(Callbacks callbacks) {
        this.callbacks = Objects.requireNonNull(callbacks, "callbacks");
    }

    @Override public void stageStarting(String stage) {
        callbacks.onStageStarting(stage);
    }

    @Override public void stageComplete(String stage) {
        callbacks.onStageComplete(stage);
    }

    @Override public void stageFailed(String stage, int portFlags, int errorCode) {
        callbacks.onStageFailed(stage, portFlags, errorCode);
    }

    @Override public void connectionStarted() {
        callbacks.onConnectionStarted();
    }

    @Override public void connectionTerminated(int errorCode) {
        callbacks.onConnectionTerminated(errorCode);
    }

    @Override public void connectionStatusUpdate(int connectionStatus) {
        callbacks.onConnectionStatusUpdate(connectionStatus);
    }

    @Override public void displayMessage(String message) {
        callbacks.onMessage(message, false);
    }

    @Override public void displayTransientMessage(String message) {
        callbacks.onMessage(message, true);
    }

    @Override public void rumble(short controllerNumber,
                                 short lowFreqMotor,
                                 short highFreqMotor) {
        callbacks.onRumble(controllerNumber, lowFreqMotor, highFreqMotor);
    }

    @Override public void rumbleTriggers(short controllerNumber,
                                         short leftTrigger,
                                         short rightTrigger) {
        callbacks.onTriggerRumble(controllerNumber, leftTrigger, rightTrigger);
    }

    @Override public void setHdrMode(boolean enabled, byte[] hdrMetadata) {
        callbacks.onHdrMode(enabled, hdrMetadata);
    }

    @Override public void setMotionEventState(short controllerNumber,
                                              byte motionType,
                                              short reportRateHz) {
        callbacks.onMotionState(controllerNumber, motionType, reportRateHz);
    }

    @Override public void setControllerLED(short controllerNumber,
                                           byte r,
                                           byte g,
                                           byte b) {
        callbacks.onControllerLed(controllerNumber, r, g, b);
    }
}
