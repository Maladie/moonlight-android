package com.limelight.console;

import android.view.KeyEvent;
import android.view.MotionEvent;

/** Session-scoped gameplay input and host-feedback boundary. */
interface ConsoleSessionInput extends ConsoleSessionEventCoordinator.Feedback {
    boolean handleKeyEvent(KeyEvent event);
    boolean handleMotionEvent(MotionEvent event);
    void enableSensors();
    void disableSensors();
    void close();
}
