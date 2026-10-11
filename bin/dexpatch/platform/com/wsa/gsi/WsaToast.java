package com.wsa.gsi;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.IBinder;
import android.os.Process;
import android.os.UserHandle;
import android.util.Log;

import java.util.Objects;

/**
 * Path A (ready GSI) counterpart of 0012 (docs/patches.md) (WsaToastForwarder): Toasts are shown by Windows, not drawn
 * on the invisible guest screen. Called from the start of com.android.systemui.toast.ToastUI.showToast/hideToast,
 * where bin/dexpatch/ToastUiPatch.java puts "if (WsaToast.show(<all parameters>)) return;". Goes into SystemUI.apk
 * as an extra classesN.dex. The text goes to the bridge org.wsalineage.shellbridge by an explicit broadcast, the
 * bridge sends it to the host as NotificationMessage{Toast}. Without the bridge in the system nothing changes.
 * Runs on the SystemUI main thread, as ToastUI does. Hidden types (ITransientNotificationCallback) — by reflection.
 */
public final class WsaToast {
    private static final String TAG = "WsaToastForwarder";
    private static final String BRIDGE_PACKAGE = "org.wsalineage.shellbridge";
    private static final String ACTION_SHOW = BRIDGE_PACKAGE + ".action.TOAST_SHOW";
    private static final String ACTION_HIDE = BRIDGE_PACKAGE + ".action.TOAST_HIDE";

    private static Boolean sEnabled;
    private static Context sContext;
    // The forwarded Toast, so that hideToast can tell it from someone else's (as ToastUI does by mPresenter).
    private static String sPackageName;
    private static IBinder sToken;
    private static Object sCallback;

    private WsaToast() {}

    /** Replaces ToastUI.showToast when the bridge is there; returns false to let ToastUI draw the Toast itself. */
    public static boolean show(Object toastUi, int uid, String packageName, IBinder token, CharSequence text,
            IBinder windowToken, int duration, Object callback, int displayId) {
        if (!enabled()) return false;
        if (sToken != null) hideCurrent();
        sPackageName = packageName;
        sToken = token;
        sCallback = callback;
        send(new Intent(ACTION_SHOW)
                .putExtra("package", packageName)
                .putExtra("token", String.valueOf(token))
                .putExtra("text", text != null ? text.toString() : "")
                .putExtra("duration", duration));
        notify(callback, "onToastShown");
        return true;
    }

    /** Replaces ToastUI.hideToast when the bridge is there. */
    public static boolean hide(Object toastUi, String packageName, IBinder token) {
        if (!enabled()) return false;
        if (sToken == null || !Objects.equals(sPackageName, packageName) || !Objects.equals(sToken, token)) {
            Log.w(TAG, "Attempt to hide non-current toast from package " + packageName);
            return true;
        }
        hideCurrent();
        return true;
    }

    private static boolean enabled() {
        if (sEnabled == null) {
            boolean enabled = false;
            try {
                sContext = (Context) Class.forName("android.app.ActivityThread").getMethod("currentApplication").invoke(null);
                ApplicationInfo ai = sContext.getPackageManager().getApplicationInfo(BRIDGE_PACKAGE, PackageManager.MATCH_SYSTEM_ONLY);
                enabled = ai.enabled;
            } catch (PackageManager.NameNotFoundException e) {
                enabled = false;
            } catch (ReflectiveOperationException | RuntimeException e) {
                Log.w(TAG, "no application context", e);
                enabled = false;
            }
            sEnabled = enabled;
            Log.i(TAG, "toasts go to Windows: " + enabled);
        }
        return sEnabled;
    }

    private static void hideCurrent() {
        send(new Intent(ACTION_HIDE).putExtra("package", sPackageName).putExtra("token", String.valueOf(sToken)));
        notify(sCallback, "onToastHidden");
        sPackageName = null;
        sToken = null;
        sCallback = null;
    }

    private static void notify(Object callback, String method) {
        if (callback == null) return;
        try {
            Class.forName("android.app.ITransientNotificationCallback").getMethod(method).invoke(callback);
        } catch (ReflectiveOperationException e) {
            Log.w(TAG, method + "() failed", e);
        }
    }

    private static void send(Intent intent) {
        intent.setPackage(BRIDGE_PACKAGE).addFlags(Intent.FLAG_RECEIVER_FOREGROUND);
        sContext.sendBroadcastAsUser(intent, UserHandle.getUserHandleForUid(Process.SYSTEM_UID));
    }
}
