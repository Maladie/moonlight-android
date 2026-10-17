package com.limelight.session;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.SystemClock;
import android.util.Base64;

import com.limelight.Game;
import com.limelight.console.transition.LaunchTransitionType;
import com.limelight.gateway.GatewayConnection;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/** Persistent reconnect material for exactly one stream lifecycle; written only by the model. */
public final class SessionResumeManager {
    private static final String PREFS_NAME = "SessionResume";
    private static final String KEY_PENDING = "pending";
    private static final String KEY_AUTO_RESUME = "autoResume";
    private static final String KEY_STREAM_SESSION_ID = "streamSessionId";
    private static final String KEY_CONTROL_GENERATION = "controlGeneration";
    private static final String KEY_CREATED_AT = "createdAt";
    private static final String KEY_UPDATED_AT = "updatedAt";
    private static final String KEY_HOST = "host";
    private static final String KEY_PORT = "port";
    private static final String KEY_HTTPS_PORT = "httpsPort";
    private static final String KEY_APP_NAME = "appName";
    private static final String KEY_STREAM_TARGET_NAME = "streamTargetName";
    private static final String KEY_NEUTRAL_STREAM_TARGET = "neutralStreamTarget";
    private static final String KEY_APP_ID = "appId";
    private static final String KEY_APP_HDR = "appHdr";
    private static final String KEY_UNIQUE_ID = "uniqueId";
    private static final String KEY_PC_UUID = "pcUuid";
    private static final String KEY_PROFILE_ID = "profileId";
    private static final String KEY_PC_NAME = "pcName";
    private static final String KEY_SERVER_CERT = "serverCert";
    private static final String KEY_QUICK_LAUNCH = "quickLaunchKey";
    private static final String KEY_APPLY_OVERRIDES = "applyOverrides";
    private static final String KEY_RUNTIME_BITRATE = "runtimeBitrate";
    private static final String KEY_CONSOLE_LOADING = "consoleLoading";
    private static final String KEY_LOADING_MESSAGE = "loadingMessage";
    private static final String KEY_LOADING_STEP = "loadingStep";
    private static final String KEY_LOADING_STATUS = "loadingStatus";
    private static final String KEY_LOADING_ARTWORK = "loadingArtwork";
    private static final String KEY_REDUCED_MOTION = "reducedMotion";
    private static final String KEY_TRANSITION_ID = "transitionId";
    private static final String KEY_TRANSITION_TYPE = "transitionType";
    private static final String KEY_TRANSITION_HOST_ID = "transitionHostId";
    private static final String KEY_TRANSITION_GAME_ID = "transitionGameId";
    private static final String KEY_TRANSITION_CREATED_AT = "transitionCreatedAt";
    private static final String KEY_SOURCE_SUSPEND_ID = "sourceSuspendId";
    private static final String KEY_SOURCE_SUSPEND_GAME_ID = "sourceSuspendGameId";

    public static final class PendingSession {
        public final String streamSessionId;
        public final String hostUuid;
        public final String profileId;
        public final String executionProfileId;
        public final int appId;
        public final String playniteGameId;
        public final boolean neutralStreamTarget;
        public final boolean autoResume;
        public final long createdAt;
        public final long updatedAt;
        public final long controlGeneration;
        public final boolean childProfile;
        public final String childSessionId;
        private final Values values;

        private PendingSession(String streamSessionId, String hostUuid, String profileId,
                               int appId,
                               String playniteGameId, boolean autoResume,
                               long createdAt, long updatedAt, Values prefs) {
            this.streamSessionId = streamSessionId;
            this.hostUuid = hostUuid;
            this.profileId = GatewayConnection.normalizeProfileId(profileId);
            this.executionProfileId = GatewayConnection.normalizeProfileId(
                    prefs.getString(Game.EXTRA_EXECUTION_PROFILE_ID, this.profileId));
            this.appId = appId;
            this.playniteGameId = normalize(playniteGameId);
            this.neutralStreamTarget = prefs.getBoolean(KEY_NEUTRAL_STREAM_TARGET, false);
            this.autoResume = autoResume;
            this.createdAt = createdAt;
            this.updatedAt = updatedAt;
            this.controlGeneration = Math.max(0L,
                    prefs.getLong(KEY_CONTROL_GENERATION, 0L));
            this.childProfile = prefs.getBoolean(Game.EXTRA_CHILD_PROFILE,
                    prefs.getString(Game.EXTRA_CHILD_SESSION_ID, null) != null);
            this.childSessionId = normalize(
                    prefs.getString(Game.EXTRA_CHILD_SESSION_ID, null));
            this.values = prefs;
        }

