package com.limelight.console;

/** The "Ograniczenie animacji" preference: an explicit choice wins, otherwise memory decides. */
final class ConsoleReducedMotion {
    static final String KEY = "reduced_motion";
    /** Devices with at most this much RAM (a "3 GB" TV reports a little less) default to on. */
    static final long LOW_TOTAL_MEMORY_BYTES = 3L * 1024 * 1024 * 1024;
    /** Also on when less than this fraction of RAM is available at startup. */
    static final int LOW_AVAILABLE_MEMORY_PERCENT = 25;

    private ConsoleReducedMotion() { }

    /** {@code stored} is null when the user never toggled the preference. */
    static boolean resolve(Boolean stored, boolean lowRamDevice, long totalMemoryBytes,
                           long availableMemoryBytes) {
        if (stored != null) return stored;
        return lowRamDevice || totalMemoryBytes <= LOW_TOTAL_MEMORY_BYTES
                || availableMemoryBytes * 100 < totalMemoryBytes * LOW_AVAILABLE_MEMORY_PERCENT;
    }
}
