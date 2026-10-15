package com.limelight.ui;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.View;

import com.limelight.console.transition.PresentationBarrier;

import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * The frame signal of one view's window for a {@link PresentationBarrier}: a wait settles when
 * the window commits its next frame (ViewTreeObserver frame commit, API 29+). Its surface
 * generation changes when the view shows a new presentation, detaches or stops; a stopped view
 * has no surface. Its waits and cancels keep their order from any thread.
 */
public final class ViewFrameSource implements View.OnAttachStateChangeListener {
    /** Process-wide, so a new Activity's view never reuses another view's generation. */
    private static final AtomicLong GENERATIONS = new AtomicLong();

    private final View view;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final PresentationBarrier barrier;
    private long generation = GENERATIONS.incrementAndGet();
    private boolean stopped;

    public ViewFrameSource(View view) {
        this.view = view;
        barrier = new PresentationBarrier(SystemClock::uptimeMillis, (task, delayMs) -> {
            handler.postDelayed(task, delayMs);
            return () -> handler.removeCallbacks(task);
        });
        view.addOnAttachStateChangeListener(this);
    }

    /** Callable from any thread: the wait starts on the main thread, after work posted before. */
    public void await(String fence, long deadlineMs,
                      Consumer<PresentationBarrier.Outcome> settled) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            handler.post(() -> await(fence, deadlineMs, settled));
            return;
        }
        long awaited = stopped ? 0L : generation;
        PresentationBarrier.Ticket ticket = barrier.await(fence, awaited, deadlineMs, settled);
        if (ticket.outcome() == null) {
            // An unattached view's observer merges into its window's once it attaches.
            view.getViewTreeObserver().registerFrameCommitCallback(
                    () -> barrier.onSignal(fence, awaited));
            view.invalidate();
        }
    }

    public void cancel(String fence) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            handler.post(() -> cancel(fence));
            return;
        }
        barrier.cancel(fence);
    }

    /** The view shows a new presentation: waits for the earlier one are unavailable. */
    public void newPresentation() {
        generation = GENERATIONS.incrementAndGet();
        barrier.surfaceChanged(stopped ? 0L : generation);
    }

    /** The view is stopped for good: pending and later waits are unavailable. */
    public void stop() {
        stopped = true;
        barrier.surfaceChanged(0L);
    }

    @Override public void onViewAttachedToWindow(View v) { }

    @Override public void onViewDetachedFromWindow(View v) {
        newPresentation();
    }
}
