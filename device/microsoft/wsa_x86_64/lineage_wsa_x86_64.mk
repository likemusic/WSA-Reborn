# LineageOS 21 for Windows Subsystem for Android, x86_64.
#
# Based on the stock LineageOS GSI target: it is designed to work with a foreign vendor,
# which is exactly what we need. There is exactly one difference, but an important one: GSI puts product and
# system_ext inside system.img, while WSA mounts them as separate disks.

# Overlays that must outrank vendor/lineage/overlay: overlays listed first win, and inherit-product appends the
# inherited product's list where it is called, so this goes before it. 13.10.2026 — desktop mode, see the file.
# Only from SDK 36 (LineageOS 23.2+, which turns it on): Android 13 has no such resource, and an overlay of a
# missing resource fails the build. PLATFORM_SDK_VERSION is set by the time the product is parsed (see device.mk).
ifneq ($(PLATFORM_SDK_VERSION),)
ifeq ($(call math_gt_or_eq,$(PLATFORM_SDK_VERSION),36),true)
PRODUCT_PACKAGE_OVERLAYS += device/microsoft/wsa_x86_64/overlay-lineage
endif
endif

$(call inherit-product, vendor/lineage/build/target/product/lineage_gsi_x86_64.mk)
$(call inherit-product, device/microsoft/wsa_x86_64/device.mk)

# Separate images instead of ones nested in system.img
PRODUCT_BUILD_SYSTEM_IMAGE     := true
PRODUCT_BUILD_SYSTEM_EXT_IMAGE := true
PRODUCT_BUILD_PRODUCT_IMAGE    := true

# Boot images are not built: WSA has its own kernel, from Microsoft.
PRODUCT_BUILD_VENDOR_IMAGE     := false
PRODUCT_BUILD_BOOT_IMAGE       := false
PRODUCT_BUILD_RECOVERY_IMAGE   := false
PRODUCT_BUILD_SUPER_PARTITION  := false

# GSI allows artifacts at the paths system/product/ and system/system_ext/ —
# that is needed when they are nested in system.img. We moved them to separate partitions,
# the entries became redundant, and the build treats redundancy as an ERROR, not
# a warning, failing at the makefile parsing stage:
#   artifact_path_requirements.mk:31: error: Build failed.
# The check guards the cleanliness of the layout for GSI certification; we do not need it.
PRODUCT_ENFORCE_ARTIFACT_PATH_REQUIREMENTS := false

# The GSI target requires all artifacts to be in system/ — it is designed for an image
# where product and system_ext are nested inside. We move them to separate partitions, and
# files legitimately end up outside this requirement (e.g. the com.android.wifi apex
# moves to system_ext). We allow these paths explicitly.
#
# Important: in Android 13 the check "the product produces files OUTSIDE its requirements"
# runs UNCONDITIONALLY, the flag above does not affect it — it only silences the second
# check, about redundant allowances. In Android 14 it was exactly the
# second one that stopped us, so a single flag was enough there.
# The requirement is declared in build/make/target/product/generic_system.mk: everything must
# be in root/ and system/, the allow list there is empty. The check looks at the
# variables of THAT makefile, not ours, so our PRODUCT_ARTIFACT_PATH_*
# do not affect it — the list has to be extended at the source of the requirement.
PRODUCTS.build/make/target/product/generic_system.mk.ARTIFACT_PATH_ALLOWED_LIST += \
    system_ext/% \
    product/%

# Properties shared by both targets (LineageOS and AOSP), the overlay, the setup wizard bypass and WsaShellBridge are
# in device.mk (moved there 21.09.2026 before building aosp_wsa_x86_64).

# The setup wizard (LineageSetupWizard, enabled by vendor/lineage/config/common.mk)
# holds HOME with intent-filter priority="9" — higher than a regular launcher — and does not
# release focus. The standard adb bypass (device_provisioned/user_setup_complete)
# does not work: finishSetupWizard() in its sources (packages/apps/SetupWizard/
# .../util/SetupWizardUtils.java) itself calls disableHome() — programmatically
# disabling ITS OWN HOME component via PackageManager. Removing the package from
# the build altogether (line below) DOES NOT WORK: get_build_var confirms that
# the entry is re-added at a later/hidden stage of product processing —
# kept as documentation of the failed approach, it has no real effect.
PRODUCT_PACKAGES := $(filter-out LineageSetupWizard,$(PRODUCT_PACKAGES))

# ro.control_privapp_permissions=disable — in product, as in aosp_wsa_x86_64.mk: Microsoft's vendor/build.prop
# sets `enforce` and is read AFTER system and system_ext, but BEFORE product, so the line from
# device.mk (system) has no effect. Without GApps this did not show up; with the Magisk modules
# `lsp_gapps` enabled (they put gearhead into /product/priv-app) system_server crashes on the allowlist in a loop, and the host
# restarts the VM every ~2 min (05.10.2026, pool lineage21).
PRODUCT_PRODUCT_PROPERTIES += ro.control_privapp_permissions=disable

# APEXes uncompressed. LineageOS 23.2 ships compressed ones (*.capex); on a clean /data apexd decompresses them
# during the very first boot, which takes minutes, the WSA host does not wait that long and restarts the VM once
# (10.10.2026, slot lineage23). The image is sized dynamically, so the extra space costs nothing else.
PRODUCT_COMPRESSED_APEX := false

PRODUCT_NAME   := lineage_wsa_x86_64
PRODUCT_DEVICE := wsa_x86_64
PRODUCT_MODEL  := Subsystem for Android(TM)
PRODUCT_BRAND  := Microsoft
