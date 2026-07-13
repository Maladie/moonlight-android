package com.limelight.computers;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;

public final class StreamStatusStore {
    static final String PREFS = "PublicStreamStatus";
    public static final String PATH = "current";

    public static final String STATE_IDLE = "idle";
    public static final String STATE_CONNECTING = "connecting";
    public static final String STATE_STREAMING = "streaming";
    public static final String STATE_RECONNECTING = "reconnecting";
    public static final String STATE_ENDED = "ended";
    public static final String STATE_ERROR = "error";

    private StreamStatusStore() {}

    public static void begin(Context context, String host, String computer, String app,
                             int bitrateKbps, int width, int height, int fps, boolean hdr) {
        preferences(context).edit()
                .putString("state", STATE_CONNECTING)
                .putString("stage", "initializing")
                .putString("host", safe(host))
                .putString("computer", safe(computer))
                .putString("app", safe(app))
                .putInt("bitrate_kbps", bitrateKbps)
                .putInt("width", width)
                .putInt("height", height)
                .putInt("fps", fps)
                .putBoolean("hdr", hdr)
                .putInt("error_code", 0)
                .putLong("updated_at", System.currentTimeMillis())
                .apply();
        notifyChanged(context);
    }

    public static void update(Context context, String state, String stage, int errorCode) {
        preferences(context).edit()
                .putString("state", state)
                .putString("stage", safe(stage))
                .putInt("error_code", errorCode)
                .putLong("updated_at", System.currentTimeMillis())
                .apply();
        notifyChanged(context);
    }

    public static void updateBitrate(Context context, int bitrateKbps) {
        preferences(context).edit()
                .putInt("bitrate_kbps", bitrateKbps)
                .putLong("updated_at", System.currentTimeMillis())
                .apply();
        notifyChanged(context);
    }

    static SharedPreferences preferences(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static Uri uri(Context context) {
        return Uri.parse("content://streamstatus." + context.getPackageName() + "/" + PATH);
    }

    private static void notifyChanged(Context context) {
        context.getContentResolver().notifyChange(uri(context), null);
    }

    private static String safe(String value) {
        return value != null ? value : "";
    }
}
