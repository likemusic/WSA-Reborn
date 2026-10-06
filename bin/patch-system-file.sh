#!/usr/bin/env bash
# Replaces a single file inside a raw ext4 partition image (system.img etc.),
# preserving ITS OWN uid/gid/mode/SELinux label — rather than
# applying the values of the donor file.
#
#   bin/patch-system-file.sh <image.img> </path/inside/image> <replacement file>
#
#   example (surfaceflinger in LineageOS replaced with the one from the WSA reference, found
#   12.09.2026 — see docs/findings.md, "continuation 17", and memory
#   wsa-surfaceflinger-root-cause-20260912.md):
#     bin/patch-system-file.sh lineage-system.img /system/bin/surfaceflinger \
#       $WSA_REF/system/system/bin/surfaceflinger
#
# WHY this way and not just debugfs write. Three pitfalls, each cost time in
# the manual experiment of 12.09:
#   * `debugfs write` writes only to the image root — the file being replaced is first
#     removed, the new one is written under a temporary name in the root, linked to
#     the target path and the temporary name is unlinked (`rm`+`write`+`ln`+`unlink`);
#   * `debugfs write` does NOT carry over owner/mode/label — if left as
#     is, the file gets the host uid/gid (usually 0/0644) and NO SELinux
#     context at all, which in an enforcing domain is a sure bootloop
#     at the earliest stage (init will not let a process exec without *_exec);
#   * metadata must be taken from the original file INSIDE THIS SAME image, and
#     not from the donor file: surfaceflinger has the same uid/gid at Microsoft
#     and in LineageOS (0/2000/0755), but the SELinux domain may differ in a live
#     build — the correct context is the one already
#     declared in the sepolicy of the TARGET system, not of the patch source.
set -euo pipefail
. "$(dirname "$0")/wsa-env.sh"

IMG="${1:?image .img (raw ext4)}"
TARGET="${2:?path inside the image, e.g. /system/bin/surfaceflinger}"
NEW_FILE="${3:?local replacement file}"

command -v debugfs >/dev/null || { echo "debugfs not found" >&2; exit 1; }
[ -r "$IMG" ] || { echo "cannot read image: $IMG" >&2; exit 1; }
[ -r "$NEW_FILE" ] || { echo "cannot read replacement file: $NEW_FILE" >&2; exit 1; }

# shared_blocks (deduplication in images built from the tree): `debugfs rm` would free blocks that
# other files use too, and those would be silently corrupted (29.09.2026, libhoudini in aosp14-vanilla).
# So deduplication is removed before replacing (e2fsck -E unshare_blocks); if there is not enough
# space for the block copies — grow the image (truncate + resize2fs) and retry.
if dumpe2fs -h "$IMG" 2>/dev/null | grep -q '^Filesystem features:.*shared_blocks'; then
    echo "== image has shared_blocks — removing deduplication =="
    e2fsck -fy -E unshare_blocks "$IMG" >/dev/null 2>&1 || true
    if dumpe2fs -h "$IMG" 2>/dev/null | grep -q '^Filesystem features:.*shared_blocks'; then
        echo "not enough space to remove shared_blocks — grow the image: truncate -s +128M $IMG; resize2fs $IMG" >&2; exit 1
    fi
fi

STAT="$(debugfs -R "stat $TARGET" "$IMG" 2>/dev/null)"
[ -n "$STAT" ] || { echo "no such file in the image: $TARGET" >&2; exit 1; }

OLD_SIZE=$(grep -oP 'Size: \K[0-9]+' <<< "$STAT" | head -1)
UID_N=$(grep -oP 'User:\s*\K[0-9]+' <<< "$STAT" | head -1)
GID_N=$(grep -oP 'Group:\s*\K[0-9]+' <<< "$STAT" | head -1)
# debugfs "Mode:" is ONLY the permissions (4 octal digits, e.g. "0755"),
# without file type bits. `sif mode`, however, needs the full st_mode value: without
# the type the filesystem writes garbage into the inode's upper bits, and the file becomes
# unreadable (`Type: bad type` on a repeated stat) — that is how the first
# version of the script broke. Only "regular" files are supported for replacement (S_IFREG,
# octal prefix 10) — this script is not meant for directories/symlinks.
grep -q 'Type: *regular' <<< "$STAT" || { echo "not a regular file: $TARGET (this operation is for regular files only)" >&2; exit 1; }
PERM=$(grep -oP 'Mode:\s*\K[0-9]+' <<< "$STAT" | head -1)
MODE="100${PERM#0}"   # "100" = S_IFREG in octal digits, ${PERM#0} = permissions without the leading zero
CTX=$(grep -oP 'security\.selinux.*=\s*"\K[^"\\]+' <<< "$STAT" | head -1)
NEW_SIZE=$(stat -c %s "$NEW_FILE")

[ -n "$UID_N" ] && [ -n "$GID_N" ] && [ -n "$PERM" ] || {
    echo "failed to parse stat for $TARGET:" >&2; echo "$STAT" >&2; exit 1; }
[ -n "$CTX" ] || echo "WARNING: the original has no security.selinux label — the new file will be left without one" >&2

echo "== replacing $TARGET =="
echo "  before: uid=$UID_N gid=$GID_N mode=$PERM ctx=${CTX:-<none>} size=$OLD_SIZE"
echo "  after: size=$NEW_SIZE (metadata as in the original, not as in the replacement file)"

BASENAME="$(basename "$TARGET")"
TMPNAME="__patch_${BASENAME}_$$"

debugfs -w -R "rm $TARGET" "$IMG" >/dev/null 2>&1
debugfs -w -R "write $NEW_FILE $TMPNAME" "$IMG" >/dev/null 2>&1
debugfs -w -R "ln /$TMPNAME $TARGET" "$IMG" >/dev/null 2>&1
debugfs -w -R "unlink /$TMPNAME" "$IMG" >/dev/null 2>&1

debugfs -w -R "sif $TARGET uid $UID_N" "$IMG" >/dev/null 2>&1
debugfs -w -R "sif $TARGET gid $GID_N" "$IMG" >/dev/null 2>&1
debugfs -w -R "sif $TARGET mode 0$MODE" "$IMG" >/dev/null 2>&1

if [ -n "$CTX" ]; then
    CTXFILE="$(mktemp)"; trap 'rm -f "$CTXFILE"' EXIT
    printf '%s\0' "$CTX" > "$CTXFILE"
    debugfs -w -R "ea_set -f $CTXFILE $TARGET security.selinux" "$IMG" >/dev/null 2>&1
fi

# The DIRECTORY modification time is also bumped to "now". debugfs does not touch it on rm/ln, and
# PackageManager keeps a package parse cache (/data/system/package_cache) and considers it fresh
# as long as the package directory (/priv-app/<Name>) is not newer than the cache entry. Result: a new apk with a stale
# manifest parse: new permissions, but added activities and providers are missing ("Activity
# class ... does not exist"). Caught 24.09.2026 on the bridge "FileSystem" channel.
PARENT="$(dirname "$TARGET")"
debugfs -w -R "sif $PARENT mtime $(date -u +%Y%m%d%H%M%S)" "$IMG" >/dev/null 2>&1

VERIFY="$(debugfs -R "stat $TARGET" "$IMG" 2>/dev/null)"
echo "== verification =="
echo "$VERIFY" | grep -E 'User:|Group:|Mode:|Size:'
echo "$VERIFY" | grep -A1 'Extended attributes' | tail -1

echo
echo "done: $TARGET replaced inside $IMG"
