package com.limelight;

import android.content.Intent;
import android.net.Uri;

/**
 * Public, versioned launch contract for starting a saved host or application.
 *
 * External callers should use either ACTION_STREAM with the public extras below,
 * or a moonlightx://stream/v1 deep link. The result is normalized to the legacy
 * extras consumed by ShortcutTrampoline so both entry points share the same WoL,
 * host polling, pairing validation, and stream launch path.
 */
public final class PublicStreamIntent {
    public static final String ACTION_STREAM = "com.limelight.action.STREAM";
    public static final String ACTION_OPEN_SETTINGS = "com.limelight.action.OPEN_SETTINGS";
    public static final String ACTION_DISCONNECT_STREAM = "com.limelight.action.DISCONNECT_STREAM";
    public static final String ACTION_QUIT_STREAM_APP = "com.limelight.action.QUIT_STREAM_APP";

    public static final String EXTRA_HOST_UUID = "com.limelight.extra.HOST_UUID";
    public static final String EXTRA_HOST_NAME = "com.limelight.extra.HOST_NAME";
    public static final String EXTRA_APP_ID = "com.limelight.extra.APP_ID";
    public static final String EXTRA_APP_NAME = "com.limelight.extra.APP_NAME";
    public static final String EXTRA_QUICK_LAUNCH = "com.limelight.extra.QUICK_LAUNCH";
    public static final String EXTRA_EXTERNAL_FRONTEND = "com.limelight.extra.EXTERNAL_FRONTEND";
    public static final String EXTRA_EXTERNAL_FRONTEND_PACKAGE = "com.limelight.extra.EXTERNAL_FRONTEND_PACKAGE";
    public static final String EXTRA_EXTERNAL_FRONTEND_MESSAGE = "com.limelight.extra.EXTERNAL_FRONTEND_MESSAGE";
    public static final String EXTRA_EXTERNAL_FRONTEND_ANIMATION_EPOCH = "com.limelight.extra.EXTERNAL_FRONTEND_ANIMATION_EPOCH";
    public static final String EXTRA_EXTERNAL_FRONTEND_REDUCED_MOTION = "com.limelight.extra.EXTERNAL_FRONTEND_REDUCED_MOTION";
    public static final String EXTRA_EXTERNAL_FRONTEND_READINESS_REQUIRED = "com.limelight.extra.EXTERNAL_FRONTEND_READINESS_REQUIRED";
    public static final String EXTRA_HOST_GATEWAY_ENDPOINT = "com.limelight.extra.HOST_GATEWAY_ENDPOINT";
    public static final String EXTRA_HOST_GATEWAY_TOKEN = "com.limelight.extra.HOST_GATEWAY_TOKEN";
    public static final String EXTRA_HOST_GATEWAY_CERTIFICATE = "com.limelight.extra.HOST_GATEWAY_CERTIFICATE";
    public static final String EXTRA_DISCORD_PROFILE_ID = "com.limelight.extra.DISCORD_PROFILE_ID";

    public static final String URI_SCHEME = "moonlightx";
    public static final String URI_HOST = "stream";
    public static final String URI_VERSION_PATH = "/v1";

    private PublicStreamIntent() {}

    public static Intent normalize(Intent source) {
        Intent normalized = new Intent(source);
        Uri uri = source.getData();

        if (isSupportedDeepLink(uri)) {
            putIfPresent(normalized, EXTRA_HOST_UUID, uri.getQueryParameter("host_uuid"));
            putIfPresent(normalized, EXTRA_HOST_NAME, uri.getQueryParameter("host_name"));
            putIfPresent(normalized, EXTRA_APP_ID, uri.getQueryParameter("app_id"));
            putIfPresent(normalized, EXTRA_APP_NAME, uri.getQueryParameter("app_name"));
            putIfPresent(normalized, EXTRA_QUICK_LAUNCH, uri.getQueryParameter("quick_launch"));
        }

        if (ACTION_STREAM.equals(source.getAction()) || isSupportedDeepLink(uri)) {
            copyPublicExtra(normalized, EXTRA_HOST_UUID, AppView.UUID_EXTRA);
            copyPublicExtra(normalized, EXTRA_HOST_NAME, AppView.NAME_EXTRA);
            copyPublicExtra(normalized, EXTRA_APP_ID, Game.EXTRA_APP_ID);
            copyPublicExtra(normalized, EXTRA_APP_NAME, Game.EXTRA_APP_NAME);
            copyPublicExtra(normalized, EXTRA_QUICK_LAUNCH, ShortcutTrampoline.EXTRA_QUICK_LAUNCH_NAME);
        }

        return normalized;
    }

