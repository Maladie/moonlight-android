package com.limelight.console;

/**
 * Session-scoped boundary used by Android input adapters to send input to the host.
 * The stream session controller owns the underlying transport connection.
 */
public interface StreamInputSender {
    void sendMouseMove(short deltaX, short deltaY);
    void sendMousePosition(short x, short y, short referenceWidth, short referenceHeight);
    void sendMouseMoveAsMousePosition(short deltaX, short deltaY, short referenceWidth, short referenceHeight);
    void sendMouseButtonDown(byte mouseButton);
    void sendMouseButtonUp(byte mouseButton);
    void sendControllerInput(short controllerNumber, short activeGamepadMask, int buttonFlags,
                             byte leftTrigger, byte rightTrigger, short leftStickX, short leftStickY,
                             short rightStickX, short rightStickY);
    void sendKeyboardInput(short keyMap, byte keyDirection, byte modifier, byte flags);
    void sendMouseScroll(byte scrollClicks);
    void sendMouseHScroll(byte scrollClicks);
    void sendMouseHighResScroll(short scrollAmount);
    void sendMouseHighResHScroll(short scrollAmount);
    int sendTouchEvent(byte eventType, int pointerId, float x, float y, float pressureOrDistance,
                       float contactAreaMajor, float contactAreaMinor, short rotation);
    int sendPenEvent(byte eventType, byte toolType, byte penButtons, float x, float y,
                     float pressureOrDistance, float contactAreaMajor, float contactAreaMinor,
                     short rotation, byte tilt);
    int sendControllerArrivalEvent(byte controllerNumber, short activeGamepadMask, byte type,
                                   int supportedButtonFlags, short capabilities);
    int sendControllerTouchEvent(byte controllerNumber, byte eventType, int pointerId,
                                 float x, float y, float pressure);
    int sendControllerMotionEvent(byte controllerNumber, byte motionType, float x, float y, float z);
    void sendControllerBatteryEvent(byte controllerNumber, byte batteryState, byte batteryPercentage);
    void sendUtf8Text(String text);
}
