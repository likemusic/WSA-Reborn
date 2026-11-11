/*
 * Package verification on install, the bridge side: the WsaPackageVerifier verifier calls
 * call("verify") with a package description and a pipe per apk, the bridge copies them to the host and waits for the verdict
 * of the Windows antivirus ("FileSystem" channel, FileSystemBridge.verifyPackage). The reply is a Bundle with
 * allow. The call blocks for up to two minutes: it is made by the verifier's background thread.
 *
 * Why the verifier is a separate package and not the bridge: reading apks from the install session directory
 * is allowed by the platform for the platform_app domain, while for the bridge (system_app) it is forbidden by a neverallow; see
 * device/microsoft/wsa_x86_64/WsaPackageVerifier.
 *
 * The provider is exported, so we check the caller ourselves: only a package signed with the same
 * platform key as the bridge.
 */
package org.wsalineage.shellbridge;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.os.Parcelable;
import android.os.Process;
import android.util.Slog;

import java.util.ArrayList;
import java.util.List;

public final class PackageVerificationProvider extends ContentProvider {
    private static final String TAG = "WsaShellBridge.FileSystem";

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        if (!"verify".equals(method) || extras == null) return null;
        int uid = Binder.getCallingUid();
        if (getContext().getPackageManager().checkSignatures(uid, Process.myUid())
                != PackageManager.SIGNATURE_MATCH) {
            Slog.w(TAG, "verify from uid " + uid + " refused: not platform-signed");
            return null;
        }
        extras.setClassLoader(ParcelFileDescriptor.class.getClassLoader());
        Parcelable[] parcels = extras.getParcelableArray("apks");
        List<ParcelFileDescriptor> apks = new ArrayList<>();
        if (parcels != null) {
            for (Parcelable p : parcels) {
                if (p instanceof ParcelFileDescriptor) apks.add((ParcelFileDescriptor) p);
            }
        }
        FileSystemBridge bridge = FileSystemBridge.get();
        boolean allow = bridge != null && !apks.isEmpty() && bridge.verifyPackage(
                extras.getInt("id", -1), apks, extras.getString("packageName"),
                extras.getInt("versionCode", -1), extras.getLong("longVersionCode", -1),
                extras.getString("installer"), extras.getInt("sessionId", -1),
                extras.getLong("deadline", 0));
        Bundle reply = new Bundle();
        reply.putBoolean("allow", allow);
        return reply;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs,
            String sortOrder) {
        return null;
    }

    @Override
    public String getType(Uri uri) {
        return null;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }
}
