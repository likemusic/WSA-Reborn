package org.wsalineage.notiftest;

import android.app.RemoteInput;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;

/** Receives a button press and a reply typed in the Windows window. */
public final class ActionReceiver extends BroadcastReceiver {
    static final String TAG = "NotifTest";
    static final String REPLY_KEY = "reply";

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getStringExtra("action");
        Bundle results = RemoteInput.getResultsFromIntent(intent);
        CharSequence reply = results != null ? results.getCharSequence(REPLY_KEY) : null;
        Log.i(TAG, "ACTION FIRED: " + action + ", reply=" + (reply != null ? "«" + reply + "»" : "none"));
    }
}
