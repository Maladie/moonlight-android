package com.limelight.console;

/**
 * Testable policy for the lifetime of the decoder's window surface.
 * Android Surface/renderer operations remain in the adapter owned by Game.
 */
public final class StreamSurfaceHost {
    public enum LossAction { IGNORE, KEEP_SESSION_ON_BACKGROUND_SURFACE, STOP_SESSION }

    private boolean windowSurfaceAttached;

    public synchronized void onWindowSurfaceCreated() {
        windowSurfaceAttached = true;
    }

    public synchronized void requireWindowSurfaceForChange() {
        if (!windowSurfaceAttached) {
            throw new IllegalStateException("Surface changed before creation!");
        }
    }

    public synchronized LossAction onWindowSurfaceDestroyed(
            boolean connectionAttempted, boolean backgroundSurfaceBound) {
        if (!windowSurfaceAttached) {
            throw new IllegalStateException("Surface destroyed before creation!");
        }
        windowSurfaceAttached = false;
        if (!connectionAttempted) return LossAction.IGNORE;
        return backgroundSurfaceBound
                ? LossAction.KEEP_SESSION_ON_BACKGROUND_SURFACE
                : LossAction.STOP_SESSION;
    }

    public synchronized boolean isWindowSurfaceAttached() {
        return windowSurfaceAttached;
    }
}
