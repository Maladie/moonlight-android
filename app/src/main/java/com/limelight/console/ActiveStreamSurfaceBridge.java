package com.limelight.console;

import android.view.SurfaceHolder;

import java.lang.ref.WeakReference;
import java.util.Objects;

/**
 * Process-local handoff between the compatibility Game Activity and the persistent
 * stream surface owned by ConsoleActivity. It does not own session lifetime.
 */
public final class ActiveStreamSurfaceBridge {
    private static final Coordinator PROCESS = new Coordinator();

    private ActiveStreamSurfaceBridge() { }

    public static void attachSession(StreamRenderTargetController session) {
        PROCESS.attachSession(session);
    }

    public static void detachSession(StreamRenderTargetController session) {
        PROCESS.detachSession(session);
    }

    public static boolean hasSession() {
        return PROCESS.hasSession();
    }

    public static void setConsoleForeground(boolean foreground) {
        PROCESS.setConsoleForeground(foreground);
    }

    public static void registerConsoleSurface(SurfaceHolder surface) {
        PROCESS.registerConsoleSurface(surface);
    }

    /** Returns true when the destroyed Console surface was not active or output moved to background. */
    public static boolean releaseConsoleSurface(SurfaceHolder surface) {
        return PROCESS.releaseConsoleSurface(surface);
    }

    public static boolean prepareConsoleHandoff(StreamRenderTargetController session) {
        return PROCESS.prepareConsoleHandoff(session);
    }

    public static boolean bindConsoleIfForeground(StreamRenderTargetController session) {
        return PROCESS.bindConsoleIfForeground(session);
    }

    public static void onGameRenderTargetBound(StreamRenderTargetController session) {
        PROCESS.onGameRenderTargetBound(session);
    }

    public static boolean isConsoleRenderTargetBound(StreamRenderTargetController session) {
        return PROCESS.isConsoleRenderTargetBound(session);
    }

    static final class Coordinator {
        private WeakReference<StreamRenderTargetController> session = new WeakReference<>(null);
        private WeakReference<SurfaceHolder> consoleSurface = new WeakReference<>(null);
        private boolean consoleForeground;
        private boolean consoleRenderTargetBound;

        synchronized void attachSession(StreamRenderTargetController newSession) {
            Objects.requireNonNull(newSession, "newSession");
            StreamRenderTargetController existing = session.get();
            if (existing != null && existing != newSession) {
                throw new IllegalStateException("A different stream session is already attached");
            }
            session = new WeakReference<>(newSession);
            consoleRenderTargetBound = false;
            bindConsoleIfReady(false);
        }

        synchronized void detachSession(StreamRenderTargetController expectedSession) {
            if (session.get() != expectedSession) return;
            session.clear();
            consoleRenderTargetBound = false;
        }

        synchronized boolean hasSession() {
            return session.get() != null;
        }

        synchronized void setConsoleForeground(boolean foreground) {
            consoleForeground = foreground;
            if (foreground) bindConsoleIfReady(false);
        }

        synchronized void registerConsoleSurface(SurfaceHolder surface) {
            consoleSurface = new WeakReference<>(Objects.requireNonNull(surface, "surface"));
            bindConsoleIfReady(false);
        }

        synchronized boolean releaseConsoleSurface(SurfaceHolder releasedSurface) {
            SurfaceHolder current = consoleSurface.get();
            if (current != releasedSurface) return true;
            consoleSurface.clear();
            if (!consoleRenderTargetBound) return true;

            StreamRenderTargetController currentSession = session.get();
            consoleRenderTargetBound = false;
            return currentSession == null || currentSession.switchToBackgroundSurface();
        }

        synchronized boolean prepareConsoleHandoff(StreamRenderTargetController expectedSession) {
            if (session.get() != expectedSession) return false;
            return bindConsoleIfReady(true);
        }

        synchronized boolean bindConsoleIfForeground(StreamRenderTargetController expectedSession) {
            if (session.get() != expectedSession || !consoleForeground) return false;
            return bindConsoleIfReady(false);
        }

        synchronized void onGameRenderTargetBound(StreamRenderTargetController expectedSession) {
            if (session.get() == expectedSession) consoleRenderTargetBound = false;
        }

        synchronized boolean isConsoleRenderTargetBound(StreamRenderTargetController expectedSession) {
            return session.get() == expectedSession && consoleRenderTargetBound;
        }

        private boolean bindConsoleIfReady(boolean ignoreForeground) {
            StreamRenderTargetController currentSession = session.get();
            SurfaceHolder currentSurface = consoleSurface.get();
            if (currentSession == null || currentSurface == null ||
                    (!ignoreForeground && !consoleForeground)) {
                return false;
            }
            if (consoleRenderTargetBound) return true;
            consoleRenderTargetBound = currentSession.switchToRenderTarget(currentSurface);
            return consoleRenderTargetBound;
        }
    }
}
