package com.limelight.console;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONException;
import org.json.JSONObject;

import com.limelight.gateway.GatewayConnection;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.UUID;

/** Persistent, per-host marker for a stream intentionally suspended on the PC. */
public final class SuspendedSessionStore {
    private static final String PREFS = "moonwaker_suspended_sessions";

    public static final class Session {
        public final String suspendId;
        public final String hostId;
        public final String profileId;
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
            this(suspendId, hostId, GatewayConnection.DEFAULT_PROFILE_ID,
                    sunshineAppId, playniteGameId, title, artwork,
                    suspendedAt, 0L, "", 0L, false);
        }

        public Session(String suspendId, String hostId, String profileId,
                       int sunshineAppId, String playniteGameId, String title,
                       String artwork, long suspendedAt) {
            this(suspendId, hostId, profileId, sunshineAppId, playniteGameId,
                    title, artwork, suspendedAt, 0L, "", 0L, false);
        }

        private Session(String suspendId, String hostId, String profileId,
                        int sunshineAppId,
                        String playniteGameId, String title, String artwork,
                        long suspendedAt, long resumedAt,
                        String resumedStreamSessionId, long sleepObservedAt,
                        boolean legacy) {
            this.suspendId = normalizeValue(suspendId);
            this.hostId = normalize(hostId);
            this.profileId = GatewayConnection.normalizeProfileId(profileId);
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
            value.put("profile_id", session.profileId);
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
                .putString(key(session.hostId, session.profileId), value.toString())
                .remove(endedAtKey(session.hostId, session.profileId))
                .remove(endedIdKey(session.hostId, session.profileId))
                .apply();
    }

    public static synchronized Session load(Context context, String hostId) {
        return load(context, hostId, GatewayConnection.DEFAULT_PROFILE_ID);
    }

