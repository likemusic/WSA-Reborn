#!/usr/bin/env bash
# Enable ARM translation (libhoudini) in our own Android 14 build — so that WSA can run
# apps whose native code exists only for ARM (arm64-v8a/armeabi-v7a).
#
#   bin/add-houdini.sh --sysext <system_ext.img> [--system <system.img>] \
#       [--wsa-vendor <reference vendor>] [--wsa-system <reference system>]
#
# Images are writable raw ext4. The translator itself goes into system_ext (Intel/Microsoft binaries
# from reference 2407.40000.4.0, BYOB — never committed to the repo); system gets only six symbolic
# links to /system_ext (--system can be omitted if system was built with them, see below). By
# default the reference is $WSA_REF/{vendor,system/system}.
#
# WHY. The translator lives in Microsoft's vendor (/vendor/lib64/libhoudini.so, ARM versions of system
# libraries in /vendor/lib64/arm64 and /vendor/lib/arm); our vendor is the stock one, and it sets the
# properties ro.dalvik.vm.native.bridge=libhoudini.so, ro.dalvik.vm.isa.arm64=x86_64 itself. But zygote
# looks up libhoudini.so with the system linker, houdini opens /system/lib64/arm64/libtcb.so by full
# path, and the vendor's binfmt_misc names /system/bin/houdini{,64} as interpreter. Microsoft's system
# has links and copies for this; our build has nothing: zygote logs "Failed to load native
# bridge implementation: library "libhoudini.so" not found", and an ARM-only app crashes in
# dlopen (EM_AARCH64 instead of EM_X86_64).
#
# WHY SYSTEM_EXT AND NOT LINKS TO VENDOR LIKE MICROSOFT (28.09.2026). The Android 14 linker checks
# accessibility by the REAL path. The system default namespace (linkerconfig,
# contents/namespace/systemdefault.cc) allows all of /system_ext/${LIB}, but from /system only the
# drm/extractors/hw subdirectories; /vendor is not allowed at all. Links to /vendor yield "is not accessible
# for the namespace", copies in /system/lib64/arm64 the same. Hence:
#   * /system_ext/lib{,64}/libhoudini.so — copies from vendor;
#   * /system_ext/lib64/arm64, /system_ext/lib/arm — directories with COPIES of the ARM libraries from vendor
#     (the only x86 file there, libtcb.so, is opened by houdini with the system linker). They cannot be
#     links to /vendor: verified 28.09.2026 — with links houdini initialises, but on the
#     app's very first ARM library it dies with "Fatal error (ID:0x01100096)"; with copies it works;
#   * /system_ext/bin/houdini{,64} — copies, like Microsoft's in /system/bin (the binfmt_misc interpreter
#     runs in the app's domain, and apps are not allowed to execute vendor files);
#   * in system — links /system/lib{,64}/libhoudini.so, /system/lib64/arm64, /system/lib/arm,
#     /system/bin/houdini{,64} to the corresponding /system_ext paths.
# system_ext needs ~215 MB and ~650 inodes; if short, the image grows in 128 MiB steps. This does not
# work for a tree build (system_ext inodes there are "used + 0.2 %" and the inode grid is sparse) — there
# the files must enter the build as proprietary blobs (found 28.09.2026).
set -euo pipefail
. "$(dirname "$0")/wsa-env.sh"

SYSEXT=""; SYSTEM=""
WSA_VENDOR="$WSA_REF/vendor"
WSA_SYSTEM="$WSA_REF/system/system"
while [ $# -gt 0 ]; do
    case "$1" in
        --sysext)     SYSEXT="$2"; shift 2 ;;
        --system)     SYSTEM="$2"; shift 2 ;;
        --wsa-vendor) WSA_VENDOR="$2"; shift 2 ;;
        --wsa-system) WSA_SYSTEM="$2"; shift 2 ;;
        *) echo "unknown argument: $1" >&2; exit 1 ;;
    esac
done
[ -n "$SYSEXT" ] || { sed -n '2,12p' "$0"; exit 1; }
HERE=$(cd "$(dirname "$0")" && pwd)
for img in "$SYSEXT" ${SYSTEM:+"$SYSTEM"}; do
    [ -w "$img" ] || { echo "no writable image: $img" >&2; exit 1; }
done
for f in lib64/libhoudini.so lib/libhoudini.so lib64/arm64/libtcb.so lib/arm/libtcb.so; do
    [ -r "$WSA_VENDOR/$f" ] || { echo "reference vendor lacks $f" >&2; exit 1; }
done
for f in bin/houdini bin/houdini64; do
    [ -r "$WSA_SYSTEM/$f" ] || { echo "reference system lacks $f" >&2; exit 1; }
