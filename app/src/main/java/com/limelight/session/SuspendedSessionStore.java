package com.limelight.session;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONException;
import org.json.JSONObject;

import com.limelight.gateway.GatewayConnection;

import java.util.Locale;
import java.util.Map;

/** Persistent, per-host marker for a stream intentionally suspended on the PC; model-owned. */
public final class SuspendedSessionStore {
    private static final String PREFS = "moonwaker_suspended_sessions";
    private static final String HOST_SELECTION = "return_to_host_selection";

    public static final class Session {
        public final String suspendId;
        public final String hostId;
        public final String profileId;
        public final String executionProfileId;
        public final int sunshineAppId;
        public final String playniteGameId;
        public final String title;
        public final String artwork;
        public final long suspendedAt;
        public final long resumedAt;
        public final String resumedStreamSessionId;
        public final long sleepObservedAt;

        public Session(String suspendId, String hostId, String profileId,
                       int sunshineAppId, String playniteGameId, String title,
                       String artwork, long suspendedAt, String executionProfileId) {
            this(suspendId, hostId, profileId, executionProfileId,
                    sunshineAppId, playniteGameId,
                    title, artwork, suspendedAt, 0L, "", 0L);
        }

        private Session(String suspendId, String hostId, String profileId,
                        String executionProfileId, int sunshineAppId,
                        String playniteGameId, String title, String artwork,
                        long suspendedAt, long resumedAt,
                        String resumedStreamSessionId, long sleepObservedAt) {
            this.suspendId = normalizeValue(suspendId);
            this.hostId = normalize(hostId);
            this.profileId = GatewayConnection.normalizeProfileId(profileId);
            this.executionProfileId = GatewayConnection.normalizeProfileId(
                    executionProfileId == null ? this.profileId : executionProfileId);
            this.sunshineAppId = sunshineAppId;
            this.playniteGameId = normalize(playniteGameId);
            this.title = title == null ? "" : title.trim();
            this.artwork = artwork == null ? "" : artwork.trim();
            this.suspendedAt = suspendedAt;
            this.resumedAt = resumedAt;
            this.resumedStreamSessionId = normalizeValue(resumedStreamSessionId);
            this.sleepObservedAt = sleepObservedAt;
        }

        private Session withResume(long at, String streamSessionId) {
            return new Session(suspendId, hostId, profileId, executionProfileId, sunshineAppId,
                    playniteGameId, title, artwork, suspendedAt, at, streamSessionId,
                    sleepObservedAt);
        }
    }

    private SuspendedSessionStore() { }

