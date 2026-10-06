# This tree must parse BOTH in the LineageOS tree AND in the vanilla AOSP tree (22.09.2026:
# for the sake of a vanilla "core" others can build their forks on — see docs/plan-20260921.md §4).
# So everything that depends on vendor/lineage is included only when it is present.
PRODUCT_MAKEFILES := $(LOCAL_DIR)/aosp_wsa_x86_64.mk
ifneq ($(wildcard vendor/lineage/config/common.mk),)
PRODUCT_MAKEFILES += $(LOCAL_DIR)/lineage_wsa_x86_64.mk
endif

# The target format differs between Android versions:
#   Android 13 (lineage-20.0): <product>-<variant>          → lineage_wsa_x86_64-userdebug
#   Android 14+ (lineage-21+): <product>-<release>-<variant> → lineage_wsa_x86_64-ap2a-userdebug
#   Android 15 (android15-security-release): release ap3a → aosp_wsa_x86_64-ap3a-userdebug
#   Android 16 (android16-security-release): release bp2a → aosp_wsa_x86_64-bp2a-userdebug
# Both are listed; the extra variant simply matches nothing.
# aosp_wsa_x86_64 — a control target without LineageOS (see aosp_wsa_x86_64.mk),
# added 13.09.2026 to check whether the boot failure reproduces on a
# neutral AOSP base.
COMMON_LUNCH_CHOICES := \
    aosp_wsa_x86_64-bp2a-userdebug \
    aosp_wsa_x86_64-ap3a-userdebug \
    aosp_wsa_x86_64-ap2a-userdebug \
    aosp_wsa_x86_64-ap2a-eng \
    aosp_wsa_x86_64-userdebug \
    aosp_wsa_x86_64-eng
ifneq ($(wildcard vendor/lineage/config/common.mk),)
COMMON_LUNCH_CHOICES += \
    lineage_wsa_x86_64-userdebug \
    lineage_wsa_x86_64-eng \
    lineage_wsa_x86_64-ap2a-userdebug \
    lineage_wsa_x86_64-ap2a-eng
endif