done
exists() { debugfs -R "stat $2" "$1" 2>/dev/null | grep -q '^Inode:'; }
free_of() { dumpe2fs -h "$1" 2>/dev/null | grep -oP "$2:\\s*\\K[0-9]+"; }
LABEL=$(mktemp); printf 'u:object_r:system_lib_file:s0\0' > "$LABEL"
FLABEL=$(mktemp); printf 'u:object_r:system_file:s0\0' > "$FLABEL"
CMDS=$(mktemp); trap 'rm -f "$LABEL" "$FLABEL" "$CMDS"' EXIT

echo "== system_ext: $SYSEXT =="
if exists "$SYSEXT" /lib64/arm64; then
    echo "  /lib64/arm64 already exists — the translator seems to be in system_ext already"
else
    for _ in 1 2 3 4; do
        [ "$(free_of "$SYSEXT" 'Free inodes')" -ge 700 ] && [ "$(free_of "$SYSEXT" 'Free blocks')" -ge 57344 ] && break
        echo "  -- growing the image by 128 MiB (inodes: $(free_of "$SYSEXT" 'Free inodes'), blocks: $(free_of "$SYSEXT" 'Free blocks'))"
        e2fsck -fy "$SYSEXT" >/dev/null 2>&1 || true
        truncate -s +128M "$SYSEXT"
        resize2fs "$SYSEXT" >/dev/null 2>&1
    done
    [ "$(free_of "$SYSEXT" 'Free inodes')" -ge 700 ] || { echo "not enough inodes even after growing" >&2; exit 1; }
    for b in houdini houdini64; do
        "$HERE/add-image-file.sh" "$SYSEXT" "$WSA_SYSTEM/bin/$b" "/bin/$b" u:object_r:system_file:s0 0755 0:2000 | tail -1
    done
    for l in lib64 lib; do
        "$HERE/add-image-file.sh" "$SYSEXT" "$WSA_VENDOR/$l/libhoudini.so" "/$l/libhoudini.so" \
            u:object_r:system_lib_file:s0 0644 0:0 | tail -1
    done
    for pair in lib64/arm64 lib/arm; do
        {
            echo "mkdir /$pair"
            echo "sif /$pair mode 040755"
            echo "sif /$pair uid 0"
            echo "sif /$pair gid 0"
            echo "ea_set -f $LABEL /$pair security.selinux"
            echo "cd /$pair"
        } >> "$CMDS"
        n=0
        for f in "$WSA_VENDOR/$pair"/*; do
            name=$(basename "$f")
            echo "write $f $name"
            echo "sif /$pair/$name mode 0100644"
            echo "sif /$pair/$name uid 0"
            echo "sif /$pair/$name gid 0"
            echo "ea_set -f $LABEL /$pair/$name security.selinux"
            n=$((n + 1))
        done >> "$CMDS"
        echo "  /$pair: $n files from /vendor/$pair"
    done
    debugfs -w -f "$CMDS" "$SYSEXT" 2>&1 | grep -v -E '^debugfs|^Allocated inode' || true
    for pair in lib64/arm64 lib/arm; do
        debugfs -R "dump /$pair/libc.so /dev/stdout" "$SYSEXT" 2>/dev/null | cmp -s - "$WSA_VENDOR/$pair/libc.so" \
            || { echo "ERROR: /$pair/libc.so in the image does not match the reference" >&2; exit 1; }
    done
    e2fsck -fy "$SYSEXT" 2>&1 | tail -1
fi

if [ -n "$SYSTEM" ]; then
    echo "== system: $SYSTEM (links to /system_ext) =="
    : > "$CMDS"
    for link in lib64/libhoudini.so lib/libhoudini.so lib64/arm64 lib/arm bin/houdini bin/houdini64; do
        dir="/system/$(dirname "$link")"; name=$(basename "$link")
        if exists "$SYSTEM" "/system/$link"; then echo "  /system/$link already exists — skipping"; continue; fi
        case "$link" in bin/*) lab=$FLABEL ;; *) lab=$LABEL ;; esac
        echo "cd $dir" >> "$CMDS"
        echo "symlink $name /system_ext/$link" >> "$CMDS"
        echo "ea_set -f $lab /system/$link security.selinux" >> "$CMDS"
        echo "  /system/$link -> /system_ext/$link"
    done
    [ -s "$CMDS" ] && debugfs -w -f "$CMDS" "$SYSTEM" 2>&1 | grep -v -E '^debugfs|^Allocated inode' || true
    e2fsck -fy "$SYSTEM" 2>&1 | tail -1
fi
echo "done"
