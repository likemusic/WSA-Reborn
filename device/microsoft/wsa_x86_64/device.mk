# Device specifics. Empty for now: everything needed comes from the GSI target, while Microsoft
# guest components are transplanted after the build (bin/transplant.sh) — they must not be
# included in the tree, they are proprietary binaries.

# TEMPORARY (diagnostics 15.09.2026):
# fstab.1.windows/fstab.2.windows — taken from Microsoft's reference system.img,
# needed by the first stage of init (GetFstabPath looks for them in /system/etc/, not only
# in /vendor/etc/ where the native copy lives) — without them Android 14 falls into
# InitFatalReboot on the very first init call. This is a TEXT config (SCSI addresses
# + mount options), not a Microsoft binary — but before including it in the tree
# permanently, check against the publication constraint (BYOB).
PRODUCT_COPY_FILES += \
    device/microsoft/wsa_x86_64/fstab.1.windows:$(TARGET_COPY_OUT_SYSTEM)/etc/fstab.1.windows \
    device/microsoft/wsa_x86_64/fstab.2.windows:$(TARGET_COPY_OUT_SYSTEM)/etc/fstab.2.windows

# Definition of the sysprep_service service. Until 28.09.2026 this file also set
# vendor.sysprep.start_phase on early-init — that is what crashed the service every 5 s;
# now, as at Microsoft, the service sets the phase itself on the host's command
# (found 28.09.2026). Placed in system_ext (our partition).
PRODUCT_COPY_FILES += \
    device/microsoft/wsa_x86_64/overlay-init/init.wsa-sysprep-bypass.rc:$(TARGET_COPY_OUT_SYSTEM_EXT)/etc/init/init.wsa-sysprep-bypass.rc

# TalkBack ("Screen reader for the subsystem"): the apk itself is placed by
# bin/transplant-a14.sh from the Microsoft image (BYOB); here — the default READ_PHONE_STATE
# permission, without which it crashes on AOSP 14 with Telecom.
PRODUCT_COPY_FILES += \
    device/microsoft/wsa_x86_64/default-permissions/default-permissions-wsa-talkback.xml:$(TARGET_COPY_OUT_SYSTEM_EXT)/etc/default-permissions/default-permissions-wsa-talkback.xml

# ---- Common to lineage_wsa_x86_64 and aosp_wsa_x86_64 (moved from the lineage target 21.09.2026) ----

# LineageOS (vendor/lineage/config/common.mk) enables
# ro.control_privapp_permissions=enforce. On a WSA installation shared with Android 13,
# /data/system/packages.xml carries grant records for packages (gearhead,
# googlequicksearchbox) that exist on no partition of this build —
# there is nowhere to add an allowlist for them, and enforce kills system_server fatally
# before boot_completed. Lower to disable — the packages are physically absent, nothing
# to violate.
PRODUCT_SYSTEM_DEFAULT_PROPERTIES += \
    ro.control_privapp_permissions=disable

# The lock screen is not needed in WSA and is harmful: while keyguard is shown, no app window
# gets a surface (NO_SURFACE for all — found 21.09.2026). The property is read by
# LockSettingsService when CREATING the locksettings database (fresh /data only), the overlay
# config_disableLockscreenByDefault (device/microsoft/wsa_x86_64/overlay) — on every check.
PRODUCT_SYSTEM_DEFAULT_PROPERTIES += \
    ro.lockscreen.disable.default=true

# The window frame/title/shadow is drawn by the Windows host — the guest WM Shell caption decoration for
# freeform tasks (a second header inside the window) is disabled by the WindowDecoration.java patch
# (fix 0007, docs/patches.md) based on this property.
PRODUCT_SYSTEM_DEFAULT_PROPERTIES += \
    ro.wsa.host_window_decor=true

# vendor.img (framework-res__auto_generated_rro_vendor.apk, a Microsoft overlay shared
# by ALL builds on this WSA installation, including Android 13) disables the stock
# Android wallpaper service: config_enableWallpaperService=false (confirmed with aapt2 dump
# resources). Because of that WallpaperManagerService is not registered in ServiceManager
# ("wallpaper" is missing from `service list`), and systemui falls into an endless
# crash-loop (NullPointerException in WallpaperManager$Globals when calling
# lockScreenWallpaperExists() from CentralSurfacesImpl) — unrelated to the
# surfaceflinger patch, reproduces without it too.
# We restore true with OUR OWN overlay: `dumpsys overlay` on a real WSA confirmed
# that an overlay from the product partition (auto_generated_rro_product__, priority 29) overrides
# the overlay from vendor (priority 0) for the android package — that is enough; vendor.img need not
# and must not be touched (shared with neighbours).
PRODUCT_PACKAGE_OVERLAYS += device/microsoft/wsa_x86_64/overlay

