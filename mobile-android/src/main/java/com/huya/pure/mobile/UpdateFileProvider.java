package com.huya.pure.mobile;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;

/**
 * Lightweight read-only FileProvider scoped strictly to <cacheDir>/updates/*.apk
 * for zero-dependency in-app APK installation on Android 8–16.
 */
public final class UpdateFileProvider extends ContentProvider {

    public static final String AUTHORITY = "com.huya.pure.mobile.update_provider";
    public static final String UPDATES_SUBDIR = "updates";
    private static final String APK_MIME = "application/vnd.android.package-archive";

    public static Uri getUriForApk(File apkFile) {
        return new Uri.Builder()
                .scheme("content")
                .authority(AUTHORITY)
                .appendPath(UPDATES_SUBDIR)
                .appendPath(apkFile.getName())
                .build();
    }

    public static File getUpdatesDir(Context context) {
        File dir = new File(context.getCacheDir(), UPDATES_SUBDIR);
        if (!dir.exists()) {
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
        }
        return dir;
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    private File resolveSafeApkFile(Uri uri) throws FileNotFoundException {
        Context ctx = getContext();
        if (ctx == null || uri == null) {
            throw new FileNotFoundException("Invalid context or URI");
        }
        String fileName = uri.getLastPathSegment();
        if (fileName == null
                || fileName.isEmpty()
                || fileName.contains("/")
                || fileName.contains("\\")
                || fileName.contains("..")
                || !fileName.endsWith(".apk")) {
            throw new FileNotFoundException("Disallowed file name: " + fileName);
        }
        File updatesDir = getUpdatesDir(ctx);
        File target = new File(updatesDir, fileName);
        try {
            String dirCanonical = updatesDir.getCanonicalPath() + File.separator;
            String fileCanonical = target.getCanonicalPath();
            if (!fileCanonical.startsWith(dirCanonical)) {
                throw new FileNotFoundException("Path traversal blocked");
            }
        } catch (IOException e) {
            throw new FileNotFoundException("Canonical path check failed");
        }
        if (!target.exists() || !target.isFile()) {
            throw new FileNotFoundException("APK file not found: " + fileName);
        }
        return target;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        try {
            File file = resolveSafeApkFile(uri);
            String[] cols = (projection != null && projection.length > 0)
                    ? projection
                    : new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE};
            MatrixCursor cursor = new MatrixCursor(cols, 1);
            Object[] row = new Object[cols.length];
            for (int i = 0; i < cols.length; i++) {
                if (OpenableColumns.DISPLAY_NAME.equals(cols[i])) {
                    row[i] = file.getName();
                } else if (OpenableColumns.SIZE.equals(cols[i])) {
                    row[i] = file.length();
                } else {
                    row[i] = null;
                }
            }
            cursor.addRow(row);
            return cursor;
        } catch (FileNotFoundException e) {
            return null;
        }
    }

    @Override
    public String getType(Uri uri) {
        return APK_MIME;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (mode != null && !mode.startsWith("r")) {
            throw new SecurityException("Read-only provider");
        }
        File file = resolveSafeApkFile(uri);
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        throw new UnsupportedOperationException("Read-only provider");
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException("Read-only provider");
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException("Read-only provider");
    }
}
