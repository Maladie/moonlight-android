package com.limelight.nvstream;

import java.util.concurrent.Semaphore;

/**
 * One connection's claim on the process-wide native connection permit. A stop can arrive while
 * the app launches; the native start then never begins, and only the actual holder releases the
 * permit, so it is never left taken nor released twice. {@link #tryBegin}, {@link #startFailed}
 * and {@link #stopped} run under the MoonBridge lock, with the native start or stop they gate.
 */
final class NativeStartGate {
    private final Semaphore permits;
    private boolean stopped;
    private boolean permitHeld;

    NativeStartGate(Semaphore permits) {
        this.permits = permits;
    }

    /** Waits for the permit; never under the MoonBridge lock, which the holder's stop needs. */
    void acquire() throws InterruptedException {
        permits.acquire();
    }

    /** After {@link #acquire}: false, with the permit returned, once this connection stopped. */
    synchronized boolean tryBegin() {
        if (stopped) {
            permits.release();
            return false;
        }
        permitHeld = true;
        return true;
    }

    /** The native start failed: nobody will stop it, so the permit returns now. */
    synchronized void startFailed() {
        release();
    }

    /** The native connection stopped (or never started): no later start may begin. */
    synchronized void stopped() {
        stopped = true;
        release();
    }

    private void release() {
        if (!permitHeld) return;
        permitHeld = false;
        permits.release();
    }
}
