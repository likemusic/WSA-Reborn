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
# the frame sink (bin/headless-vendor.sh has the same). 16.10.2026. With qemu.hw.mainkeys=0 since 27.10.2026; Microsoft's OMX plugin (Latte) stays since 29.10.2026 (LATTE=0 removes it), with the MPEG-4 Part 2 role fixed, VP9's codec-config buffer dropped and Codec2 on beside it (ccodec=4).
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
# Microsoft's OMX plugin (Latte, /lib/libstagefrighthw.so) asks WsaClient's media service over vsock at the start of
# media.codec and waits for an answer forever: IOmxStore never registers, mediaserver waits for it, and so does
# StorageManagerService (handleSystemReady -> MediaCodecList) — emulated;0 never mounts, no /sdcard, Settings > Storage
# crashes on external_primary (27.10.2026). The own host answers it since 28-29.10.2026 (its media channel,
# docs/own-host.md), so the plugin stays; LATTE=0 removes it for a host without the channel, and then OMXStore
# registers with the software codecs only.
if [ "${LATTE:-1}" = 0 ]; then
    for f in /lib/libstagefrighthw.so /lib64/libstagefrighthw.so; do
        debugfs -R "stat $f" "$w/vendor.img" 2>/dev/null | grep -q Inode: && debugfs -w -R "rm $f" "$w/vendor.img" >/dev/null 2>&1 && echo "removed: $f"
    done
else
    # The plugin's MPEG-4 Part 2 component has the role "video_decoder.mp4v" instead of "video_decoder.mpeg4", and
    # Android drops it: no MPEG-4 decoder at all (29.10.2026, bin/latte-mpeg4-role.py).
    # It also lists its components only with debug.stagefright.ccodec=0, which turns Codec2 off: no c2.android.*
    # software codecs. Without that check and with ccodec=4 the list is mixed, Latte first (bin/latte-ccodec.py).
    for f in /lib/libstagefrighthw.so /lib64/libstagefrighthw.so; do
        debugfs -R "cat $f" "$w/vendor.img" 2>/dev/null > "$w/sfhw.so"
        if "$here/latte-mpeg4-role.py" "$w/sfhw.so" "$w/sfhw-role.so"; then cp "$w/sfhw-role.so" "$w/sfhw.so"; fi
        if "$here/latte-ccodec.py" "$w/sfhw.so" "$w/sfhw-c2.so"; then cp "$w/sfhw-c2.so" "$w/sfhw.so"; fi
        q "$f" "$w/sfhw.so"
    done
    # Its VP9 component sends the codec-config buffer of Android 15+ (csd-0 from WebM/MP4) to the host decoder like a
    # frame, which then holds 128 frames: dropped as AOSP's VP9 decoders do (29.10.2026, bin/latte-vp9-csd.py; only the
    # 32-bit plugin is loaded, by the 32-bit OMX service).
    debugfs -R "cat /lib/libstagefrighthw.so" "$w/vendor.img" 2>/dev/null > "$w/sfhw.so"
    if "$here/latte-vp9-csd.py" "$w/sfhw.so" "$w/sfhw-vp9.so"; then q /lib/libstagefrighthw.so "$w/sfhw-vp9.so"; fi
fi
# WSA's config_showNavigationBar is false, and Launcher3 of Android 17 creates its taskbar only on a display with a
# navigation bar (Android 16 does not ask): no taskbar in the one-window mode (scale_desktop resize). qemu.hw.mainkeys=0
# makes DisplayPolicy report one; with the taskbar SystemUI adds no navigation bar of its own, so A16 and A17 both get just
# the taskbar (27.10.2026).
debugfs -R "cat /build.prop" "$w/vendor.img" 2>/dev/null > "$w/build.prop"
grep -q '^qemu.hw.mainkeys=' "$w/build.prop" || printf 'qemu.hw.mainkeys=0\n' >> "$w/build.prop"
[ "${LATTE:-1}" = 0 ] || sed -i 's/^debug.stagefright.ccodec=0$/debug.stagefright.ccodec=4/' "$w/build.prop"
q /build.prop "$w/build.prop"
e2fsck -fn "$w/vendor.img" >/dev/null
md5sum "$w/vendor.img" | sed "s|$w/||"
qemu-img convert -f raw -O vhdx -o subformat=dynamic "$w/vendor.img" "$out"
echo "written: $out"
