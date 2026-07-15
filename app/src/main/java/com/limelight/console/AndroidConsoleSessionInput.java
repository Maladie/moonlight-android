package com.limelight.console;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.MotionEvent;

import com.limelight.binding.input.ControllerHandler;
import com.limelight.binding.input.KeyboardTranslator;
import com.limelight.binding.video.MediaCodecDecoderRenderer;
import com.limelight.nvstream.input.ControllerPacket;
import com.limelight.nvstream.input.KeyboardPacket;
import com.limelight.preferences.PreferenceConfiguration;
import com.limelight.ui.GameGestures;
import com.limelight.ui.overlay.CustomCommand;

import java.util.List;
import java.util.Objects;

/** ControllerHandler adapter owned strictly by one unified Console session. */
final class AndroidConsoleSessionInput implements ConsoleSessionInput {
    interface Presentation {
        void onStatus(String status);
        void onConnectionStatus(int status);
        void onMessage(String message, boolean transientMessage);
        void onOverlayOpen();
    }

    private final ControllerHandler controllers;
    private final StreamInputSender inputSender;
    private final KeyboardTranslator keyboardTranslator = new KeyboardTranslator();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final MediaCodecDecoderRenderer renderer;
    private final Presentation presentation;
    private boolean closed;

    AndroidConsoleSessionInput(Activity activity,
                               StreamInputSender inputSender,
                               GameGestures gestures,
                               PreferenceConfiguration preferences,
                               MediaCodecDecoderRenderer renderer,
                               Presentation presentation) {
        this.inputSender = Objects.requireNonNull(inputSender, "inputSender");
        controllers = new ControllerHandler(
                Objects.requireNonNull(activity, "activity"),
                this.inputSender,
                Objects.requireNonNull(gestures, "gestures"),
                Objects.requireNonNull(preferences, "preferences"));
        this.renderer = Objects.requireNonNull(renderer, "renderer");
        this.presentation = Objects.requireNonNull(presentation, "presentation");
        controllers.setOverlayMenuListener(new ControllerHandler.OverlayMenuListener() {
            @Override public void onOverlayMenuOpen() {
                presentation.onOverlayOpen();
            }

            @Override public void onOverlayMenuCancel() {
                // No progress indicator is shown by the unified console.
            }
        });
    }

