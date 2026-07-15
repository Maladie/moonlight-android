package com.limelight.console;

import android.view.KeyEvent;
import android.view.MotionEvent;

import java.util.Objects;

/** Breaks the transport-listener/input-sender initialization cycle without losing callbacks. */
final class DeferredConsoleSessionInput implements ConsoleSessionInput {
    private ConsoleSessionInput delegate;
    private boolean closed;

    synchronized void bind(ConsoleSessionInput delegate) {
        Objects.requireNonNull(delegate, "delegate");
        if (this.delegate != null) {
            throw new IllegalStateException("Session input already bound");
        }
        if (closed) {
            delegate.close();
            return;
        }
        this.delegate = delegate;
    }

    @Override public synchronized boolean handleKeyEvent(KeyEvent event) {
        return delegate != null && delegate.handleKeyEvent(event);
    }

    @Override public synchronized boolean handleMotionEvent(MotionEvent event) {
        return delegate != null && delegate.handleMotionEvent(event);
    }

    @Override public synchronized void enableSensors() {
        if (delegate != null) delegate.enableSensors();
    }

    @Override public synchronized void disableSensors() {
        if (delegate != null) delegate.disableSensors();
    }

    @Override public synchronized void close() {
        if (closed) return;
        closed = true;
        if (delegate != null) delegate.close();
    }

    @Override public synchronized void onStatus(String status) {
        if (delegate != null) delegate.onStatus(status);
    }

    @Override public synchronized void onConnectionStatus(int status) {
        if (delegate != null) delegate.onConnectionStatus(status);
    }

    @Override public synchronized void onMessage(String message, boolean transientMessage) {
        if (delegate != null) delegate.onMessage(message, transientMessage);
    }

    @Override public synchronized void onRumble(
            short controller, short lowFrequency, short highFrequency) {
        if (delegate != null) delegate.onRumble(controller, lowFrequency, highFrequency);
    }

    @Override public synchronized void onTriggerRumble(
            short controller, short leftTrigger, short rightTrigger) {
        if (delegate != null) delegate.onTriggerRumble(controller, leftTrigger, rightTrigger);
    }

    @Override public synchronized void onHdrMode(boolean enabled, byte[] metadata) {
        if (delegate != null) delegate.onHdrMode(enabled, metadata);
    }

    @Override public synchronized void onMotionState(
            short controller, byte motionType, short reportRateHz) {
        if (delegate != null) delegate.onMotionState(controller, motionType, reportRateHz);
    }

    @Override public synchronized void onControllerLed(
            short controller, byte red, byte green, byte blue) {
        if (delegate != null) delegate.onControllerLed(controller, red, green, blue);
    }
}
