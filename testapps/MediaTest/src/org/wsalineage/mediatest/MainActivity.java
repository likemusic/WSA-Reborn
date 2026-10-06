package org.wsalineage.mediatest;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.widget.TextView;

/** The "player" window: without a window the host does not set up the media panel. On open it starts the session. */
public final class MainActivity extends Activity {
    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        TextView t = new TextView(this);
        t.setText("MediaTest: media session started, check the Windows media panel");
        t.setTextSize(20);
        t.setPadding(40, 40, 40, 40);
        setContentView(t);
        startForegroundService(new Intent(this, PlayerService.class));
    }
}
