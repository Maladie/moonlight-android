package com.limelight.console;

import android.view.InputDevice;
import android.view.KeyEvent;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ConsolePinEntryTest {
    private static final int GAMEPAD = InputDevice.SOURCE_GAMEPAD;

    @Test public void gamepadMapsEveryDigitWithoutChangingFocus() {
        int[] keys = {
                KeyEvent.KEYCODE_BUTTON_X,
                KeyEvent.KEYCODE_DPAD_LEFT,
                KeyEvent.KEYCODE_DPAD_UP,
                KeyEvent.KEYCODE_DPAD_RIGHT,
                KeyEvent.KEYCODE_DPAD_DOWN,
                KeyEvent.KEYCODE_BUTTON_R1,
                KeyEvent.KEYCODE_BUTTON_R2,
                KeyEvent.KEYCODE_BUTTON_L1,
                KeyEvent.KEYCODE_BUTTON_L2,
                KeyEvent.KEYCODE_BUTTON_Y
        };
        for (int digit = 0; digit <= 9; digit++) {
            assertEquals(digit, ConsolePinEntry.digitForKey(keys[digit], GAMEPAD));
        }
    }

    @Test public void pinBoardUsesBundledControllerGlyphMappings() {
        assertEquals("\uEECB", com.limelight.ui.ControllerGlyphs.text(
                false, com.limelight.ui.ControllerGlyphs.Button.DPAD_LEFT));
        assertEquals("\uEEC9", com.limelight.ui.ControllerGlyphs.text(
                false, com.limelight.ui.ControllerGlyphs.Button.DPAD_UP));
        assertEquals("\uEECA", com.limelight.ui.ControllerGlyphs.text(
                false, com.limelight.ui.ControllerGlyphs.Button.DPAD_RIGHT));
        assertEquals("\uEECC", com.limelight.ui.ControllerGlyphs.text(
                false, com.limelight.ui.ControllerGlyphs.Button.DPAD_DOWN));
        assertEquals("\uEED9", com.limelight.ui.ControllerGlyphs.text(
                true, com.limelight.ui.ControllerGlyphs.Button.RIGHT_TRIGGER));
        assertEquals("\uEED1", com.limelight.ui.ControllerGlyphs.text(
                false, com.limelight.ui.ControllerGlyphs.Button.RIGHT_TRIGGER));
    }

    @Test public void remoteDpadRemainsNavigation() {
        for (int key : new int[]{KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_UP,
                KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_DOWN}) {
            assertEquals(-1, ConsolePinEntry.digitForKey(key, InputDevice.SOURCE_DPAD));
        }
    }

    @Test public void physicalNumberKeysAreAccepted() {
        for (int digit = 0; digit <= 9; digit++) {
            assertEquals(digit, ConsolePinEntry.digitForKey(
                    KeyEvent.KEYCODE_0 + digit, InputDevice.SOURCE_KEYBOARD));
            assertEquals(digit, ConsolePinEntry.digitForKey(
                    KeyEvent.KEYCODE_NUMPAD_0 + digit, InputDevice.SOURCE_KEYBOARD));
        }
    }

    @Test public void bufferIsFourDigitsMaskedByCountAndClearedWhenTaken() {
        ConsolePinEntry entry = new ConsolePinEntry();
        assertEquals(ConsolePinEntry.Action.CHANGED, entry.appendDigit(1));
        assertEquals(ConsolePinEntry.Action.CHANGED, entry.appendDigit(2));
        assertEquals(2, entry.length());
        entry.appendDigit(3);
        assertEquals(ConsolePinEntry.Action.SUBMIT, entry.appendDigit(4));
        assertEquals(4, entry.length());
        assertEquals("1234", entry.takeAndClear());
        assertEquals(0, entry.length());
    }

    @Test public void fourDigitsAutoSubmitAndAOnlySubmitsAFullEntry() {
        ConsolePinEntry entry = new ConsolePinEntry();
        entry.appendDigit(1);
        assertEquals(ConsolePinEntry.Action.CONSUMED, entry.handle(KeyEvent.ACTION_UP,
                KeyEvent.KEYCODE_BUTTON_A, GAMEPAD));
        entry.appendDigit(2);
        entry.appendDigit(3);
        entry.appendDigit(4);
        assertEquals(ConsolePinEntry.Action.SUBMIT, entry.handle(KeyEvent.ACTION_UP,
                KeyEvent.KEYCODE_BUTTON_A, GAMEPAD));
    }

    @Test public void bDeletesThenReturnsToProfileSelectionWhenEmpty() {
        ConsolePinEntry entry = new ConsolePinEntry();
        entry.appendDigit(1);
        assertEquals(ConsolePinEntry.Action.CHANGED, entry.handle(KeyEvent.ACTION_UP,
                KeyEvent.KEYCODE_BUTTON_B, GAMEPAD));
        assertEquals(0, entry.length());
        assertEquals(ConsolePinEntry.Action.BACK, entry.handle(KeyEvent.ACTION_UP,
                KeyEvent.KEYCODE_BUTTON_B, GAMEPAD));
    }

    @Test public void staleHostProfileOrGenerationNeverMatches() {
        assertTrue(ConsolePinEntry.matchesResult(7, "host", "profile",
                7, "host", "profile"));
        assertFalse(ConsolePinEntry.matchesResult(7, "host", "profile",
                8, "host", "profile"));
        assertFalse(ConsolePinEntry.matchesResult(7, "host", "profile",
                7, "other", "profile"));
        assertFalse(ConsolePinEntry.matchesResult(7, "host", "profile",
                7, "host", "other"));
    }

    @Test public void invalidAndRateLimitedResponsesEnforceCooldown() {
        assertEquals(1, ConsolePinEntry.retryDelaySeconds("invalid_pin", 0));
        assertEquals(8, ConsolePinEntry.retryDelaySeconds("rate_limited", 8));
        assertEquals(0, ConsolePinEntry.retryDelaySeconds("unavailable", 8));
    }

    @Test public void blockedEntryConsumesDigitsWithoutChangingSecretButAllowsBack() {
        ConsolePinEntry entry = new ConsolePinEntry();
        entry.appendDigit(1);
        assertEquals(ConsolePinEntry.Action.CONSUMED, entry.handleBlocked(
                KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_UP, GAMEPAD));
        assertEquals(ConsolePinEntry.Action.CONSUMED, entry.handleBlocked(
                KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BUTTON_A, GAMEPAD));
        assertEquals(1, entry.length());
        assertEquals(ConsolePinEntry.Action.BACK, entry.handleBlocked(
                KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BUTTON_B, GAMEPAD));
        assertEquals(1, entry.length());
    }

    @Test public void recreationHasNoUnlockOrEnteredDigits() {
        ConsolePinEntry beforeRecreation = new ConsolePinEntry();
        beforeRecreation.appendDigit(9);

        ConsolePinEntry afterRecreation = new ConsolePinEntry();

        assertEquals(0, afterRecreation.length());
    }
}
