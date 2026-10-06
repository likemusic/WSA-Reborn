#!/usr/bin/env bash
# Packs a partition from a directory into an ext4 image and then into a vhdx for WSA.
#
#   bin/pack-vhdx.sh <partition tree directory> <partition name> [destination directory]
#
#   example: bin/pack-vhdx.sh ~/work/new/system_ext system_ext ~/work/out
#           → ~/work/out/system_ext.vhdx
#
# No root needed: the image is filled via `mke2fs -d`, no mounting required.
#
# IMPORTANT about SELinux. Android will not start if files in system partitions lack
# correct labels. `mke2fs -d` does NOT set them. If a tool from an AOSP
# build is available (`mkuserimg_mke2fs` + `file_contexts.bin`), it is used — it
# sets the labels. Otherwise the script warns: the image will be built, but the system
# will most likely not come up with it.
#
# Even more important: FILE_CONTEXTS must come FROM THE SAME TREE as the partition
# contents. Labels are rules on PATHS, and paths move between Android
# revisions: in `android-14.0.0_r75` the hwservicemanager binary lives in
# system_ext (rule `/(system_ext|system/system_ext)/bin/hwservicemanager`), while
# in GSI `UQ1A.231205.015` it is in `/system/bin/`. Packing a foreign GSI with our
# file_contexts.bin gives it the system_file label, and init refuses to start
# the service: "has incorrect label or no domain transition". From outside this looks
# like an endless "Waited for hwservicemanager.ready for a second, waiting another…"
# without a single error — the same symptom as a missing
# /system/system_ext symlink (see bin/split-gsi.sh). Found 23.09.2026 while building
# the aosp14-gsi slot. For a foreign GSI use its own
# <unpacked GSI>/system/system/etc/selinux/plat_file_contexts.
set -euo pipefail
. "$(dirname "$0")/wsa-env.sh"

SRC="${1:?specify the partition tree directory}"
NAME="${2:?specify the partition name: system | system_ext | product | vendor}"
DEST="${3:-$(dirname "$SRC")/out}"

[ -d "$SRC" ] || { echo "no such directory: $SRC" >&2; exit 1; }
command -v qemu-img >/dev/null || { echo "qemu-img not found (package qemu-utils)" >&2; exit 1; }

mkdir -p "$DEST"
IMG="$DEST/$NAME.img"
VHDX="$DEST/$NAME.vhdx"

# Size: contents plus 15% and at least 32 MB of headroom. WSA images are built
# "tight" (only a handful of free blocks), but we need headroom: re-signed
# apks are larger than the originals.
used_kb=$(du -sk "$SRC" | cut -f1)
size_kb=$(( used_kb * 115 / 100 + 32768 ))
echo "== $NAME: contents $((used_kb/1024)) MB, image $((size_kb/1024)) MB =="

# --- locating AOSP tools -----------------------------------------------------
MKUSERIMG="${MKUSERIMG:-}"
FILE_CONTEXTS="${FILE_CONTEXTS:-}"
if [ -z "$MKUSERIMG" ] && [ -n "${ANDROID_HOST_OUT:-}" ]; then
    [ -x "$ANDROID_HOST_OUT/bin/mkuserimg_mke2fs" ] && MKUSERIMG="$ANDROID_HOST_OUT/bin/mkuserimg_mke2fs"
fi
if [ -z "$FILE_CONTEXTS" ] && [ -n "${ANDROID_PRODUCT_OUT:-}" ]; then
    for c in "$ANDROID_PRODUCT_OUT/obj/ETC/file_contexts.bin_intermediates/file_contexts.bin" \
             "$ANDROID_PRODUCT_OUT/etc/selinux/plat_file_contexts"; do
        [ -r "$c" ] && { FILE_CONTEXTS="$c"; break; }
    done
fi

