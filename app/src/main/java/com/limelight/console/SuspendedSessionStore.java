package com.limelight.console;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONException;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.UUID;

/** Persistent, per-host marker for a stream intentionally suspended on the PC. */
public final class SuspendedSessionStore {
    private static final String PREFS = "moonwaker_suspended_sessions";

    public static final class Session {
        public final String suspendId;
        public final String hostId;
        public final int sunshineAppId;
        public final String playniteGameId;
        public final String title;
        public final String artwork;
        public final long suspendedAt;
        public final long resumedAt;
        public final String resumedStreamSessionId;
        public final long sleepObservedAt;
        public final boolean legacy;

        public Session(String suspendId, String hostId, int sunshineAppId,
                       String playniteGameId, String title, String artwork,
                       long suspendedAt) {
            this(suspendId, hostId, sunshineAppId, playniteGameId, title, artwork,
                    suspendedAt, 0L, "", 0L, false);
        }

        private Session(String suspendId, String hostId, int sunshineAppId,
                        String playniteGameId, String title, String artwork,
                        long suspendedAt, long resumedAt,
                        String resumedStreamSessionId, long sleepObservedAt,
                        boolean legacy) {
            this.suspendId = normalizeValue(suspendId);
            this.hostId = normalize(hostId);
            this.sunshineAppId = sunshineAppId;
            this.playniteGameId = normalize(playniteGameId);
            this.title = title == null ? "" : title.trim();
            this.artwork = artwork == null ? "" : artwork.trim();
            this.suspendedAt = suspendedAt;
            this.resumedAt = resumedAt;
            this.resumedStreamSessionId = normalizeValue(resumedStreamSessionId);
            this.sleepObservedAt = sleepObservedAt;
            this.legacy = legacy;
        }
    }

    private SuspendedSessionStore() { }

    public static synchronized void save(Context context, Session session) {
        if (session.hostId.isEmpty() || session.suspendId.isEmpty()) return;
        JSONObject value = new JSONObject();
        try {
            value.put("suspend_id", session.suspendId);
            value.put("host_id", session.hostId);
            value.put("sunshine_app_id", session.sunshineAppId);
            value.put("playnite_game_id", session.playniteGameId);
            value.put("title", session.title);
            value.put("artwork", session.artwork);
            value.put("suspended_at", session.suspendedAt);
            value.put("resumed_at", session.resumedAt);
            value.put("resumed_stream_session_id", session.resumedStreamSessionId);
            value.put("sleep_observed_at", session.sleepObservedAt);
        } catch (JSONException impossible) {
            throw new IllegalStateException(impossible);
        }
        prefs(context).edit()
                .putString(session.hostId, value.toString())
                .remove("ended_at." + session.hostId)
                .remove("ended_id." + session.hostId)
                .apply();
    }

    public static synchronized Session load(Context context, String hostId) {
        String key = normalize(hostId);
        String raw = prefs(context).getString(key, "");
        if (raw == null || raw.isEmpty()) return null;
        try {
            JSONObject value = new JSONObject(raw);
            long suspendedAt = value.optLong("suspended_at", 0L);
            int appId = value.optInt("sunshine_app_id", 0);
            String storedSuspendId = normalizeValue(value.optString("suspend_id", ""));
            boolean legacy = storedSuspendId.isEmpty();
            String suspendId = legacy ? legacySuspendId(key, appId, suspendedAt)
                    : storedSuspendId;
            String playniteGameId = value.optString("playnite_game_id", "");
            if (playniteGameId.isEmpty()) {
                playniteGameId = context.getSharedPreferences(
                        "console_dashboard", Context.MODE_PRIVATE)
                        .getString("selected_playnite." + key, "");
            }
            return new Session(suspendId, key, appId, playniteGameId,
                    value.optString("title", ""), value.optString("artwork", ""),
                    suspendedAt, value.optLong("resumed_at", 0L),
                    value.optString("resumed_stream_session_id", ""),
                    value.optLong("sleep_observed_at", 0L), legacy);
        } catch (JSONException malformed) {
            return null;
        }
    }

