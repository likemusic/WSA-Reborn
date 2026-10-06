#!/usr/bin/env bash
# Builds a diagnostic initrd: mounts the partitions and starts Android init.
#
#   bin/make-diag-initrd.sh [where to put the initrd]
#
# WHY. The stock WSA initrd (`Tools/initrd.img`) is unusable for the local
# stand — in OUR install (built as WSABuilds/MagiskOnWSALocal)
# it is patched with the chain `lspinit → magiskinit → wsainit`, and
# `lspinit` under bare QEMU crashes in the second second ("Hello from lspinit..."
# → `WSL (1) ERROR: InitEntryUtilityVm: fcntl failed 9` → "Attempted to kill
# init"), since it expects a communication channel to the Windows host (hvsocket), which
# QEMU does not have. That tells about the initrd, not about the system.img under test — hence
# the rule adopted here, "the stand must first be able to say 'yes'" (see
# qemu-boot.sh), and why qemu-boot.sh must boot by default
# with EXACTLY this initrd, not the original Tools/initrd.img.
#
# This initrd does exactly what diagnostics need: mounts partitions from
# the same addresses the WSA fstab uses, switches to the new root and starts
# /system/bin/init. Everything Android says during boot goes to the console —
# exactly what is invisible in WSA itself, because logd has not started there yet.
#
# T (working directory) and BUSYBOX are set via environment variables,
# as in qemu-boot.sh — by default T=$WSA_WORK/diag, and a static
# busybox lives right there ($T/busybox); the system (Ubuntu from apt) has neither
# a static busybox nor cpio — both are taken from this same directory
# (the shim wrapper $T/shim/cpio runs `busybox cpio`).
set -euo pipefail

T="${T:-${WSA_WORK:-$HOME/wsa-work}/diag}"
OUT="${1:-$T/diag-initrd.img}"
BB="${BUSYBOX:-$T/busybox}"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

[ -x "$BB" ] || { echo "no static busybox: $BB (set BUSYBOX=)" >&2; exit 1; }
command -v cpio >/dev/null || { echo "cpio not found — add $T/shim to PATH (a wrapper over busybox cpio) or install the cpio package" >&2; exit 1; }

mkdir -p "$WORK"/{bin,dev,proc,sys,mnt/system,mnt/vendor,mnt/system_ext,mnt/product}
cp "$BB" "$WORK/bin/busybox"
chmod 755 "$WORK/bin/busybox"
for a in sh mount umount ls cat echo sleep switch_root mkdir dmesg; do
    ln -sf busybox "$WORK/bin/$a"
done

cat > "$WORK/init" <<'INIT'
#!/bin/sh
# Diagnostic first stage. Everything goes to the console: that is the result.
export PATH=/bin
/bin/mount -t proc proc /proc
/bin/mount -t sysfs sysfs /sys
/bin/mount -t devtmpfs devtmpfs /dev

echo "=== DIAGNOSTICS: first stage started ==="
echo "--- disks seen by the kernel:"
/bin/ls /dev/sd* 2>&1
echo "--- SCSI bus addresses (what the WSA fstab works with):"
/bin/ls /sys/bus/scsi/devices/ 2>&1
echo "--- full sysfs path of sda (for androidboot.boot_devices=, PCI prefix):"
/bin/busybox readlink -f /sys/class/block/sda 2>&1

# Same order as in vendor/etc/fstab.1.windows: system first, then by
# logical unit number.
echo "--- mounting system (sda):"
/bin/mount -t ext4 -o ro /dev/sda /mnt/system 2>&1 && echo "  system: mounted" || echo "  system: ERROR"
echo "--- system root contents:"
/bin/ls /mnt/system 2>&1

# vendor (sdb) is NOT mounted in advance — trying to do so (13-14.09) turned out
# to be a mistake: it does NOT help find fstab.1.windows (the first stage reads it from
# /system/etc/, see below), and instead GETS IN THE WAY — it "eats" the uevent for sdb, and
# the real BlockDevInitializer of init's first stage cannot rediscover it
# at address SCSI=0:0:0:1, hanging on "Wait for partitions returned
# after 10014ms" -> InitFatalReboot. The real cause of the Android 14 crash was
# elsewhere (see below) — on a live WSA the first stage of init mounts vendor by itself
# from fstab, without our help, exactly as happens with Microsoft's
# image.

# system_ext/product are NOT mounted — Android init's first stage does not need them,
# it will now find the right fstab itself via /vendor and mount the rest from
# it on its own terms (by SCSI addresses). Mounting them here would mean
# taking init's job away and getting "Device or resource busy" instead of
# the real result.

echo "--- checking what we will boot with:"
/bin/ls -l /mnt/system/system/bin/init 2>&1
echo "--- are there startup rules:"
/bin/ls /mnt/system/system/etc/init/hw/ 2>&1 | /bin/cat

# A uevent retrigger right here (echo add > .../uevent) before exec was tried —
# it DID NOT HELP, and could not: the retrigger fires BEFORE switch_root, i.e. before
# Android init has even started and opened its netlink socket — the event
# still goes nowhere, just slightly earlier than the original one
# from the kernel (~0.6s). To actually hit the window, the retrigger must fire
# AFTER init starts (inside the new root), and switch_root by design kills
# all foreign processes of the old initramfs — a background "watchdog" cannot survive
# switch_root. The working approach (not done): replace switch_root with
# a manual pivot_root/chroot+exec that keeps exactly ONE background busybox process
# alive in the new namespace, firing the retrigger with a delay
# (it must land in the window between init opening the netlink socket and the expiry of
# its 10-second timeout). Not done 15.09.2026:
# decided not to waste effort on a stand artifact that
# probably does not exist on real Hyper-V (its synthetic SCSI controller is
# noticeably slower than QEMU virtio-scsi, the race may simply not occur).
echo "=== handing control to Android init ==="
exec /bin/switch_root /mnt/system /system/bin/init
echo "!!! switch_root failed — staying here"
exec /bin/sh
INIT
chmod 755 "$WORK/init"

( cd "$WORK" && find . | cpio -o -H newc --quiet ) > "$OUT"
echo "done: $OUT ($(stat -c %s "$OUT") bytes)"
