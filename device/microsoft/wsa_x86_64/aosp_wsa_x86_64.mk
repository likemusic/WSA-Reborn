# AOSP target for Windows Subsystem for Android, x86_64 — a control target, paired with
# lineage_wsa_x86_64.mk. IMPORTANT (22.09.2026): "without a single line of LineageOS" did not work out —
# the platform in this tree is patched, see the note about lineage-sdk at the end of the file; the target gives
# an AOSP PRODUCT (SystemUI, Settings, launcher, no LineageParts/SetupWizard) on the Lineage platform.
#
# Why it exists: LineageOS 21 in the same configuration (see lineage_wsa_x86_64.mk)
# builds and packs normally, but does not boot in a real WSA — the guest
# exits on its own (`GracefulExit`/`PowerOff`, `Initiator: GuestOS`) within the first
# 2-3 seconds, before adb comes up. It is unknown whether this is a general problem of
# Android 14 on the current WSA vendor/kernel (Android 13) or specific to the
# LineageOS build/code. This target is the same test on the most neutral base,
# closest to Google's reference: if it fails the same way, the problem is Android 14
# in general; if it boots, the problem is LineageOS.
#
# The base is ONLY the system partition from the stock AOSP GSI target
# (generic_system_x86_64.mk). We deliberately do NOT take handheld_system_ext.mk /
# telephony_system_ext.mk / aosp_product.mk — they are meant for a full-featured
# phone (APN, telephony, etc.) and bring conflicts with Soong installs of the
# same paths (PRODUCT_COPY_FILES is resolved through the deferred product graph —
# filtering it in a child .mk did not work). None of that is needed to check "does Android 14 boot at all on this
# vendor/kernel" — system_ext/product can be
# minimal, as long as they exist as separate partitions (WSA mounts them
# as separate disks).
$(call inherit-product, $(SRC_TARGET_DIR)/product/generic_system_x86_64.mk)

$(call inherit-product, device/microsoft/wsa_x86_64/device.mk)

# generic_system_x86_64.mk is system only; the system_ext/product skeleton (fs_config, group/passwd,
# selinux_policy_system_ext — without it system_ext has no etc/selinux at all and transplant-a14.sh
# has nowhere to append Microsoft's CIL, VINTF manifests) has to be added separately (22.09.2026,
# second AOSP build: system_ext without etc/selinux, product without fs_config).
$(call inherit-product, $(SRC_TARGET_DIR)/product/base_system_ext.mk)
$(call inherit-product, $(SRC_TARGET_DIR)/product/base_product.mk)

# VNDK snapshot for Microsoft's vendor (ro.vndk.version=33). In the LineageOS GSI it comes from
# gsi_release.mk (all versions 30–34 in system_ext/apex); without it linkerconfig fails in the very first
# second ("SANITIZER_DEFAULT_VENDOR is not defined" — the variables come from
# /apex/com.android.vndk.v33), /linkerconfig is not generated, keystore2 does not link
# (libandroidicu.so), the guest never reaches adbd — found 22.09.2026 on the qemu-boot.sh stand.
# On Android 13 (SDK 33) VNDK v33 is the platform's own — no snapshot, and asking for it fails
# with "VNDK version 33 not found" (11.10.2026, aosp13-vanilla).
ifneq ($(PLATFORM_SDK_VERSION),)
ifeq ($(call math_gt_or_eq,$(PLATFORM_SDK_VERSION),34),true)
PRODUCT_EXTRA_VNDK_VERSIONS := 33
PRODUCT_PACKAGES += vndk_apex_snapshot_package
endif
endif
# Same as GSI: do not wipe /data keys on metadata init — the pool is shared with LineageOS 21.
PRODUCT_PRODUCT_PROPERTIES += ro.crypto.metadata_init_delete_all_keys.enabled=false

