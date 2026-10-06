package org.wsalineage.windowtest;

import android.app.Activity;
import android.app.PictureInPictureParams;
import android.content.Intent;
import android.content.res.Configuration;
import android.os.Bundle;
import android.util.Log;
import android.util.Rational;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Test app for window loose ends (plan §1): minimizing, focus, PiP, fullscreen mode,
 * placement of new windows. Logs everything that happens to the window to logcat (tag WindowTest).
 *
 *   am start -n org.wsalineage.windowtest/.MainActivity [-e do <action>] [--ei delay <ms>]
 *   action = pip | immersive | restore | back | newtask | big | tall
 *   delay — run after this many milliseconds (pip of the root activity: wait for onResume)
 *
 * Intent.filterEquals ignores extras: a second am start of the same activity without -S needs a
 * different -a, otherwise the action is not delivered.
 *
 * The same actions are available as buttons in the window (the owner does the clicks).
 */
public class MainActivity extends Activity {
    static final String TAG = "WindowTest";
    private TextView mState;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(32, 32, 32, 32);
        mState = new TextView(this);
        root.addView(mState);
        for (String action : new String[] {"pip", "immersive", "restore", "back", "newtask", "big", "tall"}) {
            Button b = new Button(this);
            b.setText(action);
            b.setOnClickListener(v -> run(action));
            root.addView(b);
        }
        setContentView(root);
        log("onCreate");
        runLater(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        log("onNewIntent");
        runLater(intent);
    }

    private void runLater(Intent intent) {
        String action = intent.getStringExtra("do");
        int delay = intent.getIntExtra("delay", 0);
        if (delay > 0) getWindow().getDecorView().postDelayed(() -> run(action), delay);
        else run(action);
    }

    private void run(String action) {
        if (action == null) return;
        log("do " + action);
        switch (action) {
            case "pip":
                enterPictureInPictureMode(new PictureInPictureParams.Builder()
                        .setAspectRatio(new Rational(16, 9)).build());
                break;
            case "immersive":
                getWindow().getInsetsController().hide(WindowInsets.Type.systemBars());
                break;
            case "restore":
                getWindow().getInsetsController().show(WindowInsets.Type.systemBars());
                break;
            case "back":
                moveTaskToBack(true);
                break;
            case "newtask":
                startActivity(new Intent(this, MainActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_MULTIPLE_TASK));
                break;
            case "big":
                startActivity(new Intent(this, BigActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                break;
            case "tall":
                startActivity(new Intent(this, TallActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                break;
            default:
                Log.w(TAG, "unknown action " + action);
        }
    }

    void log(String event) {
        String line = getClass().getSimpleName() + " task " + getTaskId() + " " + event
                + " bounds=" + getWindowManager().getCurrentWindowMetrics().getBounds()
                + " multiWindow=" + isInMultiWindowMode()
                + " pip=" + isInPictureInPictureMode();
        Log.i(TAG, line);
        if (mState != null) mState.setText(line);
    }

    @Override protected void onResume() { super.onResume(); log("onResume"); }
    @Override protected void onPause() { super.onPause(); log("onPause"); }
    @Override protected void onStop() { super.onStop(); log("onStop"); }
    @Override public void onTopResumedActivityChanged(boolean top) { log("topResumed=" + top); }
    @Override public void onWindowFocusChanged(boolean focus) { log("focus=" + focus); }
    @Override public void onConfigurationChanged(Configuration c) { super.onConfigurationChanged(c); log("config"); }
    @Override public void onPictureInPictureModeChanged(boolean pip, Configuration c) {
        super.onPictureInPictureModeChanged(pip, c);
        log("pipChanged=" + pip);
    }
}
