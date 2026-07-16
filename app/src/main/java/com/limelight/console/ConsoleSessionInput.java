package com.limelight.console;

import android.view.KeyEvent;
import android.view.MotionEvent;

import com.limelight.binding.input.ControllerHandler;
import com.limelight.ui.overlay.CustomCommand;

import java.util.List;
import java.util.Collections;

/** Session-scoped gameplay input and host-feedback boundary. */
interface ConsoleSessionInput extends ConsoleSessionEventCoordinator.Feedback {
    boolean handleKeyEvent(KeyEvent event);
    boolean handleMotionEvent(MotionEvent event);
    default List<ControllerHandler.ControllerBatteryInfo> controllerBatteryInfo() {
        return Collections.emptyList();
    }
    default void refreshControllerBatteryInfo(Runnable completion) { }
    default void ensureControllersReported(boolean announceArrival) { }
    default void toggleMouseEmulation() { }
    default void sendGuideButton() { }
    default void sendCustomCommand(CustomCommand command, Runnable completion) { }
    void enableSensors();
    void disableSensors();
    void close();
}
