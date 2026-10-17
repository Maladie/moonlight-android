package com.limelight.console;

import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

/** Presents the remaining lease reported by the host using elapsed realtime. */
public final class ChildSessionCountdown {
    /** Below the last full hour; above it every full remaining hour is a warning. */
    private static final int[] LAST_HOUR_WARNING_MINUTES = {30, 15, 10, 5, 1};
    private static final long MILLIS_PER_SECOND = 1_000L;

    private final String sessionId;
    private final Set<Long> shownWarnings = new HashSet<>();
    private long deadlineElapsedMs;
    private long playableNowSeconds;
    private long lastReceivedElapsedMs;
    private long lastObservedRemainingSeconds;
    private boolean hasUpdate;

    public ChildSessionCountdown(String sessionId) {
        this.sessionId = Objects.requireNonNull(sessionId, "sessionId");
    }

    /** Returns false when the update belongs to another or an older session. */
    public boolean update(String sessionId, long playableNowSeconds,
                          long receivedElapsedRealtimeMs) {
        if (!this.sessionId.equals(sessionId) || playableNowSeconds < 0
                || receivedElapsedRealtimeMs < 0
                || (hasUpdate && receivedElapsedRealtimeMs < lastReceivedElapsedMs)) {
            return false;
        }

        lastObservedRemainingSeconds = hasUpdate
                ? Math.max(lastObservedRemainingSeconds, playableNowSeconds)
                : playableNowSeconds;
        this.playableNowSeconds = playableNowSeconds;
        deadlineElapsedMs = deadline(receivedElapsedRealtimeMs, playableNowSeconds);
        lastReceivedElapsedMs = receivedElapsedRealtimeMs;
        hasUpdate = true;
        return true;
    }

    public long remainingSeconds(long nowElapsedMs) {
        if (!hasUpdate || nowElapsedMs <= lastReceivedElapsedMs) return playableNowSeconds;
        long remainingMs = deadlineElapsedMs - nowElapsedMs;
        if (remainingMs <= 0) return 0;
        return remainingMs / MILLIS_PER_SECOND
                + (remainingMs % MILLIS_PER_SECOND == 0 ? 0 : 1);
    }

    /**
     * Returns the latest newly crossed warning in minutes (every full remaining hour, then 30,
     * 15, 10, 5 and 1 minutes), or -1 when there is none. Each warning is shown once.
     */
    public long crossedWarningMinutes(long nowElapsedMs) {
        if (!hasUpdate) return -1;
        long current = remainingSeconds(nowElapsedMs);
        long previous = lastObservedRemainingSeconds;
        long warning = -1;
        for (long hours = previous / 3_600L; hours >= 1; hours--) {
            warning = mark(previous, current, hours * 60L, warning);
        }
        for (int minutes : LAST_HOUR_WARNING_MINUTES) {
            warning = mark(previous, current, minutes, warning);
        }
        lastObservedRemainingSeconds = current;
        return warning;
    }

    public boolean isExpired(long nowElapsedMs) {
        return !hasUpdate || remainingSeconds(nowElapsedMs) == 0;
    }

    /** Minutes shown to the child: rounded up, so a started minute still counts. */
    public static long displayMinutes(long remainingSeconds) {
        return Math.max(1L, (remainingSeconds + 59L) / 60L);
    }

    private long mark(long previous, long current, long minutes, long latest) {
        if (!crossed(previous, current, minutes * 60L) || !shownWarnings.add(minutes)) {
            return latest;
        }
        return latest < 0 ? minutes : Math.min(latest, minutes);
    }

    private static boolean crossed(long previous, long current, long threshold) {
        return current <= threshold
                && (previous > threshold || previous == threshold && current < threshold);
    }

    private static long deadline(long receivedElapsedMs, long seconds) {
        if (seconds > (Long.MAX_VALUE - receivedElapsedMs) / MILLIS_PER_SECOND) {
            return Long.MAX_VALUE;
        }
        return receivedElapsedMs + seconds * MILLIS_PER_SECOND;
    }
}
