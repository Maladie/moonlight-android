package com.limelight.computers;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.util.Log;

import com.limelight.PosterContentProvider;
import com.limelight.nvstream.http.NvApp;
import com.limelight.nvstream.http.NvHTTP;
import com.limelight.utils.CacheHelper;

import java.io.StringReader;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/** Exposes Moonlight's cached app list to same-signature TV frontends. */
public final class SavedAppsProvider extends ContentProvider {
    private static final String TAG = "SavedAppsProvider";
    public static final String[] COLUMNS = {"app_id", "name", "hdr_supported", "poster_uri"};

    @Override public boolean onCreate() { return true; }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
                        String[] selectionArgs, String sortOrder) {
        List<String> segments = uri != null ? uri.getPathSegments() : Collections.emptyList();
        if (segments.size() != 2 || !"apps".equals(segments.get(0))) {
            throw new IllegalArgumentException("Unsupported URI: " + uri);
        }

        String hostUuid = segments.get(1);
        MatrixCursor cursor = new MatrixCursor(COLUMNS);
        try {
            String raw = CacheHelper.readInputStreamToString(
                    CacheHelper.openCacheFileForInput(getContext().getCacheDir(), "applist", hostUuid));
            List<NvApp> apps = NvHTTP.getAppListByReader(new StringReader(raw));
            apps.sort(Comparator.comparing(app -> app.getAppName().toLowerCase(java.util.Locale.ROOT)));
            for (NvApp app : apps) {
                cursor.addRow(new Object[] {
                        app.getAppId(),
                        app.getAppName(),
                        app.isHdrSupported() ? 1 : 0,
                        PosterContentProvider.createBoxArtUri(hostUuid, String.valueOf(app.getAppId())).toString()
                });
            }
            Log.i(TAG, "Returning " + apps.size() + " cached app(s) for host " + hostUuid);
        } catch (Exception error) {
            Log.w(TAG, "No readable cached app list for host " + hostUuid, error);
        }
        cursor.setNotificationUri(getContext().getContentResolver(), uri);
        return cursor;
    }

    @Override public String getType(Uri uri) { return "vnd.android.cursor.dir/vnd.moonlight.saved-app"; }
    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException("Read only"); }
    @Override public int delete(Uri uri, String selection, String[] selectionArgs) { throw new UnsupportedOperationException("Read only"); }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) { throw new UnsupportedOperationException("Read only"); }
}