# Working workaround — an init.rc service that programmatically performs the same action
# (pm disable on the HOME component) that the wizard itself does on completion, but before
# the user could go through it interactively. adb shell will not do for this
# (SecurityException: Shell cannot change component state — a UID check
# in PackageManagerService); it needs init running as system. Details of the
# three obstacles: SELinux domain, UID, pm disable vs disable-user (found
# 18.09.2026). IMPORTANT: after being copied by the build, wsa-no-setupwizard.sh
# gets the plain system_file label, while the shell domain needs exactly
# shell_exec for entrypoint — in a normal build without a manual post-patch
# (bin/patch-system-file.sh, ea_set security.selinux) exec will fail
# (Permission denied). The manual patch is still needed after every rebuild of this
# file, until a proper way to assign file_contexts for system_ext is found.
PRODUCT_COPY_FILES += \
    device/microsoft/wsa_x86_64/overlay-init/init.wsa-no-setupwizard.rc:$(TARGET_COPY_OUT_SYSTEM_EXT)/etc/init/init.wsa-no-setupwizard.rc \
    device/microsoft/wsa_x86_64/overlay-init/wsa-no-setupwizard.sh:$(TARGET_COPY_OUT_SYSTEM_EXT)/bin/wsa-no-setupwizard.sh

# Bridge "Android tasks ↔ Windows windows" — our own implementation of the RCM "Shell" channel protocol
# (docs/protocol.md). Replaces WsaTaskManager from Microsoft's SystemUIApp.apk, which does not
# start on Android 14 (tied to the Android 13 WM Shell, see docs/architecture.md).
# Runs as uid system: sepolicy for hal_rcm_service/vsock for system_app is already in vendor.img.
PRODUCT_PACKAGES += WsaShellBridge

# Package verifier (install checks by the Windows antivirus through the bridge). A separate package, like
# Microsoft's UserApp: the platform_app domain can read the install session apk, system_app cannot.
PRODUCT_PACKAGES += WsaPackageVerifier

# ARM translation (libhoudini) — proprietary blobs from the WSA reference, not in git: the directory is created by
# bin/extract-houdini-blobs.sh <tree>. No directory — an image without ARM translation (ARM-only apps
# will not start). Layout: files in system_ext, links in system (houdini/Android.bp).
$(call inherit-product-if-exists, device/microsoft/wsa-houdini/houdini.mk)

# The Windows user folder in /sdcard/Windows: our replacement for Microsoft's mount_shared_folder,
# mounts the virtiofs disk windows_user at the end of boot (wsa_mount_shared_folder/). Label and vold
# permissions — in sepolicy/ (file_contexts, wsa_shared_folder.te), properties — in sepolicy/property_contexts.
PRODUCT_PACKAGES += wsa_mount_shared_folder

# ---- Android 17 and newer only (SDK ≥ 37; docs/patches.md, a17/0018, a17/0021) ----
# The platform patch a17/0021 returns the first stage of init in A17 to /system/bin/init — the device needs nothing.
# PLATFORM_SDK_VERSION is already set by the time the product is parsed (envsetup.mk: version_util.mk before
# product_config.mk); in trees where this is not so, the condition simply does not fire.
ifneq ($(PLATFORM_SDK_VERSION),)
ifeq ($(call math_gt_or_eq,$(PLATFORM_SDK_VERSION),37),true)
# Microsoft touchscreen: the native /vendor/usr/idc/virtual_touchscreen.idc is bound to
# touch.displayId = local:100, while in A17 the display uniqueId is built from EDID — the viewport is empty. Our own .idc
# without the binding; system_ext/usr/ is searched before vendor/usr/ (InputDevice.cpp).
PRODUCT_COPY_FILES += \
    device/microsoft/wsa_x86_64/idc/virtual_touchscreen.idc:$(TARGET_COPY_OUT_SYSTEM_EXT)/usr/idc/virtual_touchscreen.idc
endif
endif
