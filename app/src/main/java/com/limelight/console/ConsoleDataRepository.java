package com.limelight.console;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/** Read-only view of Moonlight's existing stores; milestone 1 performs no migration. */
final class ConsoleDataRepository {
    static final class Host {
        final String uuid;
        final String name;
        final String address;
        final int port;
        final String macAddress;
        Host(String uuid, String name, String address) {
            this(uuid, name, address, 0, null);
        }
        Host(String uuid, String name, String address, int port, String macAddress) {
            this.uuid = uuid;
            this.name = name;
            this.address = address;
            this.port = port;
            this.macAddress = macAddress;
        }
    }

    static final class App {
        final int id;
        final String name;
        final Uri posterUri;
        App(int id, String name, Uri posterUri) {
            this.id = id;
            this.name = name;
            this.posterUri = posterUri;
        }
    }

    static final class Session {
        final String state;
        final String stage;
        final String host;
        final String app;
        final int width;
        final int height;
        final int fps;
        final boolean alive;
        Session(String state, String stage, String host, String app,
                int width, int height, int fps, boolean alive) {
            this.state = state;
            this.stage = stage;
            this.host = host;
            this.app = app;
            this.width = width;
            this.height = height;
            this.fps = fps;
            this.alive = alive;
        }
    }

    private final ContentResolver resolver;
    private final String packageName;

    ConsoleDataRepository(Context context) {
        resolver = context.getContentResolver();
        packageName = context.getPackageName();
    }

    List<Host> hosts() {
        Uri uri = Uri.parse("content://hosts." + packageName + "/hosts");
        List<Host> result = new ArrayList<>();
        try (Cursor cursor = resolver.query(uri, null, null, null, null)) {
            if (cursor != null) while (cursor.moveToNext()) {
                String uuid = text(cursor, "uuid");
                String name = text(cursor, "name");
                String local = text(cursor, "local_address");
                String manual = text(cursor, "manual_address");
                if (uuid != null && name != null) {
                    boolean useLocal = local != null && !local.isEmpty();
                    result.add(new Host(uuid, name, useLocal ? local : manual,
                            number(cursor, useLocal ? "local_port" : "manual_port", 0),
                            text(cursor, "mac_address")));
                }
            }
        }
        result.sort(Comparator.comparing(host -> host.name.toLowerCase(java.util.Locale.ROOT)));
        return result;
    }

    List<App> apps(Host host) {
        if (host == null) return Collections.emptyList();
        Uri uri = Uri.parse("content://apps." + packageName + "/apps/" + Uri.encode(host.uuid));
        List<App> result = new ArrayList<>();
        try (Cursor cursor = resolver.query(uri, null, null, null, null)) {
            if (cursor != null) while (cursor.moveToNext()) {
                int id = number(cursor, "app_id", -1);
                String name = text(cursor, "name");
                String poster = text(cursor, "poster_uri");
                if (id >= 0 && name != null) {
                    result.add(new App(id, name, poster != null ? Uri.parse(poster) : null));
                }
            }
        }
        result.sort(Comparator.comparing(app -> app.name.toLowerCase(java.util.Locale.ROOT)));
        return result;
    }

    Session session() {
        Uri uri = Uri.parse("content://streamstatus." + packageName + "/current");
        try (Cursor cursor = resolver.query(uri, null, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                return new Session(text(cursor, "state"), text(cursor, "stage"),
                        text(cursor, "host"), text(cursor, "app"),
                        number(cursor, "width", 0), number(cursor, "height", 0),
                        number(cursor, "fps", 0), number(cursor, "activity_alive", 0) != 0);
            }
        }
        return null;
    }

    private static String text(Cursor cursor, String column) {
        int index = cursor.getColumnIndex(column);
        return index >= 0 && !cursor.isNull(index) ? cursor.getString(index) : null;
    }

    private static int number(Cursor cursor, String column, int fallback) {
        int index = cursor.getColumnIndex(column);
        return index >= 0 && !cursor.isNull(index) ? cursor.getInt(index) : fallback;
    }
}
