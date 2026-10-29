package dev.dsh.agent;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.webkit.MimeTypeMap;

import java.io.File;
import java.util.List;

public final class AgentFileProvider extends ContentProvider {
    @Override
    public boolean onCreate() {
        return true;
    }

    private File file(Uri uri) throws Exception {
        List<String> parts = uri.getPathSegments();
        if (parts.size() < 2) throw new SecurityException("missing file path");
        String root = parts.get(0);
        if (!root.equals("dsh") && !root.equals("download")) throw new SecurityException("path is not allowed");
        StringBuilder relative = new StringBuilder();
        for (int i = 1; i < parts.size(); i++) {
            if (i > 1) relative.append('/');
            relative.append(parts.get(i));
        }
        File base = new File(Environment.getExternalStorageDirectory(), root.equals("dsh") ? ".dsh" : "Download");
        File file = SafePaths.resolve(base, relative.toString(), true);
        if (!file.isFile()) throw new SecurityException("not a regular file");
        return file;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws java.io.FileNotFoundException {
        if (!"r".equals(mode)) throw new SecurityException("read only");
        try {
            return ParcelFileDescriptor.open(file(uri), ParcelFileDescriptor.MODE_READ_ONLY);
        } catch (java.io.FileNotFoundException e) {
            throw e;
        } catch (Exception e) {
            throw new SecurityException("invalid shared path");
        }
    }

    @Override
    public String getType(Uri uri) {
        String extension = MimeTypeMap.getFileExtensionFromUrl(uri.toString());
        String type = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension);
        return type == null ? "application/octet-stream" : type;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        try {
            File file = file(uri);
            MatrixCursor cursor = new MatrixCursor(new String[]{"_display_name", "_size"});
            cursor.addRow(new Object[]{file.getName(), file.length()});
            return cursor;
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        throw new UnsupportedOperationException("read only");
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException("read only");
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException("read only");
    }
}
