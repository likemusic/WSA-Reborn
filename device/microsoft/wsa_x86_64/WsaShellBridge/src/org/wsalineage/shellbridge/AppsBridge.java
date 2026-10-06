/*
 * The "Apps" channel: Android apps in the Windows Start menu.
 *
 * Host → guest: list what is installed (GetList), uninstall an app (UninstallRequest), here are
 * the window compatibility settings (SyncCompatShims), will the app accept such files (FileShare),
 * stop the app (ForceStopPackage).
 * Guest → host: an app appeared, disappeared or was updated (Status), uninstall finished
 * (UninstallResult), an app's shortcuts changed (JumpListChanged).
 *
 * Schema — proto/apps.proto (the genuine one, from the host WsaClient.exe). Reference for the guest half —
 * AppsRedirectionHandler and AppCompat from SystemApp.apk; the code here is our own. Differences are deliberate
 * and marked in place: package info is read with one query instead of four, an icon without
 * dimensions or a too-heavy icon does not break the listing, the receiver for our own intent
 * is declared non-exported (required since Android 14). Analysis — docs/wsa-shell-protocol.md,
 * section "The "Apps" channel".
 */
package org.wsalineage.shellbridge;

import android.app.ActivityManager;
import android.app.PendingIntent;
import android.app.compat.CompatChanges;
import android.app.compat.PackageOverride;
import android.app.usage.StorageStats;
import android.app.usage.StorageStatsManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.IntentSender;
import android.content.pm.ApplicationInfo;
import android.content.pm.FeatureInfo;
import android.content.pm.InstallSourceInfo;
import android.content.pm.LauncherApps;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.ShortcutInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.PatternMatcher;
import android.os.RemoteException;
import android.os.SystemClock;
import android.os.UserHandle;
import android.text.TextUtils;
import android.util.Slog;
import android.webkit.MimeTypeMap;

import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;

import org.wsalineage.shellbridge.proto.Apps.App;
import org.wsalineage.shellbridge.proto.Apps.AppCompatShim;
import org.wsalineage.shellbridge.proto.Apps.AppLink;
import org.wsalineage.shellbridge.proto.Apps.AppsMessage;
import org.wsalineage.shellbridge.proto.Apps.CompatShim;
import org.wsalineage.shellbridge.proto.Apps.FileShare;
import org.wsalineage.shellbridge.proto.Apps.GetList;
import org.wsalineage.shellbridge.proto.Apps.JumpListChanged;
import org.wsalineage.shellbridge.proto.Apps.JumpListShortcut;
import org.wsalineage.shellbridge.proto.Apps.NonResizeHeuristic;
import org.wsalineage.shellbridge.proto.Apps.Result;
import org.wsalineage.shellbridge.proto.Apps.Status;
import org.wsalineage.shellbridge.proto.Apps.UninstallResult;
import org.wsalineage.shellbridge.proto.Common.ActivityId;
import org.wsalineage.shellbridge.proto.Common.PNG;
import org.wsalineage.shellbridge.proto.ResultCodes.ResultCode;