        public boolean matches(String expectedStreamSessionId) {
            return streamSessionId.equals(normalize(expectedStreamSessionId));
        }
    }

    private static final class Values {
        private final Map<String, ?> values;

        Values(Map<String, ?> source) {
            values = Collections.unmodifiableMap(new HashMap<>(source));
        }

        String getString(String key, String fallback) {
            Object value = values.get(key);
            return value instanceof String ? (String) value : fallback;
        }

        int getInt(String key, int fallback) {
            Object value = values.get(key);
            return value instanceof Number ? ((Number) value).intValue() : fallback;
        }

        long getLong(String key, long fallback) {
            Object value = values.get(key);
            return value instanceof Number ? ((Number) value).longValue() : fallback;
        }

        boolean getBoolean(String key, boolean fallback) {
            Object value = values.get(key);
            return value instanceof Boolean ? (Boolean) value : fallback;
        }
    }

    private SessionResumeManager() { }

    /** Replaces the record; {@code autoResume} false keeps it for process-death recovery only. */
    static void save(SharedPreferences prefs, Intent gameIntent, String sessionId,
                     boolean autoResume) {
        PendingSession current = read(prefs.getAll());
        long now = System.currentTimeMillis();
        long createdAt = current != null && current.matches(sessionId)
                && current.createdAt > 0L ? current.createdAt : now;
        SharedPreferences.Editor editor = prefs.edit();
        editor.clear();
        editor.putBoolean(KEY_PENDING, true);
        editor.putBoolean(KEY_AUTO_RESUME, autoResume);
        editor.putString(KEY_STREAM_SESSION_ID, sessionId);
        editor.putLong(KEY_CONTROL_GENERATION,
                gameIntent.getLongExtra(Game.EXTRA_CONTROL_GENERATION, 0L));
        putString(editor, Game.EXTRA_CONTROL_INTENT, "reconnect");
        editor.putLong(KEY_CREATED_AT, createdAt);
        editor.putLong(KEY_UPDATED_AT, now);
        editor.putString(KEY_HOST, gameIntent.getStringExtra(Game.EXTRA_HOST));
        editor.putInt(KEY_PORT, gameIntent.getIntExtra(Game.EXTRA_PORT, 0));
        editor.putInt(KEY_HTTPS_PORT, gameIntent.getIntExtra(Game.EXTRA_HTTPS_PORT, 0));
        editor.putString(KEY_APP_NAME, gameIntent.getStringExtra(Game.EXTRA_APP_NAME));
        editor.putString(KEY_STREAM_TARGET_NAME,
                gameIntent.getStringExtra(Game.EXTRA_STREAM_TARGET_NAME));
        editor.putBoolean(KEY_NEUTRAL_STREAM_TARGET,
                gameIntent.getBooleanExtra(Game.EXTRA_NEUTRAL_STREAM_TARGET, false));
        editor.putInt(KEY_APP_ID, gameIntent.getIntExtra(Game.EXTRA_APP_ID, 0));
        editor.putBoolean(KEY_APP_HDR, gameIntent.getBooleanExtra(Game.EXTRA_APP_HDR, false));
        editor.putString(KEY_UNIQUE_ID, gameIntent.getStringExtra(Game.EXTRA_UNIQUEID));
        editor.putString(KEY_PC_UUID, gameIntent.getStringExtra(Game.EXTRA_PC_UUID));
        editor.putString(KEY_PROFILE_ID, GatewayConnection.normalizeProfileId(
                gameIntent.getStringExtra(Game.EXTRA_PROFILE_ID)));
        editor.putString(KEY_PC_NAME, gameIntent.getStringExtra(Game.EXTRA_PC_NAME));
        editor.putBoolean(KEY_APPLY_OVERRIDES,
                gameIntent.getBooleanExtra(Game.EXTRA_APPLY_PREFERENCE_OVERRIDES, false));
        editor.putInt(KEY_RUNTIME_BITRATE,
                gameIntent.getIntExtra(Game.EXTRA_RUNTIME_BITRATE_KBPS, 0));
        editor.putBoolean(KEY_CONSOLE_LOADING,
                gameIntent.getBooleanExtra(Game.EXTRA_CONSOLE_LOADING, false));
        putString(editor, KEY_QUICK_LAUNCH,
                gameIntent.getStringExtra(Game.EXTRA_QUICK_LAUNCH_APP_KEY));
        putString(editor, KEY_LOADING_MESSAGE,
                gameIntent.getStringExtra(Game.EXTRA_CONSOLE_LOADING_MESSAGE));
        editor.putInt(KEY_LOADING_STEP,
                gameIntent.getIntExtra(Game.EXTRA_CONSOLE_LOADING_STEP, 2));
        putString(editor, KEY_LOADING_STATUS,
                gameIntent.getStringExtra(Game.EXTRA_CONSOLE_LOADING_STATUS));
        putString(editor, KEY_LOADING_ARTWORK,
                gameIntent.getStringExtra(Game.EXTRA_CONSOLE_LOADING_ARTWORK));
        editor.putBoolean(KEY_REDUCED_MOTION,
                gameIntent.getBooleanExtra(Game.EXTRA_CONSOLE_REDUCED_MOTION, false));
        putString(editor, KEY_TRANSITION_ID,
                gameIntent.getStringExtra(Game.EXTRA_TRANSITION_ID));
        putString(editor, KEY_TRANSITION_TYPE,
                gameIntent.getStringExtra(Game.EXTRA_TRANSITION_TYPE));
        putString(editor, KEY_TRANSITION_HOST_ID,
                gameIntent.getStringExtra(Game.EXTRA_TRANSITION_HOST_ID));
        putString(editor, KEY_TRANSITION_GAME_ID,
                gameIntent.getStringExtra(Game.EXTRA_TRANSITION_PLAYNITE_GAME_ID));
        editor.putLong(KEY_TRANSITION_CREATED_AT,
                gameIntent.getLongExtra(Game.EXTRA_TRANSITION_CREATED_AT, 0L));
        putString(editor, KEY_SOURCE_SUSPEND_ID,
                gameIntent.getStringExtra(Game.EXTRA_SOURCE_SUSPEND_ID));
        putString(editor, KEY_SOURCE_SUSPEND_GAME_ID,
                gameIntent.getStringExtra(Game.EXTRA_SOURCE_SUSPEND_PLAYNITE_GAME_ID));
        putString(editor, Game.EXTRA_EXECUTION_PROFILE_ID,
                gameIntent.getStringExtra(Game.EXTRA_EXECUTION_PROFILE_ID));
        editor.putBoolean(Game.EXTRA_CHILD_PROFILE, gameIntent.getBooleanExtra(
                Game.EXTRA_CHILD_PROFILE, gameIntent.hasExtra(Game.EXTRA_CHILD_SESSION_ID)));
        putString(editor, Game.EXTRA_CHILD_SESSION_ID,
                gameIntent.getStringExtra(Game.EXTRA_CHILD_SESSION_ID));
        putString(editor, Game.EXTRA_CHILD_EXECUTION_PROFILE_ID,
                gameIntent.getStringExtra(Game.EXTRA_CHILD_EXECUTION_PROFILE_ID));
        putString(editor, Game.EXTRA_CHILD_GAME_ID,
                gameIntent.getStringExtra(Game.EXTRA_CHILD_GAME_ID));
        editor.putLong(Game.EXTRA_CHILD_DEADLINE_ELAPSED_MS,
                gameIntent.getLongExtra(Game.EXTRA_CHILD_DEADLINE_ELAPSED_MS, Long.MIN_VALUE));
        editor.putLong(Game.EXTRA_CHILD_POLICY_REVISION,
                gameIntent.getLongExtra(Game.EXTRA_CHILD_POLICY_REVISION, 0L));
        byte[] cert = gameIntent.getByteArrayExtra(Game.EXTRA_SERVER_CERT);
        putString(editor, KEY_SERVER_CERT, cert == null ? null
                : Base64.encodeToString(cert, Base64.NO_WRAP));
        editor.apply();
    }

