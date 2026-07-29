package com.limelight.console;

/** Drops only near-simultaneous duplicate navigation presses from mirrored input devices. */
final class NavigationInputDeduplicator {
    private final long duplicateWindowMillis;
    private int lastKeyCode = Integer.MIN_VALUE;
    private long lastEventTime = Long.MIN_VALUE;

    NavigationInputDeduplicator(long duplicateWindowMillis) {
        if (duplicateWindowMillis < 0L) throw new IllegalArgumentException("Negative window");
        this.duplicateWindowMillis = duplicateWindowMillis;
    }

    boolean shouldSuppress(int keyCode, long eventTime) {
        long elapsed = eventTime - lastEventTime;
        boolean duplicate = keyCode == lastKeyCode
                && elapsed >= 0L && elapsed <= duplicateWindowMillis;
        if (!duplicate) {
            lastKeyCode = keyCode;
            lastEventTime = eventTime;
        }
        return duplicate;
    }
}