import java.io.ByteArrayOutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class AppsBridge implements ApiChannel.Handler {
    private static final String TAG = "WsaShellBridge.Apps";
    private static final String CHANNEL = "Apps";
    private static final long RECONNECT_MS = 2_000;
    private static final long REPLY_TIMEOUT_MS = 15_000;

    /** Our own intent for uninstall completion; comes from PackageInstaller. */
    private static final String ACTION_UNINSTALL_DONE =
            "org.wsalineage.shellbridge.APPS_UNINSTALL_DONE";
    private static final String EXTRA_ACTIVITY_ID = "activityId";

    /**
     * Platform change id FORCE_NON_RESIZE_APP. The only compatibility setting
     * the channel knows: Windows asks not to let the window change size.
     */
    private static final long FORCE_NON_RESIZE_APP = 181136395L;

    /** App icon. Windows shows nothing larger anyway, and the channel frame is not elastic. */
    private static final int ICON_MAX_PX = 192;
    private static final int JUMP_LIST_ICON_PX = 24;
    private static final int FALLBACK_ICON_PX = 48;

    /**
     * Apps that Windows does not show in Start: they have their own host replacement
     * (Explorer, camera, settings). The list is from the reference, one to one.
     */
    private static final Set<String> HIDDEN_PACKAGES = new HashSet<>(Arrays.asList(
            "com.android.camera2", "com.android.contacts", "com.android.documentsui",
            "com.android.gallery3d", "com.android.settings", "com.android.traceur"));

    private final Context mContext;
    private final Handler mH;
    private final ApiChannel mChannel = new ApiChannel(CHANNEL, this);
    private final PackageManager mPm;
    private final LauncherApps mLauncherApps;
    private final SimpleDateFormat mUpdateTimeFormat =
            new SimpleDateFormat("yyyyMMdd", Locale.US);

    /** Packages whose uninstall was started by the host: so the reply goes with the same operation id. */
    private final Map<String, String> mUninstallActivityIds = new HashMap<>();

    public AppsBridge(Context context, Handler handler) {
        mContext = context;
        mH = handler;
        mPm = context.getPackageManager();
        mLauncherApps = context.getSystemService(LauncherApps.class);
    }

    public void start() {
        mH.post(() -> {
            registerReceivers();
            connect();
        });
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

    private void registerReceivers() {
        IntentFilter packages = new IntentFilter(Intent.ACTION_PACKAGE_ADDED);
        packages.addAction(Intent.ACTION_PACKAGE_FULLY_REMOVED);
        packages.addAction(Intent.ACTION_PACKAGE_REPLACED);
        packages.addDataScheme("package");
        // Protected system intents — no export flag is required for them.
        mContext.registerReceiver(new PackageEventsReceiver(), packages);

        // But our own intent must declare export explicitly since Android 14, otherwise
        // registerReceiver throws. The reference is built for 13 and sets no flag.
        mContext.registerReceiver(new UninstallDoneReceiver(),
                new IntentFilter(ACTION_UNINSTALL_DONE), Context.RECEIVER_NOT_EXPORTED);

        if (mLauncherApps != null) {
            mLauncherApps.registerCallback(new ShortcutWatcher(), mH);
        } else {
            Slog.w(TAG, "LauncherApps is not available, jump lists will not be reported");
        }
        Slog.i(TAG, "package receivers registered");
    }

    // ---------------------------------------------------------------- host → guest

    @Override
    public byte[] onRequest(byte[] payload) throws ApiChannel.ApiException {
        AppsMessage msg;
        try {
            msg = AppsMessage.parseFrom(payload);
        } catch (InvalidProtocolBufferException e) {
            throw new ApiChannel.ApiException("bad AppsMessage: " + e,
                    ResultCode.InvalidProtocolFormat);
        }
        Slog.i(TAG, "host -> " + msg.getMessageType());
        try {
            switch (msg.getMessageType()) {
                case GetList:
                    return msg.toBuilder().setGetList(buildAppList()).build().toByteArray();
                case UninstallRequest:
                    return uninstall(msg).toByteArray();
                case SyncCompatShims:
                    return applyCompatShims(msg).toByteArray();
                case FileShare:
                    return checkFileShare(msg).toByteArray();
                case ForceStopPackage:
                    forceStop(msg.getForceStopPackage().getPackageName());
                    return result(msg, true, null).toByteArray();
                default:
                    throw new ApiChannel.ApiException(
                            "unexpected message type " + msg.getMessageType(),
                            ResultCode.InvalidProtocolFormat);
            }
        } catch (ApiChannel.ApiException | RuntimeException e) {
            // The error goes in the result field rather than as the frame's return code: the reference does so, and this way
            // the host keeps the text for its log.
            Slog.w(TAG, "request " + msg.getMessageType() + " failed: " + e);
            return result(msg, false, String.valueOf(e.getMessage())).toByteArray();
        }
    }

    private static AppsMessage result(AppsMessage request, boolean success, String message) {
        Result.Builder result = Result.newBuilder().setSuccess(success);
        if (message != null) result.setStatusMessage(message);
        return request.toBuilder().setResult(result.build()).build();
    }

    // ---------------------------------------------------------------- app list

    private GetList buildAppList() {
        long started = SystemClock.uptimeMillis();
        List<ApplicationInfo> installed =
                mPm.getInstalledApplications(PackageManager.ApplicationInfoFlags.of(
                        PackageManager.MATCH_DISABLED_COMPONENTS));
        Collections.sort(installed, new ApplicationInfo.DisplayNameComparator(mPm));
        GetList.Builder list = GetList.newBuilder();
        int skipped = 0;
        for (ApplicationInfo info : installed) {
            App app = describe(info);
            if (app != null) list.addApps(app); else skipped++;
        }
        Slog.i(TAG, "app list: " + list.getAppsCount() + " apps, " + skipped + " skipped, "
                + (SystemClock.uptimeMillis() - started) + " ms");
        return list.build();
    }

    /** Full description of the app for Windows, or null if it should not be shown. */
    private App describe(ApplicationInfo info) {
        if (HIDDEN_PACKAGES.contains(info.packageName)) return null;
        ResolveInfo launch = launchInfo(info.packageName);
        boolean launchable = launch != null;
        boolean systemUpdatedOrThirdParty = (info.flags & ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
                || (info.flags & ApplicationInfo.FLAG_SYSTEM) == 0;
        if (!systemUpdatedOrThirdParty && !launchable) return null;

        App.Builder app = App.newBuilder()
                .setPackageName(info.packageName)
                .setDisplayName(label(info, launch))
                .setDeveloperName(developerName(info.packageName))
                .setIsLaunchable(launchable)
                .setIsUninstallable((info.flags & ApplicationInfo.FLAG_SYSTEM) == 0)
                .setTargetSdkVersion(info.targetSdkVersion)
                .setSizeInBytes(sizeInBytes(info))
                .setInstallSource(installSource(info))
                .setUsesFeaturePc(usesFeature(info.packageName, "android.hardware.type.pc"))
                .addAllIntentFilterDataScheme(intentFilterSchemes(info.packageName))
                .addAllApplinks(appLinks(info.packageName))
                .addAllJumpList(jumpList(info.packageName));

        // For the version, version code, long code and update time the reference calls
        // PackageManager FOUR times per app — once per field. Here it is one query.
        PackageInfo pkg = packageInfo(info.packageName, 0);
        if (pkg != null) {
            long longVersionCode = pkg.getLongVersionCode();
            app.setLongVersionCode(longVersionCode);
            app.setVersionCode(String.valueOf((int) (longVersionCode & 0xffffffffL)));
            if (pkg.versionName != null) app.setVersionName(pkg.versionName);
            app.setLastUpdateTime(mUpdateTimeFormat.format(new java.util.Date(pkg.lastUpdateTime)));
        }

        // setIcon(null) in protobuf-lite throws NullPointerException, and an app's icon may
        // not be found — in the reference the listing breaks off entirely on such a package.
        PNG icon = appIcon(info, launch);
        if (icon != null) app.setIcon(icon);
        return app.build();
    }

    private ResolveInfo launchInfo(String pkg) {
        Intent intent = mPm.getLaunchIntentForPackage(pkg);
        return intent != null ? mPm.resolveActivity(intent, 0) : null;
    }

    private String label(ApplicationInfo info, ResolveInfo launch) {
        try {
            CharSequence label = launch != null ? launch.loadLabel(mPm) : info.loadLabel(mPm);
            return label != null ? label.toString() : info.packageName;
        } catch (RuntimeException e) {
            return info.packageName;
        }
    }

    /**
     * The publisher name is the package name reversed without the last part: com.example.app gives
     * example.com. The reference's trick; looks naive, but Android has no other publisher info.
     */
    private static String developerName(String packageName) {
        int lastDot = packageName.lastIndexOf('.');
        if (lastDot <= 0) return "Unknown";
        String[] parts = packageName.substring(0, lastDot).split("\\.");
        if (parts.length < 2) return "Unknown";
        StringBuilder sb = new StringBuilder();
        for (int i = parts.length - 1; i >= 0; i--) {
            if (sb.length() > 0) sb.append('.');
            sb.append(parts[i]);
        }
        return sb.toString();
    }

    private PackageInfo packageInfo(String pkg, long flags) {
        try {
            return mPm.getPackageInfo(pkg, PackageManager.PackageInfoFlags.of(flags));
        } catch (PackageManager.NameNotFoundException | RuntimeException e) {
            Slog.w(TAG, "no package info for " + pkg + ": " + e);
            return null;
        }
    }

    private long sizeInBytes(ApplicationInfo info) {
        StorageStatsManager stats = mContext.getSystemService(StorageStatsManager.class);
        if (stats == null) return 0;
        try {
            StorageStats s = stats.queryStatsForUid(info.storageUuid, info.uid);
            return s.getAppBytes();
        } catch (Exception e) {
            Slog.w(TAG, "no size for " + info.packageName + ": " + e);
            return 0;
        }
    }

    private String installSource(ApplicationInfo info) {
        if ((info.flags & ApplicationInfo.FLAG_SYSTEM) != 0) return "System";
        try {
            InstallSourceInfo source = mPm.getInstallSourceInfo(info.packageName);
            String installer = source.getInstallingPackageName();
            if (installer == null) return "Sideload";
            String originating = source.getOriginatingPackageName();
            if (originating == null) return installer;
            ApplicationInfo installerInfo = mPm.getApplicationInfo(installer,
                    PackageManager.ApplicationInfoFlags.of(0));
            return (installerInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0 ? originating : installer;
        } catch (PackageManager.NameNotFoundException | RuntimeException e) {
            Slog.w(TAG, "no install source for " + info.packageName + ": " + e);
            return "Unknown";
        }
    }

    private boolean usesFeature(String pkg, String feature) {
        PackageInfo info = packageInfo(pkg, PackageManager.GET_CONFIGURATIONS);
        if (info == null || info.reqFeatures == null) return false;
        for (FeatureInfo requested : info.reqFeatures) {
            if (feature.equals(requested.name)) return true;
        }
        return false;
    }

    /** Schemes the app is willing to open: Windows uses them to decide whom to hand a link. */
    private List<String> intentFilterSchemes(String pkg) {
        List<String> schemes = new ArrayList<>();
        for (IntentFilter filter : allIntentFilters(pkg)) {
            if (!filter.hasAction(Intent.ACTION_VIEW)
                    || !filter.hasCategory(Intent.CATEGORY_DEFAULT)
                    || filter.countDataTypes() > 0
                    || filter.countDataSchemes() == 0) {
                continue;
            }
            Iterator<String> it = filter.schemesIterator();
            while (it != null && it.hasNext()) {
                String scheme = it.next();
                // http/https with a specified host are app links, they go into applinks.
                boolean web = "http".equals(scheme) || "https".equals(scheme);
                if (!web || filter.countDataAuthorities() <= 0) schemes.add(scheme);
            }
        }
        return schemes;
    }

    private List<AppLink> appLinks(String pkg) {
        List<AppLink> links = new ArrayList<>();
        for (IntentFilter filter : allIntentFilters(pkg)) {
            Set<String> hosts = new LinkedHashSet<>();
            Set<String> paths = new LinkedHashSet<>();
            if (filter.hasAction(Intent.ACTION_VIEW)
                    && filter.hasCategory(Intent.CATEGORY_DEFAULT)
                    && filter.hasCategory(Intent.CATEGORY_BROWSABLE)
                    && filter.getAutoVerify()
                    && filter.countDataTypes() == 0
                    && (filter.hasDataScheme("http") || filter.hasDataScheme("https"))) {
                hosts.addAll(filter.getHostsList());
                Iterator<PatternMatcher> it = filter.pathsIterator();
                while (it != null && it.hasNext()) {
                    PatternMatcher path = it.next();
                    paths.add(pathPatternPrefix(path.getType()) + path.getPath());
                }
            }
            links.add(AppLink.newBuilder().addAllHostname(hosts).addAllPath(paths).build());
        }
        return links;
    }

    private List<IntentFilter> allIntentFilters(String pkg) {
        try {
            return mPm.getAllIntentFilters(pkg);
        } catch (RuntimeException e) {
            Slog.w(TAG, "no intent filters for " + pkg + ": " + e);
            return Collections.emptyList();
        }
    }

    private static String pathPatternPrefix(int type) {
        switch (type) {
            case PatternMatcher.PATTERN_LITERAL: return "LITERAL:";
            case PatternMatcher.PATTERN_PREFIX: return "PREFIX:";
            case PatternMatcher.PATTERN_SIMPLE_GLOB: return "GLOB:";
            case PatternMatcher.PATTERN_ADVANCED_GLOB: return "ADVANCED:";
            case PatternMatcher.PATTERN_SUFFIX: return "SUFFIX:";
            default: return "UNKNOWN:";
        }
    }

    // ---------------------------------------------------------------- shortcuts and icons

    private List<JumpListShortcut> jumpList(String pkg) {
        List<JumpListShortcut> shortcuts = new ArrayList<>();
        if (mLauncherApps == null) return shortcuts;
        LauncherApps.ShortcutQuery query = new LauncherApps.ShortcutQuery()
                .setQueryFlags(LauncherApps.ShortcutQuery.FLAG_MATCH_MANIFEST
                        | LauncherApps.ShortcutQuery.FLAG_MATCH_DYNAMIC)
                .setPackage(pkg);
        List<ShortcutInfo> found;
        try {
            found = mLauncherApps.getShortcuts(query, UserHandle.SYSTEM);
        } catch (RuntimeException e) {
            Slog.w(TAG, "no shortcuts for " + pkg + ": " + e);
            return shortcuts;
        }
        if (found == null) return shortcuts;
        int dpi = mContext.getResources().getDisplayMetrics().densityDpi;
        for (ShortcutInfo shortcut : found) {
            if (!shortcut.isEnabled() || shortcut.getActivity() == null) continue;
            CharSequence name = shortcut.getLongLabel();
            if (TextUtils.isEmpty(name)) name = shortcut.getShortLabel();
            if (TextUtils.isEmpty(name)) name = shortcut.getId();
            JumpListShortcut.Builder builder = JumpListShortcut.newBuilder()
                    .setShortcutId(shortcut.getId())
                    .setDisplayName(name.toString());
            PNG icon = toPng(safely(() -> mLauncherApps.getShortcutIconDrawable(shortcut, dpi)),
                    JUMP_LIST_ICON_PX);
            if (icon != null) builder.setIcon(icon);
            shortcuts.add(builder.build());
        }
        return shortcuts;
    }

    private PNG appIcon(ApplicationInfo info, ResolveInfo launch) {
        Drawable icon = safely(() ->
                launch != null ? launch.activityInfo.loadIcon(mPm) : info.loadIcon(mPm));
        if (icon == null) Slog.w(TAG, "no icon for " + info.packageName);
        return toPng(icon, ICON_MAX_PX);
    }

    private interface DrawableSource { Drawable get(); }

    private Drawable safely(DrawableSource source) {
        try {
            return source.get();
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * Icon as PNG, no larger than maxPx per side. The size limit is ours: icons travel through the channel
     * WHOLE and all at once in a single GetList frame, and an adaptive icon unpacks into hundreds of
     * kilobytes. A hundred apps push the frame over the limit, and the channel breaks for no apparent reason.
     */
    private PNG toPng(Drawable drawable, int maxPx) {
        if (drawable == null) return null;
        Bitmap bitmap = null;
        try {
            if (drawable instanceof BitmapDrawable) bitmap = ((BitmapDrawable) drawable).getBitmap();
            if (bitmap == null) {
                // There may be no dimensions at all (ColorDrawable returns -1) — createBitmap
                // throws on that, and in the reference this breaks the whole app listing.
                int w = drawable.getIntrinsicWidth() > 0
                        ? drawable.getIntrinsicWidth() : FALLBACK_ICON_PX;
                int h = drawable.getIntrinsicHeight() > 0
                        ? drawable.getIntrinsicHeight() : FALLBACK_ICON_PX;
                bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
                Canvas canvas = new Canvas(bitmap);
                drawable.setBounds(0, 0, w, h);
                drawable.draw(canvas);
            }
            int side = Math.max(bitmap.getWidth(), bitmap.getHeight());
            if (side > maxPx) {
                int w = Math.max(1, bitmap.getWidth() * maxPx / side);
                int h = Math.max(1, bitmap.getHeight() * maxPx / side);
                bitmap = Bitmap.createScaledBitmap(bitmap, w, h, true);
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) return null;
            return PNG.newBuilder()
                    .setWidth(bitmap.getWidth())
                    .setHeight(bitmap.getHeight())
                    .setFormat(pngFormat(bitmap.getConfig()))
                    .setBytes(ByteString.copyFrom(out.toByteArray()))
                    .setSize(out.size())
                    .build();
        } catch (RuntimeException e) {
            Slog.w(TAG, "cannot convert icon: " + e);
            return null;
        }
    }

    private static PNG.Format pngFormat(Bitmap.Config config) {
        if (config == null) return PNG.Format.ARGB_8888;
        switch (config) {
            case ALPHA_8: return PNG.Format.ALPHA_8;
            case ARGB_4444: return PNG.Format.ARGB_4444;
            case HARDWARE: return PNG.Format.HARDWARE;
            case RGBA_F16: return PNG.Format.RGBA_F16;
            case RGB_565: return PNG.Format.RGB_565;
            default: return PNG.Format.ARGB_8888;
        }
    }

    // ---------------------------------------------------------------- uninstall

    private AppsMessage uninstall(AppsMessage msg) throws ApiChannel.ApiException {
        String pkg = msg.getUninstallRequest().getApp().getPackageName();
        String activityId = msg.getActivityId().getValue();
        PackageInstaller installer = mPm.getPackageInstaller();
        Intent done = new Intent(ACTION_UNINSTALL_DONE)
                .setPackage(mContext.getPackageName())
                .putExtra(EXTRA_ACTIVITY_ID, activityId);
        IntentSender sender = PendingIntent.getBroadcast(mContext, pkg.hashCode(), done,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE).getIntentSender();
        synchronized (mUninstallActivityIds) {
            mUninstallActivityIds.put(pkg, activityId);
        }
        try {
            installer.uninstall(pkg, sender);
        } catch (RuntimeException e) {
            synchronized (mUninstallActivityIds) {
                mUninstallActivityIds.remove(pkg);
            }
            throw new ApiChannel.ApiException("uninstall " + pkg + " failed: " + e, ResultCode.Fail);
        }
        Slog.i(TAG, "uninstall started: " + pkg);
        // Only "accepted for execution" here; how it ended — in a separate UninstallResult.
        return result(msg, true, null);
    }

    private final class UninstallDoneReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            String pkg = intent.getStringExtra(PackageInstaller.EXTRA_PACKAGE_NAME);
            if (pkg == null) return;
            int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS,
                    PackageInstaller.STATUS_FAILURE);
            String message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
            String activityId = intent.getStringExtra(EXTRA_ACTIVITY_ID);
            boolean success = status == PackageInstaller.STATUS_SUCCESS;
            Slog.i(TAG, "uninstall done: " + pkg + " status=" + status
                    + (message != null ? " (" + message + ")" : ""));
            synchronized (mUninstallActivityIds) {
                mUninstallActivityIds.remove(pkg);
            }
            Result.Builder result = Result.newBuilder().setSuccess(success);
            if (message != null) result.setStatusMessage(message);
            App.Builder app = App.newBuilder().setPackageName(pkg);
            String label = labelOf(pkg);
            if (label != null) app.setDisplayName(label);
            AppsMessage msg = newMessage(AppsMessage.MessageType.UninstallResult, activityId)
                    .setUninstallResult(UninstallResult.newBuilder()
                            .setApp(app.build()).setResult(result.build()).build())
                    .build();
            mH.post(() -> send(msg));
        }
    }

    private void forceStop(String pkg) throws ApiChannel.ApiException {
        try {
            ActivityManager.getService().forceStopPackage(pkg, mContext.getUserId());
            Slog.i(TAG, "force stopped " + pkg);
        } catch (RemoteException | RuntimeException e) {
            throw new ApiChannel.ApiException("force stop " + pkg + " failed: " + e, ResultCode.Fail);
        }
    }

    // ---------------------------------------------------------------- window compatibility

    private AppsMessage applyCompatShims(AppsMessage msg) {
        for (AppCompatShim shim : msg.getApplyCompatShims().getAppShimsList()) {
            applyShims(shim.getPackageName(), shim.getShimsList());
        }
        return result(msg, true, null);
    }

    private void applyShims(String pkg, List<CompatShim> shims) {
        Map<Long, PackageOverride> overrides = new HashMap<>();
        for (CompatShim shim : shims) {
            if (shim.getShimId() != CompatShim.ShimId.ForceNonResizeableApp) {
                Slog.w(TAG, "unknown compat shim " + shim.getShimId() + " for " + pkg);
                continue;
            }
            if (!shim.hasEnabled()) {
                Slog.w(TAG, "compat shim " + shim.getShimId() + " for " + pkg + " has no flag");
                continue;
            }
            overrides.put(FORCE_NON_RESIZE_APP,
                    new PackageOverride.Builder().setEnabled(shim.getEnabled()).build());
        }
        if (overrides.isEmpty()) return;
        try {
            CompatChanges.putPackageOverrides(pkg, overrides);
            Slog.i(TAG, "compat overrides applied to " + pkg + ": " + overrides.size());
        } catch (RuntimeException e) {
            Slog.w(TAG, "cannot apply compat overrides to " + pkg + ": " + e);
        }
    }

    /** What the guest thinks about window resizing — the host takes it into account when deciding what to send. */
    private NonResizeHeuristic nonResizeHeuristic(ApplicationInfo info) {
        NonResizeHeuristic.Builder heuristic = NonResizeHeuristic.newBuilder()
                .setHeuristicsEnabledProperty(true);
        if ((info.flags & ApplicationInfo.FLAG_SYSTEM) != 0) {
            return heuristic.setApply(false)
                    .setCategory(NonResizeHeuristic.HeuristicCategory.SYSTEM_APP).build();
        }
        if (info.targetSdkVersion < 24) {
            return heuristic.setApply(true)
                    .setCategory(NonResizeHeuristic.HeuristicCategory.RESIZE_LOWER_SDK).build();
        }
        ResolveInfo launch = launchInfo(info.packageName);
        if (launch != null && launch.activityInfo != null && launch.activityInfo.isFixedOrientation()) {
            return heuristic.setApply(true).setCategory(
                    NonResizeHeuristic.HeuristicCategory.LAUNCH_ACTIVITY_FIXED_ORIENTATION).build();
        }
        return heuristic.setApply(false)
                .setCategory(NonResizeHeuristic.HeuristicCategory.NO_HEURISTICS).build();
    }

    // ---------------------------------------------------------------- receiving files from Windows

    private AppsMessage checkFileShare(AppsMessage msg) {
        FileShare request = msg.getFileShare();
        List<String> extensions = request.getExtensionList();
        String action = extensions.size() > 1 ? Intent.ACTION_SEND_MULTIPLE : Intent.ACTION_SEND;
        List<String> supported = new ArrayList<>();
        for (String extension : extensions) {
            String mime = extension.length() > 1
                    ? MimeTypeMap.getSingleton().getMimeTypeFromExtension(
                            extension.substring(1).toLowerCase(Locale.US))
                    : null;
            if (mime == null) mime = "*/*";
            Intent send = new Intent(action).setType(mime).setPackage(request.getPackageName());
            if (!mPm.queryIntentActivities(send, 0).isEmpty()) supported.add(extension);
        }
        FileShare.ShareSupport support;
        if (supported.isEmpty()) {
            support = FileShare.ShareSupport.NotShareable;
        } else if (supported.size() == extensions.size()) {
            support = FileShare.ShareSupport.Shareable;
        } else {
            support = FileShare.ShareSupport.PartiallyShareable;
        }
        FileShare response = FileShare.newBuilder()
                .setPackageName(request.getPackageName())
                .setResponse(FileShare.CheckResponse.newBuilder()
                        .setShareSupport(support)
                        .setTotalFileCount(extensions.size())
                        .setSupportedFileCount(supported.size())
                        .build())
                .addAllAllowedExtensions(supported)
                .build();
        return msg.toBuilder().setFileShare(response).build();
    }

    // ---------------------------------------------------------------- guest → host

    private final class PackageEventsReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent.getData() == null) return;
            String pkg = intent.getData().getEncodedSchemeSpecificPart();
            String action = intent.getAction();
            if (Intent.ACTION_PACKAGE_ADDED.equals(action)) {
                if (intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)) return;
                mH.post(() -> sendStatus(pkg, Status.Action.ADDED));
            } else if (Intent.ACTION_PACKAGE_REPLACED.equals(action)) {
                mH.post(() -> sendStatus(pkg, Status.Action.REPLACED));
            } else if (Intent.ACTION_PACKAGE_FULLY_REMOVED.equals(action)) {
                mH.post(() -> sendRemoved(pkg));
            }
        }
    }

    private final class ShortcutWatcher extends LauncherApps.Callback {
        @Override public void onPackageAdded(String pkg, UserHandle user) { }
        @Override public void onPackageChanged(String pkg, UserHandle user) { }
        @Override public void onPackageRemoved(String pkg, UserHandle user) { }
        @Override public void onPackagesAvailable(String[] pkgs, UserHandle u, boolean r) { }
        @Override public void onPackagesUnavailable(String[] pkgs, UserHandle u, boolean r) { }

        @Override
        public void onShortcutsChanged(String pkg, List<ShortcutInfo> shortcuts, UserHandle user) {
            mH.post(() -> sendJumpListChanged(pkg));
        }
    }

    private void sendStatus(String pkg, Status.Action action) {
        if (!mChannel.isOpen()) return;
        ApplicationInfo info;
        try {
            info = mPm.getApplicationInfo(pkg, PackageManager.ApplicationInfoFlags.of(
                    PackageManager.MATCH_DISABLED_COMPONENTS));
        } catch (PackageManager.NameNotFoundException | RuntimeException e) {
            Slog.w(TAG, "no application info for " + pkg + ": " + e);
            return;
        }
        App app = describe(info);
        if (app == null) return;
        AppsMessage msg = newMessage(AppsMessage.MessageType.Status, null)
                .setStatus(Status.newBuilder()
                        .setApp(app)
                        .setAction(action)
                        .setNonResizeHeuristic(nonResizeHeuristic(info))
                        .build())
                .build();
        Slog.i(TAG, action + " " + pkg);
        // The host's reply carries compatibility settings for this package — they must be applied.
        try {
            byte[] reply = mChannel.send(msg.toByteArray(), REPLY_TIMEOUT_MS);
            if (reply != null) {
                applyShims(pkg, AppsMessage.parseFrom(reply).getStatus().getShimsList());
            }
        } catch (ApiChannel.ApiException | InvalidProtocolBufferException e) {
            Slog.w(TAG, "status for " + pkg + " failed: " + e);
        }
    }

    private void sendRemoved(String pkg) {
        String activityId;
        synchronized (mUninstallActivityIds) {
            activityId = mUninstallActivityIds.remove(pkg);
        }
        App.Builder app = App.newBuilder().setPackageName(pkg);
        String label = labelOf(pkg);
        if (label != null) app.setDisplayName(label);
        Slog.i(TAG, "REMOVED " + pkg);
        send(newMessage(AppsMessage.MessageType.Status, activityId)
                .setStatus(Status.newBuilder()
                        .setApp(app.build()).setAction(Status.Action.REMOVED).build())
                .build());
    }

    private void sendJumpListChanged(String pkg) {
        if (!mChannel.isOpen()) return;
        List<JumpListShortcut> shortcuts = jumpList(pkg);
        Slog.i(TAG, "jump list of " + pkg + ": " + shortcuts.size() + " shortcuts");
        send(newMessage(AppsMessage.MessageType.JumpListChanged, null)
                .setJumpListChanged(JumpListChanged.newBuilder()
                        .setApp(App.newBuilder()
                                .setPackageName(pkg).addAllJumpList(shortcuts).build())
                        .build())
                .build());
    }

    private String labelOf(String pkg) {
        try {
            ApplicationInfo info = mPm.getApplicationInfo(pkg,
                    PackageManager.ApplicationInfoFlags.of(PackageManager.MATCH_UNINSTALLED_PACKAGES));
            CharSequence label = info.loadLabel(mPm);
            return label != null ? label.toString() : null;
        } catch (PackageManager.NameNotFoundException | RuntimeException e) {
            return null;
        }
    }

    private static AppsMessage.Builder newMessage(AppsMessage.MessageType type, String activityId) {
        return AppsMessage.newBuilder()
                .setMessageType(type)
                .setActivityId(ActivityId.newBuilder()
                        .setValue(activityId != null ? activityId : UUID.randomUUID().toString())
                        .build());
    }

    private void send(AppsMessage msg) {
        try {
            mChannel.sendAsync(msg.toByteArray());
        } catch (ApiChannel.ApiException e) {
            Slog.w(TAG, "send " + msg.getMessageType() + " failed: " + e.getMessage());
        }
    }
}