rm -f "$IMG"
if [ -n "$MKUSERIMG" ] && [ -n "$FILE_CONTEXTS" ]; then
    echo "-- building via mkuserimg_mke2fs (SELinux labels will be set)"
    # mkuserimg_mke2fs is a wrapper: it calls e2fsdroid and mke2fs via PATH. If they
    # are not next to it, it fails with an obscure Python stack inside RunCommand. We put the wrapper's
    # own directory into PATH: these three files are taken from the build machine together.
    export PATH="$(dirname "$MKUSERIMG"):$PATH"
    for need in e2fsdroid mke2fs; do
        command -v "$need" >/dev/null || {
            echo "   MISSING $need — take it from the build machine, same directory:" >&2
            echo "     out/host/linux-x86/bin/$need" >&2
            exit 1; }
    done
    # Mount point from which e2fsdroid computes paths when looking up rules
    # in file_contexts. For system_ext and product these are /system_ext and /product —
    # their trees are flat. But the system tree in WSA is the ROOT: inside it there is
    # another system/, and the init file is stored at /system/bin/init. Passing
    # "/system" here would make e2fsdroid look up rules for
    # /system/system/bin/init — there are none at all, and EVERY file of the partition
    # gets the default label, system_file.
    #
    # The image is then built without a single complaint and looks correct. But it
    # has no init_exec, no logd_exec, no zygote_exec — so init cannot
    # transition the domain for any service. logd does not start, and logs never
    # appear AT ALL: the failure is silent not because it is early, but because
    # there is physically nothing to log with. That is how all our system images were built.
    case "$NAME" in
        system) MOUNTPOINT="/" ;;
        *)      MOUNTPOINT="/$NAME" ;;
    esac
    "$MKUSERIMG" "$SRC" "$IMG" ext4 "$MOUNTPOINT" $((size_kb * 1024)) \
                 -j 0 "$FILE_CONTEXTS" || exit 1
else
    echo "-- building via mke2fs -d"
    echo "   WARNING: SELinux labels are NOT set — a system with this image"
    echo "   will almost certainly not boot. For a correct build set:"
    echo "     MKUSERIMG=<out>/host/linux-x86/bin/mkuserimg_mke2fs"
    echo "     FILE_CONTEXTS=<out>/target/product/<device>/obj/ETC/file_contexts.bin_intermediates/file_contexts.bin"
    mke2fs -q -t ext4 -b 4096 -d "$SRC" -L "$NAME" \
           -O ^has_journal,^resize_inode,^huge_file "$IMG" "${size_kb}k" || exit 1
fi

# Volume label. Microsoft labels every partition: system carries the label "/",
# the rest their own name. Without a label the subsystem does not start at all: the VM is not created,
# WsaService returns state 5, and WsaClient fail-fasts on it
# (0xc0000409, InstanceManager.cpp) — from outside it looks like "the window flashed and
# vanished", without a single guest log. Cost a day of investigation on 08.09.2026.
case "$NAME" in
    system) LABEL="/" ;;
    *)      LABEL="$NAME" ;;
esac
tune2fs -L "$LABEL" "$IMG" >/dev/null 2>&1 || echo "   failed to set the volume label!" >&2
echo "-- volume label: $LABEL"

e2fsck -fy "$IMG" >/dev/null 2>&1 || true   # -fy on a fresh image always "fixes" counters
resize2fs -M "$IMG" >/dev/null 2>&1 || true  # shrink to minimum, as in the original

echo "-- converting to vhdx"
rm -f "$VHDX"
qemu-img convert -f raw -O vhdx "$IMG" "$VHDX"
rm -f "$IMG"

ls -la "$VHDX" | awk '{printf "done: %s (%.0f MB)\n", $9, $5/1048576}'
echo
echo "Replacing in the WSA install (the subsystem must be SHUT DOWN via the GUI):"
echo "  cp \"$VHDX\" $WSA_INSTALL_DIR/$NAME.vhdx"
echo "Check that the file is not in use like this:"
echo "  python3 -c \"open('$WSA_INSTALL_DIR/$NAME.vhdx','r+b').close()\""