    /** The Game intent that reconnects {@code current}, or null without a record. */
    public static Intent buildResumeIntent(Context context, PendingSession current) {
        if (current == null) return null;
        Values prefs = current.values;
        Intent intent = new Intent(context, Game.class);
        intent.putExtra(Game.EXTRA_STREAM_SESSION_ID, current.streamSessionId);
        intent.putExtra(Game.EXTRA_CONTROL_GENERATION,
                prefs.getLong(KEY_CONTROL_GENERATION, 0L));
        intent.putExtra(Game.EXTRA_CONTROL_INTENT,
                prefs.getString(Game.EXTRA_CONTROL_INTENT, "reconnect"));
        intent.putExtra(Game.EXTRA_HOST, prefs.getString(KEY_HOST, null));
        intent.putExtra(Game.EXTRA_PORT, prefs.getInt(KEY_PORT, 0));
        intent.putExtra(Game.EXTRA_HTTPS_PORT, prefs.getInt(KEY_HTTPS_PORT, 0));
        intent.putExtra(Game.EXTRA_APP_NAME, prefs.getString(KEY_APP_NAME, null));
        intent.putExtra(Game.EXTRA_STREAM_TARGET_NAME,
                prefs.getString(KEY_STREAM_TARGET_NAME, null));
        intent.putExtra(Game.EXTRA_NEUTRAL_STREAM_TARGET,
                prefs.getBoolean(KEY_NEUTRAL_STREAM_TARGET, false));
        intent.putExtra(Game.EXTRA_APP_ID, prefs.getInt(KEY_APP_ID, 0));
        intent.putExtra(Game.EXTRA_APP_HDR, prefs.getBoolean(KEY_APP_HDR, false));
        intent.putExtra(Game.EXTRA_UNIQUEID, prefs.getString(KEY_UNIQUE_ID, null));
        intent.putExtra(Game.EXTRA_PC_UUID, prefs.getString(KEY_PC_UUID, null));
        intent.putExtra(Game.EXTRA_PROFILE_ID, current.profileId);
        intent.putExtra(Game.EXTRA_PC_NAME, prefs.getString(KEY_PC_NAME, null));
        intent.putExtra(Game.EXTRA_APPLY_PREFERENCE_OVERRIDES,
                prefs.getBoolean(KEY_APPLY_OVERRIDES, false));
        putExtra(intent, Game.EXTRA_QUICK_LAUNCH_APP_KEY,
                prefs.getString(KEY_QUICK_LAUNCH, null));
        int runtimeBitrate = prefs.getInt(KEY_RUNTIME_BITRATE, 0);
        if (runtimeBitrate > 0) intent.putExtra(Game.EXTRA_RUNTIME_BITRATE_KBPS, runtimeBitrate);
        if (prefs.getBoolean(KEY_CONSOLE_LOADING, false)) {
            intent.putExtra(Game.EXTRA_CONSOLE_LOADING, true);
            putExtra(intent, Game.EXTRA_CONSOLE_LOADING_MESSAGE,
                    prefs.getString(KEY_LOADING_MESSAGE, null));
            // A resume is a new transition: its cover counts from now, never from the parked
            // stream's own start (TV 2026-10-16: a resumed cover showed 8 h).
            intent.putExtra(Game.EXTRA_CONSOLE_LOADING_EPOCH, SystemClock.elapsedRealtime());
            intent.putExtra(Game.EXTRA_CONSOLE_LOADING_STEP,
                    prefs.getInt(KEY_LOADING_STEP, 2));
            putExtra(intent, Game.EXTRA_CONSOLE_LOADING_STATUS,
                    prefs.getString(KEY_LOADING_STATUS, null));
            putExtra(intent, Game.EXTRA_CONSOLE_LOADING_ARTWORK,
                    prefs.getString(KEY_LOADING_ARTWORK, null));
            intent.putExtra(Game.EXTRA_CONSOLE_REDUCED_MOTION,
                    prefs.getBoolean(KEY_REDUCED_MOTION, false));
            putExtra(intent, Game.EXTRA_TRANSITION_ID,
                    prefs.getString(KEY_TRANSITION_ID, null));
            String transitionType = reconnectTransitionType(
                    prefs.getString(KEY_TRANSITION_TYPE, null));
            putExtra(intent, Game.EXTRA_TRANSITION_TYPE,
                    transitionType);
            putExtra(intent, Game.EXTRA_TRANSITION_HOST_ID,
                    prefs.getString(KEY_TRANSITION_HOST_ID, null));
            putExtra(intent, Game.EXTRA_TRANSITION_PLAYNITE_GAME_ID,
                    prefs.getString(KEY_TRANSITION_GAME_ID, null));
            intent.putExtra(Game.EXTRA_TRANSITION_CREATED_AT,
                    prefs.getLong(KEY_TRANSITION_CREATED_AT, 0L));
        }
        putExtra(intent, Game.EXTRA_SOURCE_SUSPEND_ID,
                prefs.getString(KEY_SOURCE_SUSPEND_ID, null));
        putExtra(intent, Game.EXTRA_SOURCE_SUSPEND_PLAYNITE_GAME_ID,
                prefs.getString(KEY_SOURCE_SUSPEND_GAME_ID, null));
        putExtra(intent, Game.EXTRA_EXECUTION_PROFILE_ID,
                prefs.getString(Game.EXTRA_EXECUTION_PROFILE_ID, null));
        intent.putExtra(Game.EXTRA_CHILD_PROFILE, prefs.getBoolean(Game.EXTRA_CHILD_PROFILE,
                prefs.getString(Game.EXTRA_CHILD_SESSION_ID, null) != null));
        putExtra(intent, Game.EXTRA_CHILD_SESSION_ID,
                prefs.getString(Game.EXTRA_CHILD_SESSION_ID, null));
        putExtra(intent, Game.EXTRA_CHILD_EXECUTION_PROFILE_ID,
                prefs.getString(Game.EXTRA_CHILD_EXECUTION_PROFILE_ID, null));
        putExtra(intent, Game.EXTRA_CHILD_GAME_ID,
                prefs.getString(Game.EXTRA_CHILD_GAME_ID, null));
        intent.putExtra(Game.EXTRA_CHILD_DEADLINE_ELAPSED_MS,
                prefs.getLong(Game.EXTRA_CHILD_DEADLINE_ELAPSED_MS, Long.MIN_VALUE));
        intent.putExtra(Game.EXTRA_CHILD_POLICY_REVISION,
                prefs.getLong(Game.EXTRA_CHILD_POLICY_REVISION, 0L));
        String cert = prefs.getString(KEY_SERVER_CERT, null);
        if (cert != null && !cert.isEmpty()) {
            intent.putExtra(Game.EXTRA_SERVER_CERT, Base64.decode(cert, Base64.DEFAULT));
        }
        return intent;
    }

