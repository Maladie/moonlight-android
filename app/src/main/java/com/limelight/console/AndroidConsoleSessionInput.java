package com.limelight.console;

import android.app.Activity;
import android.view.KeyEvent;
import android.view.MotionEvent;

import com.limelight.binding.input.ControllerHandler;
import com.limelight.binding.video.MediaCodecDecoderRenderer;
import com.limelight.preferences.PreferenceConfiguration;
import com.limelight.ui.GameGestures;

import java.util.Objects;

/** ControllerHandler adapter owned strictly by one unified Console session. */
final class AndroidConsoleSessionInput implements ConsoleSessionInput {
    interface Presentation {
        void onStatus(String status);
        void onConnectionStatus(int status);
        void onMessage(String message, boolean transientMessage);
    }

    private final ControllerHandler controllers;
    private final MediaCodecDecoderRenderer renderer;
    private final Presentation presentation;
    private boolean closed;

    AndroidConsoleSessionInput(Activity activity,
                               StreamInputSender inputSender,
                               GameGestures gestures,
                               PreferenceConfiguration preferences,
                               MediaCodecDecoderRenderer renderer,
                               Presentation presentation) {
        controllers = new ControllerHandler(
                Objects.requireNonNull(activity, "activity"),
                Objects.requireNonNull(inputSender, "inputSender"),
                Objects.requireNonNull(gestures, "gestures"),
                Objects.requireNonNull(preferences, "preferences"));
        this.renderer = Objects.requireNonNull(renderer, "renderer");
        this.presentation = Objects.requireNonNull(presentation, "presentation");
    }

    @Override public synchronized boolean handleKeyEvent(KeyEvent event) {
        if (closed || event == null) return false;
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            return controllers.handleButtonDown(event);
        }
        if (event.getAction() == KeyEvent.ACTION_UP) {
            return controllers.handleButtonUp(event);
        }
        return false;
    }

    @Override public synchronized boolean handleMotionEvent(MotionEvent event) {
        if (closed || event == null) return false;
        return controllers.handleMotionEvent(event) || controllers.tryHandleTouchpadEvent(event);
    }

    @Override public synchronized void enableSensors() {
        if (!closed) controllers.enableSensors();
    }

    @Override public synchronized void disableSensors() {
        if (!closed) controllers.disableSensors();
    }

    @Override public synchronized void close() {
        if (closed) return;
        closed = true;
        controllers.stop();
        controllers.destroy();
    }

    @Override public synchronized void onStatus(String status) {
        if (!closed) presentation.onStatus(status);
    }

    @Override public synchronized void onConnectionStatus(int status) {
        if (!closed) presentation.onConnectionStatus(status);
    }

    @Override public synchronized void onMessage(String message, boolean transientMessage) {
        if (!closed) presentation.onMessage(message, transientMessage);
    }

    @Override public synchronized void onRumble(
            short controller, short lowFrequency, short highFrequency) {
        if (!closed) controllers.handleRumble(controller, lowFrequency, highFrequency);
    }

    @Override public synchronized void onTriggerRumble(
            short controller, short leftTrigger, short rightTrigger) {
        if (!closed) controllers.handleRumbleTriggers(controller, leftTrigger, rightTrigger);
    }

    @Override public synchronized void onHdrMode(boolean enabled, byte[] metadata) {
        if (!closed) renderer.setHdrMode(enabled, metadata);
    }

    @Override public synchronized void onMotionState(
            short controller, byte motionType, short reportRateHz) {
        if (!closed) {
            controllers.handleSetMotionEventState(controller, motionType, reportRateHz);
        }
    }

    @Override public synchronized void onControllerLed(
            short controller, byte red, byte green, byte blue) {
        if (!closed) controllers.handleSetControllerLED(controller, red, green, blue);
    }
}
