package com.limelight.console;

import java.util.Objects;

/** Presents the remaining lease reported by the host using elapsed realtime. */
public final class ChildSessionCountdown {
    private static final int[] WARNING_MINUTES = {60, 30, 15, 10, 5, 1};
    private static final long MILLIS_PER_SECOND = 1_000L;

    private final String sessionId;
    private long deadlineElapsedMs;
    private long playableNowSeconds;
    private long lastReceivedElapsedMs;
    private long lastObservedRemainingSeconds;
    private int shownWarnings;
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

    /** Returns one newly crossed warning in minutes, or -1 when there is none. */
    public int crossedWarningMinutes(long nowElapsedMs) {
        if (!hasUpdate) return -1;
        long current = remainingSeconds(nowElapsedMs);
        int warning = -1;
        for (int i = 0; i < WARNING_MINUTES.length; i++) {
            long thresholdSeconds = WARNING_MINUTES[i] * 60L;
            if (crossed(lastObservedRemainingSeconds, current, thresholdSeconds)
                    && (shownWarnings & (1 << i)) == 0) {
                shownWarnings |= 1 << i;
                warning = WARNING_MINUTES[i];
            }
        }
        lastObservedRemainingSeconds = current;
        return warning;
    }

    public boolean isExpired(long nowElapsedMs) {
        return !hasUpdate || remainingSeconds(nowElapsedMs) == 0;
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
