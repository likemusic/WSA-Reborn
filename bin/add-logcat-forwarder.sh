#!/usr/bin/env bash
# Puts the STOCK logcat-to-host channel back into a slot: a Microsoft component we never carried over
# at any point of the work, neither via path A nor via path B.
#
#   bin/add-logcat-forwarder.sh --product <product.img|vhdx> --sysext <system_ext.img|vhdx> \
#       --wsa-product <unpacked product of the Microsoft 2407.40000.4.0 reference> [--out <dir>]
#
#   example (edit images in place):
#     bin/add-logcat-forwarder.sh --product slot/product.img --sysext slot/system_ext.img \
#         --wsa-product $WSA_REF/product
#
#   example (ready WSA slot, sources are not touched):
#     bin/add-logcat-forwarder.sh --out $WSA_STORAGE/wsa-a14v-logcat \
#         --product $WSA_INSTALL_DIR/product.vhdx.vanilla14-20260923 \
#         --sysext  $WSA_INSTALL_DIR/system_ext.vhdx.vanilla14-20260923 \
#         --wsa-product $WSA_REF/product
#
# With --out the input files are opened READ-ONLY and the result goes into the given directory
# as product.vhdx/system_ext.vhdx. This is the working mode for slots: slot files live in the
# WSA install directory on C:, which must not be written to (and need not be: switch-build.sh does the placing).
# Input format is detected by signature: vhdx is expanded to raw with qemu-img and packed back
# with it; .img is used as is. The channel does not touch system.vhdx, only these two files
# need to be carried over.
#
# WHY. WSA has its own logcat export: the guest /product/bin/logcat_forwarder
# reads the log (popen of /system/bin/logcat) and hands lines to the host half over RCM/vsock, which
# writes them to LocalState/diagnostics/logcat/<date>.log next to ETW traces and tombstones. The host
# half ALWAYS runs and creates a file on every subsystem start — that is why this directory
# had a pile of empty .log files: it was waiting for a guest that was not in our images. The only
# non-empty file is from 17.09.2026, when the disk still had Microsoft's product.
#
# This closes the gap that capped diagnostics: the kernel ETW console
# (bin/wsa-guest-console.sh) gives printk from 0.2 seconds, but has no Java exceptions, while adb on
# a user build is unavailable and never lives until the system_server crash loop anyway.
#
# WHAT IT ADDS (verbatim from the reference, nothing of our own):
#   the bin/logcat_forwarder binary, four libraries in lib64 (libapipipe, librcm,
#            windows.services.rcm-V1-ndk, libprotobuf-cpp-lite-3.9.1) and etc/init/
#            logcat_forwarder.rc (early_hal class service, user/group system) — into product OR
#            into system_ext, the script decides itself, see "WHERE IT GOES" below;
#   system_ext: eleven rules from the reference product_sepolicy.cil — appended to
#            system_ext_sepolicy.cil rather than adding a separate product policy: secilc
#            compiles all .cil files in one pass, the file of origin does not matter.
#
# WHERE IT GOES, and why it matters. Microsoft has the binary in /product/bin, and that works
# only because their product/etc/build.prop sets ro.product.vndk.version=33: then linkerconfig
# builds a SEPARATE namespace for /product/bin with links into VNDK, which provides libbase,
# libcutils, libutils, liblog, libhidlbase. Google's prebuilt GSIs have the property too (in aosp14-gsi —
# version 34), so there the "Microsoft way" transfer worked on the first try. In OUR builds
# (aosp14-vanilla, lineage21) the property is absent: the product namespace has no VNDK links, and
# the forwarder dies at startup — "CANNOT LINK EXECUTABLE ... library "libbase.so" not found", init
# restarts it every five seconds, the channel stays silent. So for such images the script puts the channel
# into system_ext: that partition is always in the SYSTEM section of linkerconfig, where the default namespace searches
# both /system/lib64 and /system_ext/lib64, i.e. all six platform dependencies are there.
# Adding ro.product.vndk.version to our own build for the sake of one binary is not an option: it changes the
# linking rules for everything in product, not just for it.
#
# The logcat_forwarder and logcat_forwarder_exec types are declared in plat_pub_versioned.cil on Microsoft's
# vendor partition, which we do not touch in any slot — so the domain policy does not need
# to be carried over, only the permissions. Versioned attributes (_33_0) are linked by wsa_vendor_compat.cil, which already
# gets into the image via bin/transplant-a14.sh.
#
# Android 14 compatibility was checked by symbols: all unresolved ones of the binary and its libraries are
# libc/liblog, i.e. the runtime APEX; not a single C++ symbol from libbase/libutils/
# libhidlbase is missing (unlike binaries with patch 0010, where fmt::v7/v10 diverge).
#
# Microsoft files are NOT in the repository (legal restriction, BYOB) — only paths to them.
set -euo pipefail
. "$(dirname "$0")/wsa-env.sh"