    @Override public synchronized boolean handleKeyEvent(KeyEvent event) {
        if (closed || event == null ||
                !ControllerHandler.isGameControllerDevice(event.getDevice())) return false;
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            return controllers.handleButtonDown(event);
        }
        if (event.getAction() == KeyEvent.ACTION_UP) {
            return controllers.handleButtonUp(event);
        }
        return false;
    }

    @Override public synchronized boolean handleMotionEvent(MotionEvent event) {
        if (closed || event == null) return false;
        int source = event.getSource();
        int deviceSources = event.getDevice() != null ? event.getDevice().getSources() : 0;
        if ((source & android.view.InputDevice.SOURCE_CLASS_JOYSTICK) != 0) {
            return controllers.handleMotionEvent(event);
        }
        if ((deviceSources & android.view.InputDevice.SOURCE_CLASS_JOYSTICK) != 0) {
            return controllers.tryHandleTouchpadEvent(event);
        }
        return false;
    }

    @Override public synchronized List<ControllerHandler.ControllerBatteryInfo>
            controllerBatteryInfo() {
        return controllers.getControllerBatteryInfo();
    }

    @Override public synchronized void refreshControllerBatteryInfo(Runnable completion) {
        if (!closed) controllers.refreshControllerBatteryInfo(completion);
    }

    @Override public synchronized void toggleMouseEmulation() {
        if (!closed) controllers.toggleMouseEmulationForController0();
    }

    @Override public synchronized void sendGuideButton() {
        if (closed) return;
        controllers.reportOscState(ControllerPacket.SPECIAL_BUTTON_FLAG,
                (short) 0, (short) 0, (short) 0, (short) 0, (byte) 0, (byte) 0);
        mainHandler.postDelayed(() -> {
            synchronized (AndroidConsoleSessionInput.this) {
                if (!closed) controllers.reportOscState(0,
                        (short) 0, (short) 0, (short) 0, (short) 0,
                        (byte) 0, (byte) 0);
            }
        }, 100L);
    }

    @Override public synchronized void sendCustomCommand(
            CustomCommand command, Runnable completion) {
        if (closed || command == null) return;
        CustomCommand.KeyCombination keys = command.getKeyCombination();
        short key = keyboardTranslator.translate(keys.getKeyCode(), -1);
        if (key == 0) return;
        short control = keyboardTranslator.translate(KeyEvent.KEYCODE_CTRL_LEFT, -1);
        short alt = keyboardTranslator.translate(KeyEvent.KEYCODE_ALT_LEFT, -1);
        short shift = keyboardTranslator.translate(KeyEvent.KEYCODE_SHIFT_LEFT, -1);
        short meta = keyboardTranslator.translate(KeyEvent.KEYCODE_META_LEFT, -1);
        byte modifiers = 0;
        if (keys.isCtrl()) modifiers |= KeyboardPacket.MODIFIER_CTRL;
        if (keys.isAlt()) modifiers |= KeyboardPacket.MODIFIER_ALT;
        if (keys.isShift()) modifiers |= KeyboardPacket.MODIFIER_SHIFT;
        if (keys.isMeta()) modifiers |= KeyboardPacket.MODIFIER_META;
        final byte finalModifiers = modifiers;
        if (keys.isCtrl()) sendKey(control, KeyboardPacket.KEY_DOWN, (byte) 0);
        if (keys.isAlt()) sendKey(alt, KeyboardPacket.KEY_DOWN, (byte) 0);
        if (keys.isShift()) sendKey(shift, KeyboardPacket.KEY_DOWN, (byte) 0);
        if (keys.isMeta()) sendKey(meta, KeyboardPacket.KEY_DOWN, (byte) 0);
        mainHandler.postDelayed(() -> {
            synchronized (AndroidConsoleSessionInput.this) {
                if (closed) return;
                sendKey(key, KeyboardPacket.KEY_DOWN, finalModifiers);
            }
            mainHandler.postDelayed(() -> {
                synchronized (AndroidConsoleSessionInput.this) {
                    if (closed) return;
                    sendKey(key, KeyboardPacket.KEY_UP, finalModifiers);
                }
                mainHandler.postDelayed(() -> {
                    synchronized (AndroidConsoleSessionInput.this) {
                        if (closed) return;
                        if (keys.isMeta()) sendKey(meta, KeyboardPacket.KEY_UP, (byte) 0);
                        if (keys.isShift()) sendKey(shift, KeyboardPacket.KEY_UP, (byte) 0);
                        if (keys.isAlt()) sendKey(alt, KeyboardPacket.KEY_UP, (byte) 0);
                        if (keys.isCtrl()) sendKey(control, KeyboardPacket.KEY_UP, (byte) 0);
                    }
                    if (completion != null) mainHandler.postDelayed(completion, 200L);
                }, 50L);
            }, 50L);
        }, 50L);
    }

    private void sendKey(short key, byte direction, byte modifiers) {
        inputSender.sendKeyboardInput(key, direction, modifiers, (byte) 0);
    }

    @Override public synchronized void enableSensors() {
        if (!closed) controllers.enableSensors();
    }

    @Override public synchronized void disableSensors() {
        if (!closed) controllers.disableSensors();
    }

    @Override public synchronized void close() {
        if (closed) return;
        closed = true;
        controllers.stop();
        controllers.destroy();
    }

    @Override public synchronized void onStatus(String status) {
        if (!closed) presentation.onStatus(status);
    }

    @Override public synchronized void onConnectionStatus(int status) {
        if (!closed) presentation.onConnectionStatus(status);
    }

    @Override public synchronized void onMessage(String message, boolean transientMessage) {
        if (!closed) presentation.onMessage(message, transientMessage);
    }

    @Override public synchronized void onRumble(
            short controller, short lowFrequency, short highFrequency) {
        if (!closed) controllers.handleRumble(controller, lowFrequency, highFrequency);
    }

    @Override public synchronized void onTriggerRumble(
            short controller, short leftTrigger, short rightTrigger) {
        if (!closed) controllers.handleRumbleTriggers(controller, leftTrigger, rightTrigger);
    }

    @Override public synchronized void onHdrMode(boolean enabled, byte[] metadata) {
        if (!closed) renderer.setHdrMode(enabled, metadata);
    }

    @Override public synchronized void onMotionState(
            short controller, byte motionType, short reportRateHz) {
        if (!closed) {
            controllers.handleSetMotionEventState(controller, motionType, reportRateHz);
        }
    }

    @Override public synchronized void onControllerLed(
            short controller, byte red, byte green, byte blue) {
        if (!closed) controllers.handleSetControllerLED(controller, red, green, blue);
    }
}
