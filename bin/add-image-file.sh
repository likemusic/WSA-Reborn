#!/usr/bin/env bash
# Add a NEW regular file to a raw ext4 partition image (system_ext.img etc.) without root and without
# mounting — via debugfs, creating missing directories and setting uid/gid/mode and the
# SELinux label. Complements bin/patch-system-file.sh, which can only REPLACE an existing
# file (and takes the metadata from it); for a new file there is nowhere to take them from — we set them explicitly.
#
#   bin/add-image-file.sh <image.img> <local file> </path/inside/image> [context] [mode] [uid:gid]
#
#   example (apk of a privileged app in system_ext, 21.09.2026):
#     bin/add-image-file.sh system_ext.img out/WsaShellBridge.apk \
#       /priv-app/WsaShellBridge/WsaShellBridge.apk u:object_r:system_file:s0 0644 0:0
#
# Missing directories are created with the DIR_CONTEXT label (default u:object_r:system_file:s0), NOT
# with the label of the file itself: a directory and a file have different contexts. Hit on 24.09.2026 when moving
# logcat_forwarder to /product/bin — the directory got the logcat_forwarder_exec label, and init, having
# only read access to such a file, would not have been able to enter the directory.
#
# Pitfalls this script exists for (found 18.09.2026; see also bin/patch-system-file.sh):
#   * `debugfs write` creates a file only in the session's CURRENT directory — hence `cd` first;
#   * `sif <path> mode` expects the FULL st_mode (with type bits: 0100xxx file, 040xxx directory),
#     otherwise the inode gets corrupted (`Type: bad type`);
#   * `write` sets neither owner nor label — without security.selinux the file is inaccessible on an enforcing system
#     (for an apk — "package scan failed", for an executable — exec denied);
#   * the image must have free space: `debugfs` does not grow the FS. Checked before writing.
set -euo pipefail

IMG="${1:?.img image (raw ext4, writable)}"
SRC="${2:?local file}"
DST="${3:?path inside the image, e.g. /priv-app/Foo/Foo.apk}"
CTX="${4:-u:object_r:system_file:s0}"
PERM="${5:-0644}"
OWNER="${6:-0:0}"
UID_N="${OWNER%%:*}"; GID_N="${OWNER##*:}"
DIR_CTX="${DIR_CONTEXT:-u:object_r:system_file:s0}"