    public static synchronized boolean clearIfMatches(Context context, String hostId,
                                                       String suspendId) {
        Session current = load(context, hostId);
        if (!matches(current, suspendId)) return false;
        prefs(context).edit().remove(current.hostId).apply();
        return true;
    }

    public static synchronized boolean markResumedIfMatches(
            Context context, String suspendId, String hostId, int sunshineAppId,
            String playniteGameId, String streamSessionId) {
        Session current = load(context, hostId);
        if (!canCompleteResume(current, suspendId, hostId, sunshineAppId,
                playniteGameId)) return false;
        save(context, new Session(current.suspendId, current.hostId,
                current.sunshineAppId, current.playniteGameId, current.title,
                current.artwork, current.suspendedAt, System.currentTimeMillis(),
                streamSessionId, current.sleepObservedAt, false));
        return true;
    }

    public static synchronized Session markSleepObservedIfMatches(
            Context context, Session expected) {
        if (expected == null || expected.sleepObservedAt > 0L) return expected;
        Session current = load(context, expected.hostId);
        if (!matches(current, expected.suspendId)) return current;
        Session updated = new Session(current.suspendId, current.hostId,
                current.sunshineAppId, current.playniteGameId, current.title,
                current.artwork, current.suspendedAt, current.resumedAt,
                current.resumedStreamSessionId, System.currentTimeMillis(), false);
        save(context, updated);
        return updated;
    }

    public static synchronized boolean markSessionEndedIfMatches(
            Context context, String hostId, String suspendId) {
        Session current = load(context, hostId);
        if (!matches(current, suspendId)) return false;
        SharedPreferences.Editor editor = prefs(context).edit();
        editor.remove(current.hostId);
        editor.putLong("ended_at." + current.hostId, System.currentTimeMillis());
        editor.putString("ended_id." + current.hostId, current.suspendId);
        editor.apply();
        return true;
    }

    public static void requestHostSelection(Context context, String hostId) {
        prefs(context).edit().putString("return_to_host_selection", normalize(hostId)).apply();
    }

    public static boolean recentlyEnded(Context context, String hostId) {
        long endedAt = prefs(context).getLong("ended_at." + normalize(hostId), 0L);
        return endedAt > 0L && System.currentTimeMillis() - endedAt < 20_000L;
    }

    public static void clearEnded(Context context, String hostId) {
        String key = normalize(hostId);
        prefs(context).edit().remove("ended_at." + key).remove("ended_id." + key).apply();
    }

    public static String consumeHostSelectionRequest(Context context) {
        String hostId = prefs(context).getString("return_to_host_selection", "");
        if (hostId == null || hostId.isEmpty()) return "";
        prefs(context).edit().remove("return_to_host_selection").apply();
        return hostId;
    }

    static String legacySuspendId(String hostId, int appId, long suspendedAt) {
        String material = normalize(hostId) + '|' + appId + '|' + suspendedAt;
        return UUID.nameUUIDFromBytes(material.getBytes(StandardCharsets.UTF_8)).toString();
    }

    static boolean canCompleteResume(Session session, String suspendId, String hostId,
                                     int sunshineAppId, String playniteGameId) {
        if (!matches(session, suspendId)
                || !session.hostId.equals(normalize(hostId))
                || session.sunshineAppId != sunshineAppId) return false;
        String expectedGameId = normalize(playniteGameId);
        return (expectedGameId.isEmpty() && session.playniteGameId.isEmpty())
                || expectedGameId.equals(session.playniteGameId);
    }

    static boolean matches(Session session, String suspendId) {
        return session != null && !session.suspendId.isEmpty()
                && session.suspendId.equals(normalizeValue(suspendId));
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static String normalize(String value) {
        return normalizeValue(value).toLowerCase(Locale.ROOT);
    }

    private static String normalizeValue(String value) {
        return value == null ? "" : value.trim();
    }
}
