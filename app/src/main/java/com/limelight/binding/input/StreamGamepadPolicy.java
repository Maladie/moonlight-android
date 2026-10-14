package com.limelight.binding.input;

import android.view.InputDevice;

/**
 * MoonWaker: decides which input devices may act as stream gamepads and is the single owner
 * of protocol controller numbers. A TV remote or keyboard/d-pad device stays local-only while a
 * real pad is connected, so it can never merge into (or shadow) that pad's player slot.
 *
 * <p>Numbers are reserved from the input threads (main and USB driver), so the pool is
 * synchronized; the real-pad presence is decided on the main thread and read anywhere.
 */
public final class StreamGamepadPolicy {
    public enum DeviceKind { REAL_PAD, REMOTE, KEYBOARD, OTHER }

    /** A device context that may hold a controller number. */
    public interface Holder {
        DeviceKind kind();
        boolean assigned();
        /** Sends this holder's neutral state and gives its number back to the policy. */
        void releaseNeutral();
    }

    private static final int KEY_ONLY_SOURCES =
            InputDevice.SOURCE_KEYBOARD | InputDevice.SOURCE_DPAD | InputDevice.SOURCE_GAMEPAD;

    private final int maxControllers;
    private int reserved;
    private int initial;
    private volatile boolean realPadConnected;

    public StreamGamepadPolicy(int maxControllers) {
        this.maxControllers = maxControllers;
    }

    /**
     * Classifies by capability, never by name or the (unreliable) external flag: a real pad
     * reports a pad source and analog stick axes; a remote or keyboard has only keys/d-pad.
     */
    public static DeviceKind classify(int sources, boolean hasStickAxes) {
        boolean padSource = (sources & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD
                || (sources & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK;
        if (padSource && hasStickAxes) return DeviceKind.REAL_PAD;
        // A d-pad without sticks is a remote, an air mouse's pointer source included.
        if ((sources & InputDevice.SOURCE_DPAD) == InputDevice.SOURCE_DPAD) return DeviceKind.REMOTE;
        // A real pad's touchpad and motion sensors (no d-pad) keep upstream handling.
        if ((sources & ~KEY_ONLY_SOURCES) != 0) return DeviceKind.OTHER;
        return (sources & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD
                ? DeviceKind.REMOTE : DeviceKind.KEYBOARD;
    }

    /** Remote/keyboard devices only drive local UI while a real pad owns the stream. */
    public static boolean isStreamGamepad(DeviceKind kind, boolean realPadConnected) {
        return !realPadConnected || (kind != DeviceKind.REMOTE && kind != DeviceKind.KEYBOARD);
    }

    public boolean isStreamGamepad(DeviceKind kind) {
        return isStreamGamepad(kind, realPadConnected);
    }

    /** A device that already holds a number must give it up once it stops being a stream pad. */
    public static boolean mustRelease(DeviceKind kind, boolean assigned, boolean realPadConnected) {
        return assigned && !isStreamGamepad(kind, realPadConnected);
    }

    /** Main thread: records presence; every holder that stops being a stream pad is released. */
    public void updateRealPadPresence(boolean present, Iterable<? extends Holder> holders) {
        realPadConnected = present;
        for (Holder holder : holders) {
            if (mustRelease(holder.kind(), holder.assigned(), present)) holder.releaseNeutral();
        }
    }

    /** Returns the lowest free controller number, or -1 when every slot is taken. */
    public synchronized short reserve() {
        for (short i = 0; i < maxControllers; i++) {
            if ((reserved & (1 << i)) == 0) {
                reserved |= 1 << i;
                // A reserved slot is no longer one only announced as attached at start.
                initial &= ~(1 << i);
                return i;
            }
        }
        return -1;
    }

    public synchronized void release(short controllerNumber) {
        reserved &= ~(1 << controllerNumber);
    }

    /** Pads attached when the stream started, announced before their first input. */
    public synchronized void setInitialMask(short mask) {
        initial = mask & 0xFFFF;
    }

    /** Reserved numbers plus pads announced at start. */
    public synchronized short activeMask() {
        return (short) (reserved | initial);
    }

    /** Diagnostics label for the device that opened the overlay. */
    public static String overlaySource(DeviceKind kind) {
        return kind == DeviceKind.REMOTE || kind == DeviceKind.KEYBOARD
                ? "remote_key" : "pad_shortcut";
    }
}
