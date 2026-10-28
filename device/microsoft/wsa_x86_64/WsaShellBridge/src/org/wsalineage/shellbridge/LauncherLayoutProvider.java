/*
 * Launcher3's default layout (own host, one window — DefaultHome.forOneWindow): the hotseat from position 0.
 *
 * Launcher3's own default puts Google Play at hotseat position 3, and with a 1280×770 screen Launcher3 shows
 * only positions 0–2 (numShownHotseatIcons 3): the desktop had no icons at the bottom (27.10.2026). Launcher3 reads
 * the layout of a new database from the provider named in Settings.Secure "launcher3.layout.provider"
 * (LayoutParserFactory.createWorkspaceLoaderFromAppRestriction), the request is content://<authority>/launcher_layout
 * with the hotseatSize parameter; the reply is AutoInstallsLayout XML. Only a new database is affected: an existing
 * desktop, and the user's changes to it, stay as they are.
 *
 * The key is set when the bridge's process starts (providers are created before Application.onCreate), unless
 * someone else has set it; the bridge is persistent and comes up before Launcher3 builds its first database.
 * Exported (Launcher3 reads it), but serves only Launcher3.
 */
package org.wsalineage.shellbridge;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Slog;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

public final class LauncherLayoutProvider extends ContentProvider {
    private static final String TAG = "WsaShellBridge";
    private static final String AUTHORITY = "org.wsalineage.shellbridge.launcherlayout";
    private static final String LAYOUT_PROVIDER_KEY = "launcher3.layout.provider";
    /** Hotseat candidates in order; missing packages are skipped, the rest take positions 0, 1, 2… */
    private static final String[] HOTSEAT = {
        "com.android.vending",
        "com.google.android.documentsui",
        "com.android.documentsui",
        "com.android.settings",
    };

    @Override
    public boolean onCreate() {
        try {
            String current = Settings.Secure.getString(getContext().getContentResolver(), LAYOUT_PROVIDER_KEY);
            if (TextUtils.isEmpty(current)) {
                Settings.Secure.putString(getContext().getContentResolver(), LAYOUT_PROVIDER_KEY, AUTHORITY);
            }
        } catch (RuntimeException e) {
            Slog.w(TAG, "cannot set " + LAYOUT_PROVIDER_KEY + ": " + e);
        }
        return true;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (!"launcher_layout".equals(uri.getLastPathSegment())
                || !DefaultHome.LAUNCHER.equals(getCallingPackage())) {
            throw new FileNotFoundException(uri.toString());
        }
        int size = 3;
        try {
            size = Integer.parseInt(uri.getQueryParameter("hotseatSize"));
        } catch (NumberFormatException e) {
            // Launcher3 always passes it; keep the visible minimum otherwise.
        }
        byte[] xml = layout(size).getBytes(StandardCharsets.UTF_8);
        try {
            ParcelFileDescriptor[] pipe = ParcelFileDescriptor.createPipe();
            // A few hundred bytes: the write fits the pipe buffer and does not wait for the reader.
            try (OutputStream out = new ParcelFileDescriptor.AutoCloseOutputStream(pipe[1])) {
                out.write(xml);
            }
            Slog.i(TAG, "Launcher3 layout served (hotseat size " + size + ")");
            return pipe[0];
        } catch (IOException e) {
            throw new FileNotFoundException(e.toString());
        }
    }

    private String layout(int size) {
        PackageManager pm = getContext().getPackageManager();
        StringBuilder xml = new StringBuilder("<workspace>\n");
        int rank = 0;
        boolean files = false;
        for (String pkg : HOTSEAT) {
            if (rank >= size) break;
            boolean isFiles = pkg.endsWith(".documentsui");
            if (isFiles && files) continue;
            // Google Play without Play services fails at start (Gservices provider missing; seen on Android 17).
            if (pkg.equals("com.android.vending") && !installed(pm, "com.google.android.gms")) continue;
            List<ResolveInfo> ris = pm.queryIntentActivities(new Intent(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_LAUNCHER).setPackage(pkg), 0);
            if (ris.isEmpty()) continue;
            files |= isFiles;
            xml.append("<appicon container=\"hotseat\" rank=\"").append(rank).append("\" x=\"").append(rank)
                    .append("\" y=\"0\" packageName=\"").append(pkg).append("\" className=\"")
                    .append(ris.get(0).activityInfo.name).append("\" />\n");
            rank++;
        }
        return xml.append("</workspace>\n").toString();
    }

    private static boolean installed(PackageManager pm, String pkg) {
        try {
            pm.getPackageInfo(pkg, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
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
