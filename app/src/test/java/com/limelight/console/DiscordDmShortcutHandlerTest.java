package com.limelight.console;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.view.KeyEvent;

import com.limelight.R;

import org.junit.Test;

public class DiscordDmShortcutHandlerTest {
    @Test public void fullDownRepeatAndUpSequenceIsConsumedAfterHold() {
        FakeScheduler scheduler = new FakeScheduler();
        int[] calls = {0};
        DiscordDmShortcutHandler handler = new DiscordDmShortcutHandler(
                "select", 1_500, scheduler, () -> true, () -> calls[0]++);

        assertTrue(handler.handle(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BUTTON_SELECT, 0));
        assertTrue(handler.handle(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BUTTON_SELECT, 1));
        scheduler.fire();
        assertEquals(1, calls[0]);
        assertTrue(handler.handle(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BUTTON_SELECT, 0));
        assertFalse(handler.handle(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BUTTON_SELECT, 0));
    }

    @Test public void earlyReleaseAndLifecycleCancelNeverFire() {
        FakeScheduler scheduler = new FakeScheduler();
        int[] calls = {0};
        DiscordDmShortcutHandler handler = new DiscordDmShortcutHandler(
                "start", 1_000, scheduler, () -> true, () -> calls[0]++);

        assertTrue(handler.handle(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BUTTON_START, 0));
        assertTrue(handler.handle(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BUTTON_START, 0));
        scheduler.fire();
        assertEquals(0, calls[0]);

        assertTrue(handler.handle(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MENU, 0));
        handler.cancel();
        scheduler.fire();
        assertEquals(0, calls[0]);
    }

    @Test public void lbRbRequiresBothButtonsAndConsumesBothReleases() {
        FakeScheduler scheduler = new FakeScheduler();
        int[] calls = {0};
        DiscordDmShortcutHandler handler = new DiscordDmShortcutHandler(
                "lb_rb", 2_000, scheduler, () -> true, () -> calls[0]++);

        assertTrue(handler.handle(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BUTTON_L1, 0));
        scheduler.fire();
        assertEquals(0, calls[0]);
        assertTrue(handler.handle(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BUTTON_R1, 0));
        scheduler.fire();
        assertEquals(1, calls[0]);
        assertTrue(handler.handle(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BUTTON_L1, 0));
        assertTrue(handler.handle(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BUTTON_R1, 0));
    }

    @Test public void sequenceDoesNotInterceptWithoutAnActionableTarget() {
        FakeScheduler scheduler = new FakeScheduler();
        DiscordDmShortcutHandler handler = new DiscordDmShortcutHandler(
                "guide", 500, scheduler, () -> false, () -> { });

        assertFalse(handler.handle(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BUTTON_MODE, 0));
        assertFalse(handler.handle(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BUTTON_MODE, 0));
    }

    @Test public void directOpenPolicyFallsBackForAuthPeerOrLeaseConflict() {
        assertTrue(DiscordDmShortcutHandler.canDirectOpen(true, true, true));
        assertFalse(DiscordDmShortcutHandler.canDirectOpen(false, true, true));
        assertFalse(DiscordDmShortcutHandler.canDirectOpen(true, false, true));
        assertFalse(DiscordDmShortcutHandler.canDirectOpen(true, true, false));
    }

    @Test public void unsupportedTriggerGlyphsUseLocalizedTextLabels() {
        assertEquals(R.string.overlay_trigger_select,
                DiscordDmShortcutHandler.triggerLabelResource("select"));
        assertEquals(R.string.overlay_trigger_start,
                DiscordDmShortcutHandler.triggerLabelResource("start"));
        assertEquals(R.string.overlay_trigger_guide,
                DiscordDmShortcutHandler.triggerLabelResource("guide"));
        assertEquals(R.string.overlay_trigger_lb_rb,
                DiscordDmShortcutHandler.triggerLabelResource("lb_rb"));
    }

    private static final class FakeScheduler implements DiscordDmShortcutHandler.Scheduler {
        private Runnable task;

        @Override public void postDelayed(Runnable task, long delayMs) {
            this.task = task;
        }

        @Override public void remove(Runnable task) {
            if (this.task == task) this.task = null;
        }

        void fire() {
            Runnable current = task;
            task = null;
            if (current != null) current.run();
        }
    }
}
