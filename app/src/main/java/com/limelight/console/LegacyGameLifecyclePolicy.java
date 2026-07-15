package com.limelight.console;

/** Pure compatibility policy for keeping the legacy Game session alive behind Console. */
public final class LegacyGameLifecyclePolicy {
    private LegacyGameLifecyclePolicy() { }

    public static boolean shouldKeepSessionOnStop(boolean externalFrontend,
                                                  boolean finishing,
                                                  boolean interactive,
                                                  boolean sessionActive,
                                                  boolean consoleRenderTargetBound) {
        if (consoleRenderTargetBound && sessionActive) return true;
        return externalFrontend && !finishing && interactive && sessionActive;
    }

    public static boolean hasAlternateRenderTarget(boolean attemptedConnection,
                                                    boolean consoleRenderTargetBound,
                                                    boolean backgroundSurfaceBound) {
        return attemptedConnection && (consoleRenderTargetBound || backgroundSurfaceBound);
    }
}
