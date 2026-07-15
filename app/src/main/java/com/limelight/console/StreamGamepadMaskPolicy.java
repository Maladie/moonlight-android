package com.limelight.console;

/** Pure initial gamepad advertisement policy shared by both stream runtimes. */
public final class StreamGamepadMaskPolicy {
    private static final int PRIMARY_GAMEPAD = 1;

    private StreamGamepadMaskPolicy() { }

    public static int evaluate(int attachedGamepadMask,
                               boolean multipleControllersEnabled,
                               boolean onscreenControllerEnabled) {
        int gamepadMask = multipleControllersEnabled ? attachedGamepadMask : PRIMARY_GAMEPAD;
        if (onscreenControllerEnabled) {
            gamepadMask |= PRIMARY_GAMEPAD;
        }
        return gamepadMask;
    }
}
