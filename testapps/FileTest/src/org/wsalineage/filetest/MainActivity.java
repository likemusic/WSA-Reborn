package org.wsalineage.filetest;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Test app for the "FileSystem" channel.
 *
 *   am start -S -n org.wsalineage.filetest/.MainActivity -e kind send [-e name a.txt] [--el size N]
 *       [-e how view|send] [--ez grant false]
 *       — create our own file and hand it to the bridge "to Windows"; size — size in bytes (otherwise
 *         a short text); grant false — do not grant the bridge read access (checks the refusal);
 *   am start -S -n org.wsalineage.filetest/.MainActivity -e kind paste
 *       — print files from the clipboard (copied in Windows Explorer).
 */
public final class MainActivity extends Activity {
    private static final ComponentName BRIDGE = new ComponentName("org.wsalineage.shellbridge",
            "org.wsalineage.shellbridge.SendToWindowsActivity");

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        Intent intent = getIntent();
        String kind = intent.getStringExtra("kind");
        if ("send".equals(kind)) {
            send(intent);
            finish();
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        // Since Android 10 the clipboard can be read only while focused.
        if (hasFocus && "paste".equals(getIntent().getStringExtra("kind"))) paste();
    }

    private void send(Intent intent) {
        String name = intent.getStringExtra("name");
        if (name == null) name = "wsa-filetest.txt";
        long size = intent.getLongExtra("size", -1);
        File f = new File(Files.dir(this), name);
        try (FileOutputStream out = new FileOutputStream(f)) {
            if (size < 0) {
                out.write(("FileTest → Windows, " + new java.util.Date() + "\nКириллица на месте.\n")
                        .getBytes(StandardCharsets.UTF_8));
            } else {
                byte[] buf = new byte[65536];
                for (int i = 0; i < buf.length; i++) buf[i] = (byte) ('a' + i % 26);
                for (long left = size; left > 0; left -= buf.length) {
                    out.write(buf, 0, (int) Math.min(left, buf.length));
                }
            }
        } catch (IOException e) {
            Log.e(Dump.TAG, "cannot write " + f + ": " + e);
            return;
        }
        Uri uri = Files.uriFor(f);
        Intent out;
        if ("send".equals(intent.getStringExtra("how"))) {
            out = new Intent(Intent.ACTION_SEND).setType(getContentResolver().getType(uri))
                    .putExtra(Intent.EXTRA_STREAM, uri);
            out.setClipData(ClipData.newRawUri(name, uri));
        } else {
            out = new Intent(Intent.ACTION_VIEW).setDataAndType(uri,
                    getContentResolver().getType(uri));
        }
        out.setComponent(BRIDGE);
        if (intent.getBooleanExtra("grant", true)) {
            out.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        }
        Log.i(Dump.TAG, "sending " + name + " (" + f.length() + " bytes) via "
                + out.getAction() + " grant=" + intent.getBooleanExtra("grant", true));
        startActivity(out);
    }

    private void paste() {
        ClipboardManager cm = getSystemService(ClipboardManager.class);
        ClipData clip = cm.getPrimaryClip();
        if (clip == null) {
            Log.i(Dump.TAG, "clipboard is empty");
            finish();
            return;
        }
        StringBuilder mimes = new StringBuilder();
        for (int i = 0; i < clip.getDescription().getMimeTypeCount(); i++) {
            mimes.append(i == 0 ? "" : ",").append(clip.getDescription().getMimeType(i));
        }
        Log.i(Dump.TAG, "clipboard: " + clip.getItemCount() + " items, mime=" + mimes);
        new Thread(() -> {
            for (int i = 0; i < clip.getItemCount(); i++) {
                Uri uri = clip.getItemAt(i).getUri();
                if (uri != null) Dump.uri(this, "clip" + i, uri);
                else Log.i(Dump.TAG, "clip" + i + " text=" + clip.getItemAt(i).getText());
            }
            runOnUiThread(this::finish);
        }).start();
    }
}
