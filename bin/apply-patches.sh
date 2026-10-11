#!/usr/bin/env bash
# Apply a WSA-Reborn patch series to a synced source tree.
#
#   bin/apply-patches.sh <target> <tree>     — e.g. bin/apply-patches.sh aosp15 ~/aosp15
#
# <target> is a directory in patches/ (aosp13, aosp14, lineage20, lineage21, lineage22, lineage23, lineage24, aosp15, aosp16, aosp17). Each of its
# subdirectories holds one project: its repo path is in the .path file inside; its
# *.patch files are applied in name order with `git am` on a new branch wsa-<target>, so the tree can be
# resynced and re-patched cleanly. Sync the tree to the pinned manifest first (manifests/README.md).
set -euo pipefail
HERE=$(cd "$(dirname "$0")/.." && pwd)
TARGET="${1:?target, e.g. aosp15}"
TREE="$(cd "${2:?source tree}" && pwd)"
SRC="$HERE/patches/$TARGET"
[ -d "$SRC" ] || { echo "no patch series: $SRC" >&2; exit 1; }

for d in "$SRC"/*/; do
    proj="$(cat "$d.path")"
    [ -d "$TREE/$proj" ] || { echo "project not in tree: $proj" >&2; exit 1; }
    echo "== $proj"
    git -C "$TREE/$proj" checkout -q -B "wsa-$TARGET"
    git -C "$TREE/$proj" am -q --keep-cr "$d"*.patch || {
        echo "git am failed in $proj — fix it or run 'git -C $TREE/$proj am --abort'" >&2; exit 1; }
done
echo "Applied patches/$TARGET to $TREE. Now add the device tree: cp -r $HERE/device/microsoft $TREE/device/"
