#!/usr/bin/env bash
# Replace the ARM translator (libhoudini) in an already built system_ext with another version — only the files
# that differ, keeping the original's metadata (bin/patch-system-file.sh).
#
#   bin/replace-houdini.sh <system_ext.img> <set directory>
#
# The image is a writable raw ext4 that already contains the translator (a tree build with blobs from
# bin/extract-houdini-blobs.sh, or bin/add-houdini.sh). The set directory has the layout
# bin/{houdini,houdini64}, lib{,64}/libhoudini.so, lib/arm, lib64/arm64 (as in the reference vendor and in
# MagiskOnWSA/libhoudini in MustardChef/WSABuilds). No new inodes are needed: the file list is the same
# across versions, and a file missing from the image is not added — the script names it and exits with an error.
#
# WHY (29.09.2026, #793 MustardChef/WSABuilds). libhoudini 14.0.0_z.GoogleGame_com1.2 from the
# 2407.40000.4.0 reference has a "time bomb": ibt_get_expire_date returns "20260901", after that date some
# ARM apps hang with threads at 100 % CPU (in the code: a flag check and `jmp $`). Version
# 1.3 from Google Play Games PC v26.8.237.1 (shipped by LTS 8) expires "20280701". No byte patching
# needed — a newer stock build of the same translator is used. Binaries are not committed to git
# (BYOB); a copy of the set is in $WSA_STORAGE/wsa-houdini/gpg-26.8.237.1/.
set -euo pipefail
. "$(dirname "$0")/wsa-env.sh"

IMG="${1:?system_ext image (raw ext4)}"
SRC="${2:?libhoudini set directory}"
HERE="$(cd "$(dirname "$0")" && pwd)"
[ -w "$IMG" ] || { echo "image is not writable: $IMG" >&2; exit 1; }
[ -r "$SRC/lib64/libhoudini.so" ] || { echo "the set has no lib64/libhoudini.so: $SRC" >&2; exit 1; }

# Images from a tree build may have shared_blocks (identical blocks shared between files): `debugfs
# rm` frees a block the neighbouring file also uses, and that file is silently corrupted (on 29.09.2026 this
# corrupted libpdfium.so and libgui.so). First remove deduplication; there may not be enough space for the block
# copies — then the image grows.
if dumpe2fs -h "$IMG" 2>/dev/null | grep -q '^Filesystem features:.*shared_blocks'; then
    echo "== image has shared_blocks — removing deduplication =="
    until e2fsck -fy -E unshare_blocks "$IMG" >/dev/null 2>&1 && ! dumpe2fs -h "$IMG" 2>/dev/null | grep -q '^Filesystem features:.*shared_blocks'; do
        echo "   not enough space — +128 MiB"
        truncate -s +128M "$IMG"; resize2fs "$IMG" >/dev/null 2>&1
    done
fi

TMP="$(mktemp -d)"; trap 'rm -rf "${TMP:?}"' EXIT
# Current image contents — one rdump per directory (debugfs per file takes minutes).
# rdump creates lib/arm and lib64/arm64 itself — it does not write into an existing directory.
for d in bin lib lib64; do mkdir -p "$TMP/img/$d"; done
debugfs -R "rdump /lib/arm $TMP/img/lib" "$IMG" >/dev/null 2>&1
debugfs -R "rdump /lib64/arm64 $TMP/img/lib64" "$IMG" >/dev/null 2>&1
for f in bin/houdini bin/houdini64 lib/libhoudini.so lib64/libhoudini.so; do
    debugfs -R "dump /$f $TMP/img/$f" "$IMG" >/dev/null 2>&1 || true
done

LIST="$TMP/changed"; : > "$LIST"; missing=0
while IFS= read -r f; do
    if [ ! -f "$TMP/img/$f" ]; then echo "image has no /$f" >&2; missing=1; continue; fi
    cmp -s "$SRC/$f" "$TMP/img/$f" || echo "$f" >> "$LIST"
done < <(cd "$SRC" && find bin/houdini bin/houdini64 lib/libhoudini.so lib64/libhoudini.so lib/arm lib64/arm64 -type f | sort)
[ "$missing" = 0 ] || { echo "the set and the image differ in file list — not replacing" >&2; exit 1; }

n=$(wc -l < "$LIST")
echo "== files that differ: $n =="
[ "$n" -gt 0 ] || exit 0
while IFS= read -r f; do
    "$HERE/patch-system-file.sh" "$IMG" "/$f" "$SRC/$f" > "$TMP/log" 2>&1 || { cat "$TMP/log" >&2; exit 1; }
done < "$LIST"

# Verification: everything replaced now matches the set.
rm -rf "${TMP:?}/img"; for d in bin lib lib64; do mkdir -p "$TMP/img/$d"; done
debugfs -R "rdump /lib/arm $TMP/img/lib" "$IMG" >/dev/null 2>&1
debugfs -R "rdump /lib64/arm64 $TMP/img/lib64" "$IMG" >/dev/null 2>&1
for f in bin/houdini bin/houdini64 lib/libhoudini.so lib64/libhoudini.so; do
    debugfs -R "dump /$f $TMP/img/$f" "$IMG" >/dev/null 2>&1
done
bad=0
while IFS= read -r f; do cmp -s "$SRC/$f" "$TMP/img/$f" || { echo "mismatch after replacement: /$f" >&2; bad=1; }; done < "$LIST"
[ "$bad" = 0 ] || exit 1
echo "done: replaced $n files in $IMG; next: e2fsck -fy and packing into vhdx"
