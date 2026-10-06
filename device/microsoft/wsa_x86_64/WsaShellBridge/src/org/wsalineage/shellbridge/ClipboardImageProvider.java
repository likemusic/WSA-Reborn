/*
 * Provider for images copied in Windows: ClipData cannot carry image bytes,
 * only a content:// link, so the PNG from shared memory is put into the bridge's private directory and
 * served from here. Read permission for the app that pastes is granted by ClipboardService itself —
 * via grantUriPermissions="true" in the manifest (androidx FileProvider, used by
 * Microsoft's UserApp.apk, works the same way; we do not need androidx in a system build).
 *
 * The provider is EXPORTED, although it looks internal: a permission on its URI cannot be granted —
 * UriGrantsManagerService does not grant on behalf of uid 1000 ("use startActivityAsCaller()
 * instead"), and the bridge runs under the system uid. Why this is not a hole — see the comment on <provider>
 * in AndroidManifest.xml.
 */
package org.wsalineage.shellbridge;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import android.util.Slog;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.UUID;

public final class ClipboardImageProvider extends ContentProvider {
    private static final String TAG = "WsaShellBridge.Clipboard";

    public static final String AUTHORITY = "org.wsalineage.shellbridge.clipboard";
    public static final String MIME_TYPE = "image/png";

    private static final String DIR_NAME = "clipboard-images";
    /** How many recent images we keep: only what is currently in the clipboard can be pasted. */
    private static final int KEEP_FILES = 4;

    /** Save a PNG and return a link to it, or null if it was not written. */
    static Uri store(Context context, byte[] png) {
        File dir = dir(context);
        if (dir == null) return null;
        File file = new File(dir, UUID.randomUUID() + ".png");
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(png);
        } catch (IOException e) {
            Slog.e(TAG, "cannot write " + file + ": " + e);
            return null;
        }
        trim(dir, file);
        return new Uri.Builder().scheme("content").authority(AUTHORITY)
                .appendPath(file.getName()).build();
    }

    private static File dir(Context context) {
        File dir = new File(context.getFilesDir(), DIR_NAME);
        if (!dir.isDirectory() && !dir.mkdirs()) {
            Slog.e(TAG, "cannot create " + dir);
            return null;
        }
        return dir;
    }

    /** Delete everything except the few most recent files (the one just written is left alone). */
    private static void trim(File dir, File keep) {
        File[] files = dir.listFiles();
        if (files == null || files.length <= KEEP_FILES) return;
        java.util.Arrays.sort(files, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
        for (int i = KEEP_FILES; i < files.length; i++) {
            if (files[i].equals(keep)) continue;
            if (!files[i].delete()) Slog.w(TAG, "cannot delete " + files[i]);
        }
    }

    private File resolve(Uri uri) throws FileNotFoundException {
        String name = uri.getLastPathSegment();
        if (name == null || name.indexOf('/') >= 0 || name.contains("..")
                || uri.getPathSegments().size() != 1) {
            throw new FileNotFoundException("bad uri " + uri);
        }
        Context context = getContext();
        File dir = context != null ? dir(context) : null;
        if (dir == null) throw new FileNotFoundException("no image directory");
        File file = new File(dir, name);
        if (!file.isFile()) throw new FileNotFoundException(String.valueOf(uri));
        return file;
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (!"r".equals(mode)) throw new FileNotFoundException("read-only provider");
        return ParcelFileDescriptor.open(resolve(uri), ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override
    public String getType(Uri uri) {
        return MIME_TYPE;
    }

    /** Apps ask the pasted URI for its name and size — we answer only that. */
    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs,
            String sortOrder) {
        File file;
        try {
            file = resolve(uri);
        } catch (FileNotFoundException e) {
            return null;
        }
        String[] columns = projection != null ? projection
                : new String[] { OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE };
        MatrixCursor cursor = new MatrixCursor(columns, 1);
        MatrixCursor.RowBuilder row = cursor.newRow();
        for (String column : columns) {
            if (OpenableColumns.DISPLAY_NAME.equals(column)) {
                row.add(file.getName());
            } else if (OpenableColumns.SIZE.equals(column)) {
                row.add(file.length());
            } else {
                row.add(null);
            }
        }
        return cursor;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        throw new UnsupportedOperationException();
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException();
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException();
    }
}
