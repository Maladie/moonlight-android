package com.limelight.console;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.Locale;

/** Persistent, per-host marker for a stream intentionally suspended on the PC. */
public final class SuspendedSessionStore {
    private static final String PREFS = "moonwaker_suspended_sessions";

    public static final class Session {
        public final String hostId;
        public final int sunshineAppId;
        public final String playniteGameId;
        public final String title;
        public final String artwork;
        public final long suspendedAt;
        public final long resumedAt;
        public final long sleepObservedAt;

        public Session(String hostId, int sunshineAppId, String playniteGameId,
                       String title, String artwork, long suspendedAt) {
            this(hostId, sunshineAppId, playniteGameId, title, artwork, suspendedAt, 0L, 0L);
        }

        private Session(String hostId, int sunshineAppId, String playniteGameId,
                        String title, String artwork, long suspendedAt, long resumedAt,
                        long sleepObservedAt) {
            this.hostId = normalize(hostId);
            this.sunshineAppId = sunshineAppId;
            this.playniteGameId = normalize(playniteGameId);
            this.title = title == null ? "" : title.trim();
            this.artwork = artwork == null ? "" : artwork.trim();
            this.suspendedAt = suspendedAt;
            this.resumedAt = resumedAt;
            this.sleepObservedAt = sleepObservedAt;
        }
    }

    private SuspendedSessionStore() {}

    public static void save(Context context, Session session) {
        if (session.hostId.isEmpty()) return;
        JSONObject value = new JSONObject();
        try {
            value.put("host_id", session.hostId);
            value.put("sunshine_app_id", session.sunshineAppId);
            value.put("playnite_game_id", session.playniteGameId);
            value.put("title", session.title);
            value.put("artwork", session.artwork);
            value.put("suspended_at", session.suspendedAt);
            value.put("resumed_at", session.resumedAt);
            value.put("sleep_observed_at", session.sleepObservedAt);
        } catch (JSONException impossible) {
            throw new IllegalStateException(impossible);
        }
        // A newly suspended session supersedes the short-lived tombstone left by
        // an earlier, explicitly terminated session on the same host.
        prefs(context).edit()
                .putString(session.hostId, value.toString())
                .remove("ended_at." + session.hostId)
                .apply();
    }

    public static Session load(Context context, String hostId) {
        String key = normalize(hostId);
        String raw = prefs(context).getString(key, "");
        if (raw == null || raw.isEmpty()) return null;
        try {
            JSONObject value = new JSONObject(raw);
            String playniteGameId = value.optString("playnite_game_id", "");
            if (playniteGameId.isEmpty()) {
                playniteGameId = context.getSharedPreferences(
                        "console_dashboard", Context.MODE_PRIVATE)
                        .getString("selected_playnite." + key, "");
            }
            Session session = new Session(key, value.optInt("sunshine_app_id", 0),
                    playniteGameId,
                    value.optString("title", ""), value.optString("artwork", ""),
                    value.optLong("suspended_at", 0L), value.optLong("resumed_at", 0L),
                    value.optLong("sleep_observed_at", 0L));
            if (!playniteGameId.equals(value.optString("playnite_game_id", ""))) {
                save(context, session);
            }
            return session;
        } catch (JSONException malformed) {
            prefs(context).edit().remove(key).apply();
            return null;
        }
    }

    public static void clear(Context context, String hostId) {
        prefs(context).edit().remove(normalize(hostId)).apply();
    }

    public static void markResumed(Context context, Session session) {
        if (session == null) return;
        save(context, new Session(session.hostId, session.sunshineAppId,
                session.playniteGameId, session.title, session.artwork,
                session.suspendedAt, System.currentTimeMillis(), session.sleepObservedAt));
    }

    public static Session markSleepObserved(Context context, Session session) {
        if (session == null || session.sleepObservedAt > 0L) return session;
        Session updated = new Session(session.hostId, session.sunshineAppId,
                session.playniteGameId, session.title, session.artwork,
                session.suspendedAt, session.resumedAt, System.currentTimeMillis());
        save(context, updated);
        return updated;
    }

    public static void requestHostSelection(Context context, String hostId) {
        prefs(context).edit().putString("return_to_host_selection", normalize(hostId)).apply();
    }

    public static void markSessionEnded(Context context, String hostId) {
        String key = normalize(hostId);
        clear(context, key);
        prefs(context).edit().putLong("ended_at." + key, System.currentTimeMillis()).apply();
    }

    public static boolean recentlyEnded(Context context, String hostId) {
        long endedAt = prefs(context).getLong("ended_at." + normalize(hostId), 0L);
        return endedAt > 0L && System.currentTimeMillis() - endedAt < 20_000L;
    }

    public static void clearEnded(Context context, String hostId) {
        prefs(context).edit().remove("ended_at." + normalize(hostId)).apply();
    }

    public static String consumeHostSelectionRequest(Context context) {
        String hostId = prefs(context).getString("return_to_host_selection", "");
        if (hostId == null || hostId.isEmpty()) return "";
        prefs(context).edit().remove("return_to_host_selection").apply();
        return hostId;
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }
}
