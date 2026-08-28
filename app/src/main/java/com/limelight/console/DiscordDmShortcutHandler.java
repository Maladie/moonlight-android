package com.limelight.console;

import android.view.KeyEvent;

import com.limelight.R;

import java.util.function.BooleanSupplier;

/** Console-only hold tracker; Game reuses ControllerHandler's existing tracker. */
final class DiscordDmShortcutHandler {
    interface Scheduler {
        void postDelayed(Runnable task, long delayMs);
        void remove(Runnable task);
    }

    private static final int FIRST = 1;
    private static final int SECOND = 2;

    private final String trigger;
    private final long holdMs;
    private final Scheduler scheduler;
    private final BooleanSupplier targetAvailable;
    private final Runnable action;
    private final Runnable timeout = this::fire;
    private int pressed;
    private boolean sequenceActive;
    private boolean scheduled;
    private boolean fired;

    DiscordDmShortcutHandler(String trigger, long holdMs, Scheduler scheduler,
                             BooleanSupplier targetAvailable, Runnable action) {
        this.trigger = trigger == null ? "select" : trigger;
        this.holdMs = Math.max(0L, holdMs);
        this.scheduler = scheduler;
        this.targetAvailable = targetAvailable;
        this.action = action;
    }

    boolean handle(KeyEvent event) {
        return event != null && handle(event.getAction(), event.getKeyCode(), event.getRepeatCount());
    }

    boolean handle(int eventAction, int keyCode, int repeatCount) {
        int bit = triggerBit(trigger, keyCode);
        if (bit == 0) return false;
        if (eventAction == KeyEvent.ACTION_DOWN) {
            if (!sequenceActive) {
                if (!targetAvailable.getAsBoolean()) return false;
                sequenceActive = true;
            }
            pressed |= bit;
            if (repeatCount == 0 && !scheduled && !fired
                    && (pressed & requiredBits(trigger)) == requiredBits(trigger)) {
                scheduled = true;
                scheduler.postDelayed(timeout, holdMs);
            }
            return true;
        }
        if (eventAction == KeyEvent.ACTION_UP && sequenceActive) {
            pressed &= ~bit;
            if (!fired && (pressed & requiredBits(trigger)) != requiredBits(trigger)) {
                cancelTimer();
            }
            if (pressed == 0) reset();
            return true;
        }
        return sequenceActive;
    }

    void cancel() {
        cancelTimer();
        reset();
    }

    private void fire() {
        scheduled = false;
        if (!sequenceActive || (pressed & requiredBits(trigger)) != requiredBits(trigger)) return;
        fired = true;
        action.run();
    }

    private void cancelTimer() {
        if (scheduled) scheduler.remove(timeout);
        scheduled = false;
    }

    private void reset() {
        cancelTimer();
        pressed = 0;
        sequenceActive = false;
        fired = false;
    }

    static boolean canDirectOpen(boolean directMessagesAvailable, boolean knownPeer,
                                 boolean leaseAcquired) {
        return directMessagesAvailable && knownPeer && leaseAcquired;
    }

    static int triggerLabelResource(String trigger) {
        if ("start".equals(trigger)) return R.string.overlay_trigger_start;
        if ("guide".equals(trigger)) return R.string.overlay_trigger_guide;
        if ("lb_rb".equals(trigger)) return R.string.overlay_trigger_lb_rb;
        return R.string.overlay_trigger_select;
    }

    private static int requiredBits(String trigger) {
        return "lb_rb".equals(trigger) ? FIRST | SECOND : FIRST;
    }

    private static int triggerBit(String trigger, int keyCode) {
        if ("start".equals(trigger)) {
            return keyCode == KeyEvent.KEYCODE_BUTTON_START || keyCode == KeyEvent.KEYCODE_MENU
                    ? FIRST : 0;
        }
        if ("guide".equals(trigger)) {
            return keyCode == KeyEvent.KEYCODE_BUTTON_MODE ? FIRST : 0;
        }
        if ("lb_rb".equals(trigger)) {
            if (keyCode == KeyEvent.KEYCODE_BUTTON_L1) return FIRST;
            if (keyCode == KeyEvent.KEYCODE_BUTTON_R1) return SECOND;
            return 0;
        }
        return keyCode == KeyEvent.KEYCODE_BUTTON_SELECT ? FIRST : 0;
    }
}
