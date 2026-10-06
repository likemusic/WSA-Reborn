package org.wsalineage.cliptest;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipDescription;
import android.content.ClipboardManager;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.text.Html;
import android.util.Log;

import java.io.InputStream;

/** Test app for the "Clipboard" channel: sets and prints the Android clipboard. */
public final class MainActivity extends Activity {
    private static final String TAG = "ClipTest";

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        handle(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handle(intent);
    }

    private void handle(Intent intent) {
        ClipboardManager cm = getSystemService(ClipboardManager.class);
        String text = intent.getStringExtra("set");
        String html = intent.getStringExtra("html");
        String uri = intent.getStringExtra("uri");
        if (html != null) {
            cm.setPrimaryClip(ClipData.newHtmlText("ClipTest",
                    Html.fromHtml(html, Html.FROM_HTML_MODE_COMPACT).toString(), html));
            Log.i(TAG, "set html: " + html);
        } else if (uri != null) {
            cm.setPrimaryClip(ClipData.newUri(getContentResolver(), "ClipTest", Uri.parse(uri)));
            Log.i(TAG, "set uri: " + uri);
        } else if (text != null) {
            cm.setPrimaryClip(ClipData.newPlainText("ClipTest", text));
            Log.i(TAG, "set text: " + text);
        }
        new Handler(getMainLooper()).postDelayed(this::dump, 800);
    }

    private void dump() {
        ClipboardManager cm = getSystemService(ClipboardManager.class);
        ClipData clip;
        try {
            clip = cm.getPrimaryClip();
        } catch (SecurityException e) {
            Log.e(TAG, "read denied: " + e);
            return;
        }
        if (clip == null) {
            Log.i(TAG, "clip=null");
            return;
        }
        ClipDescription d = clip.getDescription();
        StringBuilder mimes = new StringBuilder();
        for (int i = 0; i < d.getMimeTypeCount(); i++) {
            mimes.append(i == 0 ? "" : ",").append(d.getMimeType(i));
        }
        Log.i(TAG, "clip label=" + d.getLabel() + " items=" + clip.getItemCount() + " mime=" + mimes);
        for (int i = 0; i < clip.getItemCount(); i++) {
            ClipData.Item item = clip.getItemAt(i);
            CharSequence t = item.getText();
            String h = item.getHtmlText();
            Uri uri = item.getUri();
            Log.i(TAG, "  item" + i
                    + " text=" + (t == null ? "null" : "[" + t.length() + "] " + t)
                    + " html=" + (h == null ? "null" : "[" + h.length() + "]")
                    + " uri=" + uri);
            if (uri != null) Log.i(TAG, "  item" + i + " uri bytes=" + bytes(uri));
        }
    }

    private String bytes(Uri uri) {
        try (InputStream in = getContentResolver().openInputStream(uri)) {
            if (in == null) return "null stream";
            long n = 0;
            byte[] buf = new byte[8192];
            for (int r; (r = in.read(buf)) > 0; ) n += r;
            return String.valueOf(n);
        } catch (Exception e) {
            return "error: " + e;
        }
    }
}