    static void save(SharedPreferences prefs, Session session) {
        if (session.hostId.isEmpty() || session.suspendId.isEmpty()) return;
        JSONObject value = new JSONObject();
        try {
            value.put("suspend_id", session.suspendId);
            value.put("host_id", session.hostId);
            value.put("profile_id", session.profileId);
            value.put("execution_profile_id", session.executionProfileId);
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
        prefs.edit()
                .putString(key(session.hostId, session.profileId), value.toString())
                .remove(endedAtKey(session.hostId, session.profileId))
                .remove(endedIdKey(session.hostId, session.profileId))
                .remove(endedAtKey(session.hostId, session.executionProfileId))
                .remove(endedIdKey(session.hostId, session.executionProfileId))
                .apply();
    }

    static Session load(Map<String, ?> stored, String hostId, String profileId) {
        String host = normalize(hostId);
        String profile = GatewayConnection.normalizeProfileId(profileId);
        Object raw = stored.get(key(host, profile));
        Session session = raw instanceof String ? parse((String) raw) : null;
        return session != null && session.hostId.equals(host)
                && session.profileId.equals(profile) ? session : null;
    }

    static Session loadForExecutionProfile(Map<String, ?> stored, String hostId,
                                           String executionProfileId) {
        String host = normalize(hostId);
        String execution = GatewayConnection.normalizeProfileId(executionProfileId);
        Session newest = null;
        for (Object raw : stored.values()) {
            if (!(raw instanceof String)) continue;
            Session candidate = parse((String) raw);
            if (candidate == null || !candidate.hostId.equals(host)
                    || !candidate.executionProfileId.equals(execution)) continue;
            if (newest == null || candidate.suspendedAt > newest.suspendedAt) newest = candidate;
        }
        return newest;
    }

    /** A record without its own suspend id or profile is not a record. */
    private static Session parse(String raw) {
        try {
            JSONObject value = new JSONObject(raw);
            String profile = value.optString("profile_id", "").trim();
            String suspendId = normalizeValue(value.optString("suspend_id", ""));
            if (profile.isEmpty() || suspendId.isEmpty()) return null;
            return new Session(suspendId, value.optString("host_id", ""), profile,
                    value.optString("execution_profile_id", profile),
                    value.optInt("sunshine_app_id", 0), value.optString("playnite_game_id", ""),
                    value.optString("title", ""), value.optString("artwork", ""),
                    value.optLong("suspended_at", 0L), value.optLong("resumed_at", 0L),
                    value.optString("resumed_stream_session_id", ""),
                    value.optLong("sleep_observed_at", 0L));
        } catch (JSONException | IllegalArgumentException malformed) {
            return null;
        }
    }

    static boolean markResumedForExecutionIfMatches(
            SharedPreferences prefs, String suspendId, String hostId, String executionProfileId,
            int sunshineAppId, String playniteGameId, String streamSessionId) {
        Session current = loadForExecutionProfile(prefs.getAll(), hostId, executionProfileId);
        if (!matches(current, suspendId)
                || current.sunshineAppId != sunshineAppId
                || !current.executionProfileId.equals(
                GatewayConnection.normalizeProfileId(executionProfileId))) return false;
        String expectedGameId = normalize(playniteGameId);
        if ((!expectedGameId.isEmpty() || !current.playniteGameId.isEmpty())
                && !expectedGameId.equals(current.playniteGameId)) return false;
        save(prefs, current.withResume(System.currentTimeMillis(), streamSessionId));
        return true;
    }

    static Session markSleepObservedIfMatches(SharedPreferences prefs, Session expected) {
        if (expected == null || expected.sleepObservedAt > 0L) return expected;
        Session current = load(prefs.getAll(), expected.hostId, expected.profileId);
        // A resumed game's host is awake: its sleep is history, not news.
        if (!matches(current, expected.suspendId) || current.resumedAt > 0L) return current;
        Session updated = new Session(current.suspendId, current.hostId, current.profileId,
                current.executionProfileId,
                current.sunshineAppId, current.playniteGameId, current.title,
                current.artwork, current.suspendedAt, current.resumedAt,
                current.resumedStreamSessionId, System.currentTimeMillis());
        save(prefs, updated);
        return updated;
    }

    static boolean markSessionEndedIfMatches(SharedPreferences prefs, String hostId,
                                             String profileId, String suspendId) {
        Session current = load(prefs.getAll(), hostId, profileId);
        if (!matches(current, suspendId)) return false;
        markSessionEnded(prefs, current);
        return true;
    }

    static boolean markSessionEndedForExecutionIfMatches(
            SharedPreferences prefs, String hostId, String executionProfileId, String suspendId) {
        Session current = loadForExecutionProfile(prefs.getAll(), hostId, executionProfileId);
        if (!matches(current, suspendId)) return false;
        markSessionEnded(prefs, current);
        return true;
    }

    private static void markSessionEnded(SharedPreferences prefs, Session current) {
        long now = System.currentTimeMillis();
        prefs.edit()
                .remove(key(current.hostId, current.profileId))
                .putLong(endedAtKey(current.hostId, current.profileId), now)
                .putString(endedIdKey(current.hostId, current.profileId), current.suspendId)
                .putLong(endedAtKey(current.hostId, current.executionProfileId), now)
                .putString(endedIdKey(current.hostId, current.executionProfileId),
                        current.suspendId)
                .apply();
    }

    static void requestHostSelection(SharedPreferences prefs, String hostId) {
        prefs.edit().putString(HOST_SELECTION, normalize(hostId)).apply();
    }

    static boolean recentlyEnded(Map<String, ?> stored, String hostId, String profileId) {
        Object endedAt = stored.get(endedAtKey(normalize(hostId),
                GatewayConnection.normalizeProfileId(profileId)));
        long at = endedAt instanceof Number ? ((Number) endedAt).longValue() : 0L;
        return at > 0L && System.currentTimeMillis() - at < 20_000L;
    }

    static String hostSelectionRequest(Map<String, ?> stored) {
        Object hostId = stored.get(HOST_SELECTION);
        return hostId instanceof String ? (String) hostId : "";
    }

    static String consumeHostSelectionRequest(SharedPreferences prefs) {
        String hostId = hostSelectionRequest(prefs.getAll());
        if (hostId.isEmpty()) return "";
        prefs.edit().remove(HOST_SELECTION).apply();
        return hostId;
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

    static SharedPreferences prefs(Context context) {
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
