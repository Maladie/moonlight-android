package com.limelight.console;

import com.limelight.BuildConfig;

/** Selects the unified renderer/input path for the configured build variant. */
final class UnifiedConsoleRuntimeGate {
    private UnifiedConsoleRuntimeGate() { }

    static boolean isEnabled() {
        return BuildConfig.UNIFIED_CONSOLE_RUNTIME;
    }
}
