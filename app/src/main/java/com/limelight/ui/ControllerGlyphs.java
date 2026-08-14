package com.limelight.ui;

import android.view.InputDevice;
import android.content.Context;
import android.graphics.Typeface;

import java.util.Locale;

public final class ControllerGlyphs {
    public enum Button {
        CONFIRM, CANCEL, WEST, NORTH, MENU, RIGHT_BUMPER, LEFT_STICK, RIGHT_STICK
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
                case RIGHT_BUMPER: return "\uEEDA";
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
                case RIGHT_BUMPER: return "\uEEDF";
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
