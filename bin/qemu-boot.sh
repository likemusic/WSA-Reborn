#!/usr/bin/env bash
# Boots WSA locally in QEMU — with the kernel console.
#
#   bin/qemu-boot.sh <system.img> [seconds to wait] [output file]
#
#   example: bin/qemu-boot.sh $WSA_WORK/diag/ms-system.img 90 $WSA_WORK/diag/boot-ms.log
#
# T (working directory with the default SYSEXT/PRODUCT/VENDOR images and
# userdata/metadata stubs) and QEMU are set via environment
# variables — T=$WSA_WORK/diag QEMU=$(command -v qemu-system-x86_64).
#
# WHY. Inside WSA the failure is silent: logcat on the host stays empty, adb does not
# come up, the Windows event log shows only the host client crash. The reason is
# that everything breaks before anything able to log exists.
#
# But we have everything WSA builds its virtual machine from:
# the kernel (Tools/kernel, Linux 5.15 built by Microsoft), the initrd and partition images.
# So the same boot can be reproduced locally with the kernel console written to a file.
#
# The stand is not WSA: WSA has Hyper-V, virtiofs and vsock, which are absent here, and the
# system will not boot to the end here. But the first stages — mounting partitions, starting
# init, loading the policy — are the same, and that is exactly where things break.
#
# A CONTROL RUN IS MANDATORY: first run Microsoft's original image. If it also
# does not get further than ours, the stand is telling about itself, not about the image.
set -euo pipefail
. "$(dirname "$0")/wsa-env.sh"

T="${T:-${WSA_WORK:-$HOME/wsa-work}/diag}"
SYSTEM="${1:?specify system.img}"
SECONDS_WAIT="${2:-90}"
OUT="${3:-$T/qemu-boot.log}"

W=$WSA_INSTALL_DIR
QEMU="${QEMU:-$(command -v qemu-system-x86_64 || true)}"
[ -x "$QEMU" ] || { echo "qemu-system-x86_64 not found: $QEMU (set QEMU= or install the qemu-system-x86 package)" >&2; exit 1; }

# KVM is mandatory, not an "optional speedup": under plain TCG (without /dev/kvm)
# Android's bionic linker itself crashes with "invalid opcode" even BEFORE the APEX and
# apexd — because qemu64 (the default CPU model under TCG) lacks the instruction
# set the runtime expects. This is also part of the stand regression
# found on 14.09: KVM=1 used to be implicit (the 11.09 session assumed it),
# there was no explicit default. KVM=0 forces a return to TCG
# (for comparing/debugging this very effect).
if [ "${KVM:-1}" != "0" ]; then
    if [ -r /dev/kvm ] && [ -w /dev/kvm ]; then
        KVM_FLAG=(-enable-kvm)
    else
        echo "warning: /dev/kvm is not available — falling back to TCG, expect an invalid-opcode panic in the linker (KVM=0 hides this warning)" >&2
        KVM_FLAG=()
    fi
else
    KVM_FLAG=()
fi

# Partitions can be substituted individually — this way any combination
# "ours / Microsoft" is tested, exactly as in the on-device runs.
SYSEXT="${SYSEXT_IMG:-$T/ms-system_ext.img}"
PRODUCT="${PRODUCT_IMG:-$T/ms-product.img}"
VENDOR="${VENDOR_IMG:-$T/vendor.img}"

# userdata/metadata default to empty stubs, and this is where the stand
# hits its ceiling: `vdc --wait cryptfs enablefilecrypto` fails on a clean partition, and
# then the system goes to recovery. Same for our image and for Microsoft's
# original, so the comparison has to be "how far it got".
#
# The ceiling is lifted by supplying a REAL /data already set up by a live WSA:
# USERDATA_IMG=... METADATA_IMG=... (raw, from vhdx — `qemu-img convert -O raw`).
# The pair must be taken WHOLE and from the same install: the encryption key lives in
# metadata, and with someone else's metadata the partition will not decrypt. The subsystem must
# be shut down meanwhile — otherwise the files are busy and the copy ends up with a half-written FS.
USERDATA="${USERDATA_IMG:-$T/qemu-userdata.img}"
METADATA="${METADATA_IMG:-$T/qemu-metadata.img}"

# IMPORTANT: by default NOT the stock Tools/initrd.img. In our install it is patched
# with the chain lspinit → magiskinit → wsainit (WSABuilds/MagiskOnWSALocal)
# — lspinit expects a channel to the Windows host (hvsocket) and
# under bare QEMU crashes in the second second ("Attempted to kill init"), NOT
# reaching the system.img under test at all. You need the one built by
# bin/make-diag-initrd.sh (default $T/diag-initrd.img), which bypasses
# this wrapper and boots /system/bin/init directly. The real Tools/initrd.img
# can be passed explicitly (INITRD=$W/Tools/initrd.img) — but then the crash in
# lspinit is expected, not a stand bug.
INITRD_DEFAULT="$T/diag-initrd.img"
INITRD="${INITRD:-$INITRD_DEFAULT}"

