package com.limelight.computers;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.util.Log;

import com.limelight.nvstream.http.ComputerDetails;

public final class SavedHostsProvider extends ContentProvider {
    private static final String TAG = "SavedHostsProvider";
    public static final String[] COLUMNS = {
            "uuid", "name", "local_address", "local_port", "manual_address",
            "manual_port", "mac_address"
    };

    @Override public boolean onCreate() { return true; }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
                        String[] selectionArgs, String sortOrder) {
        if (uri == null || !"hosts".equals(uri.getLastPathSegment())) {
            throw new IllegalArgumentException("Unsupported URI: " + uri);
        }
        MatrixCursor cursor = new MatrixCursor(COLUMNS);
        ComputerDatabaseManager database = new ComputerDatabaseManager(getContext());
        try {
            java.util.List<ComputerDetails> hosts = database.getAllComputers();
            Log.i(TAG, "Returning " + hosts.size() + " saved host(s) for " + uri);
            for (ComputerDetails host : hosts) {
                cursor.addRow(new Object[] {
                        host.uuid != null ? host.uuid.toString() : null,
                        host.name,
                        address(host.localAddress),
                        port(host.localAddress),
                        address(host.manualAddress),
                        port(host.manualAddress),
                        host.macAddress
                });
            }
        } finally {
            database.close();
        }
        cursor.setNotificationUri(getContext().getContentResolver(), uri);
        return cursor;
    }

    private static String address(ComputerDetails.AddressTuple tuple) {
        return tuple != null ? tuple.address : null;
    }

    private static Integer port(ComputerDetails.AddressTuple tuple) {
        return tuple != null ? tuple.port : null;
    }

    @Override public String getType(Uri uri) { return "vnd.android.cursor.dir/vnd.moonlight.saved-host"; }
    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException("Read only"); }
    @Override public int delete(Uri uri, String selection, String[] selectionArgs) { throw new UnsupportedOperationException("Read only"); }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) { throw new UnsupportedOperationException("Read only"); }
}
