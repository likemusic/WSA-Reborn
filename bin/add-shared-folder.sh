#!/usr/bin/env bash
# Add the Windows user shared folder (/sdcard/Windows) to a ready slot's system_ext image:
# our own mount program, init script, vold permissions and external_storage.* properties.
#
#   bin/add-shared-folder.sh <system_ext.img>
#
# The image is writable raw ext4. Re-running is safe: the program and init script
# are replaced, policy rules and properties are appended only if not present yet.
#
# What is added (all our own, from device/microsoft/wsa_x86_64):
#   /bin/wsa_mount_shared_folder          — wsa_mount_shared_folder/main.c, built statically with the
#                                           NDK; label vold_exec (vold domain);
#   /etc/init/wsa_mount_shared_folder.rc  — mount on sys.boot_completed=1;
#   /etc/selinux/system_ext_sepolicy.cil  — + sepolicy/wsa_shared_folder.cil;
#   /etc/selinux/system_ext_property_contexts — + sepolicy/property_contexts.
#
# In a tree build the same is achieved by PRODUCT_PACKAGES += wsa_mount_shared_folder and the directory
# sepolicy (wsa_shared_folder.te, property_contexts, file_contexts).
#
# The host gives the guest the windows_user disk only if "share user folders" is enabled in the
# WSA settings, and decides this when the subsystem starts. Details — docs/wsa-shell-protocol.md,
# section on the Windows user shared folder.
set -euo pipefail

IMG="${1:?system_ext.img (raw ext4, writable)}"
HERE=$(cd "$(dirname "$0")/.." && pwd)
DEV="$HERE/device/microsoft/wsa_x86_64"
SDK="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-$HOME/Android/Sdk}}"
NDK="${NDK:-$(ls -1d "$SDK"/ndk/* 2>/dev/null | sort -V | tail -1)}"
CC="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/clang"
MARK="wsa_shared_folder"

[ -w "$IMG" ] || { echo "no writable image: $IMG" >&2; exit 1; }
[ -x "$CC" ] || { echo "NDK not found (looking for clang at $CC)" >&2; exit 1; }

WORK=$(mktemp -d); trap 'rm -rf "$WORK"' EXIT

echo "-- building ($(basename "$NDK"))"
"$CC" --target=x86_64-linux-android34 -static -O2 -s -Wall -Werror \
    -o "$WORK/wsa_mount_shared_folder" "$DEV/wsa_mount_shared_folder/main.c"

exists() { debugfs -R "stat $1" "$IMG" 2>/dev/null | grep -q '^Inode:'; }
put() { # <path in image> <file> <context> <mode> <uid:gid>
    if exists "$1"; then
        "$HERE/bin/patch-system-file.sh" "$IMG" "$1" "$2" | tail -1
    else
        "$HERE/bin/add-image-file.sh" "$IMG" "$2" "$1" "$3" "$4" "$5" | tail -1
    fi
}
put /bin/wsa_mount_shared_folder "$WORK/wsa_mount_shared_folder" u:object_r:vold_exec:s0 0755 0:2000
put /etc/init/wsa_mount_shared_folder.rc "$DEV/wsa_mount_shared_folder/wsa_mount_shared_folder.rc" \
    u:object_r:system_file:s0 0644 0:0

append() { # <path in image> <text to append>
    debugfs -R "dump $1 $WORK/cur" "$IMG" >/dev/null 2>&1
    if grep -q "$MARK" "$WORK/cur"; then
        echo "-- already present in $1"
        return
    fi
    { cat "$WORK/cur"; printf '\n; %s\n' "$MARK"; cat "$2"; } > "$WORK/new"
    "$HERE/bin/patch-system-file.sh" "$IMG" "$1" "$WORK/new" | tail -1
}
append /etc/selinux/system_ext_sepolicy.cil "$DEV/sepolicy/wsa_shared_folder.cil"
# property_contexts uses "#" for comments, not ";".
debugfs -R "dump /etc/selinux/system_ext_property_contexts $WORK/pc" "$IMG" >/dev/null 2>&1
if grep -q "external_storage.windows_user.mounted" "$WORK/pc"; then
    echo "-- properties already present"
else
    { cat "$WORK/pc"; printf '\n'; cat "$DEV/sepolicy/property_contexts"; } > "$WORK/pc.new"
    "$HERE/bin/patch-system-file.sh" "$IMG" /etc/selinux/system_ext_property_contexts "$WORK/pc.new" | tail -1
fi

e2fsck -fy "$IMG" 2>&1 | tail -1
echo "done: shared folder added to $IMG"
