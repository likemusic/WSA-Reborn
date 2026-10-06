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

echo "== 1. syncing AOSP ($TAG, pinned manifest manifests/aosp14.xml) into $TREE =="
mkdir -p "$TREE"; cd "$TREE"
python3 "$REPO_BIN" init -u https://android.googlesource.com/platform/manifest -b "$TAG" --depth=1
cp "$SELF_DIR/manifests/aosp14.xml" "$TREE/.repo/manifests/wsa-pinned.xml"
python3 "$REPO_BIN" init -m wsa-pinned.xml
python3 "$REPO_BIN" sync -c -j8 --no-tags --no-clone-bundle --optimized-fetch --prune

echo "== 2. our device tree =="
mkdir -p "$TREE/device/microsoft"
rsync -a --delete "$SELF_DIR/device/microsoft/wsa_x86_64/" "$TREE/device/microsoft/wsa_x86_64/"

echo "== 3. patches (patches/aosp14, git am per project) =="
"$SELF_DIR/bin/apply-patches.sh" aosp14 "$TREE"

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
