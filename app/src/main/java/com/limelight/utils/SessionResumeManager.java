package com.limelight.utils;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.util.Base64;

import com.limelight.Game;
import com.limelight.console.transition.LaunchTransitionType;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Persistent reconnect material for exactly one stream lifecycle. */
public final class SessionResumeManager {
    private static final String PREFS_NAME = "SessionResume";
    private static final String KEY_PENDING = "pending";
    private static final String KEY_AUTO_RESUME = "autoResume";
    private static final String KEY_STREAM_SESSION_ID = "streamSessionId";
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
    private static final String KEY_PC_NAME = "pcName";
    private static final String KEY_SERVER_CERT = "serverCert";
    private static final String KEY_QUICK_LAUNCH = "quickLaunchKey";
    private static final String KEY_APPLY_OVERRIDES = "applyOverrides";
    private static final String KEY_RUNTIME_BITRATE = "runtimeBitrate";
    private static final String KEY_CONSOLE_LOADING = "consoleLoading";
    private static final String KEY_LOADING_MESSAGE = "loadingMessage";
    private static final String KEY_LOADING_EPOCH = "loadingEpoch";
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
        public final int appId;
        public final String playniteGameId;
        public final boolean neutralStreamTarget;
        public final boolean autoResume;
        public final long createdAt;
        public final long updatedAt;
        public final boolean legacy;
        private final Values values;

        private PendingSession(String streamSessionId, String hostUuid, int appId,
                               String playniteGameId, boolean autoResume,
                               long createdAt, long updatedAt, boolean legacy,
                               SharedPreferences prefs) {
            this.streamSessionId = streamSessionId;
            this.hostUuid = hostUuid;
            this.appId = appId;
            this.playniteGameId = normalize(playniteGameId);
            this.neutralStreamTarget = prefs.getBoolean(KEY_NEUTRAL_STREAM_TARGET, false);
            this.autoResume = autoResume;
            this.createdAt = createdAt;
            this.updatedAt = updatedAt;
            this.legacy = legacy;
            this.values = new Values(prefs.getAll());
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

    public static synchronized void save(Context context, Intent gameIntent,
                                         String streamSessionId) {
        save(context, gameIntent, streamSessionId, true);
    }

    /** Persist correlation for process-death recovery without forcing foreground resume. */
    public static synchronized void saveActive(Context context, Intent gameIntent,
                                               String streamSessionId) {
        save(context, gameIntent, streamSessionId, false);
    }

    private static void save(Context context, Intent gameIntent,
                             String streamSessionId, boolean autoResume) {
        String sessionId = requireId(streamSessionId);
        gameIntent.putExtra(Game.EXTRA_STREAM_SESSION_ID, sessionId);
        SharedPreferences prefs = prefs(context);
        PendingSession current = read(prefs);
        long now = System.currentTimeMillis();
        long createdAt = current != null && current.matches(sessionId)
                && current.createdAt > 0L ? current.createdAt : now;
        SharedPreferences.Editor editor = prefs.edit();
        editor.clear();
        editor.putBoolean(KEY_PENDING, true);
        editor.putBoolean(KEY_AUTO_RESUME, autoResume);
        editor.putString(KEY_STREAM_SESSION_ID, sessionId);
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
        editor.putLong(KEY_LOADING_EPOCH,
                gameIntent.getLongExtra(Game.EXTRA_CONSOLE_LOADING_EPOCH, 0L));
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
        byte[] cert = gameIntent.getByteArrayExtra(Game.EXTRA_SERVER_CERT);
        putString(editor, KEY_SERVER_CERT, cert == null ? null
                : Base64.encodeToString(cert, Base64.NO_WRAP));
        editor.apply();
    }

    public static synchronized PendingSession pendingSession(Context context) {
        return read(prefs(context));
    }

    public static synchronized boolean hasPendingSession(Context context) {
        return read(prefs(context)) != null;
    }

    public static synchronized String getPendingPcUuid(Context context) {
        PendingSession pending = read(prefs(context));
        return pending == null ? null : pending.hostUuid;
    }

    public static synchronized int getPendingAppId(Context context) {
        PendingSession pending = read(prefs(context));
        return pending == null ? 0 : pending.appId;
    }

    public static synchronized Intent buildResumeIntent(Context context) {
        return buildResumeIntent(context, read(prefs(context)));
    }

    public static synchronized Intent buildResumeIntent(Context context,
                                                        PendingSession expected) {
        if (expected == null) return null;
        PendingSession current = read(prefs(context));
        if (current == null || !current.matches(expected.streamSessionId)) return null;
        Values prefs = current.values;
        Intent intent = new Intent(context, Game.class);
        intent.putExtra(Game.EXTRA_STREAM_SESSION_ID, current.streamSessionId);
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
            intent.putExtra(Game.EXTRA_CONSOLE_LOADING_EPOCH,
                    prefs.getLong(KEY_LOADING_EPOCH, 0L));
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
        String cert = prefs.getString(KEY_SERVER_CERT, null);
        if (cert != null && !cert.isEmpty()) {
            intent.putExtra(Game.EXTRA_SERVER_CERT, Base64.decode(cert, Base64.DEFAULT));
        }
        return intent;
    }

    public static synchronized boolean clearIfMatches(Context context,
                                                      String streamSessionId) {
        PendingSession current = read(prefs(context));
        if (current == null || !current.matches(streamSessionId)) return false;
        prefs(context).edit().clear().apply();
        return true;
    }

    static String resolveStreamSessionId(String storedId, String hostUuid,
                                         int appId, String uniqueId) {
        String current = normalize(storedId);
        return current.isEmpty() ? legacyStreamSessionId(hostUuid, appId, uniqueId) : current;
    }

    static String legacyStreamSessionId(String hostUuid, int appId, String uniqueId) {
        String material = normalize(hostUuid) + '|' + appId + '|' + normalize(uniqueId);
        return UUID.nameUUIDFromBytes(material.getBytes(StandardCharsets.UTF_8)).toString();
    }

    static String reconnectTransitionType(String transitionType) {
        return LaunchTransitionType.GAME.name().equals(transitionType)
                ? LaunchTransitionType.GAME_CONNECTION.name() : transitionType;
    }

    private static PendingSession read(SharedPreferences prefs) {
        if (!prefs.getBoolean(KEY_PENDING, false)) return null;
        String hostUuid = normalize(prefs.getString(KEY_PC_UUID, null));
        int appId = prefs.getInt(KEY_APP_ID, 0);
        String storedId = normalize(prefs.getString(KEY_STREAM_SESSION_ID, null));
        boolean legacy = storedId.isEmpty();
        String streamSessionId = resolveStreamSessionId(storedId, hostUuid, appId,
                prefs.getString(KEY_UNIQUE_ID, null));
        return new PendingSession(streamSessionId, hostUuid, appId,
                prefs.getString(KEY_TRANSITION_GAME_ID, null),
                prefs.getBoolean(KEY_AUTO_RESUME, true),
                prefs.getLong(KEY_CREATED_AT, 0L), prefs.getLong(KEY_UPDATED_AT, 0L),
                legacy, prefs);
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    private static String requireId(String value) {
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
