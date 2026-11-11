#!/usr/bin/env bash
# Builds the vendor image of the headless WSA: Microsoft's vendor.vhdx with the four graphics
# parts swapped in place, so that our own VM (the own host, no virtio-gpu) boots enforcing without any bind mounts.
#
#   bin/headless-vendor.sh <vendor.vhdx of an install> <parts dir> <out vendor-headless.vhdx>
#   bin/headless-vendor.sh /mnt/c/WSA/vendor.vhdx <parts> \
#       /mnt/c/Users/you/AppData/Local/Temp/wsa-reborn-host-6/vendor-headless.vhdx
#
# What goes where (same paths and SELinux labels as Microsoft's files, bin/patch-system-file.sh keeps them):
#   static/lib64|lib/vulkan.pastel.so           -> /lib64|lib/hw/vulkan.ranchu.so   SwiftShader (ro.hardware.vulkan=ranchu)
#   gralloc/bin/hw/…allocator@4.0-service.minigbm_dmaheap -> …allocator@4.0-service.windows  minigbm, dmaheap backend
#   gralloc/lib64|lib/hw/…mapper@4.0-impl.minigbm_dmaheap.so -> …mapper@4.0-impl.windows.so  (both ABIs)
#   gralloc/lib64/hw/hwcomposer.wsa_headless.so  -> /lib64/hw/hwcomposer.windows.so    device/microsoft/wsa_x86_64/headless-hwc
#   build.prop: ro.hardware.egl=emulation -> angle (ANGLE from /system over SwiftShader)
#   vendor_sepolicy.cil: + execmem for surfaceflinger (SwiftShader JIT; the one denial of an enforcing boot, 15.10.2026),
#   + vsock create/connect for the composer (frame sink of headless-hwc, frames to the host window)
# Parts dir layout is what the build machine put on F: (md5 in gralloc/md5.txt; the 32-bit mapper since 15.10). Image grows by
# 96 MiB for SwiftShader. 15.10.2026.
set -euo pipefail
src=${1:?vendor.vhdx} parts=${2:?parts dir} out=${3:?out vhdx}
here=$(cd "$(dirname "$0")" && pwd); P=$here/patch-system-file.sh
w=$(mktemp -d); trap 'rm -rf "$w"' EXIT
qemu-img convert -f vhdx -O raw "$src" "$w/vendor.img"
truncate -s +96M "$w/vendor.img"; e2fsck -fy "$w/vendor.img" >/dev/null 2>&1 || true; resize2fs "$w/vendor.img" >/dev/null 2>&1
q() { "$P" "$w/vendor.img" "$1" "$2" | grep -E '^done:' ; }
q /lib64/hw/vulkan.ranchu.so "$parts/static/lib64/vulkan.pastel.so"
q /lib/hw/vulkan.ranchu.so "$parts/static/lib/vulkan.pastel.so"
q /bin/hw/android.hardware.graphics.allocator@4.0-service.windows \
  "$parts/gralloc/bin/hw/android.hardware.graphics.allocator@4.0-service.minigbm_dmaheap"
q /lib64/hw/android.hardware.graphics.mapper@4.0-impl.windows.so \
  "$parts/gralloc/lib64/hw/android.hardware.graphics.mapper@4.0-impl.minigbm_dmaheap.so"
q /lib/hw/android.hardware.graphics.mapper@4.0-impl.windows.so \
  "$parts/gralloc/lib/hw/android.hardware.graphics.mapper@4.0-impl.minigbm_dmaheap.so"
q /lib64/hw/hwcomposer.windows.so "$parts/gralloc/lib64/hw/hwcomposer.wsa_headless.so"
debugfs -R "cat /build.prop" "$w/vendor.img" 2>/dev/null > "$w/build.prop"
grep -q '^ro.hardware.egl=emulation$' "$w/build.prop" || { echo "no ro.hardware.egl=emulation in build.prop" >&2; exit 1; }
sed -i 's/^ro.hardware.egl=emulation$/ro.hardware.egl=angle/' "$w/build.prop"
# Launcher3 of Android 17 shows its taskbar only with a navigation bar (see bin/hwc-vendor.sh), 27.10.2026.
grep -q '^qemu.hw.mainkeys=' "$w/build.prop" || printf 'qemu.hw.mainkeys=0\n' >> "$w/build.prop"
q /build.prop "$w/build.prop"
debugfs -R "cat /etc/selinux/vendor_sepolicy.cil" "$w/vendor.img" 2>/dev/null > "$w/vendor_sepolicy.cil"
printf '%s\n' ';; WSA-Reborn headless: SwiftShader JITs shaders inside surfaceflinger (anon:swiftshader_jit, PROT_EXEC) - 15.10.2026' \
    '(allow surfaceflinger_33_0 self (process (execmem)))' \
    ';; WSA-Reborn headless: the HWC2 frame sink connects to the host on vsock 50100 (Microsoft composer only had read/write)' \
    '(allow windows-composer-hal self (vsock_socket (create connect getopt setopt)))' >> "$w/vendor_sepolicy.cil"
q /etc/selinux/vendor_sepolicy.cil "$w/vendor_sepolicy.cil"
# Microsoft's OMX plugin (Latte) waits forever for WsaClient's media service: no /sdcard (see bin/hwc-vendor.sh), 27.10.2026.
for f in /lib/libstagefrighthw.so /lib64/libstagefrighthw.so; do
    debugfs -R "stat $f" "$w/vendor.img" 2>/dev/null | grep -q Inode: && debugfs -w -R "rm $f" "$w/vendor.img" >/dev/null 2>&1 && echo "removed: $f"
done
e2fsck -fn "$w/vendor.img" >/dev/null
md5sum "$w/vendor.img" | sed "s|$w/||"
qemu-img convert -f raw -O vhdx -o subformat=dynamic "$w/vendor.img" "$out"
echo "written: $out"
