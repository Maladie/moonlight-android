package com.limelight.console;

import android.view.KeyEvent;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ConsoleKeyRoutingTest {
    @Test public void gamepadBRemainsGameplayInputDuringStream() {
        assertFalse(ConsoleKeyRouting.isNavigationBack(
                KeyEvent.KEYCODE_BUTTON_B, true, true));
    }

    @Test public void remoteButtonBStillNavigatesBackDuringStream() {
        assertTrue(ConsoleKeyRouting.isNavigationBack(
                KeyEvent.KEYCODE_BUTTON_B, true, false));
    }

    @Test public void gamepadBClosesConsoleUiOutsideGameplay() {
        assertTrue(ConsoleKeyRouting.isNavigationBack(
                KeyEvent.KEYCODE_BUTTON_B, false, true));
    }

    @Test public void androidBackAlwaysNavigatesBack() {
        assertTrue(ConsoleKeyRouting.isNavigationBack(
                KeyEvent.KEYCODE_BACK, true, true));
    }
}
