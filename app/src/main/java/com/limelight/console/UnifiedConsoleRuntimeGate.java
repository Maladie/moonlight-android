package com.limelight.console;

import com.limelight.BuildConfig;

/** Must remain false until the unified renderer/input path passes the TV P0 suite. */
final class UnifiedConsoleRuntimeGate {
    private UnifiedConsoleRuntimeGate() { }

    static boolean isEnabled() {
        return BuildConfig.UNIFIED_CONSOLE_RUNTIME;
    }
}
