package com.aicode.powerbar;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;

public class PowerConfigProvider extends ContentProvider {

    static final String AUTHORITY = "com.aicode.powerbar.config";

    static Uri uri() {
        return Uri.parse("content://" + AUTHORITY);
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
                        String[] selectionArgs, String sortOrder) {
        MatrixCursor cursor = new MatrixCursor(
                new String[]{"enabled", "charging_only", "interval_ms"});
        if (getContext() != null) {
            cursor.addRow(new Object[]{
                    Prefs.enabled(getContext()) ? 1 : 0,
                    Prefs.chargingOnly(getContext()) ? 1 : 0,
                    Prefs.intervalMs(getContext())});
        }
        return cursor;
    }

    @Override
    public String getType(Uri uri) {
        return null;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }
}
