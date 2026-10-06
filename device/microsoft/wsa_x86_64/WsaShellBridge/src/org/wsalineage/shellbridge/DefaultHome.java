/*
 * The bridge's empty home screen (HomeActivity), the default home like Microsoft's HomeApp.
 *
 * Launcher3 with a wallpaper under app windows gets in the way: after leaving full screen (F11)
 * the host shows the wallpaper in the app window (plan §1, 27–28.09.2026). The HOME role is set once,
 * after the user unlocks (HomeActivity is not directBootAware), and this is remembered in
 * Settings.Global: if the user later picks Launcher3 themselves, we leave that choice alone.
 * On an already running system the role change takes effect at the next home screen launch
 * (usually the next boot): we do not kick out the launcher already sitting under the windows, so as not to
 * cover open app windows.
 */
package org.wsalineage.shellbridge;

import android.app.role.RoleManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Handler;
import android.os.Process;
import android.os.UserHandle;
import android.os.UserManager;
import android.provider.Settings;
import android.util.Slog;

import java.util.concurrent.Executor;
import java.util.function.Consumer;

final class DefaultHome {
    private static final String TAG = "WsaShellBridge";
    private static final String APPLIED_KEY = "wsa_default_home_applied";

    private DefaultHome() {}

    static void applyOnce(Context context, Handler handler) {
        if (Settings.Global.getInt(context.getContentResolver(), APPLIED_KEY, 0) == 1) return;
        if (context.getSystemService(UserManager.class).isUserUnlocked()) {
            setRole(context);
            return;
        }
        context.registerReceiver(new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent intent) {
                context.unregisterReceiver(this);
                setRole(context);
            }
        }, new IntentFilter(Intent.ACTION_USER_UNLOCKED), null, handler);
    }

    private static void setRole(Context context) {
        Consumer<Boolean> callback = ok -> {
            if (ok) {
                Settings.Global.putInt(context.getContentResolver(), APPLIED_KEY, 1);
                Slog.i(TAG, "HOME role set to " + context.getPackageName());
            } else {
                Slog.w(TAG, "cannot set HOME role to " + context.getPackageName());
            }
        };
        // addRoleHolderAsUser is a @SystemApi of the permission module: the local build's android.jar
        // does not have it, hence reflection (the method exists at runtime and is accessible to the bridge as uid system).
        try {
            RoleManager rm = context.getSystemService(RoleManager.class);
            RoleManager.class.getMethod("addRoleHolderAsUser", String.class, String.class,
                    int.class, UserHandle.class, Executor.class, Consumer.class)
                    .invoke(rm, RoleManager.ROLE_HOME, context.getPackageName(), 0,
                            Process.myUserHandle(), context.getMainExecutor(), callback);
        } catch (ReflectiveOperationException e) {
            Slog.w(TAG, "cannot set HOME role: " + e);
        }
    }
}
