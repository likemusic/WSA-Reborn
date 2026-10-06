#!/usr/bin/env bash
# Sets up a VANILLA AOSP 14 tree with all our changes on the build machine — from scratch, with one command.
#
#   bin/bootstrap-aosp14.sh [tree dir] [manifest tag]
#   example: bin/bootstrap-aosp14.sh /build/aosp-14 android-14.0.0_r75
#
# Why a separate script and not "just repo sync":
#
#   * the VPS layout is Block Storage: the persistent volume stays, the compute instance is brought up on demand and
#     destroyed. Everything outside the volume is lost when the instance is destroyed. Restoring the AOSP tree
#     by hand is expensive and it is easy to forget a step (on 22.09.2026 the tree ended up by oversight
#     on the instance's system disk — this script is the insurance against such a loss);
#   * it is also the instructions for third parties: someone else's fork needs exactly this order — vanilla
#     tree, our device tree, our patches. Nothing from LineageOS is required here.
#
# Requires: python3, git, ~150 GB for the tree and another ~90 GB for out. Run it ON the build machine.
set -euo pipefail

TREE="${1:-/build/aosp-14}"
TAG="${2:-android-14.0.0_r75}"
SELF_DIR="$(cd "$(dirname "$0")/.." && pwd)"   # root of our repository

command -v git >/dev/null || { echo "git is missing" >&2; exit 1; }

# repo comes from the official source; if a tree with .repo/repo is already around, use it
# (avoids extra network traffic and does not depend on googlesource being reachable for the launcher).
REPO_BIN=""
for c in "$TREE/.repo/repo/repo" /build/lineage-21/.repo/repo/repo "$(command -v repo || true)"; do
    [ -n "$c" ] && [ -f "$c" ] && { REPO_BIN="$c"; break; }
done
if [ -z "$REPO_BIN" ]; then
    mkdir -p "$HOME/bin"
    curl -fsSL https://storage.googleapis.com/git-repo-downloads/repo -o "$HOME/bin/repo"
    chmod +x "$HOME/bin/repo"; REPO_BIN="$HOME/bin/repo"
fi

git config --global user.email >/dev/null 2>&1 || git config --global user.email "build@wsa-reborn.local"
git config --global user.name  >/dev/null 2>&1 || git config --global user.name  "wsa build"

echo "== 1. syncing vanilla AOSP ($TAG) into $TREE =="
mkdir -p "$TREE"; cd "$TREE"
# On 24.09.2026, before the compute instance was deleted, the tree's .repo (30 GB, tag r75) was saved to the Block Storage volume.
# If it exists, the tree is checked out from it without network (minutes instead of a ~40-minute sync).
REPO_CACHE="${REPO_CACHE:-/build/aosp-14-repo/.repo}"
if [ ! -d "$TREE/.repo" ] && [ -d "$REPO_CACHE" ] && [ "$TAG" = "android-14.0.0_r75" ]; then
    echo "  .repo taken from the volume: $REPO_CACHE"
    cp -a "$REPO_CACHE" "$TREE/.repo"
    python3 "$TREE/.repo/repo/repo" sync -l -c -j8
else
    python3 "$REPO_BIN" init -u https://android.googlesource.com/platform/manifest -b "$TAG" --depth=1
    python3 "$REPO_BIN" sync -c -j8 --no-tags --no-clone-bundle --optimized-fetch --prune
fi

echo "== 2. our device tree =="
mkdir -p "$TREE/device/microsoft"
rsync -a --delete "$SELF_DIR/device/microsoft/wsa_x86_64/" "$TREE/device/microsoft/wsa_x86_64/"

echo "== 3. patches (order matters: 0008 applies on context introduced by 0006) =="
# "dir | -p level | file" triple: some patches have paths from the tree root, some from the project.
apply() {
    local dir="$1" strip="$2" patch="$3"
    [ -f "$SELF_DIR/docs/patches/$patch" ] || { echo "  missing patch: $patch" >&2; return 1; }
    if (cd "$TREE/$dir" && patch -p"$strip" --forward --silent < "$SELF_DIR/docs/patches/$patch"); then
        echo "  applied: $patch"
    else
        # --forward stays silent if already applied; tell that apart from a real error
        (cd "$TREE/$dir" && patch -p"$strip" --reverse --dry-run --silent < "$SELF_DIR/docs/patches/$patch") \
            && echo "  already applied: $patch" || { echo "  ERROR: $patch" >&2; return 1; }
    fi
}
apply .                 0 0003-settings-devsettings-alias.diff
apply .                 0 0004-ethernet-relax-checks.diff
apply .                 0 0005-networkcapabilities-relax-specifier.diff
apply frameworks/native 1 0006-surfaceflinger-inherit-wsa-metadata.diff
apply frameworks/base   1 0007-wmshell-host-window-decor.diff
apply frameworks/native 1 0008-surfaceflinger-hide-taskless-statusbar.diff
apply frameworks/base   1 0009-appops-legacy-onopnoted-for-systemapp.diff
# 0010 is mandatory: without it first-stage init cannot resolve the SCSI=H:C:T:L addresses in the WSA fstab,
# does not mount /vendor and goes into InitFatalReboot — exactly where the first build of the
# vanilla tree stopped on 23.09.2026 (until then the fix lived only in the tree on the build machine).
apply system/core       1 0010-fs-mgr-wsa-scsi-address.diff
# 0012: Toast is shown by Windows — SystemUI forwards it to the WsaShellBridge bridge ("Notification" channel).
apply frameworks/base   1 0012-systemui-toast-to-windows.diff

echo "== 3b. new files (not diffs) =="
# The device/microsoft/wsa_x86_64 overlay sets config_deviceSpecificDisplayAreaPolicyProvider; without
# the class itself system_server goes into a crash loop (ClassNotFoundException), verified 23.09.2026.
install -D -m 0644 "$SELF_DIR/docs/patches/0011-wsa-display-area-policy-provider.java" \
    "$TREE/frameworks/base/services/core/java/com/android/server/wm/WsaDisplayAreaPolicyProvider.java"
echo "  installed: WsaDisplayAreaPolicyProvider.java"

cat <<TXT

Done. Next, on the build machine:

  cd $TREE && source build/envsetup.sh
  lunch aosp_wsa_x86_64-ap2a-userdebug
  m -j\$(nproc) systemimage systemextimage productimage

A clean build takes about 8 hours on 6 cores. Memory: one build at a time, 16 GB RAM is barely enough
(soong_build hit OOM), swap of at least 16 GB is required.
Microsoft components are added to the image by bin/transplant-a14.sh — they are not part of the repository
(legal restriction) and are taken from YOUR OWN WSA installation.
TXT
