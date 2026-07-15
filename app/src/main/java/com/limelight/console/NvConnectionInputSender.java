package com.limelight.console;

import com.limelight.nvstream.NvConnection;

import java.util.Objects;

/** Adapts the controller-owned NvConnection to the input-only session boundary. */
final class NvConnectionInputSender implements StreamInputSender {
    private final NvConnection connection;

    NvConnectionInputSender(NvConnection connection) {
        this.connection = Objects.requireNonNull(connection, "connection");
    }

    @Override public void sendMouseMove(short deltaX, short deltaY) {
        connection.sendMouseMove(deltaX, deltaY);
    }

    @Override public void sendMousePosition(short x, short y, short referenceWidth, short referenceHeight) {
        connection.sendMousePosition(x, y, referenceWidth, referenceHeight);
    }

    @Override public void sendMouseMoveAsMousePosition(short deltaX, short deltaY, short referenceWidth, short referenceHeight) {
        connection.sendMouseMoveAsMousePosition(deltaX, deltaY, referenceWidth, referenceHeight);
    }

    @Override public void sendMouseButtonDown(byte mouseButton) {
        connection.sendMouseButtonDown(mouseButton);
    }

    @Override public void sendMouseButtonUp(byte mouseButton) {
        connection.sendMouseButtonUp(mouseButton);
    }

    @Override public void sendControllerInput(short controllerNumber, short activeGamepadMask, int buttonFlags,
                                              byte leftTrigger, byte rightTrigger, short leftStickX, short leftStickY,
                                              short rightStickX, short rightStickY) {
        connection.sendControllerInput(controllerNumber, activeGamepadMask, buttonFlags,
                leftTrigger, rightTrigger, leftStickX, leftStickY, rightStickX, rightStickY);
    }

    @Override public void sendKeyboardInput(short keyMap, byte keyDirection, byte modifier, byte flags) {
        connection.sendKeyboardInput(keyMap, keyDirection, modifier, flags);
    }

    @Override public void sendMouseScroll(byte scrollClicks) {
        connection.sendMouseScroll(scrollClicks);
    }

    @Override public void sendMouseHScroll(byte scrollClicks) {
        connection.sendMouseHScroll(scrollClicks);
    }

    @Override public void sendMouseHighResScroll(short scrollAmount) {
        connection.sendMouseHighResScroll(scrollAmount);
    }

    @Override public void sendMouseHighResHScroll(short scrollAmount) {
        connection.sendMouseHighResHScroll(scrollAmount);
    }

    @Override public int sendTouchEvent(byte eventType, int pointerId, float x, float y,
                                        float pressureOrDistance, float contactAreaMajor,
                                        float contactAreaMinor, short rotation) {
        return connection.sendTouchEvent(eventType, pointerId, x, y, pressureOrDistance,
                contactAreaMajor, contactAreaMinor, rotation);
    }

    @Override public int sendPenEvent(byte eventType, byte toolType, byte penButtons, float x, float y,
                                      float pressureOrDistance, float contactAreaMajor,
                                      float contactAreaMinor, short rotation, byte tilt) {
        return connection.sendPenEvent(eventType, toolType, penButtons, x, y, pressureOrDistance,
                contactAreaMajor, contactAreaMinor, rotation, tilt);
    }

    @Override public int sendControllerArrivalEvent(byte controllerNumber, short activeGamepadMask,
                                                     byte type, int supportedButtonFlags, short capabilities) {
        return connection.sendControllerArrivalEvent(controllerNumber, activeGamepadMask, type,
                supportedButtonFlags, capabilities);
    }

    @Override public int sendControllerTouchEvent(byte controllerNumber, byte eventType, int pointerId,
                                                   float x, float y, float pressure) {
        return connection.sendControllerTouchEvent(controllerNumber, eventType, pointerId, x, y, pressure);
    }

    @Override public int sendControllerMotionEvent(byte controllerNumber, byte motionType,
                                                    float x, float y, float z) {
        return connection.sendControllerMotionEvent(controllerNumber, motionType, x, y, z);
    }

    @Override public void sendControllerBatteryEvent(byte controllerNumber, byte batteryState,
                                                      byte batteryPercentage) {
        connection.sendControllerBatteryEvent(controllerNumber, batteryState, batteryPercentage);
    }

    @Override public void sendUtf8Text(String text) {
        connection.sendUtf8Text(text);
    }
}
