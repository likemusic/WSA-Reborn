#!/usr/bin/env bash
# Apply a WSA-Reborn patch series to a synced source tree.
#
#   bin/apply-patches.sh <target> <tree>     — e.g. bin/apply-patches.sh aosp15 ~/aosp15
#
# <target> is a directory in patches/ (aosp14, lineage21, aosp15, aosp16, aosp17). Each of its
# subdirectories is a project path with '/' replaced by '_' (frameworks_base = frameworks/base); its
# *.patch files are applied in name order with `git am` on a new branch wsa-<target>, so the tree can be
# resynced and re-patched cleanly. Sync the tree to the pinned manifest first (manifests/README.md).
set -euo pipefail
HERE=$(cd "$(dirname "$0")/.." && pwd)
TARGET="${1:?target, e.g. aosp15}"
TREE="$(cd "${2:?source tree}" && pwd)"
SRC="$HERE/patches/$TARGET"
[ -d "$SRC" ] || { echo "no patch series: $SRC" >&2; exit 1; }

for d in "$SRC"/*/; do
    proj="$(basename "$d" | tr _ /)"
    # a few project paths contain '_' themselves; fall back to the exact name if the mapped one is absent
    [ -d "$TREE/$proj" ] || proj="$(basename "$d")"
    [ -d "$TREE/$proj" ] || { echo "project not in tree: $proj" >&2; exit 1; }
    echo "== $proj"
    git -C "$TREE/$proj" checkout -q -B "wsa-$TARGET"
    git -C "$TREE/$proj" am -q --keep-cr "$d"*.patch || {
        echo "git am failed in $proj — fix it or run 'git -C $TREE/$proj am --abort'" >&2; exit 1; }
done
echo "Applied patches/$TARGET to $TREE. Now add the device tree: cp -r $HERE/device/microsoft $TREE/device/"
