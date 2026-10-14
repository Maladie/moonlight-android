package com.limelight.stream;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;

/**
 * Owns the one real stop of one transport generation and orders its start after the
 * predecessor's stop. The generation itself and the predecessor are assigned by
 * {@link StreamRuntime}, the single owner of transport generations.
 */
public final class TransportStopCoordinator {
    public enum Result { STOPPED, FAILED, CANCELLED }

    public interface Completion {
        void complete(Result result);
    }

    private enum State { NEW, STARTING, ACTIVE, STOPPING, STOPPED }

    private TransportStopCoordinator predecessor;
    private final Runnable stopEffect;
    private final Executor executor;
    private final List<Completion> completions = new ArrayList<>();
    private State state = State.NEW;
    private Result terminalResult;

    /** {@code predecessor}: the previous generation's stop this start waits for; may be null. */
    TransportStopCoordinator(TransportStopCoordinator predecessor, Runnable stopEffect,
                             Executor executor) {
        if (stopEffect == null || executor == null) throw new NullPointerException();
        this.predecessor = predecessor;
        this.stopEffect = stopEffect;
        this.executor = executor;
    }

    public synchronized boolean hasStopStarted() {
        return state == State.STOPPING || state == State.STOPPED;
    }

    public synchronized boolean hasWorkStarted() {
        return state != State.NEW;
    }

    public static void completeSafely(Completion completion, Result result) {
        if (completion == null) return;
        try {
            completion.complete(result);
        } catch (RuntimeException ignored) {
            // Completion observers cannot change the transport's terminal result.
        }
    }

    public void start(Executor dispatcher, Runnable startEffect) {
        if (dispatcher == null || startEffect == null) throw new NullPointerException();
        synchronized (this) {
            if (state != State.NEW) return;
            state = State.STARTING;
        }
        Completion afterPrevious = result -> {
            if (result != Result.STOPPED) {
                complete(Result.FAILED);
                return;
            }
            try {
                dispatcher.execute(() -> runStart(startEffect));
            } catch (RuntimeException error) {
                complete(Result.FAILED);
            }
        };
        awaitPredecessor(afterPrevious);
    }

    private void awaitPredecessor(Completion completion) {
        TransportStopCoordinator previous;
        synchronized (this) {
            previous = predecessor;
            predecessor = null;
        }
        if (previous == null) completion.complete(Result.STOPPED);
        else previous.awaitStop(completion);
    }

    private void awaitStop(Completion completion) {
        Result completed;
        boolean waitForPredecessor = false;
        synchronized (this) {
            completed = terminalResult;
            if (completed == null) {
                if (completion != null) completions.add(completion);
                if (state == State.NEW) {
                    state = State.STOPPING;
                    waitForPredecessor = true;
                }
                else return;
            }
        }
        if (completed != null) completeSafely(completion, completed);
        else if (waitForPredecessor) {
            awaitPredecessor(result -> complete(result == Result.STOPPED
                    ? Result.STOPPED : Result.FAILED));
        }
    }

    /** Observes this generation's terminal result without requesting a stop. */
    public void whenStopped(Completion completion) {
        Result completed;
        synchronized (this) {
            completed = terminalResult;
            if (completed == null) {
                completions.add(completion);
                return;
            }
        }
        completeSafely(completion, completed);
    }

    private void runStart(Runnable startEffect) {
        boolean cancelled;
        synchronized (this) {
            cancelled = state == State.STOPPING;
            if (!cancelled) {
                if (state != State.STARTING) return;
                state = State.ACTIVE;
                try {
                    startEffect.run();
                    return;
                } catch (RuntimeException error) {
                    // Complete below, outside the state transition.
                }
            }
        }
        complete(cancelled ? Result.STOPPED : Result.FAILED);
    }

    public void stop(Completion completion) {
        stop(null, completion);
    }

    public void stop(Runnable beforeStop, Completion completion) {
        Result completed;
        boolean runStop = false;
        boolean waitForPredecessor = false;
        synchronized (this) {
            completed = terminalResult;
            if (completed == null) {
                if (completion != null) completions.add(completion);
                if (state == State.STOPPING) return;
                if (state == State.NEW) {
                    state = State.STOPPING;
                    waitForPredecessor = true;
                } else {
                    runStop = state == State.ACTIVE;
                    state = State.STOPPING;
                    if (!runStop) return;
                }
            }
        }
        if (completed != null) {
            completeSafely(completion, completed);
            return;
        }
        if (waitForPredecessor) {
            awaitPredecessor(result -> complete(result == Result.STOPPED
                    ? Result.STOPPED : Result.FAILED));
            return;
        }
        boolean failed = false;
        try {
            if (beforeStop != null) beforeStop.run();
        } catch (RuntimeException error) {
            failed = true;
        }
        boolean teardownFailed = failed;
        try {
            executor.execute(() -> runStop(teardownFailed));
        } catch (RuntimeException error) {
            complete(Result.FAILED);
        }
    }

    private void runStop(boolean teardownFailed) {
        Result result = teardownFailed ? Result.FAILED : Result.STOPPED;
        try {
            stopEffect.run();
        } catch (RuntimeException error) {
            result = Result.FAILED;
        }
        complete(result);
    }

    private void complete(Result result) {
        List<Completion> waiting;
        synchronized (this) {
            if (terminalResult != null) return;
            terminalResult = result;
            state = State.STOPPED;
            predecessor = null;
            waiting = new ArrayList<>(completions);
            completions.clear();
        }
        for (Completion completion : waiting) {
            completeSafely(completion, result);
        }
    }
}