# Disable the privapp-permissions check — exactly as AOSP's own GSI targets do
# (build/make/target/board/gsi_system_ext.prop). Reason: this WSA installation carries Google services
# (GmsCore, Phonesky, Velvet, AndroidAutoStub), which Magisk mounts into /product on top of
# our image from the subsystem ramdisk, and their privapp-permissions lists are not in our product.
# Without this system_server crashes at startup (23.09.2026, vanilla AOSP 14 on a live WSA):
#   Signature|privileged permissions not in privapp-permissions allowlist:
#   {com.google.android.projection.gearhead …SCHEDULE_EXACT_ALARM,
#    com.google.android.googlequicksearchbox …SCHEDULE_EXACT_ALARM}
# In the LineageOS tree the same problem was solved by their `ro.control_privapp_permissions?=enforce`
# in vendor/lineage/config/common.mk plus disable in lineage_wsa_x86_64.mk. In the vanilla tree
# the property is not set anywhere, so we simply declare it — there will be no sysprop conflict.
# IMPORTANT, where to put it: PRODUCT_SYSTEM_EXT_PROPERTIES does not work — the property ends up in
# /system_ext/etc/build.prop (checked in the image and in the guest), but `getprop` still returns `enforce`,
# and system_server keeps crashing; while exactly the same line in /product/etc/build.prop
# is applied and the system boots (both checks — 23.09.2026 on a live WSA, the files' SELinux labels
# are the same `u:object_r:system_file:s0`, no property_service errors in the log). Reason (05.10.2026):
# Microsoft's vendor/build.prop sets `enforce` and is read after system and system_ext, but before product.
PRODUCT_PRODUCT_PROPERTIES += ro.control_privapp_permissions=disable

# FUSE without BPF (03.11.2026, probe 7 / aosp17-vanilla). bpfloader of A15+ refuses Microsoft's kernel 5.15.104
# ("Android V+ requires 5.15 kernel to be 5.15.136+"), so MediaProvider's FuseDaemon starts without the fuseMedia
# program, while vold, seeing /sys/fs/fuse/features/fuse_bpf, takes BPF as running and skips the bind mounts of
# Android/data and Android/obb: Play (mountMode=INSTALLER) gets "Rejected access to app-private dir on FUSE" and
# cannot install a game with OBB. With false both sides go without BPF and the bind mounts are back. In product, like
# the line above: checked live, `getprop ro.fuse.bpf.enabled` false (as root — storage_config_prop), 8 bind mounts,
# Play wrote the 108 MB OBB of Strawberry Shortcake. A14 not checked (its bpfloader may accept 5.15.104).
PRODUCT_PRODUCT_PROPERTIES += ro.fuse.bpf.enabled=false

# generic_system_x86_64.mk is only the system part of GSI: no SystemUI, Settings or launcher
# (the first build on 22.09.2026 produced a 1.3 MB system_ext with just WsaShellBridge). We take from
# handheld_system_ext.mk / handheld_product.mk exactly what windows and settings need,
# without inheriting the .mk files themselves (they bring telephony and PRODUCT_COPY_FILES conflicts, see above).
# SystemUI is mandatory: it hosts WM Shell (patch 0007, docs/patches.md, Shell Transitions),
# without which freeform tasks do not move along the host's bounds.
# Launcher is TrebuchetQuickStep: the LineageOS tree has no packages/apps/Launcher3 at all (replaced by
# Trebuchet, vendor/lineage/config/common.mk excludes Launcher3* from the existence check),
# so Launcher3QuickStep silently did not get into any image in the second build on 22.09.2026.
PRODUCT_PACKAGES += \
    SystemUI \
    Settings \
    SettingsIntelligence \
    Provision \
    WallpaperCropper \
    LatinIME \
    androidx.window.extensions \
    androidx.window.sidecar \
    hwservicemanager \
    android.hidl.allocator@1.0-service \
    webview

