package com.limelight.console;

import android.content.Context;

import java.util.function.LongSupplier;

/**
 * The one Play queued behind a close on its host: an app-scoped fact (main thread) that
 * outlives the screen that queued it, so a stream Home replaced by the console, or a recreated
 * console, still shows and starts it. {@link SessionOrchestrator} alone decides when it is
 * queued, started or dropped; screens only render it. The user leaving MoonWaker clears it.
 */
public final class PlayQueue {
    /** The Application holds the process's queue. */
    public interface Owner { PlayQueue playQueue(); }

    private final LongSupplier clock;
    private PlayIntent intent;
    /** When this intent was first queued; re-queues of the same intent keep it. */
    private long since;
    /** Bumped on every change: fences a timeout armed for an older entry. */
    private long generation;

    /** {@code clock}: monotonic milliseconds (the main thread's uptime in the app). */
    public PlayQueue(LongSupplier clock) {
        this.clock = clock;
    }

    static PlayQueue of(Context context) {
        return ((Owner) context.getApplicationContext()).playQueue();
    }

    /** MoonWaker went to the background (no Activity of it started): nothing waits for it. */
    public void appLeft() {
        clear();
    }

    PlayIntent intent() { return intent; }
    long since() { return since; }
    long generation() { return generation; }
    long now() { return clock.getAsLong(); }

    /** Queues {@code next}, first queued at {@code firstQueuedAt}; returns its generation. */
    long put(PlayIntent next, long firstQueuedAt) {
        intent = next;
        since = firstQueuedAt;
        return ++generation;
    }

    /** Empties the queue; returns what it held, or null. */
    PlayIntent clear() {
        PlayIntent held = intent;
        if (held == null) return null;
        intent = null;
        since = 0L;
        generation++;
        return held;
    }
}
