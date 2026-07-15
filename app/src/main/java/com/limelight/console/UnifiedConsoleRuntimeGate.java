package com.limelight.console;

/** Must remain false until the unified renderer/input path passes the TV P0 suite. */
final class UnifiedConsoleRuntimeGate {
    private static final boolean ENABLED = false;

    private UnifiedConsoleRuntimeGate() { }

    static boolean isEnabled() {
        return ENABLED;
    }
}