# The launcher depends on the tree: in vanilla AOSP it is the stock Launcher3QuickStep, while the LineageOS tree
# has no packages/apps/Launcher3 at all (replaced by Trebuchet, and vendor/lineage/config/common.mk excludes
# Launcher3* from the existence check — that is why on 22.09.2026 Launcher3QuickStep silently did not get
# into any image there). Choose by the presence of the directory so the file works for both trees.
ifneq ($(wildcard packages/apps/Launcher3),)
ifeq ($(call math_lt,$(PLATFORM_SDK_VERSION),34),true)
# Android 13: Launcher3QuickStep's taskbar crashes in NavbarButtonsViewController on WSA's large screen
# ("Quickstep keeps stopping" ends up on the Windows desktop); the bridge is HOME anyway, and window
# management is the host's — the plain Launcher3 is enough (11.10.2026).
PRODUCT_PACKAGES += Launcher3
else
PRODUCT_PACKAGES += Launcher3QuickStep
endif
else
PRODUCT_PACKAGES += TrebuchetQuickStep
endif
# androidx.window.* — WM extensions for SystemUI/WM Shell (present in LineageOS system_ext,
# absent from generic_system); hwservicemanager/allocator — HIDL HALs of Microsoft's vendor (in LineageOS
# they are in system_ext); webview — in AOSP it lives in product via handheld_product.mk, which we do not
# inherit, and the autotest apps cannot do without WebView.

# packages/apps/FMRadio (pulled in by the common product list) requires an explicit
# namespace for libfmjni — the LineageOS target gets it implicitly through its
# own inheritance chain, here it has to be set manually.
PRODUCT_SOONG_NAMESPACES += packages/apps/FMRadio/jni/fmr

# Separate images instead of being nested in system.img — same as for the
# LineageOS target, for the same reason (WSA mounts product/system_ext
# as separate disks).
PRODUCT_BUILD_SYSTEM_IMAGE     := true
PRODUCT_BUILD_SYSTEM_EXT_IMAGE := true
PRODUCT_BUILD_PRODUCT_IMAGE    := true

PRODUCT_BUILD_VENDOR_IMAGE     := false
PRODUCT_BUILD_BOOT_IMAGE       := false
PRODUCT_BUILD_RECOVERY_IMAGE   := false
PRODUCT_BUILD_SUPER_PARTITION  := false

PRODUCT_ENFORCE_ARTIFACT_PATH_REQUIREMENTS := false
PRODUCTS.build/make/target/product/generic_system.mk.ARTIFACT_PATH_ALLOWED_LIST += \
    system_ext/% \
    product/%

# build/make in this tree is LineageOS's fork of AOSP, not vanilla Google: their
# media_system.mk (inherited via generic_system_x86_64.mk) unconditionally
# adds org.lineageos.platform to PRODUCT_SYSTEM_SERVER_JARS, even for a
# target without a single line of LineageOS code. We do not inherit the package itself (vendor/lineage/config/
# common.mk) — so the jar is declared but not built, and
# dex_preopt_check.mk fails the build ("Missing compilation artifacts",
# found 18.09.2026 on the first build of this target). Since the target is meant as a
# control WITHOUT LineageOS at all — an attempt to remove the entry via
# PRODUCT_SYSTEM_SERVER_JARS := $(filter-out ...) DID NOT WORK (get_build_var
# after it still shows org.lineageos.platform — the entry is added
# at some later/hidden stage of product processing, not in the usual
# $(call inherit-product,...) order). It is simpler and more reliable to just disable
# the check itself — for a diagnostic control target (we check whether
# the system boots at all on a real WSA) correctness of dexpreopt of system jars
# does not matter, similar to the already disabled PRODUCT_ENFORCE_ARTIFACT_PATH_REQUIREMENTS.
DISABLE_DEXPREOPT_CHECK := true