command -v debugfs >/dev/null || { echo "no debugfs" >&2; exit 1; }
[ -w "$IMG" ] || { echo "image is not writable: $IMG" >&2; exit 1; }
[ -r "$SRC" ] || { echo "file is not readable: $SRC" >&2; exit 1; }
case "$DST" in /*) ;; *) echo "path inside the image must start with /: $DST" >&2; exit 1;; esac

# debugfs returns 0 even on "File not found" — check the output.
exists() { debugfs -R "stat $1" "$IMG" 2>/dev/null | grep -q '^Inode:'; }

if exists "$DST"; then
    echo "already exists in the image: $DST — use bin/patch-system-file.sh to replace it" >&2; exit 1
fi

NEED=$(stat -c %s "$SRC")
FREE=$(( $(dumpe2fs -h "$IMG" 2>/dev/null | grep -oP 'Free blocks:\s*\K[0-9]+') * $(dumpe2fs -h "$IMG" 2>/dev/null | grep -oP 'Block size:\s*\K[0-9]+') ))
[ "$FREE" -gt $((NEED + 1048576)) ] || {
    echo "not enough space in the image: $FREE bytes free, $NEED needed (+1 MB margin). Grow it: truncate -s +N && e2fsck -fy && resize2fs" >&2; exit 1; }

DIR=$(dirname "$DST"); BASE=$(basename "$DST")

# The destination directory may EXIST but not be a directory: in a GSI, for example,
# /odm/etc is a symlink to /vendor/odm/etc. Then `cd` inside debugfs silently
# fails, the session stays at the root, and `write` puts the file into the image ROOT
# under a name without a path. The orphan file then takes part in boot (init looks for
# /fstab.<suffix> in the root too!) and causes inexplicable behaviour — it cost an hour
# on 23.09.2026 on the aosp14-gsi slot. Check in advance and explain what to do.
if exists "$DIR"; then
    if ! debugfs -R "stat $DIR" "$IMG" 2>/dev/null | grep -q 'Type: *directory'; then
        echo "$DIR in the image is not a directory (probably a symlink). Make it a real directory:" >&2
        echo "  debugfs -w -R \"unlink $DIR\" $IMG" >&2
        echo "  debugfs -w -R \"mkdir $DIR\" $IMG" >&2
        echo "  printf 'u:object_r:system_file:s0\\0' > /tmp/label.bin" >&2
        echo "  debugfs -w -R \"ea_set -f /tmp/label.bin $DIR security.selinux\" $IMG" >&2
        echo "  debugfs -w -R \"sif $DIR mode 040755\" $IMG && e2fsck -fy $IMG" >&2
        exit 1
    fi
fi

# Labels in Android images are stored with a trailing NUL (that is how mkuserimg/e2fsdroid write them) — do the same
# so the new file does not differ from its neighbours; NUL cannot be passed on the command line, only via -f.
CTX_FILE=$(mktemp); printf '%s\0' "$CTX" > "$CTX_FILE"
DIR_CTX_FILE=$(mktemp); printf '%s\0' "$DIR_CTX" > "$DIR_CTX_FILE"
trap 'rm -f "$CTX_FILE" "$DIR_CTX_FILE"' EXIT

# Directories — one at a time, with directory metadata.
CMDS=""
PARENT=""
IFS='/' read -ra PARTS <<< "${DIR#/}"
for p in "${PARTS[@]}"; do
    [ -n "$p" ] || continue
    CUR="$PARENT/$p"
    if ! exists "$CUR"; then
        CMDS+="mkdir $CUR"$'\n'
        CMDS+="sif $CUR mode 040755"$'\n'
        CMDS+="sif $CUR uid 0"$'\n'
        CMDS+="sif $CUR gid 0"$'\n'
        CMDS+="ea_set -f $DIR_CTX_FILE $CUR security.selinux"$'\n'
        echo "  + directory $CUR ($DIR_CTX)"
    fi
    PARENT="$CUR"
done

CMDS+="cd $DIR"$'\n'
CMDS+="write $SRC $BASE"$'\n'
CMDS+="sif $DST mode 0100${PERM#0}"$'\n'
CMDS+="sif $DST uid $UID_N"$'\n'
CMDS+="sif $DST gid $GID_N"$'\n'
CMDS+="ea_set -f $CTX_FILE $DST security.selinux"$'\n'

echo "== adding $DST ($NEED bytes, $CTX, $PERM, $OWNER) =="
debugfs -w -f <(printf '%s' "$CMDS") "$IMG" 2>&1 | grep -v -E '^debugfs|^Allocated inode' || true

# Check: size, type, label.
STAT="$(debugfs -R "stat $DST" "$IMG" 2>/dev/null)"
GOT_SIZE=$(grep -oP 'Size: \K[0-9]+' <<< "$STAT" | head -1)
[ "$GOT_SIZE" = "$NEED" ] || { echo "ERROR: size in the image $GOT_SIZE ≠ $NEED" >&2; exit 1; }
grep -q 'Type: *regular' <<< "$STAT" || { echo "ERROR: file type is not regular" >&2; echo "$STAT" >&2; exit 1; }
grep -q "security.selinux.*$CTX" <<< "$STAT" || { echo "ERROR: label not set" >&2; echo "$STAT" >&2; exit 1; }
debugfs -R "dump $DST /dev/stdout" "$IMG" 2>/dev/null | cmp -s - "$SRC" || { echo "ERROR: content differs" >&2; exit 1; }
echo "ok: $DST"
