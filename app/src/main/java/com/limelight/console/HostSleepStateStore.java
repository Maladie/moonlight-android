package com.limelight.console;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Locale;

/** Persists sleep commands initiated by MoonWaker for hosts without a suspended game. */
final class HostSleepStateStore {
    private static final String PREFS = "moonwaker_host_sleep_states";

    static final class State {
        final long requestedAt;
        final long sleepObservedAt;

        State(long requestedAt, long sleepObservedAt) {
            this.requestedAt = requestedAt;
            this.sleepObservedAt = sleepObservedAt;
        }
    }

    private HostSleepStateStore() {}

    static void request(Context context, String hostId) {
        prefs(context).edit().putString(key(hostId),
                System.currentTimeMillis() + ":0").apply();
    }

    static State load(Context context, String hostId) {
        String raw = prefs(context).getString(key(hostId), "");
        if (raw == null || raw.isEmpty()) return null;
        String[] parts = raw.split(":", -1);
        try {
            return new State(Long.parseLong(parts[0]),
                    parts.length > 1 ? Long.parseLong(parts[1]) : 0L);
        } catch (NumberFormatException malformed) {
            clear(context, hostId);
            return null;
        }
    }

    static State markObserved(Context context, String hostId, State state) {
        if (state == null || state.sleepObservedAt > 0L) return state;
        State updated = new State(state.requestedAt, System.currentTimeMillis());
        prefs(context).edit().putString(key(hostId),
                updated.requestedAt + ":" + updated.sleepObservedAt).apply();
        return updated;
    }

    static void clear(Context context, String hostId) {
        prefs(context).edit().remove(key(hostId)).apply();
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static String key(String hostId) {
        return hostId == null ? "" : hostId.trim().toLowerCase(Locale.ROOT);
    }
}
