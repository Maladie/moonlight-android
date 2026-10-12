package com.limelight.console;

/**
 * When the entry intro (UX_MENU_PL.md section 3.8) leaves for the home screen: never before the
 * minimum unless a key skips it, never past the limit, otherwise once the home screen is ready.
 * Every time here is on the intro's own clock, which {@link #TIME_SCALE} stretches.
 */
final class ConsoleIntroTiming {
    /**
     * Slow-motion factor for frame-by-frame checks (2 = half speed): stretches every phase,
     * the minimum and the limit alike. Always 1 in a normal build.
     */
    static final float TIME_SCALE = 1f;
    /**
     * Owner-tunable: the intro shows at least this long, then leaves once home is ready; the
     * mockup (intro-v4.webp) holds the mark until 1800 ms.
     */
    static final long MINIMUM_MS = 1_800L;
    /** Owner-tunable: past this the intro stops waiting; home shows glass cover placeholders. */
    static final long LIMIT_MS = 4_500L;
    /** Entering a different host than the last one: the shortened version. */
    static final long HOST_CHANGE_MS = 600L;
    /**
     * The entrance, in phase time (the mockup's clock, which a host change runs faster): the
     * avatar's flight and fade (0-650 ms), the band of light (300-950 ms) and the mark and
     * greeting appearing (750-1100 ms). After it only the motes move.
     */
    static final float ENTRANCE_MS = 1_100f;
    /** Owner-tunable: the opening frame stands still at most this long, real ms. */
    static final long STILL_LIMIT_MS = 1_000L;
    /** No step starts later than this before the limit: a carousel card's build still fits. */
    static final long STILL_STEP_MS = 70L;
    /** A key skips a ready intro only after this. */
    static final long SKIP_AFTER_MS = 300L;
    /** Reduced motion: the static mark cross-fades in and out in this time. */
    static final long REDUCED_FADE_MS = 200L;

    private ConsoleIntroTiming() { }

    /**
     * Whether the intro should start leaving now; {@code keyAtMs} is when the first key that
     * may skip it came, -1 for none. A key before {@link #SKIP_AFTER_MS} does not count.
     */
    static boolean leave(long elapsedMs, long minimumMs, boolean ready, long keyAtMs) {
        if (elapsedMs >= LIMIT_MS) return true;
        return ready && (elapsedMs >= minimumMs || keyAtMs >= SKIP_AFTER_MS);
    }

    /**
     * Whether the intro still stands on its opening frame {@code stillMs} after it showed:
     * for its first two frames, then while home's first render is still to come or steps wait
     * to run, and never past the limit.
     */
    static boolean standsStill(long stillMs, int frames, boolean stepsWaiting,
                               boolean homeRendered) {
        return stillMs < STILL_LIMIT_MS - STILL_STEP_MS
                && (frames < 2 || stepsWaiting || !homeRendered);
    }

    /** Whether the entrance is over at {@code phaseMs}; home's waiting steps run from then. */
    static boolean entered(float phaseMs) {
        return phaseMs >= ENTRANCE_MS;
    }

    /** Mockup "ease_out": cubic, clamped to [0, 1]. */
    static float easeOut(float t) {
        t = clamp(t);
        float u = 1f - t;
        return 1f - u * u * u;
    }

    /** Mockup "ease_in_out": cubic, clamped to [0, 1]. */
    static float easeInOut(float t) {
        t = clamp(t);
        if (t < .5f) return 4f * t * t * t;
        float u = -2f * t + 2f;
        return 1f - u * u * u / 2f;
    }

    static float clamp(float t) {
        return Math.max(0f, Math.min(1f, t));
    }
}
