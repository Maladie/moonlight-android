package com.limelight.console;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Last-known-good Playnite library, isolated by Moonlight host UUID. */
final class PlayniteLibraryCache {
    static final class Entry {
        final List<PlayniteLibraryGame> games;
        final long savedAt;
        final String revision;
        final String apiVersion;

        Entry(List<PlayniteLibraryGame> games, long savedAt,
              String revision, String apiVersion) {
            this.games = Collections.unmodifiableList(new ArrayList<>(games));
            this.savedAt = savedAt;
            this.revision = revision == null ? "" : revision;
            this.apiVersion = apiVersion == null ? "" : apiVersion;
        }
    }

    private final File directory;

    PlayniteLibraryCache(Context context) {
        directory = new File(context.getFilesDir(), "playnite-library");
    }

    Entry read(String hostUuid) {
        File file = fileFor(hostUuid);
        if (file == null || !file.isFile()) return null;
        try (BufferedInputStream input = new BufferedInputStream(new FileInputStream(file));
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (output.size() + read > 32 * 1024 * 1024) return null;
                output.write(buffer, 0, read);
            }
            JSONObject root = new JSONObject(output.toString(StandardCharsets.UTF_8.name()));
            JSONArray values = root.optJSONArray("games");
            List<PlayniteLibraryGame> games = new ArrayList<>();
            if (values != null) {
                for (int index = 0; index < values.length(); index++) {
                    JSONObject value = values.optJSONObject(index);
                    if (value == null) continue;
                    String id = value.optString("id", "");
                    String name = value.optString("name", "").trim();
                    if (!HostGatewayClient.isPlayniteId(id) || name.isEmpty()) continue;
                    String source = value.optString("source", "").trim();
                    String provider = HostGatewayClient.providerOrLegacy(
                            value.optString("provider", ""), source);
                    String providerGameId = value.optString("provider_game_id", "");
                    boolean exactIdentity = HostGatewayClient.hasExactProviderIdentity(
                            id, provider, providerGameId);
                    String legacyLibraryKey = source.isEmpty() ? "playnite"
                            : source.toLowerCase(java.util.Locale.ROOT);
                    games.add(new PlayniteLibraryGame(id, name,
                            value.optBoolean("installed", false),
                            value.optBoolean("installing", false),
                            value.optBoolean("hidden", false),
                            Math.max(0L, value.optLong("playtime_seconds", 0L)),
                            value.optString("last_activity", ""),
                            value.optString("cover", ""),
                            value.optString("background", ""),
                            value.optString("description", ""),
                            Math.max(0, value.optInt("play_count", 0)),
                            source,
                            value.optString("genres", ""),
                            value.optBoolean("install_requires_attention", false),
                            value.optString("install_attention_reason", ""),
                            value.optString("install_window_title", ""),
                            value.optString("install_launcher", ""),
                            value.optString("operation_state", ""),
                            value.optInt("operation_progress", -1),
                            value.optBoolean("uninstalling", false), "",
                            provider,
                            providerGameId,
                            value.optString("playnite_game_id",
                                    id.matches("(?i)[0-9a-f-]{36}") ? id : ""),
                            value.optString("library_key", legacyLibraryKey),
                            value.optString("library_name",
                                    source.isEmpty() ? "Playnite" : source),
                            exactIdentity && value.optBoolean("can_launch", true),
                            exactIdentity && value.optBoolean("can_install", true),
                            exactIdentity && value.optBoolean("can_uninstall", true)));
                }
            }
            return new Entry(games, root.optLong("saved_at", file.lastModified()),
                    root.optString("revision", ""), root.optString("api_version", ""));
        } catch (IOException | JSONException invalidCache) {
            return null;
        }
    }

    void write(String hostUuid, Entry entry) throws IOException {
        File target = fileFor(hostUuid);
        if (target == null) throw new IOException("Invalid host cache key");
        if (!directory.isDirectory() && !directory.mkdirs() && !directory.isDirectory()) {
            throw new IOException("Unable to create Playnite library cache");
        }
        JSONObject root = new JSONObject();
        JSONArray values = new JSONArray();
        try {
            root.put("saved_at", entry.savedAt);
            root.put("revision", entry.revision);
            root.put("api_version", entry.apiVersion);
            for (PlayniteLibraryGame game : entry.games) {
                JSONObject value = new JSONObject();
                value.put("id", game.playniteGameId);
                value.put("name", game.name);
                value.put("installed", game.installed);
                value.put("installing", game.installing);
                value.put("hidden", game.hidden);
                value.put("playtime_seconds", game.playtimeSeconds);
                value.put("last_activity", game.lastActivity);
                value.put("cover", game.coverKey);
                value.put("background", game.backgroundKey);
                value.put("description", game.description);
                value.put("play_count", game.playCount);
                value.put("source", game.source);
                value.put("provider", game.provider);
                value.put("provider_game_id", game.providerGameId);
                value.put("playnite_game_id", game.metadataPlayniteGameId);
                value.put("library_key", game.libraryKey);
                value.put("library_name", game.libraryName);
                value.put("can_launch", game.canLaunch);
                value.put("can_install", game.canInstall);
                value.put("can_uninstall", game.canUninstall);
                value.put("genres", game.genres);
                value.put("install_requires_attention", game.installRequiresAttention);
                value.put("install_attention_reason", game.installAttentionReason);
                value.put("install_window_title", game.installWindowTitle);
                value.put("install_launcher", game.installLauncher);
                value.put("operation_state", game.operationState);
                value.put("operation_progress", game.operationProgress);
                value.put("uninstalling", game.uninstalling);
                values.put(value);
            }
            root.put("games", values);
        } catch (JSONException impossible) {
            throw new IOException(impossible);
        }
        File temporary = new File(directory, target.getName() + ".tmp");
        byte[] data = root.toString().getBytes(StandardCharsets.UTF_8);
        try (FileOutputStream file = new FileOutputStream(temporary);
             BufferedOutputStream output = new BufferedOutputStream(file)) {
            output.write(data);
            output.flush();
            file.getFD().sync();
        }
        if (target.exists() && !target.delete()) {
            temporary.delete();
            throw new IOException("Unable to replace Playnite library cache");
        }
        if (!temporary.renameTo(target)) {
            temporary.delete();
            throw new IOException("Unable to publish Playnite library cache");
        }
    }

    private File fileFor(String hostUuid) {
        if (hostUuid == null || !hostUuid.matches("[A-Za-z0-9._-]{1,128}")) return null;
        return new File(directory, hostUuid + ".json");
    }
}
