/*
 * "Intents" channel: launching apps on the host's command (Start menu shortcut, wsa://<package>) and
 * the other way: links from Android open in Windows (LaunchUri, see OpenInWindowsActivity).
 *
 * Without this channel WsaClient.exe shows a splash placeholder and waits forever: nobody starts the guest
 * activity. In Microsoft's image the handler lives in UserApp.apk (not transplanted), the protocol is
 * WsaShellBridge/proto/intents.proto. The reply to the host is the same IntentMessage with result filled in.
 */
package org.wsalineage.shellbridge;

import android.app.ActivityManager;
import android.app.ActivityTaskManager;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
import android.os.RemoteException;
import android.os.SystemClock;
import android.os.UserManager;
import android.util.Slog;
import android.view.Display;

import com.google.protobuf.InvalidProtocolBufferException;

import org.wsalineage.shellbridge.proto.Intents;
import org.wsalineage.shellbridge.proto.Intents.IntentMessage;
import org.wsalineage.shellbridge.proto.Intents.LaunchPackage;
import org.wsalineage.shellbridge.proto.ResultCodes.ResultCode;

import java.util.ArrayList;
import java.util.UUID;

public final class IntentsBridge implements ApiChannel.Handler {
    private static final String TAG = "WsaShellBridge.Intents";
    private static final String CHANNEL = "Intents";
    private static final long RECONNECT_MS = 2_000;
    // How long to wait for the user to unlock if the host sent a launch before the guest finished booting.
    private static final long USER_UNLOCK_WAIT_MS = 60_000;

    private final Context mContext;
    private final Handler mH;
    private final ApiChannel mChannel = new ApiChannel(CHANNEL, this);

    private static volatile IntentsBridge sInstance;

    public IntentsBridge(Context context, Handler handler) {
        mContext = context;
        mH = handler;
        sInstance = this;
    }

    /** For OpenInWindowsActivity: activities live in the same process as the bridge. */
    static IntentsBridge get() {
        return sInstance;
    }

    /**
     * Open a URI in Windows. ignoreAppUri: do not look up a Windows app for the link, open it in the
     * browser (the reference sends App Links this way). The host replies with an empty frame; we do not wait for it.
     */
    boolean launchUri(String uri, boolean ignoreAppUri) {
        if (!mChannel.isOpen()) {
            Slog.w(TAG, "channel is not open, cannot open uri in Windows");
            return false;
        }
        IntentMessage msg = IntentMessage.newBuilder()
                .setMessageType(IntentMessage.MessageType.LaunchUri)
                .setActivityId(org.wsalineage.shellbridge.proto.Common.ActivityId.newBuilder()
                        .setValue(UUID.randomUUID().toString()).build())
                .setLaunchUri(Intents.LaunchUri.newBuilder()
                        .setUri(uri).setIgnoreAppUri(ignoreAppUri).build())
                .build();
        try {
            mChannel.sendAsync(msg.toByteArray());
            Slog.i(TAG, "-> host LaunchUri scheme=" + android.net.Uri.parse(uri).getScheme()
                    + " activityId=" + msg.getActivityId().getValue());
            return true;
        } catch (ApiChannel.ApiException e) {
            Slog.w(TAG, "LaunchUri send failed: " + e.getMessage());
            return false;
        }
    }

    public void start() {
        mH.post(this::connect);
    }

    private void connect() {
        try {
            mChannel.open();
        } catch (ApiChannel.ApiException e) {
            Slog.w(TAG, "open failed: " + e.getMessage() + ", retry in " + RECONNECT_MS + "ms");
            mH.postDelayed(this::connect, RECONNECT_MS);
        }
    }

    @Override
    public void onClosed() {
        mH.postDelayed(this::connect, RECONNECT_MS);
    }

    @Override
    public byte[] onRequest(byte[] payload) throws ApiChannel.ApiException {
        IntentMessage msg;
        try {
            msg = IntentMessage.parseFrom(payload);
        } catch (InvalidProtocolBufferException e) {
            throw new ApiChannel.ApiException("bad IntentMessage: " + e,
                    ResultCode.InvalidProtocolFormat);
        }
        Slog.i(TAG, "host -> " + msg.getMessageType() + " activityId="
                + msg.getActivityId().getValue());
        Intents.Result.Builder result = Intents.Result.newBuilder();
        switch (msg.getMessageType()) {
            case LaunchPackage:
                try {
                    result.setSucceeded(launchPackage(msg.getLaunchPackage()));
                } catch (RuntimeException e) {
                    Slog.e(TAG, "launch failed", e);
                    result.setSucceeded(false).setErrorMessage(String.valueOf(e));
                }
                break;
            default:
                throw new ApiChannel.ApiException("unexpected message type " + msg.getMessageType(),
                        ResultCode.InvalidProtocolFormat);
        }
        return msg.toBuilder().setResult(result).build().toByteArray();
    }