# 22.09.2026, first run of this target on a live WSA: the guest brings up adb, but is in a
# bootloop — zygote crashes every 5 s with `java.io.IOException: Failed to load asset path
# /system/framework/org.lineageos.platform-res.apk` (AssetManager.createSystemAssetsInZygoteLocked
# → Resources.getSystem → GsmAlphabet.<clinit> in preloadClasses). The cause is the same as in the note
# about media_system.mk above, only deeper: the LineageOS fork patches not only build/make, but the
# framework itself — `frameworks/base/core/java/android/content/res/AssetManager.java:71` holds a
# HARDCODED path LINEAGE_APK_PATH and loads it unconditionally (the same path is duplicated in
# core/jni/fd_utils.cpp as an allowed zygote fd). No product from this tree, including
# "pure AOSP", starts without lineage-sdk — this cannot be worked around at the product level.
#
# Hence a conclusion important for plan §4: the `aosp_wsa_x86_64` target in the LineageOS tree is NOT
# vanilla AOSP, but "an AOSP product on the LineageOS platform" (framework with Lineage patches +
# lineage-sdk). Real vanilla AOSP requires a separate sync of the google/AOSP tree
# (~100 GB and a full build from scratch) — for the "what to use as the base" comparison this does not pay off:
# we compare the product part, the platform is shared. So we pull in the stock LineageOS SDK set
# — it also fixes the system-server jar declared in media_system.mk.
#
# We inherit lineage_sdk_common.mk entirely. An attempt to save space and take only two packages from it
# (org.lineageos.platform-res + .jar), without the seven permissions xml, failed in three rounds over the evening
# of 22.09.2026 — each new layer surfaced only after the previous one stopped failing:
#   1) without SDK packages             — zygote does not create the system AssetManager (see above);
#   2) without LineageSettingsProvider  — system_server crashes with an NPE in DisplayPolicy.updateSettings:
#      Lineage patches read LineageSettings from six places (DisplayPolicy, PhoneWindowManager,
#      PowerManagerService, DisplayPowerController, InputSettingsObserver, InputMethodManagerService);
#   3) without permissions xml          — SystemUI (also from this tree, also patched) crash-loops:
#      "Couldn't get lineageglobalactions on binder". The reason is written right in
#      vendor/lineage/config/permissions/org.lineageos.globalactions.xml: LineageSystemServer
#      publishes the service only if PackageManager.hasSystemFeature(...) — and the feature is declared by this xml.
# Conclusion: in this tree the "AOSP product" cannot be separated from the LineageOS platform — the SDK, the settings
# provider and the feature declarations are needed in full. The difference from lineage_wsa_x86_64 comes down to the set
# of apps (no LineageParts, LineageSetupWizard, etc.), not to the "purity" of the platform.
# PRODUCT_RESTRICT_VENDOR_FILES := false below is mandatory: the xml files are in vendor/, and without it
# build/make/core/tasks/vendor_module_check.mk fails ckati ("has unknown owner") — the LineageOS target
# disables the same check in its own vendor/lineage/config/common.mk.
#
# IMPORTANT: all this is needed ONLY in the LineageOS tree. In the vanilla AOSP tree (the very "core" for
# which a separate android-14.0.0_r75 sync was made on 22.09.2026) none of these dependencies exist:
# the framework does not look for org.lineageos.platform-res.apk, WindowManager does not read LineageSettings,
# SystemUI does not ask for lineageglobalactions. So the block is enabled by the presence of vendor/lineage —
# one and the same device directory serves both trees.
ifneq ($(wildcard vendor/lineage/config/lineage_sdk_common.mk),)
PRODUCT_RESTRICT_VENDOR_FILES := false
$(call inherit-product, vendor/lineage/config/lineage_sdk_common.mk)
# Lineage settings provider (a system_ext_specific module; after it was added, system_ext is again
# run through bin/transplant-a14.sh — Microsoft components + CIL).
PRODUCT_PACKAGES += LineageSettingsProvider
endif

PRODUCT_NAME   := aosp_wsa_x86_64
PRODUCT_DEVICE := wsa_x86_64
PRODUCT_MODEL  := Subsystem for Android(TM)
PRODUCT_BRAND  := Microsoft
