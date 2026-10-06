#!/usr/bin/env bash
# Replace files inside the system_ext of WORKING slots — directly in their system_ext.vhdx.<suffix> files, without
# the source raw images.
#
#   bin/patch-slots.sh </path/in/image>=<file> [</path>=<file>…] -- <slot> [<slot>…]
#   example: bin/patch-slots.sh /app/talkback/talkback.apk=out/talkback.apk -- aosp14-vanilla lineage21
#
# A file absent from the image is added (bin/add-image-file.sh: system_file, in /lib64 — system_lib_file; 0644, root); "</path>=" without
# a file deletes it (a directory only if empty; list it after its files).
# For each slot: vhdx -> raw in a temporary directory on F: ($WORK), previous versions of replaced files go
# there too (<name>.<slot>.pre), replacement (bin/patch-system-file.sh keeps metadata and bumps the time of
# the package directory), removal of stale oat/x86_64/<apk>.{odex,vdex} next to the replaced apk (otherwise ART
# would use the old code), e2fsck, raw -> vhdx, copy into the slot file. Does NOT touch the live files: afterwards run
# bin/switch-build.sh <slot>. Wrapper for the bridge — bin/bridge-to-slots.sh.
# GROW_MIB=<n> — first grow the image by n MiB (the slots' system_ext is tight, 06.10.2026).
set -euo pipefail
. "$(dirname "$0")/wsa-env.sh"
HERE=$(cd "$(dirname "$0")/.." && pwd)
WSA_DIR=$WSA_INSTALL_DIR
WORK="${WORK:-$WSA_STORAGE/wsa-patch-slots-$(date +%Y%m%d)}"

PAIRS=()
while [ $# -gt 0 ] && [ "$1" != "--" ]; do PAIRS+=("$1"); shift; done
[ "${1:-}" = "--" ] && shift
[ ${#PAIRS[@]} -gt 0 ] && [ $# -gt 0 ] || { echo "usage: $0 </path>=<file>… -- <slot>…" >&2; exit 1; }
for p in "${PAIRS[@]}"; do
    [ -z "${p#*=}" ] || [ -r "${p#*=}" ] || { echo "no such file ${p#*=}" >&2; exit 1; }
done
mkdir -p "$WORK"

for slot in "$@"; do
    file="$WSA_DIR/system_ext.vhdx.$slot"   # slot naming of bin/switch-build.sh
    [ -w "$file" ] || { echo "$slot: no file $file" >&2; exit 1; }
    img="$WORK/system_ext.$slot.img"
    echo "== $slot ($file)"
    qemu-img convert -f vhdx -O raw "$file" "$img"
    if [ -n "${GROW_MIB:-}" ]; then
        # Grow the FS: zeros take no space in a vhdx, so C: is barely used.
        e2fsck -fy "$img" >/dev/null 2>&1 || true
        truncate -s "+$((GROW_MIB * 1024 * 1024))" "$img"
        resize2fs "$img" >/dev/null 2>&1 && echo "-- image grown by $GROW_MIB MiB"
    fi
    for p in "${PAIRS[@]}"; do
        path="${p%%=*}" src="${p#*=}"
        if [ -z "$src" ]; then
            # "path=" without a file — delete (a directory only if empty).
            if debugfs -R "stat $path" "$img" 2>/dev/null | grep -q "Type: directory"; then
                debugfs -w -R "rmdir $path" "$img" 2>/dev/null && echo "-- removed directory $path"
            elif debugfs -R "stat $path" "$img" 2>/dev/null | grep -q Inode; then
                debugfs -w -R "rm $path" "$img" 2>/dev/null && echo "-- removed $path"
            fi
            continue
        fi
        if debugfs -R "stat $path" "$img" 2>/dev/null | grep -q Inode; then
            debugfs -R "dump $path $WORK/$(basename "$path").$slot.pre" "$img" 2>/dev/null || true
            "$HERE/bin/patch-system-file.sh" "$img" "$path" "$src" | tail -1
        else
            # The new file is not in the image — add it (system_file, 0644, root; missing directories too).
            ctx=u:object_r:system_file:s0
            case "$path" in /lib/*|/lib64/*) ctx=u:object_r:system_lib_file:s0 ;; esac   # as in file_contexts
            "$HERE/bin/add-image-file.sh" "$img" "$src" "$path" "$ctx" 0644 0:0 | tail -1 | grep -v '^$' || true
        fi
        case "$path" in
            *.apk)
                dir="$(dirname "$path")" base="$(basename "$path" .apk)"
                for f in "$base.odex" "$base.vdex"; do
                    if debugfs -R "stat $dir/oat/x86_64/$f" "$img" 2>/dev/null | grep -q Inode; then
                        debugfs -w -R "rm $dir/oat/x86_64/$f" "$img" 2>/dev/null
                        echo "-- removed stale $dir/oat/x86_64/$f"
                    fi
                done ;;
        esac
    done
    e2fsck -fy "$img" 2>&1 | tail -1
    qemu-img convert -f raw -O vhdx "$img" "$img.vhdx"
    cp -f "$img.vhdx" "$file"
    rm -f "$img" "$img.vhdx"
    echo "-- $slot: done (previous files — $WORK/*.$slot.pre)"
done
