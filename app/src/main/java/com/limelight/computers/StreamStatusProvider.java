package com.limelight.computers;

import com.limelight.Game;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;

public final class StreamStatusProvider extends ContentProvider {
    public static final String[] COLUMNS = {
            "state", "stage", "host", "computer", "app", "bitrate_kbps",
            "width", "height", "fps", "hdr", "error_code", "activity_alive",
            "started_at", "updated_at"
    };

    @Override public boolean onCreate() { return true; }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
                        String[] selectionArgs, String sortOrder) {
        if (getContext() == null || !StreamStatusStore.PATH.equals(uri.getLastPathSegment())) {
            throw new IllegalArgumentException("Unknown stream status URI: " + uri);
        }
        SharedPreferences prefs = StreamStatusStore.preferences(getContext());
        String[] columns = projection != null && projection.length > 0 ? projection : COLUMNS;
        MatrixCursor cursor = new MatrixCursor(columns, 1);
        MatrixCursor.RowBuilder row = cursor.newRow();
        for (String column : columns) row.add(column, value(prefs, column));
        cursor.setNotificationUri(getContext().getContentResolver(), StreamStatusStore.uri(getContext()));
        return cursor;
    }

    private Object value(SharedPreferences prefs, String column) {
        switch (column) {
            case "state": return prefs.getString(column, StreamStatusStore.STATE_IDLE);
            case "stage":
            case "host":
            case "computer":
            case "app": return prefs.getString(column, "");
            case "hdr": return prefs.getBoolean(column, false) ? 1 : 0;
            case "activity_alive": return Game.hasActiveStream() ? 1 : 0;
            case "started_at":
            case "updated_at": return prefs.getLong(column, 0L);
            default: return prefs.getInt(column, 0);
        }
    }

    @Override public String getType(Uri uri) { return "vnd.android.cursor.item/vnd.com.limelight.stream-status"; }
    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException("Read-only"); }
    @Override public int delete(Uri uri, String selection, String[] selectionArgs) { throw new UnsupportedOperationException("Read-only"); }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) { throw new UnsupportedOperationException("Read-only"); }
}
