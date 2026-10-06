/*
 * "Share" and "Open" target labeled "Windows": the file goes over the "FileSystem" channel to
 * Windows and is opened there. The activity has no window (Theme.NoDisplay) — it hands the URIs to the bridge
 * and closes right away. At Microsoft the same is done by ShareIntentHandler and FileSystemIntentHandler
 * from UserApp.apk.
 *
 * The bridge runs as uid system and can read almost any content:// URI. So a file
 * is taken only if the app that started the activity could read it ITSELF: otherwise any app could
 * send someone else's data (contacts, other apps' files) to Windows through the bridge. Who
 * started it — ActivityClient.getLaunchedFromUid; the system app chooser starts the target via
 * startActivityAsCaller, so that holds the uid of the original app, not of the chooser. The reference has no such
 * check — it does not need one: UserApp runs under its own unprivileged uid.
 */
package org.wsalineage.shellbridge;

import android.app.Activity;
import android.app.ActivityClient;
import android.content.ClipData;
import android.content.ContentProvider;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ProviderInfo;
import android.net.Uri;
import android.os.Bundle;
import android.os.Process;
import android.os.UserHandle;
import android.util.Slog;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.Set;

public final class SendToWindowsActivity extends Activity {
    private static final String TAG = "WsaShellBridge.FileSystem";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        try {
            handle(getIntent());
        } finally {
            // Theme.NoDisplay requires finishing before onResume.
            finish();
        }
    }

    private void handle(Intent intent) {
        if (intent == null) return;
        FileSystemBridge bridge = FileSystemBridge.get();
        if (bridge == null) {
            Slog.e(TAG, "FileSystem bridge is not running");
            return;
        }
        int caller = ActivityClient.getInstance().getLaunchedFromUid(getActivityToken());
        Set<Uri> uris = collect(intent);
        if (uris.isEmpty()) {
            // E.g. "Share" with plain text: there is no way to send it to Windows.
            Slog.i(TAG, "nothing to send: " + intent.getAction() + " without file");
            return;
        }
        for (Uri uri : uris) {
            if (!callerCanRead(uri, caller)) {
                Slog.w(TAG, "uid " + caller + " has no read access to " + uri + ", not sent");
                continue;
            }
            bridge.sendFile(uri, true);
        }
    }

    private static Set<Uri> collect(Intent intent) {
        Set<Uri> uris = new LinkedHashSet<>();
        if (intent.getData() != null) uris.add(intent.getData());
        if (Intent.ACTION_SEND.equals(intent.getAction())) {
            Uri stream = intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri.class);
            if (stream != null) uris.add(stream);
        } else if (Intent.ACTION_SEND_MULTIPLE.equals(intent.getAction())) {
            ArrayList<Uri> streams = intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM,
                    Uri.class);
            if (streams != null) uris.addAll(streams);
        }
        ClipData clip = intent.getClipData();
        if (clip != null) {
            for (int i = 0; i < clip.getItemCount(); i++) {
                Uri u = clip.getItemAt(i).getUri();
                if (u != null) uris.add(u);
            }
        }
        return uris;
    }

    /**
     * Could the caller read this URI itself. Context.checkUriPermission does not fit here: it
     * sees only explicit grants and refuses even the provider's owner. Hence three cases:
     * an explicit grant (the app forwards a file it received), the provider's owner (its own file via
     * FileProvider — the most common case) and an exported provider whose read permission the
     * caller holds. pathPermissions are not evaluated — such a provider passes only through the first
     * two paths.
     */
    private boolean callerCanRead(Uri uri, int uid) {
        // file:// between apps is forbidden since Android 7, and there is no way to check access to it.
        if (!"content".equals(uri.getScheme()) || uri.getAuthority() == null) return false;
        if (uid == Process.INVALID_UID || uid < 0) return false;
        if (checkUriPermission(uri, -1, uid, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                == PackageManager.PERMISSION_GRANTED) {
            return true;
        }
        ProviderInfo pi = getPackageManager().resolveContentProvider(
                ContentProvider.getAuthorityWithoutUserId(uri.getAuthority()), 0);
        if (pi == null) return false;
        if (UserHandle.isSameApp(pi.applicationInfo.uid, uid)) return true;
        if (!pi.exported) return false;
        return pi.readPermission == null
                || checkPermission(pi.readPermission, -1, uid) == PackageManager.PERMISSION_GRANTED;
    }
}
