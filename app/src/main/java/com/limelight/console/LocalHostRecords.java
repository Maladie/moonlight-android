package com.limelight.console;

import android.content.SharedPreferences;

import java.io.File;
import java.util.Locale;
import java.util.Map;

/**
 * Removal of what this device keeps about one host, used when MoonWaker forgets it ("Usuń"
 * and "Odparuj hosta"). Purely local: nothing here contacts the host.
 */
public final class LocalHostRecords {
    /** Removes every entry whose key names the host or whose value is the host ("last host"). */
    public static void sweep(SharedPreferences preferences, String hostUuid) {
        String host = id(hostUuid);
        if (host.isEmpty()) return;
        SharedPreferences.Editor editor = preferences.edit();
        boolean changed = false;
        for (Map.Entry<String, ?> entry : preferences.getAll().entrySet()) {
            if (names(entry.getKey(), host) || isHost(entry.getValue(), host)) {
                editor.remove(entry.getKey());
                changed = true;
            }
        }
        if (changed) editor.apply();
    }

    /** True when any entry names the host; a single-record store then drops the whole record. */
    public static boolean mentions(SharedPreferences preferences, String hostUuid) {
        String host = id(hostUuid);
        if (host.isEmpty()) return false;
        for (Map.Entry<String, ?> entry : preferences.getAll().entrySet()) {
            if (names(entry.getKey(), host) || isHost(entry.getValue(), host)) return true;
        }
        return false;
    }

    /** Deletes the host's per-profile entries ({@code <host>__<profile>...}) of a disk cache. */
    static void deleteProfileEntries(File directory, String hostUuid) {
        String host = id(hostUuid);
        File[] entries = directory == null || host.isEmpty() ? null : directory.listFiles();
        if (entries == null) return;
        for (File entry : entries) {
            if (entry.getName().toLowerCase(Locale.ROOT).startsWith(host + "__")) delete(entry);
        }
    }

    private static void delete(File file) {
        File[] children = file.listFiles();
        if (children != null) for (File child : children) delete(child);
        file.delete();
    }

    private static boolean names(String key, String host) {
        return key != null && key.toLowerCase(Locale.ROOT).contains(host);
    }

    private static boolean isHost(Object value, String host) {
        return value instanceof String && id((String) value).equals(host);
    }

    private static String id(String hostUuid) {
        return hostUuid == null ? "" : hostUuid.trim().toLowerCase(Locale.ROOT);
    }

    private LocalHostRecords() { }
}
