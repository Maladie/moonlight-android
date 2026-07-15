package com.limelight.console;

import android.view.KeyEvent;

/** Pure policy separating Android navigation Back from a gameplay face button. */
final class ConsoleKeyRouting {
    private ConsoleKeyRouting() { }

    static boolean isNavigationBack(int keyCode, boolean gameplayCaptured,
                                    boolean fromGameController) {
        return keyCode == KeyEvent.KEYCODE_BACK ||
                keyCode == KeyEvent.KEYCODE_ESCAPE ||
                (keyCode == KeyEvent.KEYCODE_BUTTON_B &&
                        (!gameplayCaptured || !fromGameController));
    }
}
