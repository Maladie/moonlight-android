package com.limelight.console;

import com.limelight.gateway.GatewayConnection;

import android.content.Context;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.concurrent.ConcurrentHashMap;

/** Content-keyed disk cache for artwork returned through the pinned Gateway. */
final class PlayniteArtworkCache {
    private static final ConcurrentHashMap<String, Object> FETCH_LOCKS = new ConcurrentHashMap<>();
    private final File directory;

    PlayniteArtworkCache(Context context) {
        directory = new File(context.getCacheDir(), "playnite-artwork");
    }

    File get(String hostUuid, String gameId, String kind, String version) {
        return get(new HostProfileKey(hostUuid,
                com.limelight.gateway.GatewayConnection.DEFAULT_PROFILE_ID),
                gameId, kind, version);
    }

    File get(HostProfileKey key, String gameId, String kind, String version) {
        if (key == null || !valid(key.cacheKey(), gameId, kind)) return null;
        File host = new File(directory, key.cacheKey());
        return new File(host, gameId + "_" + kind + "_" + digest(version) + ".img");
    }

    File fetch(HostGatewayClient client, GatewayConnection connection,
               String hostUuid, String gameId, String kind, String version) throws IOException {
        return fetch(client, connection,
                new HostProfileKey(hostUuid, connection.profileId()),
                gameId, kind, version);
    }

    File fetch(HostGatewayClient client, GatewayConnection connection,
               HostProfileKey key, String gameId, String kind,
               String version) throws IOException {
        File target = get(key, gameId, kind, version);
        if (target == null) throw new IOException("Invalid artwork cache key");
        String lockKey = target.getAbsolutePath();
        Object lock = FETCH_LOCKS.computeIfAbsent(lockKey, ignored -> new Object());
        try {
            synchronized (lock) {
                if (target.isFile() && target.length() > 0) return target;
                File parent = target.getParentFile();
                if (!parent.isDirectory() && !parent.mkdirs() && !parent.isDirectory()) {
                    throw new IOException("Unable to create artwork cache");
                }
                byte[] bytes = client.getPlayniteArtwork(connection, gameId, kind);
                File temporary = new File(parent, target.getName() + ".tmp");
                try (BufferedOutputStream output =
                             new BufferedOutputStream(new FileOutputStream(temporary))) {
                    output.write(bytes);
                }
                if (!temporary.renameTo(target)) {
                    temporary.delete();
                    throw new IOException("Unable to publish Playnite artwork");
                }
                return target;
            }
        } finally {
            FETCH_LOCKS.remove(lockKey, lock);
        }
    }

    private static boolean valid(String hostUuid, String gameId, String kind) {
        return hostUuid != null && hostUuid.matches("[A-Za-z0-9._-]{1,128}") &&
                HostGatewayClient.isPlayniteId(gameId) &&
                ("cover".equals(kind) || "background".equals(kind)
                        || "hero".equals(kind) || "icon".equals(kind));
    }

    private static String digest(String value) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256").digest(
                    (value == null ? "" : value).getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder();
            for (int index = 0; index < 8; index++) {
                result.append(String.format(java.util.Locale.US, "%02x", bytes[index]));
            }
            return result.toString();
        } catch (NoSuchAlgorithmException impossible) {
            return "default";
        }
    }
}
