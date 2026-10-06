/*
 * Windows files for Android apps: content://org.wsalineage.shellbridge.windowsfiles/<GUID>.
 * The bridge puts such links into the clipboard (a file copied in Explorer) and into the
 * "Share" intent from Windows to an Android app. When a link is opened, the bridge asks the host over channel
 * "FileSystem" to place the file in /mnt/shared_tmp/content/<GUID> and serves it read-only.
 * Microsoft does the same with util.WindowsContentProvider from UserApp.apk.
 *
 * The provider is EXPORTED for the same reason as ClipboardImageProvider: the system refuses to grant
 * URI permission on behalf of uid 1000. Whoever knows the GUID can open the file, and the GUID
 * is random and present only in the ClipData or intent the recipient sees anyway.
 *
 * Deviation from the reference: if the GUID has no extension, the type is taken from the real file name
 * reported by the host, rather than defaulting to "*\/*".
 */
package org.wsalineage.shellbridge;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import android.webkit.MimeTypeMap;

import org.wsalineage.shellbridge.proto.FileSystem.ProvideContentFileDetails;

import java.io.File;
import java.io.FileNotFoundException;
import java.util.Locale;

public final class WindowsFileProvider extends ContentProvider {
    public static final String AUTHORITY = "org.wsalineage.shellbridge.windowsfiles";
    private static final String ANY_TYPE = "*/*";

    static Uri uriFor(String guid) {
        return new Uri.Builder().scheme("content").authority(AUTHORITY).appendPath(guid).build();
    }

    /**
     * Type of a Windows file. The host names the file by a GUID with the original file's extension, so we
     * look at that; if there is no extension, we ask the host for the real name. "*\/*" if that fails.
     */
    static String mimeTypeOf(String guid) {
        String type = mimeTypeOfName(guid);
        if (type != null) return type;
        FileSystemBridge bridge = FileSystemBridge.get();
        ProvideContentFileDetails d = bridge != null ? bridge.details(guid) : null;
        type = d != null && d.hasFileName() ? mimeTypeOfName(d.getFileName()) : null;
        return type != null ? type : ANY_TYPE;
    }

    private static String mimeTypeOfName(String name) {
        int dot = name.lastIndexOf('.');
        if (dot < 0) return null;
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(
                name.substring(dot + 1).toLowerCase(Locale.ROOT));
    }

    private static String guidOf(Uri uri) {
        if (uri.getPathSegments().size() != 1) return null;
        String guid = uri.getLastPathSegment();
        return FileSystemBridge.isValidGuid(guid) ? guid : null;
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (!"r".equals(mode)) throw new FileNotFoundException("read-only provider");
        String guid = guidOf(uri);
        FileSystemBridge bridge = FileSystemBridge.get();
        if (guid == null || bridge == null) throw new FileNotFoundException(String.valueOf(uri));
        File f = bridge.fetch(guid);
        if (f == null) throw new FileNotFoundException("host did not provide " + uri);
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override
    public String getType(Uri uri) {
        String guid = guidOf(uri);
        return guid != null ? mimeTypeOf(guid) : null;
    }

    /** Apps ask the link for name and size; that is all we answer. */
    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs,
            String sortOrder) {
        String guid = guidOf(uri);
        FileSystemBridge bridge = FileSystemBridge.get();
        if (guid == null || bridge == null) return null;
        ProvideContentFileDetails d = bridge.details(guid);
        if (d == null) return null;
        String[] columns = projection != null ? projection
                : new String[] { OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE };
        MatrixCursor cursor = new MatrixCursor(columns, 1);
        MatrixCursor.RowBuilder row = cursor.newRow();
        for (String column : columns) {
            if (OpenableColumns.DISPLAY_NAME.equals(column)) {
                row.add(d.hasFileName() ? d.getFileName() : guid);
            } else if (OpenableColumns.SIZE.equals(column)) {
                row.add(d.hasFileSize() ? d.getFileSize() : null);
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