    public static synchronized Session load(Context context, String hostId,
                                            String profileId) {
        String host = normalize(hostId);
        String profile = GatewayConnection.normalizeProfileId(profileId);
        String raw = prefs(context).getString(key(host, profile), "");
        if (raw == null || raw.isEmpty()) return null;
        try {
            JSONObject value = new JSONObject(raw);
            String storedProfile = value.optString("profile_id", "").trim();
            if (storedProfile.isEmpty() || !profile.equals(storedProfile)) return null;
            long suspendedAt = value.optLong("suspended_at", 0L);
            int appId = value.optInt("sunshine_app_id", 0);
            String storedSuspendId = normalizeValue(value.optString("suspend_id", ""));
            boolean legacy = storedSuspendId.isEmpty();
            String suspendId = legacy ? legacySuspendId(host, profile, appId, suspendedAt)
                    : storedSuspendId;
            String playniteGameId = value.optString("playnite_game_id", "");
            return new Session(suspendId, host, profile, appId, playniteGameId,
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
        return clearIfMatches(context, hostId, GatewayConnection.DEFAULT_PROFILE_ID,
                suspendId);
    }

    public static synchronized boolean clearIfMatches(Context context, String hostId,
                                                       String profileId, String suspendId) {
        Session current = load(context, hostId, profileId);
        if (!matches(current, suspendId)) return false;
        prefs(context).edit().remove(key(current.hostId, current.profileId)).apply();
        return true;
    }

    public static synchronized boolean markResumedIfMatches(
            Context context, String suspendId, String hostId, int sunshineAppId,
            String playniteGameId, String streamSessionId) {
        return markResumedIfMatches(context, suspendId, hostId,
                GatewayConnection.DEFAULT_PROFILE_ID, sunshineAppId,
                playniteGameId, streamSessionId);
    }

    public static synchronized boolean markResumedIfMatches(
            Context context, String suspendId, String hostId, String profileId,
            int sunshineAppId, String playniteGameId, String streamSessionId) {
        Session current = load(context, hostId, profileId);
        if (!canCompleteResume(current, suspendId, hostId, profileId, sunshineAppId,
                playniteGameId)) return false;
        save(context, new Session(current.suspendId, current.hostId, current.profileId,
                current.sunshineAppId, current.playniteGameId, current.title,
                current.artwork, current.suspendedAt, System.currentTimeMillis(),
                streamSessionId, current.sleepObservedAt, false));
        return true;
    }

    public static synchronized Session markSleepObservedIfMatches(
            Context context, Session expected) {
        if (expected == null || expected.sleepObservedAt > 0L) return expected;
        Session current = load(context, expected.hostId, expected.profileId);
        if (!matches(current, expected.suspendId)) return current;
        Session updated = new Session(current.suspendId, current.hostId, current.profileId,
                current.sunshineAppId, current.playniteGameId, current.title,
                current.artwork, current.suspendedAt, current.resumedAt,
                current.resumedStreamSessionId, System.currentTimeMillis(), false);
        save(context, updated);
        return updated;
    }

    public static synchronized boolean markSessionEndedIfMatches(
            Context context, String hostId, String suspendId) {
        return markSessionEndedIfMatches(context, hostId,
                GatewayConnection.DEFAULT_PROFILE_ID, suspendId);
    }

    public static synchronized boolean markSessionEndedIfMatches(
            Context context, String hostId, String profileId, String suspendId) {
        Session current = load(context, hostId, profileId);
        if (!matches(current, suspendId)) return false;
        SharedPreferences.Editor editor = prefs(context).edit();
        editor.remove(key(current.hostId, current.profileId));
        editor.putLong(endedAtKey(current.hostId, current.profileId),
                System.currentTimeMillis());
        editor.putString(endedIdKey(current.hostId, current.profileId), current.suspendId);
        editor.apply();
        return true;
    }

    public static void requestHostSelection(Context context, String hostId) {
        prefs(context).edit().putString("return_to_host_selection", normalize(hostId)).apply();
    }

    public static boolean recentlyEnded(Context context, String hostId) {
        return recentlyEnded(context, hostId, GatewayConnection.DEFAULT_PROFILE_ID);
    }

    public static boolean recentlyEnded(Context context, String hostId, String profileId) {
        long endedAt = prefs(context).getLong(endedAtKey(normalize(hostId),
                GatewayConnection.normalizeProfileId(profileId)), 0L);
        return endedAt > 0L && System.currentTimeMillis() - endedAt < 20_000L;
    }

    public static void clearEnded(Context context, String hostId) {
        clearEnded(context, hostId, GatewayConnection.DEFAULT_PROFILE_ID);
    }

    public static void clearEnded(Context context, String hostId, String profileId) {
        String host = normalize(hostId);
        String profile = GatewayConnection.normalizeProfileId(profileId);
        prefs(context).edit().remove(endedAtKey(host, profile))
                .remove(endedIdKey(host, profile)).apply();
    }

    public static String consumeHostSelectionRequest(Context context) {
        String hostId = prefs(context).getString("return_to_host_selection", "");
        if (hostId == null || hostId.isEmpty()) return "";
        prefs(context).edit().remove("return_to_host_selection").apply();
        return hostId;
    }

    static String legacySuspendId(String hostId, int appId, long suspendedAt) {
        return legacySuspendId(hostId, GatewayConnection.DEFAULT_PROFILE_ID,
                appId, suspendedAt);
    }

    static String legacySuspendId(String hostId, String profileId,
                                  int appId, long suspendedAt) {
        String material = normalize(hostId) + '|' +
                GatewayConnection.normalizeProfileId(profileId) + '|' + appId + '|' + suspendedAt;
        return UUID.nameUUIDFromBytes(material.getBytes(StandardCharsets.UTF_8)).toString();
    }

    static boolean canCompleteResume(Session session, String suspendId, String hostId,
                                     int sunshineAppId, String playniteGameId) {
        return canCompleteResume(session, suspendId, hostId,
                GatewayConnection.DEFAULT_PROFILE_ID, sunshineAppId, playniteGameId);
    }

    static boolean canCompleteResume(Session session, String suspendId, String hostId,
                                     String profileId, int sunshineAppId,
                                     String playniteGameId) {
        if (!matches(session, suspendId)
                || !session.hostId.equals(normalize(hostId))
                || !session.profileId.equals(
                GatewayConnection.normalizeProfileId(profileId))
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

    private static String key(String hostId, String profileId) {
        return normalize(hostId) + ".profile." +
                GatewayConnection.normalizeProfileId(profileId);
    }

    private static String endedAtKey(String hostId, String profileId) {
        return "ended_at." + key(hostId, profileId);
    }

    private static String endedIdKey(String hostId, String profileId) {
        return "ended_id." + key(hostId, profileId);
    }

    private static String normalize(String value) {
        return normalizeValue(value).toLowerCase(Locale.ROOT);
    }

    private static String normalizeValue(String value) {
        return value == null ? "" : value.trim();
    }
}
