package org.wsalineage.filetest;

import android.app.Activity;
import android.content.ClipData;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.widget.TextView;

import java.util.ArrayList;

/**
 * Receives "Share": prints every received file. Windows sends here via the "Intents" channel —
 * including when a file is dragged from Explorer onto the app window. The window stays open with
 * the summary: the host starts the receiver with CLEAR_TASK, and if the activity closed, the window would vanish too.
 */
public final class ReceiveActivity extends Activity {
    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        Intent intent = getIntent();
        Log.i(Dump.TAG, "received " + intent.getAction() + " type=" + intent.getType());
        ArrayList<Uri> uris = new ArrayList<>();
        Uri one = intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri.class);
        if (one != null) uris.add(one);
        ArrayList<Uri> many = intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri.class);
        if (many != null) uris.addAll(many);
        ClipData clip = intent.getClipData();
        if (uris.isEmpty() && clip != null) {
            for (int i = 0; i < clip.getItemCount(); i++) {
                if (clip.getItemAt(i).getUri() != null) uris.add(clip.getItemAt(i).getUri());
            }
        }
        TextView view = new TextView(this);
        view.setPadding(32, 32, 32, 32);
        view.setText("Files received: " + uris.size() + "\nreading…");
        setContentView(view);
        new Thread(() -> {
            for (int i = 0; i < uris.size(); i++) Dump.uri(this, "share" + i, uris.get(i));
            runOnUiThread(() -> view.setText("Files received: " + uris.size()
                    + "\nread, details — adb logcat -s FileTest"));
        }).start();
    }
}
