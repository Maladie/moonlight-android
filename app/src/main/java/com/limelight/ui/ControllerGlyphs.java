package com.limelight.ui;

import android.view.InputDevice;
import android.content.Context;
import android.graphics.Typeface;

import java.util.Locale;

public final class ControllerGlyphs {
    public enum Button {
        CONFIRM, CANCEL, WEST, NORTH, MENU,
        DPAD_LEFT, DPAD_UP, DPAD_RIGHT, DPAD_DOWN,
        RIGHT_BUMPER, RIGHT_TRIGGER, LEFT_BUMPER, LEFT_TRIGGER,
        LEFT_STICK, RIGHT_STICK
    }

    private ControllerGlyphs() {}
    private static Typeface glyphTypeface;

    public static boolean hasPlayStationController() {
        for (int id : InputDevice.getDeviceIds()) {
            InputDevice device = InputDevice.getDevice(id);
            if (isGamepad(device) && isPlayStation(device)) return true;
        }
        return false;
    }

    public static boolean isPlayStation(InputDevice device) {
        if (device == null) return false;
        String name = device.getName() == null ? "" : device.getName().toLowerCase(Locale.ROOT);
        return device.getVendorId() == 0x054c || name.contains("dualsense")
                || name.contains("dualshock") || name.contains("playstation")
                || name.contains("sony interactive") || name.contains("wireless controller");
    }

    public static synchronized Typeface typeface(Context context) {
        if (glyphTypeface == null) {
            glyphTypeface = Typeface.createFromAsset(
                    context.getApplicationContext().getAssets(), "controller_glyphs.ttf");
        }
        return glyphTypeface;
    }

    public static String text(boolean playStation, Button button) {
        if (playStation) {
            switch (button) {
                case CONFIRM: return "\uE5C9";
                case CANCEL: return "\uEEE1";
                case WEST: return "\uEEC7";
                case NORTH: return "\uEEC6";
                case MENU: return "\uE5D2";
                case DPAD_LEFT: return "\uEECB";
                case DPAD_UP: return "\uEEC9";
                case DPAD_RIGHT: return "\uEECA";
                case DPAD_DOWN: return "\uEECC";
                case RIGHT_BUMPER: return "\uEEDA";
                case RIGHT_TRIGGER: return "\uEED9";
                case LEFT_BUMPER: return "\uEEDD";
                case LEFT_TRIGGER: return "\uEEDC";
                case LEFT_STICK: return "\uEED6";
                case RIGHT_STICK: return "\uEED4";
            }
        } else {
            switch (button) {
                case CONFIRM: return "\uF01A";
                case CANCEL: return "\uEEE2";
                case WEST: return "\uE349";
                case NORTH: return "\uEEC5";
                case MENU: return "\uE5D2";
                case DPAD_LEFT: return "\uEECB";
                case DPAD_UP: return "\uEEC9";
                case DPAD_RIGHT: return "\uEECA";
                case DPAD_DOWN: return "\uEECC";
                case RIGHT_BUMPER: return "\uEEDF";
                case RIGHT_TRIGGER: return "\uEED1";
                case LEFT_BUMPER: return "\uEEE0";
                case LEFT_TRIGGER: return "\uEED2";
                case LEFT_STICK: return "\uEED6";
                case RIGHT_STICK: return "\uEED4";
            }
        }
        throw new IllegalArgumentException("Unsupported controller button " + button);
    }

    private static boolean isGamepad(InputDevice device) {
        if (device == null) return false;
        int sources = device.getSources();
        return (sources & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD
                || (sources & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK;
    }
}