PRODUCT=""; SYSEXT=""; WSAPRD=""; OUT=""
while [ $# -gt 0 ]; do
    case "$1" in
        --product)     PRODUCT="$2"; shift 2 ;;
        --sysext)      SYSEXT="$2";  shift 2 ;;
        --wsa-product) WSAPRD="$2";  shift 2 ;;
        --out)         OUT="$2";     shift 2 ;;
        *) echo "unknown argument: $1" >&2; exit 1 ;;
    esac
done
[ -n "$PRODUCT" ] && [ -n "$SYSEXT" ] && [ -n "$WSAPRD" ] || { sed -n '2,10p' "$0"; exit 1; }

HERE=$(cd "$(dirname "$0")" && pwd)
LIBS="libapipipe.so librcm.so windows.services.rcm-V1-ndk.so libprotobuf-cpp-lite-3.9.1.so"

for f in bin/logcat_forwarder etc/init/logcat_forwarder.rc; do
    [ -r "$WSAPRD/$f" ] || { echo "missing in reference: $WSAPRD/$f" >&2; exit 1; }
done
for l in $LIBS; do
    [ -r "$WSAPRD/lib64/$l" ] || { echo "missing in reference: $WSAPRD/lib64/$l" >&2; exit 1; }
done
is_vhdx() { [ "$(head -c 4 "$1" 2>/dev/null)" = vhdx ]; }

if [ -n "$OUT" ]; then
    command -v qemu-img >/dev/null || { echo "qemu-img not found (package qemu-utils)" >&2; exit 1; }
    [ -r "$PRODUCT" ] && [ -r "$SYSEXT" ] || { echo "images must be readable" >&2; exit 1; }
    mkdir -p "$OUT"
    WORK="$OUT/.work-$$"; mkdir -p "$WORK"
    CLEAN="${CLEAN:-} $WORK"
    trap 'rm -rf $CLEAN' EXIT
    IN_PRODUCT="$PRODUCT"; IN_SYSEXT="$SYSEXT"
    for part in product system_ext; do
        src=$([ "$part" = product ] && echo "$IN_PRODUCT" || echo "$IN_SYSEXT")
        dst="$WORK/$part.img"
        if is_vhdx "$src"; then
            echo "-- expanding $part from vhdx: $src"
            qemu-img convert -f vhdx -O raw "$src" "$dst"
        else
            cp -f "$src" "$dst"
        fi
        [ "$part" = product ] && PRODUCT="$dst" || SYSEXT="$dst"
    done
else
    [ -w "$PRODUCT" ] && [ -w "$SYSEXT" ] || { echo "images must be writable" >&2; exit 1; }
fi

exists() { debugfs -R "stat $2" "$1" 2>/dev/null | grep -q '^Inode:'; }

# --- 1. choosing the partition ------------------------------------------------------------------
# Decided by the ro.product.vndk.version property in product/etc/build.prop: if present, /product/bin
# has its own namespace with links into VNDK (as at Microsoft and in prebuilt GSIs) and the binary goes
# there, as in the reference. If absent — it is our own build, product is isolated, and the only
# place libbase is visible from without editing the linker policy is system_ext. Details in the header.
PRDPROP=$(mktemp); CLEAN="${CLEAN:-} $PRDPROP"
trap 'rm -rf $CLEAN' EXIT
debugfs -R "dump /etc/build.prop $PRDPROP" "$PRODUCT" 2>/dev/null >/dev/null
if grep -q '^ro\.product\.vndk\.version=' "$PRDPROP" 2>/dev/null; then
    TGT="$PRODUCT"; TGTNAME=product; BINPATH=/product/bin/logcat_forwarder
    echo "-- product with VNDK ($(grep -m1 '^ro\.product\.vndk\.version=' "$PRDPROP")): putting the channel into product, as in the reference"
else
    TGT="$SYSEXT";  TGTNAME=system_ext; BINPATH=/system_ext/bin/logcat_forwarder
    echo "-- product has no ro.product.vndk.version (our own build): putting the channel into system_ext,"
    echo "   otherwise the linker will not find libbase.so and the service will restart every 5 s"
fi

# --- 2. space in the target partition -----------------------------------------------------------
# The build sizes the image tightly (the aosp14-gsi slot had 140 KB free), and ~1.1 MB is needed.
NEED=$(( $(stat -c %s "$WSAPRD/bin/logcat_forwarder") + $(stat -c %s "$WSAPRD/etc/init/logcat_forwarder.rc") ))
for l in $LIBS; do NEED=$(( NEED + $(stat -c %s "$WSAPRD/lib64/$l") )); done
BS=$(dumpe2fs -h "$TGT" 2>/dev/null | grep -oP 'Block size:\s*\K[0-9]+')
FREE=$(( $(dumpe2fs -h "$TGT" 2>/dev/null | grep -oP 'Free blocks:\s*\K[0-9]+') * BS ))
if [ "$FREE" -le $(( NEED + 4 * 1048576 )) ]; then
    echo "-- $TGTNAME has $((FREE/1024)) KB free, $((NEED/1024)) KB needed: growing by 16 MiB"
    truncate -s +16M "$TGT"
    e2fsck -fy "$TGT" >/dev/null 2>&1 || true
    resize2fs "$TGT" >/dev/null 2>&1