    /**
     * Copies the shell-launch contract to the internal streaming Activity. When enabled,
     * Moonlight must not place PcView behind the stream. This allows the external frontend
     * to become visible again as soon as the stream task finishes.
     */
    public static Intent copyFrontendContract(Intent source, Intent target) {
        if (source.getBooleanExtra(EXTRA_EXTERNAL_FRONTEND, false)) {
            target.putExtra(EXTRA_EXTERNAL_FRONTEND, true);
            String packageName = source.getStringExtra(EXTRA_EXTERNAL_FRONTEND_PACKAGE);
            if (packageName != null && !packageName.isEmpty()) {
                target.putExtra(EXTRA_EXTERNAL_FRONTEND_PACKAGE, packageName);
            }
            String message = source.getStringExtra(EXTRA_EXTERNAL_FRONTEND_MESSAGE);
            if (message != null && !message.isEmpty()) {
                target.putExtra(EXTRA_EXTERNAL_FRONTEND_MESSAGE, message);
            }
            long animationEpoch = source.getLongExtra(EXTRA_EXTERNAL_FRONTEND_ANIMATION_EPOCH, 0L);
            if (animationEpoch > 0L) {
                target.putExtra(EXTRA_EXTERNAL_FRONTEND_ANIMATION_EPOCH, animationEpoch);
            }
            target.putExtra(EXTRA_EXTERNAL_FRONTEND_REDUCED_MOTION,
                    source.getBooleanExtra(EXTRA_EXTERNAL_FRONTEND_REDUCED_MOTION, false));
            target.putExtra(EXTRA_EXTERNAL_FRONTEND_READINESS_REQUIRED,
                    source.getBooleanExtra(EXTRA_EXTERNAL_FRONTEND_READINESS_REQUIRED, false));
        }
        copyStringExtra(source, target, EXTRA_HOST_GATEWAY_ENDPOINT);
        copyStringExtra(source, target, EXTRA_HOST_GATEWAY_TOKEN);
        copyStringExtra(source, target, EXTRA_HOST_GATEWAY_CERTIFICATE);
        copyStringExtra(source, target, EXTRA_DISCORD_PROFILE_ID);
        return target;
    }

    public static boolean isExternalFrontend(Intent intent) {
        return intent != null && intent.getBooleanExtra(EXTRA_EXTERNAL_FRONTEND, false);
    }

    public static String getExternalFrontendPackage(Intent intent) {
        return intent != null ? intent.getStringExtra(EXTRA_EXTERNAL_FRONTEND_PACKAGE) : null;
    }

    private static boolean isSupportedDeepLink(Uri uri) {
        return uri != null &&
                URI_SCHEME.equalsIgnoreCase(uri.getScheme()) &&
                URI_HOST.equalsIgnoreCase(uri.getHost()) &&
                URI_VERSION_PATH.equals(uri.getPath());
    }

    private static void putIfPresent(Intent intent, String key, String value) {
        if (value != null && !value.isEmpty()) {
            intent.putExtra(key, value);
        }
    }

    private static void copyPublicExtra(Intent intent, String publicKey, String internalKey) {
        String value = intent.getStringExtra(publicKey);
        if (value != null && !value.isEmpty()) {
            intent.putExtra(internalKey, value);
        }
    }

    private static void copyStringExtra(Intent source, Intent target, String key) {
        String value = source.getStringExtra(key);
        if (value != null && !value.isEmpty()) {
            target.putExtra(key, value);
        }
    }
}