    private boolean launchPackage(LaunchPackage lp) {
        String pkg = lp.getPackageName();
        // The bridge is persistent+directBootAware and starts before user 0 is unlocked, while the host
        // sends LaunchPackage as soon as the channel is open (cold start via wsa://).
        // While the user is locked, PackageManager does not see regular (non-directBootAware)
        // activities and getLaunchIntentForPackage returns null. Wait for unlock; the host waits for the reply.
        if (!waitForUserUnlocked()) {
            Slog.e(TAG, "user still locked after " + USER_UNLOCK_WAIT_MS + " ms, cannot launch " + pkg);
            return false;
        }
        Intent intent;
        switch (lp.getIntentType()) {
            case MainLaunch:
                if (bringVisibleTaskToFront(pkg)) return true;
                intent = mContext.getPackageManager().getLaunchIntentForPackage(pkg);
                break;
            case Activity:
                intent = new Intent().setClassName(pkg, lp.getActivityName());
                break;
            case Implicit:
                intent = new Intent(lp.getImplicitIntent().getAction());
                if (!lp.getImplicitIntent().getData().isEmpty()) {
                    intent.setData(Uri.parse(lp.getImplicitIntent().getData()));
                }
                if (!lp.getImplicitIntent().getAppLink()) intent.setPackage(pkg);
                break;
            case FileShare:
                intent = fileShareIntent(lp);
                if (intent != null) intent.setPackage(pkg);
                break;
            default:
                Slog.w(TAG, "intent type " + lp.getIntentType() + " not supported (package " + pkg + ")");
                return false;
        }
        if (intent == null) {
            Slog.e(TAG, "no launch intent for " + pkg);
            return false;
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        try {
            mContext.startActivity(intent);
        } catch (ActivityNotFoundException e) {
            Slog.e(TAG, "activity not found for " + pkg + ": " + e.getMessage());
            return false;
        }
        Slog.i(TAG, "started " + pkg + " (" + lp.getIntentType() + ")");
        return true;
    }

    /**
     * MainLaunch for an app that already has a visible task: just bring it to front.
     * The host sends such a LaunchPackage when it has lost the package's windows but not the task itself: after
     * the "Shell" channel drops (bridge process crash) it closes all windows and immediately "relaunches"
     * the app (verified 27.09.2026, am crash of the bridge). With CLEAR_TASK this would wipe the app's
     * state, and if its main activity is in another task it would create an extra task and window.
     * A regular click on a Start menu shortcut does not end up here: a window closed with the X has no task.
     */
    private boolean bringVisibleTaskToFront(String pkg) {
        try {
            for (ActivityManager.RunningTaskInfo t : ActivityTaskManager.getService()
                    .getTasks(Integer.MAX_VALUE, false, false, Display.INVALID_DISPLAY)) {
                if (t.isVisible && t.baseActivity != null
                        && pkg.equals(t.baseActivity.getPackageName())) {
                    ActivityTaskManager.getService().moveTaskToFront(null, mContext.getPackageName(),
                            t.taskId, 0, null);
                    Slog.i(TAG, "MainLaunch " + pkg + ": task " + t.taskId
                            + " already visible, moved to front");
                    return true;
                }
            }
        } catch (RemoteException e) {
            Slog.w(TAG, "getTasks failed, launching " + pkg + " anew", e);
        }
        return false;
    }

    /**
     * "Share" from Windows to an Android app: files travel as WindowsFileProvider links, the bytes
     * are published by the host over the "FileSystem" channel when the app opens them. We do not set the URI
     * grant flag: the system does not grant it from uid system anyway, and the provider is exported.
     */
    private static Intent fileShareIntent(LaunchPackage lp) {
        if (lp.getFileGuidCount() == 0) {
            Slog.w(TAG, "FileShare without files for " + lp.getPackageName());
            return null;
        }
        ArrayList<Uri> uris = new ArrayList<>();
        ClipData clip = null;
        for (String guid : lp.getFileGuidList()) {
            if (!FileSystemBridge.isValidGuid(guid)) {
                Slog.w(TAG, "FileShare: bad file GUID skipped");
                continue;
            }
            Uri uri = WindowsFileProvider.uriFor(guid);
            uris.add(uri);
            if (clip == null) {
                clip = new ClipData(null, new String[] { "*/*" }, new ClipData.Item(uri));
            } else {
                clip.addItem(new ClipData.Item(uri));
            }
        }
        if (uris.isEmpty()) return null;
        Intent intent = new Intent();
        intent.setClipData(clip);
        if (uris.size() > 1) {
            intent.setAction(Intent.ACTION_SEND_MULTIPLE);
            intent.putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris);
            intent.setType("*/*");
        } else {
            intent.setAction(Intent.ACTION_SEND);
            intent.putExtra(Intent.EXTRA_STREAM, uris.get(0));
            intent.setType(WindowsFileProvider.mimeTypeOf(uris.get(0).getLastPathSegment()));
        }
        return intent;
    }

    private boolean waitForUserUnlocked() {
        UserManager um = mContext.getSystemService(UserManager.class);
        long deadline = SystemClock.uptimeMillis() + USER_UNLOCK_WAIT_MS;
        boolean waited = false;
        while (!um.isUserUnlocked()) {
            if (SystemClock.uptimeMillis() >= deadline) return false;
            if (!waited) {
                Slog.i(TAG, "user locked, waiting for unlock before launching");
                waited = true;
            }
            SystemClock.sleep(250);
        }
        return true;
    }
}