fi

# --- 3. files ------------------------------------------------------------------------------------
# Directory labels stay system (the add-image-file.sh default): the
# logcat_forwarder_exec context belongs to the FILE; on a directory it would block init from entering bin/.
add() { # <path in image> <source> <context> <mode>
    if exists "$TGT" "$1"; then echo "-- already present, skipping: $TGTNAME$1"; return; fi
    "$HERE/add-image-file.sh" "$TGT" "$2" "$1" "$3" "$4" 0:0
}
add /bin/logcat_forwarder          "$WSAPRD/bin/logcat_forwarder"          u:object_r:logcat_forwarder_exec:s0 0755
for l in $LIBS; do
    add "/lib64/$l"                "$WSAPRD/lib64/$l"                      u:object_r:system_lib_file:s0       0644
done

# The reference .rc names the binary by absolute path — when moving to system_ext the path must be rewritten,
# otherwise init launches a nonexistent file every five seconds.
RC=$(mktemp); CLEAN="$CLEAN $RC"
sed "s#^service logcat_forwarder .*#service logcat_forwarder $BINPATH#" \
    "$WSAPRD/etc/init/logcat_forwarder.rc" > "$RC"
add /etc/init/logcat_forwarder.rc  "$RC"                                   u:object_r:system_file:s0           0644

# --- 3. rules in system_ext_sepolicy.cil --------------------------------------------------------
CIL=$(mktemp); CLEAN="$CLEAN $CIL"
debugfs -R "dump /etc/selinux/system_ext_sepolicy.cil $CIL" "$SYSEXT" 2>/dev/null >/dev/null
[ -s "$CIL" ] || { echo "failed to read system_ext_sepolicy.cil from $SYSEXT" >&2; exit 1; }

if grep -q "typetransition init logcat_forwarder_exec" "$CIL"; then
    echo "-- logcat_forwarder rules already in system_ext_sepolicy.cil, skipping"
else
    cat >> "$CIL" <<'CILEOF'

; logcat channel to the host: permissions of the logcat_forwarder domain. Verbatim copy of
; /product/etc/selinux/product_sepolicy.cil from the 2407.40000.4.0 reference — the types themselves are declared in
; plat_pub_versioned.cil on vendor, versioned attributes are linked in wsa_vendor_compat.cil.
; See bin/add-logcat-forwarder.sh and docs/microsoft-components-checklist.md.
(allow init logcat_forwarder_exec (file (read getattr map execute open)))
(allow init logcat_forwarder (process (transition)))
(allow logcat_forwarder logcat_forwarder_exec (file (read getattr map execute open entrypoint)))
(dontaudit init logcat_forwarder (process (noatsecure)))
(allow init logcat_forwarder (process (siginh rlimitinh)))
(typetransition init logcat_forwarder_exec process logcat_forwarder)
(allow logcat_forwarder self (vsock_socket (write create getattr connect setopt)))
(allow logcat_forwarder shell_exec (file (ioctl read getattr lock map execute open watch watch_reads execute_no_trans)))
(allow logcat_forwarder logcat_exec (file (ioctl read getattr lock map execute open watch watch_reads execute_no_trans)))
(allow logcat_forwarder logdr_socket (sock_file (write)))
(allow logcat_forwarder logd (unix_stream_socket (read write setopt connectto)))
CILEOF
    "$HERE/patch-system-file.sh" "$SYSEXT" /etc/selinux/system_ext_sepolicy.cil "$CIL" >/dev/null
    echo "-- logcat_forwarder rules appended to system_ext_sepolicy.cil"
fi

if [ -n "$OUT" ]; then
    echo
    for part in product system_ext; do
        src=$([ "$part" = product ] && echo "$PRODUCT" || echo "$SYSEXT")
        e2fsck -fy "$src" >/dev/null 2>&1 || true
        echo "-- packing $part.vhdx"
        qemu-img convert -f raw -O vhdx "$src" "$OUT/$part.vhdx"
    done
    echo "-- ready slot files: $OUT/{product,system_ext}.vhdx (the channel does not touch system.vhdx)"
fi

echo
echo "done. The channel will show up on the host here (a file is created on every subsystem start):"
echo "  .../LocalState/diagnostics/logcat/<YYYYMMDDhhmmss>.log"
echo "If the file stays empty — check the ETW console (bin/wsa-guest-console.sh) for"
echo "avc: denied for logcat_forwarder and whether boot reached the early_hal class."
