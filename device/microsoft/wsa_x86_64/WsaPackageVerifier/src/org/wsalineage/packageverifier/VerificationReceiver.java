/*
 * Required package verifier for WSA. Like the reference (PackageVerificationReceiver from UserApp.apk):
 * immediately extend the wait to two minutes with a "reject" default outcome, hand the package off for checking in the background,
 * answer the system. The checking is done not by it but by the Windows antivirus (AMSI) via the host; the bridge
 * WsaShellBridge (channel "FileSystem") talks to the host, so the apks are passed to it as bytes over a pipe: the bridge itself
 * (domain system_app) cannot read the session directory, while this package (platform_app) can.
 * It must be the verifier that answers the system: PackageManager accepts the decision only from its uid.
 *
 * Disabled with standard Android settings:
 *   settings put global verifier_verify_adb_installs 0   — do not verify adb install (autotests);
 *   settings put global package_verifier_enable 0        — verify nothing.
 */
package org.wsalineage.packageverifier;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

public final class VerificationReceiver extends BroadcastReceiver {
    private static final String TAG = "WsaPackageVerifier";
    /** How long the reference waits: the host copies the package and runs it through the antivirus. */
    private static final long VERIFICATION_TIMEOUT_MS = 120_000;
    private static final Uri BRIDGE = Uri.parse("content://org.wsalineage.shellbridge.verify");

    @Override
    public void onReceive(Context context, Intent intent) {
        if (!Intent.ACTION_PACKAGE_NEEDS_VERIFICATION.equals(intent.getAction())) return;
        final int id = intent.getIntExtra(PackageManager.EXTRA_VERIFICATION_ID, -1);
        final PackageManager pm = context.getPackageManager();
        pm.extendVerificationTimeout(id, PackageManager.VERIFICATION_REJECT, VERIFICATION_TIMEOUT_MS);
        final Intent copy = new Intent(intent);
        final Context app = context.getApplicationContext();
        new Thread(() -> {
            boolean allow = verify(app, id, copy);
            Log.i(TAG, "verification " + id + ": " + (allow ? "allow" : "REJECT"));
            pm.verifyPendingInstall(id, allow
                    ? PackageManager.VERIFICATION_ALLOW : PackageManager.VERIFICATION_REJECT);
        }, "verify-" + id).start();
    }

    private static boolean verify(Context context, int id, Intent intent) {
        String dir = intent.getData() != null ? intent.getData().getPath() : null;
        List<File> apks = apksOf(dir,
                intent.getStringExtra("android.content.pm.extra.VERIFICATION_ROOT_HASH"));
        if (apks.isEmpty()) {
            Log.w(TAG, "verification " + id + ": no apk in " + dir);
            return false;
        }
        Bundle extras = new Bundle();
        extras.putInt("id", id);
        extras.putString("packageName",
                intent.getStringExtra("android.content.pm.extra.VERIFICATION_PACKAGE_NAME"));
        extras.putInt("versionCode",
                intent.getIntExtra("android.content.pm.extra.VERIFICATION_VERSION_CODE", -1));
        extras.putLong("longVersionCode",
                intent.getLongExtra("android.content.pm.extra.VERIFICATION_LONG_VERSION_CODE", -1));
        extras.putString("installer",
                intent.getStringExtra("android.content.pm.extra.VERIFICATION_INSTALLER_PACKAGE"));
        extras.putInt("sessionId", intent.getIntExtra("android.content.pm.extra.SESSION_ID", -1));

        ParcelFileDescriptor[] readEnds = new ParcelFileDescriptor[apks.size()];
        List<Thread> writers = new ArrayList<>();
        try {
            for (int i = 0; i < apks.size(); i++) {
                ParcelFileDescriptor[] pipe = ParcelFileDescriptor.createPipe();
                readEnds[i] = pipe[0];
                writers.add(startWriter(apks.get(i), pipe[1]));
            }
            extras.putParcelableArray("apks", readEnds);
            Bundle reply = context.getContentResolver().call(BRIDGE, "verify", null, extras);
            return reply != null && reply.getBoolean("allow", false);
        } catch (IOException | RuntimeException e) {
            Log.e(TAG, "verification " + id + " failed: " + e);
            return false;
        } finally {
            for (ParcelFileDescriptor p : readEnds) closeQuietly(p);
            for (Thread t : writers) t.interrupt();
        }
    }

    /**
     * apk names: from "name:hash;name:hash" (incremental install), otherwise all *.apk in the session
     * directory (streaming install sends no hashes; the reference crashed on null here).
     */
    private static List<File> apksOf(String dir, String rootHash) {
        List<File> apks = new ArrayList<>();
        if (dir == null) return apks;
        if (rootHash != null) {
            for (String entry : rootHash.split(";")) {
                String name = entry.split(":")[0];
                if (!name.isEmpty()) apks.add(new File(dir, name));
            }
        } else {
            File[] files = new File(dir).listFiles((d, name) -> name.endsWith(".apk"));
            if (files != null) {
                for (File f : files) apks.add(f);
            }
        }
        return apks;
    }

    private static Thread startWriter(File apk, ParcelFileDescriptor writeEnd) {
        Thread t = new Thread(() -> {
            try (InputStream in = new FileInputStream(apk);
                 OutputStream out = new ParcelFileDescriptor.AutoCloseOutputStream(writeEnd)) {
                byte[] buf = new byte[256 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            } catch (IOException e) {
                Log.w(TAG, "cannot stream " + apk + ": " + e);
            }
        }, "pipe-" + apk.getName());
        t.start();
        return t;
    }

    private static void closeQuietly(ParcelFileDescriptor p) {
        if (p == null) return;
        try {
            p.close();
        } catch (IOException ignored) {
        }
    }
}
