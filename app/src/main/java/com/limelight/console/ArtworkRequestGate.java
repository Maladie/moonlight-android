package com.limelight.console;

/** Monotonic cancellation gate for asynchronous artwork work. */
final class ArtworkRequestGate {
    private int generation;

    synchronized int next() {
        return ++generation;
    }

    synchronized void invalidate() {
        generation++;
    }

    synchronized boolean isCurrent(int requestGeneration) {
        return generation == requestGeneration;
    }
}