    static boolean clearIfMatches(SharedPreferences prefs, String streamSessionId) {
        PendingSession current = read(prefs.getAll());
        if (current == null || !current.matches(streamSessionId)) return false;
        prefs.edit().clear().apply();
        return true;
    }

    static String reconnectTransitionType(String transitionType) {
        return LaunchTransitionType.GAME.name().equals(transitionType)
                ? LaunchTransitionType.GAME_CONNECTION.name() : transitionType;
    }

    /** The stored record; one without a stream session id or profile is not a record. */
    static PendingSession read(Map<String, ?> stored) {
        Values prefs = new Values(stored);
        if (!prefs.getBoolean(KEY_PENDING, false)) return null;
        if (prefs.getString(KEY_PROFILE_ID, null) == null) return null;
        String hostUuid = normalize(prefs.getString(KEY_PC_UUID, null));
        String profileId;
        try {
            profileId = GatewayConnection.normalizeProfileId(
                    prefs.getString(KEY_PROFILE_ID, null));
        } catch (IllegalArgumentException invalidProfile) {
            return null;
        }
        int appId = prefs.getInt(KEY_APP_ID, 0);
        String streamSessionId = normalize(prefs.getString(KEY_STREAM_SESSION_ID, null));
        if (streamSessionId.isEmpty()) return null;
        return new PendingSession(streamSessionId, hostUuid, profileId, appId,
                prefs.getString(KEY_TRANSITION_GAME_ID, null),
                prefs.getBoolean(KEY_AUTO_RESUME, true),
                prefs.getLong(KEY_CREATED_AT, 0L), prefs.getLong(KEY_UPDATED_AT, 0L), prefs);
    }

    static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    static String requireId(String value) {
        String normalized = normalize(value);
        if (normalized.isEmpty() || normalized.length() > 128) {
            throw new IllegalArgumentException("Invalid stream session ID");
        }
        return normalized;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim();
    }

    private static void putString(SharedPreferences.Editor editor, String key, String value) {
        if (value == null) editor.remove(key);
        else editor.putString(key, value);
    }

    private static void putExtra(Intent intent, String key, String value) {
        if (value != null) intent.putExtra(key, value);
    }
}
