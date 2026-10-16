#!/usr/bin/env bash
# Builds the vendor image for the own host WITH its own virtio-gpu: Microsoft's vendor.vhdx as is —
# its gralloc, EGL and Vulkan of gfxstream render on the host GPU through gfxstream_backend.dll — with only the composer
# swapped: our HWC2 (device/microsoft/wsa_x86_64/headless-hwc) in place of hwcomposer.windows.so, whose frames would go
# to the GSK renderer that only WsaClient has. Our HWC2 reads the client target and the task layers back from the host
# (TRANSFER_FROM_HOST) and sends them to host/host on vsock 50100, as for the headless vendor.
#
#   bin/hwc-vendor.sh <vendor.vhdx of an install> <hwcomposer.wsa_headless.so> <out vendor-gpu.vhdx>
#   bin/hwc-vendor.sh /mnt/c/WSA/vendor.vhdx \
#       "$(device/microsoft/wsa_x86_64/headless-hwc/ndk-build.sh)" \
#       /mnt/c/Users/you/AppData/Local/Temp/wsa-reborn-host-6/vendor-gpu.vhdx
#
# Same file path and SELinux label as Microsoft's (bin/patch-system-file.sh); vendor_sepolicy.cil gets the vsock rule of
# the frame sink (bin/headless-vendor.sh has the same). 16.10.2026.
set -euo pipefail
src=${1:?vendor.vhdx} hwc=${2:?hwcomposer .so} out=${3:?out vhdx}
here=$(cd "$(dirname "$0")" && pwd); P=$here/patch-system-file.sh
w=$(mktemp -d); trap 'rm -rf "$w"' EXIT
qemu-img convert -f vhdx -O raw "$src" "$w/vendor.img"
truncate -s +8M "$w/vendor.img"; e2fsck -fy "$w/vendor.img" >/dev/null 2>&1 || true; resize2fs "$w/vendor.img" >/dev/null 2>&1
q() { "$P" "$w/vendor.img" "$1" "$2" | grep -E '^done:' ; }
q /lib64/hw/hwcomposer.windows.so "$hwc"
debugfs -R "cat /etc/selinux/vendor_sepolicy.cil" "$w/vendor.img" 2>/dev/null > "$w/vendor_sepolicy.cil"
grep -q 'vsock_socket (create connect getopt setopt)' "$w/vendor_sepolicy.cil" || {
    printf '%s\n' ';; WSA-Reborn (own host): the HWC2 frame sink connects to the host on vsock 50100 (Microsoft composer only had read/write)' \
        '(allow windows-composer-hal self (vsock_socket (create connect getopt setopt)))' >> "$w/vendor_sepolicy.cil"
    q /etc/selinux/vendor_sepolicy.cil "$w/vendor_sepolicy.cil"
}
e2fsck -fn "$w/vendor.img" >/dev/null
md5sum "$w/vendor.img" | sed "s|$w/||"
qemu-img convert -f raw -O vhdx -o subformat=dynamic "$w/vendor.img" "$out"
echo "written: $out"
