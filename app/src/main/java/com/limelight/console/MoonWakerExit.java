package com.limelight.console;

import android.app.ActivityManager;
import android.content.Context;

import java.util.concurrent.atomic.AtomicLong;

/**
 * The one pending "close the stream and leave MoonWaker". Process-scoped, so the close's
 * completion and any console instance agree on it; any MoonWaker screen the user starts
 * withdraws it, so a close that completes later never ends a newer visit.
 */
public final class MoonWakerExit {
    private static final AtomicLong issued = new AtomicLong();
    private static final AtomicLong pending = new AtomicLong();

    private MoonWakerExit() {}

    /** Replaces any earlier request; its token is what {@link #take} must name. */
    static long request() {
        long token = issued.incrementAndGet();
        pending.set(token);
        return token;
    }

    /** The user is back in MoonWaker. */
    public static void withdraw() {
        pending.set(0L);
    }

    /** True once, and only while {@code token} is still the pending request. */
    static boolean take(long token) {
        return token != 0L && pending.compareAndSet(token, 0L);
    }

    /** Ends MoonWaker's tasks; needs no Activity, so it works after the console is gone. */
    static void finishTasks(Context context) {
        ActivityManager manager = context.getSystemService(ActivityManager.class);
        if (manager == null) return;
        for (ActivityManager.AppTask task : manager.getAppTasks()) task.finishAndRemoveTask();
    }
}
