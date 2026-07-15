package com.limelight.console;

import android.view.SurfaceHolder;

import com.limelight.LimeLog;

import java.lang.ref.WeakReference;
import java.util.Objects;

/**
 * Process-local handoff between the compatibility Game Activity and the persistent
 * stream surface owned by ConsoleActivity. It does not own session lifetime.
 */
public final class ActiveStreamSurfaceBridge {
    public enum Target { NONE, GAME, CONSOLE, BACKGROUND }

    /** Immutable, host-data-free state suitable for tests and log evidence. */
    public static final class Snapshot {
        public final long generation;
        public final boolean sessionAttached;
        public final Target target;
        public final boolean consoleSurfaceRegistered;
        public final boolean consoleForeground;
        public final int successfulTargetChanges;
        public final int failedTargetChanges;

        Snapshot(long generation, boolean sessionAttached, Target target,
                 boolean consoleSurfaceRegistered, boolean consoleForeground,
                 int successfulTargetChanges, int failedTargetChanges) {
            this.generation = generation;
            this.sessionAttached = sessionAttached;
            this.target = target;
            this.consoleSurfaceRegistered = consoleSurfaceRegistered;
            this.consoleForeground = consoleForeground;
            this.successfulTargetChanges = successfulTargetChanges;
            this.failedTargetChanges = failedTargetChanges;
        }

        public String diagnosticLine() {
            return "generation=" + generation +
                    " attached=" + sessionAttached +
                    " target=" + target +
                    " consoleSurface=" + consoleSurfaceRegistered +
                    " consoleForeground=" + consoleForeground +
                    " targetChanges=" + successfulTargetChanges +
                    " targetFailures=" + failedTargetChanges;
        }
    }

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

    public static boolean switchToBackgroundSurface(StreamRenderTargetController session) {
        return PROCESS.switchToBackgroundSurface(session);
    }

    public static Snapshot snapshot() {
        return PROCESS.snapshot();
    }

    static final class Coordinator {
        private WeakReference<StreamRenderTargetController> session = new WeakReference<>(null);
        private WeakReference<SurfaceHolder> consoleSurface = new WeakReference<>(null);
        private boolean consoleForeground;
        private boolean consoleRenderTargetBound;
        private long generation;
        private Target target = Target.NONE;
        private int successfulTargetChanges;
        private int failedTargetChanges;

        synchronized void attachSession(StreamRenderTargetController newSession) {
            Objects.requireNonNull(newSession, "newSession");
            StreamRenderTargetController existing = session.get();
            if (existing != null && existing != newSession) {
                throw new IllegalStateException("A different stream session is already attached");
            }
            if (existing == newSession) return;
            session = new WeakReference<>(newSession);
            consoleRenderTargetBound = false;
            generation++;
            target = Target.NONE;
            successfulTargetChanges = 0;
            failedTargetChanges = 0;
            log("session_attached");
            bindConsoleIfReady(false);
        }

        synchronized void detachSession(StreamRenderTargetController expectedSession) {
            if (session.get() != expectedSession) return;
            session.clear();
            consoleRenderTargetBound = false;
            target = Target.NONE;
            log("session_detached");
        }

        synchronized boolean hasSession() {
            return session.get() != null;
        }

        synchronized void setConsoleForeground(boolean foreground) {
            if (consoleForeground == foreground) return;
            consoleForeground = foreground;
            log(foreground ? "console_foreground" : "console_background");
            if (foreground) bindConsoleIfReady(false);
        }

        synchronized void registerConsoleSurface(SurfaceHolder surface) {
            SurfaceHolder newSurface = Objects.requireNonNull(surface, "surface");
            boolean changed = consoleSurface.get() != newSurface;
            consoleSurface = new WeakReference<>(newSurface);
            if (changed) log("console_surface_registered");
            bindConsoleIfReady(false);
        }

        synchronized boolean releaseConsoleSurface(SurfaceHolder releasedSurface) {
            SurfaceHolder current = consoleSurface.get();
            if (current != releasedSurface) return true;
            consoleSurface.clear();
            if (!consoleRenderTargetBound) {
                log("console_surface_released_inactive");
                return true;
            }

            StreamRenderTargetController currentSession = session.get();
            if (currentSession == null) {
                consoleRenderTargetBound = false;
                target = Target.NONE;
                log("console_surface_released_without_session");
                return true;
            }
            return switchToBackgroundSurface(currentSession);
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
            if (session.get() == expectedSession) {
                consoleRenderTargetBound = false;
                target = Target.GAME;
                successfulTargetChanges++;
                log("target_game");
            }
        }

        synchronized boolean isConsoleRenderTargetBound(StreamRenderTargetController expectedSession) {
            return session.get() == expectedSession && consoleRenderTargetBound;
        }

        synchronized boolean switchToBackgroundSurface(StreamRenderTargetController expectedSession) {
            if (session.get() != expectedSession) return false;
            boolean switched = expectedSession.switchToBackgroundSurface();
            consoleRenderTargetBound = false;
            if (switched) {
                target = Target.BACKGROUND;
                successfulTargetChanges++;
                log("target_background");
            }
            else {
                target = Target.NONE;
                failedTargetChanges++;
                log("target_background_failed");
            }
            return switched;
        }

        synchronized Snapshot snapshot() {
            return new Snapshot(generation, session.get() != null, target,
                    consoleSurface.get() != null, consoleForeground,
                    successfulTargetChanges, failedTargetChanges);
        }

        private boolean bindConsoleIfReady(boolean ignoreForeground) {
            StreamRenderTargetController currentSession = session.get();
            SurfaceHolder currentSurface = consoleSurface.get();
            if (currentSession == null || currentSurface == null ||
                    (!ignoreForeground && !consoleForeground)) {
                return false;
            }
            if (consoleRenderTargetBound) return true;
            if (!currentSession.isRenderTargetSwitchReady()) {
                // MediaCodec needs its initial Surface before setup() can create
                // the decoder. This is staging, not a runtime setOutputSurface()
                // switch, and matches Game.surfaceChanged().
                currentSession.setInitialRenderTarget(currentSurface);
                consoleRenderTargetBound = true;
                target = Target.CONSOLE;
                successfulTargetChanges++;
                log("target_console_staged");
                return true;
            }
            consoleRenderTargetBound = currentSession.switchToRenderTarget(currentSurface);
            if (consoleRenderTargetBound) {
                target = Target.CONSOLE;
                successfulTargetChanges++;
                log("target_console");
            }
            else {
                failedTargetChanges++;
                log("target_console_failed");
            }
            return consoleRenderTargetBound;
        }

        private void log(String event) {
            LimeLog.info("MoonWakerSurface event=" + event + " " + snapshot().diagnosticLine());
        }
    }
}
