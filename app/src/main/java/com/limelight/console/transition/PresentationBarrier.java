package com.limelight.console.transition;

import com.limelight.diagnostics.MoonWakerDiagnostics;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/**
 * Orders an effect after a cover's frame. Each wait settles exactly once: SUBMITTED when its
 * frame was handed to the compositor, CANCELLED when it was cancelled or a wait of another fence
 * replaced it, UNAVAILABLE when its surface is gone or changed or its deadline passed. SUBMITTED
 * orders effects only; it never proves the cover is visible and never reveals anything.
 */
public final class PresentationBarrier {
    public enum Outcome { SUBMITTED, CANCELLED, UNAVAILABLE }

    /** Schedules {@code task} after {@code delayMs}; the returned runnable unschedules it. */
    public interface Scheduler {
        Runnable schedule(Runnable task, long delayMs);
    }

    /** A cover wait before a transition or operation effect. */
    public static final long COVER_DEADLINE_MS = 500L;
    /** The Home's first frame before a hidden warm-up starts its transport. */
    public static final long HOME_DEADLINE_MS = 2000L;

    public final class Ticket {
        final String fence;
        final long surfaceGen;
        final long startedAtMs;
        final Consumer<Outcome> settled;
        Runnable unscheduleDeadline;
        Outcome outcome;

        Ticket(String fence, long surfaceGen, long startedAtMs, Consumer<Outcome> settled) {
            this.fence = fence;
            this.surfaceGen = surfaceGen;
            this.startedAtMs = startedAtMs;
            this.settled = settled;
        }

        /** Null while the wait is pending. */
        public Outcome outcome() {
            synchronized (PresentationBarrier.this) {
                return outcome;
            }
        }
    }

    private final LongSupplier clockMs;
    private final Scheduler scheduler;
    private final List<Ticket> pending = new ArrayList<>();

    public PresentationBarrier(LongSupplier clockMs, Scheduler scheduler) {
        this.clockMs = Objects.requireNonNull(clockMs, "clockMs");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
    }

    /**
     * Waits for {@code fence}'s frame on surface generation {@code surfaceGen} (0: no surface).
     * Pending waits of other fences are replaced (CANCELLED).
     */
    public Ticket await(String fence, long surfaceGen, long deadlineMs,
                        Consumer<Outcome> settled) {
        Objects.requireNonNull(fence, "fence");
        Objects.requireNonNull(settled, "settled");
        Ticket ticket = new Ticket(fence, surfaceGen, clockMs.getAsLong(), settled);
        List<Ticket> replaced = new ArrayList<>();
        synchronized (this) {
            for (Iterator<Ticket> it = pending.iterator(); it.hasNext(); ) {
                Ticket other = it.next();
                if (other.fence.equals(fence)) continue;
                it.remove();
                close(other, Outcome.CANCELLED);
                replaced.add(other);
            }
            if (surfaceGen > 0L) {
                pending.add(ticket);
                ticket.unscheduleDeadline = scheduler.schedule(
                        () -> settle(ticket, Outcome.UNAVAILABLE), Math.max(0L, deadlineMs));
            } else {
                close(ticket, Outcome.UNAVAILABLE);
                replaced.add(ticket);
            }
        }
        deliver(replaced);
        return ticket;
    }

    /** The user cancelled {@code fence}: its pending waits settle CANCELLED. */
    public void cancel(String fence) {
        settleWhere(ticket -> ticket.fence.equals(fence) ? Outcome.CANCELLED : null);
    }

    /**
     * A frame of {@code surfaceGen} awaited for {@code fence} was handed to the compositor. A
     * signal of another generation is a stale wait's: {@link #surfaceChanged} settled that one.
     */
    public void onSignal(String fence, long surfaceGen) {
        settleWhere(ticket -> ticket.fence.equals(fence) && ticket.surfaceGen == surfaceGen
                ? Outcome.SUBMITTED : null);
    }

    /** The surface is now {@code surfaceGen} (0: none): waits on another one cannot complete. */
    public void surfaceChanged(long surfaceGen) {
        settleWhere(ticket -> ticket.surfaceGen != surfaceGen ? Outcome.UNAVAILABLE : null);
    }

    private interface Rule {
        Outcome outcomeFor(Ticket ticket);
    }

    private void settleWhere(Rule rule) {
        List<Ticket> due = new ArrayList<>();
        synchronized (this) {
            for (Iterator<Ticket> it = pending.iterator(); it.hasNext(); ) {
                Ticket ticket = it.next();
                Outcome outcome = rule.outcomeFor(ticket);
                if (outcome == null) continue;
                it.remove();
                close(ticket, outcome);
                due.add(ticket);
            }
        }
        deliver(due);
    }

    private void settle(Ticket ticket, Outcome outcome) {
        synchronized (this) {
            if (!pending.remove(ticket)) return;
            close(ticket, outcome);
        }
        deliver(Collections.singletonList(ticket));
    }

    private void close(Ticket ticket, Outcome outcome) {
        ticket.outcome = outcome;
        if (ticket.unscheduleDeadline != null) ticket.unscheduleDeadline.run();
    }

    /**
     * Outside the lock: an outcome's effect may wait again. Each ticket is delivered on its
     * own: an effect that throws is logged and never skips the others of its batch.
     */
    private void deliver(List<Ticket> settled) {
        long now = clockMs.getAsLong();
        for (Ticket ticket : settled) {
            MoonWakerDiagnostics.record(ticket.outcome == Outcome.UNAVAILABLE ? "WARN" : "INFO",
                    "android.presentation-barrier", "barrier.settled",
                    "fence", ticket.fence,
                    "surface_generation", ticket.surfaceGen,
                    "outcome", ticket.outcome.name(),
                    "duration_ms", now - ticket.startedAtMs);
            try {
                ticket.settled.accept(ticket.outcome);
            } catch (RuntimeException error) {
                MoonWakerDiagnostics.record("ERROR", "android.presentation-barrier",
                        "barrier.effect_failed",
                        "fence", ticket.fence,
                        "outcome", ticket.outcome.name(),
                        "error", error.toString());
            }
        }
    }
}
