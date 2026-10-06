package com.limelight.session;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Locale;
import java.util.Map;

/** Persists sleep commands initiated by MoonWaker for hosts without a suspended game. */
public final class HostSleepStateStore {
    private static final String PREFS = "moonwaker_host_sleep_states";

    public static final class State {
        public final long requestedAt;
        public final long sleepObservedAt;

        State(long requestedAt, long sleepObservedAt) {
            this.requestedAt = requestedAt;
            this.sleepObservedAt = sleepObservedAt;
        }
    }

    private HostSleepStateStore() {}

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

    static State markObserved(SharedPreferences prefs, String hostId, State state) {
        if (state == null || state.sleepObservedAt > 0L) return state;
        State current = load(prefs.getAll(), hostId);
        if (current == null || current.requestedAt != state.requestedAt) return current;
        State updated = new State(state.requestedAt, System.currentTimeMillis());
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