for f in "$SYSTEM" "$VENDOR" "$SYSEXT" "$PRODUCT" "$INITRD"; do
    [ -r "$f" ] || { echo "image not found: $f" >&2; exit 1; }
done
if [ "$INITRD" = "$INITRD_DEFAULT" ] && [ ! -s "$INITRD" ]; then
    echo "diagnostic initrd is empty/missing — build it: bin/make-diag-initrd.sh" >&2
    exit 1
fi

# The command line is the same one WsaService.exe sets (found in its strings),
# plus console output to a serial port, which WSA does not have.
CMDLINE="androidboot.hardware=windows_x86_64 androidboot.veritymode=enforcing"
CMDLINE="$CMDLINE androidboot.verifiedbootstate=green loglevel=7"
CMDLINE="$CMDLINE transparent_hugepage=never swiotlb=noforce"
CMDLINE="$CMDLINE androidboot.fstab_suffix=1.windows androidboot.hw_timeout_multiplier=50"
CMDLINE="$CMDLINE androidboot.adb_auto_auth=1 androidboot.lcd_density=160"
CMDLINE="$CMDLINE console=ttyS0 earlyprintk=serial printk.devkmsg=on"
# Hypothesis test (15.09.2026, continuation 27): androidboot.partition_map= is
# a stock, open AOSP mechanism (system/core/init/devices.cpp,
# GetPartitionNameForDevice) that maps a kernel block device name
# (sdX) to the label BlockDevInitializer::InitDevices() looks for from fstab.
# The sdX letters in this stand do NOT follow the LUN number (order of the asynchronous virtio-scsi
# probe), see dmesg. PARTITION_MAP=0 disables it if the letters differ.
if [ "${PARTITION_MAP:-1}" != "0" ]; then
    # sdc,SCSI=0:0:0:4 (userdata) added 15.09.2026: not needed for the first stage
    # (latemount), but needed LATER — vold/vdc (`vdc checkpoint restoreCheckpoint`)
    # opens it directly by this same name; without it, it hangs and eventually
    # reboots itself.
    CMDLINE="$CMDLINE androidboot.partition_map=sdf,SCSI=0:0:0:1;sde,SCSI=0:0:0:2;sdd,SCSI=0:0:0:3;sdc,SCSI=0:0:0:4;sdb,SCSI=0:0:0:5"
    # Without this DeviceHandler::GetBlockDeviceSymlinks() does not create the
    # /dev/block/by-name/<partition_map value> symlink at all — that branch sits
    # behind the info.is_boot_device condition, which is checked against a DIFFERENT set
    # (boot_devices_ from androidboot.boot_devices=), unrelated to
    # partition_map. The value is the PCI prefix of the virtio-scsi controller
    # (see devices.cpp FindPciDevicePrefix): domain:bus/slot.func of the first two
    # segments after /devices/, found via readlink -f /sys/class/block/sda
    # in make-diag-initrd.sh. Specific to THIS stand (virtio-scsi-pci at
    # 00:03.0) — on real hardware there is no PCI here at all (Hyper-V VMBus).
    CMDLINE="$CMDLINE androidboot.boot_devices=pci0000:00/0000:00:03.0"
fi

# Bus addresses are the same as in vendor/etc/fstab.1.windows: SCSI=0:0:0:N.
# system is LUN zero — it has no line in fstab, it is attached earlier.
disk() { echo "-drive file=$1,format=raw,if=none,id=d$2,readonly=$3 -device scsi-hd,bus=scsi0.0,channel=0,scsi-id=0,lun=$2,drive=d$2"; }

echo "== starting, output to $OUT (waiting ${SECONDS_WAIT}s) =="
rm -f "$OUT"
timeout "$SECONDS_WAIT" "$QEMU" \
    -M q35 -m 4096 -smp "${SMP:-1}" \
    -no-reboot \
    "${KVM_FLAG[@]}" \
    -kernel "$W/Tools/kernel" \
    -initrd "$INITRD" \
    -append "$CMDLINE" \
    -device virtio-scsi-pci,id=scsi0 \
    $(disk "$SYSTEM" 0 on) \
    $(disk "$VENDOR" 1 on) \
    $(disk "$SYSEXT" 2 on) \
    $(disk "$PRODUCT" 3 on) \
    $(disk "$USERDATA" 4 off) \
    $(disk "$METADATA" 5 off) \
    -nographic -serial "file:$OUT" -display none \
    >/dev/null 2>&1 || true

echo "== lines received: $(wc -l < "$OUT" 2>/dev/null || echo 0)"
