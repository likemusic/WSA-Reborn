package org.wsalineage.filetest;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;
import android.util.Log;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** Prints what is behind a content:// URI: type, name, size, SHA-256 and the beginning of the text. */
final class Dump {
    static final String TAG = "FileTest";

    static void uri(Context context, String what, Uri uri) {
        Log.i(TAG, what + " uri=" + uri + " type=" + context.getContentResolver().getType(uri));
        try (Cursor c = context.getContentResolver().query(uri, null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int n = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                int s = c.getColumnIndex(OpenableColumns.SIZE);
                Log.i(TAG, what + " name=" + (n < 0 ? "?" : c.getString(n))
                        + " size=" + (s < 0 ? "?" : c.getString(s)));
            } else {
                Log.i(TAG, what + " query: no row");
            }
        } catch (Exception e) {
            Log.e(TAG, what + " query failed: " + e);
        }
        long started = System.currentTimeMillis();
        try (InputStream in = context.getContentResolver().openInputStream(uri)) {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            byte[] head = new byte[120];
            int headLen = 0;
            long total = 0;
            byte[] buf = new byte[65536];
            for (int r; (r = in.read(buf)) > 0; ) {
                sha.update(buf, 0, r);
                int take = Math.min(r, head.length - headLen);
                System.arraycopy(buf, 0, head, headLen, take);
                headLen += take;
                total += r;
            }
            StringBuilder hex = new StringBuilder();
            for (byte b : sha.digest()) hex.append(String.format("%02x", b));
            Log.i(TAG, what + " read " + total + " bytes in "
                    + (System.currentTimeMillis() - started) + " ms, sha256=" + hex);
            Log.i(TAG, what + " head: "
                    + new String(head, 0, headLen, StandardCharsets.UTF_8).replace('\n', '|'));
        } catch (Exception e) {
            Log.e(TAG, what + " read failed: " + e);
        }
    }

    private Dump() {
    }
}
