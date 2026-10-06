#!/usr/bin/env bash
# Transplant Microsoft's mount components into our system tree.
#
#   bin/transplant-mount.sh <our system tree> <Microsoft system tree> [where to put the originals]
#
#   example: bin/transplant-mount.sh /mnt/los/new13/system /mnt/los/wsa/system /mnt/los/mount-orig
#
# WHY. WSA describes partitions in vendor/etc/fstab.1.windows in a non-standard way:
#
#   SCSI=0:0:0:1  /vendor      ext4  noatime,ro,errors=panic  wait,first_stage_mount
#
# `SCSI=0:0:0:1` is not a device path but a bus address. A stock fs_mgr cannot parse
# such an entry: the support was added by Microsoft and lives in
# libfs_mgr.so as android::fs_mgr::TranslateScsiAddress. Our build does not have this
# function at all.
#
# Consequence: with our init the first boot stage finds no devices for vendor,
# system_ext and product and panics BEFORE logd starts. From outside — a silent failure,
# indistinguishable from any other. This explains why replacing product worked
# (init stayed Microsoft's), while replacing system and a full replacement did not.
#
# This could not be seen in logs: at this stage logs do not exist yet.
# It was found by comparing strings in the binaries.
set -euo pipefail

OUR="${1:?specify our system tree}"
MS="${2:?specify the Microsoft system tree}"
KEEP="${3:-}"

[ -d "$OUR/system/bin" ] || { echo "does not look like a system tree: $OUR" >&2; exit 1; }
[ -d "$MS/system/bin" ]  || { echo "does not look like a system tree: $MS" >&2; exit 1; }

# init pulls in the libraries it was built with: take a consistent set
# from one build, not a mix. ueventd need not be transplanted — it is a symlink to init.
FILES=(
    # The fstab itself. At the first stage vendor is not mounted yet, so init looks for
    # the table in the system root — /system/etc/fstab.<suffix>, where suffix is set by
    # androidboot.fstab_suffix from the kernel command line (1.windows on WSA).
    # Without it init does not know what to mount and fails with
    #   ReadDefaultFstab(): failed to find device default fstab
    # before there is anything to log with.
    "system/etc/fstab.1.windows"
    "system/etc/fstab.2.windows"
    "system/bin/init"
    "system/bin/snapshotctl"
    "system/lib64/libfs_mgr.so"
    "system/lib64/libfs_mgr_binder.so"
    "system/lib64/libbootloader_message.so"
)

[ -n "$KEEP" ] && mkdir -p "$KEEP"

echo "== transplanting mount components =="
for f in "${FILES[@]}"; do
    src="$MS/$f"; dst="$OUR/$f"
    [ -r "$src" ] || { echo "  MISSING at Microsoft: $f" >&2; exit 1; }
    if [ -n "$KEEP" ] && [ -e "$dst" ]; then
        mkdir -p "$KEEP/$(dirname "$f")"
        cp -a "$dst" "$KEEP/$f"
    fi
    cp -a "$src" "$dst"
    printf "  %-38s %s\n" "$(basename "$f")" "$(stat -c '%s' "$dst") bytes"
done

echo
echo "== check: does the transplanted set understand SCSI addresses =="
for f in "system/bin/init" "system/lib64/libfs_mgr.so"; do
    n=$(strings "$OUR/$f" 2>/dev/null | grep -c 'TranslateScsiAddress' || true)
    printf "  %-38s mentions: %s\n" "$(basename "$f")" "${n:-0}"
    [ "${n:-0}" -gt 0 ] || { echo "  transplant failed: no SCSI support" >&2; exit 1; }
done

echo
echo "ueventd: $(readlink "$OUR/system/bin/ueventd" 2>/dev/null || echo '(not a symlink — check by hand)')"

# --- Microsoft's part of the system server -----------------------------------
# WSA extends system_server with its own classes: the display area policy
# (WsaDisplayAreaPolicyProvider), window launch parameter tweaks
# (WsaLaunchParamsModifier), the link to the system app (WsaSystemServer).
# They live in a separate /system/framework/windows-system_server.jar, which
# Microsoft adds to SYSTEMSERVERCLASSPATH next to its services.jar.
#
# Without it system_server crashes in the android.display thread:
#   ClassNotFoundException: com.android.server.wm.WsaDisplayAreaPolicyProvider
#     for config_deviceSpecificDisplayAreaPolicyProvider
# The resource is set by vendor, which we do not touch, and our build lacks the class.
#
# odex/vdex for it are NOT transplanted: they are compiled against Microsoft's boot image
# and will not match ours by checksums. ART will notice and
# take the dex from the jar itself — it is there, both classes.dex inside.
JAR="system/framework/windows-system_server.jar"
if [ -r "$MS/$JAR" ]; then
    cp -a "$MS/$JAR" "$OUR/$JAR"
    echo "framework: $(basename "$JAR") — $(stat -c '%s' "$OUR/$JAR") bytes"

    # The jar list for system_server is in protobuf. The entry format is simple:
    #   0A <len+4> 0A <len> <path> 10 02
    # Our list cannot be replaced wholesale: it contains org.lineageos.platform.jar,
    # which Microsoft does not have. So we append one entry.
    python3 - "$OUR/system/etc/classpaths/systemserverclasspath.pb" "/system/framework/windows-system_server.jar" <<'PY'
import sys
pb, path = sys.argv[1], sys.argv[2].encode()
data = open(pb, 'rb').read()
entry = b'\x0a' + bytes([len(path) + 4]) + b'\x0a' + bytes([len(path)]) + path + b'\x10\x02'
if entry in data:
    data = data.replace(entry, b'')
# AT THE END, as Microsoft itself does. Putting their jar first is tempting: then
# their patches to system services would take effect (their SystemApp calls EthernetManager.
# updateConfiguration() with capabilities, and unpatched AOSP answers
# UnsupportedOperationException). But this must not be done:
#
#   NoSuchMethodError ... at register_android_server_InputManager
#     (/system/lib64/libandroid_servers.so)
#
# The native libandroid_servers.so registers JNI methods of the system server's Java
# classes, and ours is our own, from LineageOS. Java classes from a foreign jar
# do not match it — system_server crashes at start. The native and Java parts
# of the system server must not be mixed across builds.
open(pb, 'wb').write(data + entry)
print("classpath: entry appended at the end (%d bytes)" % len(entry))
PY
else
    echo "  MISSING at Microsoft: $JAR" >&2
fi

# --- Microsoft network module: DO NOT TRANSPLANT -----------------------------
# We tried replacing com.android.tethering.apex with Microsoft's module to
# get its config_ethernet_interfaces resource (because of which SystemApp
# crashes on updateConfiguration with capabilities). Attempt 19: apexd accepted the module
# and mounted it, SystemApp stopped crashing — but the system did not come up:
#
#   NetworkPolicyManagerService.sendUidsAllowedTransportsUL
#   NetworkPolicyManagerService.initService
#
# Inside the apex is framework-connectivity.jar — part of the bootclasspath against
# which our services.jar is built. A foreign version does not match it, and
# system_server crashes. Same nature as the ban on changing the classpath order:
# the Java parts of the system must come from one build.
#
# The right way is to override only the resource, with an overlay (RRO), without touching code.
