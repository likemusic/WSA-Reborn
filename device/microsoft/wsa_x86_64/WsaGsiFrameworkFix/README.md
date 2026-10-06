# `WsaGsiFrameworkFix` — `framework-res` RRO overlay for path A (prebuilt GSI)

Needed **only on path A**, when the system is taken as a prebuilt Google GSI rather than built from source. On path B both fixes below are done differently: the first by patch `0011` (the `WsaDisplayAreaPolicyProvider` class in `frameworks/base`), the second by a product overlay of our build.

Until 24.09.2026 the module was called `WsaGsiDisplayAreaFix` (package `com.wsa.gsi.displayareafix`) and carried only the first fix. It was renamed when it turned out that the second one is fixed the same way and in the same file — there is no point in keeping two static RROs for one `targetPackage`.

**Common cause of both.** Microsoft's frozen `vendor.img` carries the static RRO `/vendor/overlay/framework-res__auto_generated_rro_vendor.apk` with `priority=0`, which overrides the platform configuration for WSA. Our overlay on the same `targetPackage="android"` with `priority=100` overrides it back where Microsoft's assumptions do not hold on a stock GSI.

**What it fixes.**

1. `config_deviceSpecificDisplayAreaPolicyProvider` → empty string. Microsoft's overlay sets it to `com.android.server.wm.WsaDisplayAreaPolicyProvider`, which does not exist in a stock GSI. An empty string is the regular AOSP path: `DisplayAreaPolicy.Provider.fromResources()` takes `DefaultProvider` when `TextUtils.isEmpty(name)`. **Works — verified live on 24.09.2026:** with the overlay the class is never mentioned in the log and `WindowManagerService` starts normally. (Worth remembering separately: the 23.09 crash loop was attributed to exactly this — and attributed WRONGLY; the real cause was `IsolatedCompilationService`, see `docs/findings.md`.)
2. `config_enableWallpaperService` → `true`. Microsoft's overlay disables the regular wallpaper service; without `WallpaperManagerService` the very first call to `WallpaperManager.lockScreenWallpaperExists()` from `CentralSurfacesImpl` throws `NullPointerException` on `WallpaperManager$Globals.mService`, `com.android.systemui` goes into a crash loop and drags `com.android.launcher3` along. On the live run of 24.09 this produced 335 `systemui` and 327 `launcher3` crashes on a fully booted system.

**What it costs.** The WSA-specific display area placement policy is not applied at all. This fixes boot and Android windows, but how it affects Windows windows has NOT been verified; if it does, the proper path is more expensive: put the class itself into the GSI `services.jar` with a dex patch (source — `docs/patches/0011-wsa-display-area-policy-provider.java`).

**Build and install** (build-tools 34, `platform.*` keys — the same ones the GSI is signed with: the certificate fingerprint was checked against the image's `framework-res.apk`, `c8a2e9bc…`):

    aapt2 compile -o compiled.zip --dir res
    aapt2 link -o unsigned.apk --manifest AndroidManifest.xml -I android.jar compiled.zip \
        --min-sdk-version 33 --target-sdk-version 34
    zipalign -f 4 unsigned.apk aligned.apk
    apksigner sign --key platform.pk8 --cert platform.x509.pem --out WsaGsiFrameworkFix.apk aligned.apk
    bin/add-image-file.sh system_ext.img WsaGsiFrameworkFix.apk \
        /overlay/WsaGsiFrameworkFix.apk u:object_r:vendor_overlay_file:s0 0644 0:0

The `vendor_overlay_file` label is not a typo: in `plat_file_contexts` it is exactly the one set on `/(system_ext|system/system_ext)/overlay(/.*)?`. When replacing the old overlay, the new file is put next to it and the old one is removed with `debugfs -w -R "rm /overlay/<old>.apk"` followed by `e2fsck -fy` (twice: the first pass fixes the counters, the second confirms).

## Overlay configuration — the second required file

A static RRO is enabled not by the mere presence of the apk but by an entry in `/system_ext/overlay/config/config.xml`. The working copy of this file lives right here, in `overlay-config/config.xml`, and is put into the image like this:

    bin/patch-system-file.sh system_ext.img /overlay/config/config.xml overlay-config/config.xml

**The package name in this file must match the apk manifest.** On a mismatch the zygote dies while parsing the configuration:

    E Zygote: Error preloading com.android.internal.telephony.GsmAlphabet.
    Caused by: java.lang.IllegalStateException: overlay com.wsa.gsi.displayareafix not present in partition /system_ext/overlay
	    at com.android.internal.content.om.OverlayConfigParser.parseOverlay
    E Zygote: System zygote died with fatal exception

Boot then does not start at all: `system_server` never starts, the log has not a single milestone, and it grows only from repeated `No Compatibility callbacks set!`. Caught on 24.09.2026 exactly when this overlay was renamed — the apk was replaced, the config was forgotten.
