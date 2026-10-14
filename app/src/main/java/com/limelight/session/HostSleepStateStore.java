package com.limelight.session;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Locale;
import java.util.Map;

/** Persists sleep commands initiated by MoonWaker for hosts without a suspended game. */
public final class HostSleepStateStore {
    private static final String PREFS = "moonwaker_host_sleep_states";

    public static final class State {
        /** The record's identity: when it was requested, from the host's acceptance on then. */
        public final long requestedAt;
        public final long sleepObservedAt;

        State(long requestedAt, long sleepObservedAt) {
            this.requestedAt = requestedAt;
            this.sleepObservedAt = sleepObservedAt;
        }
    }

    private HostSleepStateStore() {}

    /** How long a requested sleep is awaited down after the host accepted it. */
    public static final long DOWN_TIMEOUT_MS = 30_000L;

    /**
     * A sleep record, timed from the host's acceptance, that no poll saw down within twice
     * the down wait (or whose follow-up died with the process): an online host then shows as
     * it is.
     */
    public static boolean requestExpired(long requestedAtMs, long nowMs) {
        return nowMs < requestedAtMs || nowMs - requestedAtMs > 2 * DOWN_TIMEOUT_MS;
    }

    static void request(SharedPreferences prefs, String hostId, long requestedAt) {
        prefs.edit().putString(key(hostId), requestedAt + ":0").apply();
    }

    static State load(Map<String, ?> stored, String hostId) {
        Object raw = stored.get(key(hostId));
        if (!(raw instanceof String) || ((String) raw).isEmpty()) return null;
        String[] parts = ((String) raw).split(":", -1);
        try {
            return new State(Long.parseLong(parts[0]),
                    parts.length > 1 ? Long.parseLong(parts[1]) : 0L);
        } catch (NumberFormatException malformed) {
            return null;
        }
    }

    /**
     * The host accepted the request: the record is timed from that acceptance from now on, so
     * its bound does not include the request's own round trip. A record cleared or replaced
     * meanwhile is left alone.
     */
    static boolean accept(SharedPreferences prefs, String hostId, long requestedAt,
                          long acceptedAt) {
        State current = load(prefs.getAll(), hostId);
        if (current == null || current.requestedAt != requestedAt) return false;
        prefs.edit().putString(key(hostId), acceptedAt + ":" + current.sleepObservedAt).apply();
        return true;
    }

    /** Marks exactly the record timed {@code requestedAt} observed asleep, once. */
    static State markObserved(SharedPreferences prefs, String hostId, long requestedAt) {
        State current = load(prefs.getAll(), hostId);
        if (current == null || current.requestedAt != requestedAt
                || current.sleepObservedAt > 0L) {
            return current;
        }
        State updated = new State(requestedAt, System.currentTimeMillis());
        prefs.edit().putString(key(hostId),
                updated.requestedAt + ":" + updated.sleepObservedAt).apply();
        return updated;
    }

    /** Clears the record; a nonzero {@code requestedAt} clears only that exact request. */
    static boolean clearIfMatches(SharedPreferences prefs, String hostId, long requestedAt) {
        State current = load(prefs.getAll(), hostId);
        if (current == null || (requestedAt != 0L && current.requestedAt != requestedAt)) {
            return false;
        }
        prefs.edit().remove(key(hostId)).apply();
        return true;
    }

    static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static String key(String hostId) {
        return hostId == null ? "" : hostId.trim().toLowerCase(Locale.ROOT);
    }
}
