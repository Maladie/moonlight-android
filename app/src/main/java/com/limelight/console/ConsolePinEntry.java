package com.limelight.console;

import android.view.InputDevice;
import android.view.KeyEvent;

import java.util.Arrays;

final class ConsolePinEntry {
    enum Action { IGNORED, CONSUMED, CHANGED, SUBMIT, BACK }

    private final char[] digits = new char[4];
    private int length;

    Action handle(int action, int keyCode, int sources) {
        int digit = digitForKey(keyCode, sources);
        boolean submit = keyCode == KeyEvent.KEYCODE_BUTTON_A;
        boolean back = keyCode == KeyEvent.KEYCODE_BUTTON_B
                || keyCode == KeyEvent.KEYCODE_BACK;
        if (digit < 0 && !submit && !back) return Action.IGNORED;
        if (action != KeyEvent.ACTION_UP) return Action.CONSUMED;
        if (back) {
            if (length == 0) return Action.BACK;
            digits[--length] = '\0';
            return Action.CHANGED;
        }
        if (submit) return length == digits.length ? Action.SUBMIT : Action.CONSUMED;
        if (length < digits.length) digits[length++] = (char) ('0' + digit);
        return length == digits.length ? Action.SUBMIT : Action.CHANGED;
    }

    Action handleBlocked(int action, int keyCode, int sources) {
        boolean back = keyCode == KeyEvent.KEYCODE_BUTTON_B
                || keyCode == KeyEvent.KEYCODE_BACK;
        if (digitForKey(keyCode, sources) < 0
                && keyCode != KeyEvent.KEYCODE_BUTTON_A && !back) {
            return Action.IGNORED;
        }
        return back && action == KeyEvent.ACTION_UP ? Action.BACK : Action.CONSUMED;
    }

    Action appendDigit(int digit) {
        if (digit < 0 || digit > 9) return Action.IGNORED;
        if (length < digits.length) digits[length++] = (char) ('0' + digit);
        return length == digits.length ? Action.SUBMIT : Action.CHANGED;
    }

    int length() {
        return length;
    }

    String takeAndClear() {
        String pin = new String(digits, 0, length);
        clear();
        return pin;
    }

    void clear() {
        Arrays.fill(digits, '\0');
        length = 0;
    }

    static int digitForKey(int keyCode, int sources) {
        if (keyCode >= KeyEvent.KEYCODE_0 && keyCode <= KeyEvent.KEYCODE_9) {
            return keyCode - KeyEvent.KEYCODE_0;
        }
        if (keyCode >= KeyEvent.KEYCODE_NUMPAD_0 && keyCode <= KeyEvent.KEYCODE_NUMPAD_9) {
            return keyCode - KeyEvent.KEYCODE_NUMPAD_0;
        }
        if (!isGamepad(sources)) return -1;
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_LEFT: return 1;
            case KeyEvent.KEYCODE_DPAD_UP: return 2;
            case KeyEvent.KEYCODE_DPAD_RIGHT: return 3;
            case KeyEvent.KEYCODE_DPAD_DOWN: return 4;
            case KeyEvent.KEYCODE_BUTTON_R1: return 5;
            case KeyEvent.KEYCODE_BUTTON_R2: return 6;
            case KeyEvent.KEYCODE_BUTTON_L1: return 7;
            case KeyEvent.KEYCODE_BUTTON_L2: return 8;
            case KeyEvent.KEYCODE_BUTTON_Y: return 9;
            case KeyEvent.KEYCODE_BUTTON_X: return 0;
            default: return -1;
        }
    }

    static boolean matchesResult(int requestGeneration, String requestHostId,
                                 String requestProfileId, int currentGeneration,
                                 String currentHostId, String currentProfileId) {
        return requestGeneration == currentGeneration
                && requestHostId != null && requestHostId.equals(currentHostId)
                && requestProfileId != null && requestProfileId.equals(currentProfileId);
    }

    static int retryDelaySeconds(String reason, int retryAfterSeconds) {
        return "invalid_pin".equals(reason) || "rate_limited".equals(reason)
                ? Math.max(1, retryAfterSeconds) : 0;
    }

    private static boolean isGamepad(int sources) {
        return (sources & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD
                || (sources & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK;
    }
}
