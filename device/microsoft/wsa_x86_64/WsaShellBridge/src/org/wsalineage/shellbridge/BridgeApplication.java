package org.wsalineage.shellbridge;

import android.app.Application;
import android.util.Slog;

/** Entry point: the app is persistent, ActivityManager brings up the process at boot. */
public final class BridgeApplication extends Application {
    private static final String TAG = "WsaShellBridge";

    @Override
    public void onCreate() {
        super.onCreate();
        Slog.i(TAG, "starting");
        ShellBridge shell = new ShellBridge(this);
        shell.start();
        new IntentsBridge(this, shell.handler()).start();
        new UserInputBridge(this, shell.handler()).start();
        new ClipboardBridge(this, shell.handler()).start();
        new NotificationBridge(this, shell.handler()).start();
        new AppsBridge(this, shell.handler()).start();
        new FileSystemBridge(this, shell.handler()).start();
        new TsfBridge(this, shell.handler()).start();
        new MediaControlsBridge(this, shell.handler()).start();
        new TtsBridge(this, shell.handler()).start();
        new UiaBridge(this, shell.handler()).start();
    }
}
