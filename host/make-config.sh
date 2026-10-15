#!/usr/bin/env bash
# Writes the HCS document for wsa-reborn-host.exe (wsa-reborn-host.cpp) — the VM that WsaService builds through
# wslcore.dll!CreateVm, reduced to what is needed to boot Android and reach adbd: kernel and initrd of the install,
# six SCSI disks in WSA's LUN order (0 system, 1 vendor, 2 system_ext, 3 product, 4 userdata, 5 metadata — the
# fstab addresses SCSI=0:0:0:N), the serial console on a named pipe and an hvsocket policy for the current user.
# virtiofs shares are added at run time by wsa-reborn-host.exe from shares.txt; the high MMIO gap below leaves room for
# shared_mem's DAX window (WslCoreVm: 16 GB default + the shared memory, base under the 36-bit limit). No network, no
# GPU yet.
#
#   host/make-config.sh <install-dir> <data-dir> [mem-MB=4096] [cpus=4] [host-dir] > config.json
#
# The same document without WSL: wsa-reborn-host.exe --config <install-dir> <data-dir> [mem-MB] [cpus] (MakeConfig in
# wsa-reborn-host.cpp, checked equal 15.10.2026) — keep the two in step.
#
# install-dir: Windows path of a WSA install (Tools\kernel, Tools\initrd.img, system/vendor/system_ext/product.vhdx);
# data-dir: Windows path with userdata.2.vhdx and metadata.2.vhdx (the package's LocalCache).
# host-dir: Windows path of the folder with the exe; initrd-own.img there (guest-init/build.sh) replaces WSA's initrd
# and wsainit (the command line then has no initrd=/WSL_ROOT_INIT), vendor-headless.vhdx there (bin/headless-vendor.sh)
# replaces the install's vendor.vhdx — as "--config" does for its current folder.
# The kernel command line is WSA's own, read from /proc/cmdline of the running guest on 12.10.2026, with the console
# plus earlycon on COM1. 12.10.2026.
set -euo pipefail
inst=${1:?install dir (Windows path)} data=${2:?data dir (Windows path)} mem=${3:-4096} cpus=${4:-4} dir=${5:-}
own= headless=
[ -n "$dir" ] && [ -f "$(wslpath "$dir")/initrd-own.img" ] && own=$dir\\initrd-own.img
[ -n "$dir" ] && [ -f "$(wslpath "$dir")/vendor-headless.vhdx" ] && headless=$dir\\vendor-headless.vhdx
sid=$(powershell.exe -NoProfile -Command '[Security.Principal.WindowsIdentity]::GetCurrent().User.Value' | tr -d '\r')
python3 - "$inst" "$data" "$mem" "$cpus" "$sid" "$own" "$headless" <<'PY'
import json, sys
inst, data, mem, cpus, sid, own, headless = sys.argv[1:8]
cmdline = ("" if own else "initrd=\\initrd.img WSL_ROOT_INIT=1 ") + ("panic=-1 nr_cpus=%s bonding.max_bonds=0 dummy.numdummies=0 fb_tunnels=none "
           "console=hvc0 debug earlycon=uart8250,io,0x3f8,115200 pty.legacy_count=0 androidboot.hardware=windows_x86_64 androidboot.veritymode=enforcing "
           "androidboot.verifiedbootstate=green loglevel=7 transparent_hugepage=never swiotlb=noforce androidboot.lcd_density=200 "
           "androidboot.fstab_suffix=2.windows scsi_mod.scan=async") % cpus
# system.vhdx is attached writable, as WSA does: the guest init mounts it at /distro without MS_RDONLY (a read-only
# disk gives EROFS there, and with the read-only flag init takes a different path that fails) 12.10.2026.
disks = [(inst + "\\system.vhdx", False), (headless or inst + "\\vendor.vhdx", True), (inst + "\\system_ext.vhdx", True),
         (inst + "\\product.vhdx", True), (data + "\\userdata.2.vhdx", False), (data + "\\metadata.2.vhdx", False)]
sddl = "D:P(A;;FA;;;SY)(A;;FA;;;BA)(A;;FA;;;%s)" % sid
doc = {
    "Owner": "wsa-reborn-host",
    "SchemaVersion": {"Major": 2, "Minor": 3},
    "ShouldTerminateOnLastHandleClosed": True,
    "VirtualMachine": {
        "StopOnReset": True,
        "Chipset": {"UseUtc": True, "LinuxKernelDirect": {
            "KernelFilePath": inst + "\\Tools\\kernel", "InitRdPath": own or inst + "\\Tools\\initrd.img", "KernelCmdLine": cmdline}},
        "ComputeTopology": {"Memory": {"SizeInMB": int(mem), "AllowOvercommit": True, "EnableDeferredCommit": True,
                                       "HighMmioGapInMB": 16384 + 1024, "HighMmioBaseInMB": 65536 - (16384 + 1024)},
                            "Processor": {"Count": int(cpus)}},
        "Devices": {
            "Scsi": {"0": {"Attachments": {str(i): {"Type": "VirtualDisk", "Path": p, "ReadOnly": ro,
    # As WslCoreVm does: the package's userdata.2/metadata.2.vhdx are sparse files, without the flag HCS refuses
    # them at power-on (0xC03A001A, 12.10.2026).
    "SupportCompressedVolumes": True, "AlwaysAllowSparseFiles": True, "SupportEncryptedFiles": True} for i, (p, ro) in enumerate(disks)}}},
            "ComPorts": {"0": {"NamedPipe": "\\\\.\\pipe\\wsa-reborn-host-com1"}},
            # hvc0 is HCS's own virtio serial device (WslCoreVm does the same), not wsldevicehost.
            "VirtioSerial": {"Ports": {"0": {"Name": "hvc0", "NamedPipe": "\\\\.\\pipe\\wsa-reborn-host-hvc0", "ConsoleSupport": True}}},
            "HvSocket": {"HvSocketConfig": {"DefaultBindSecurityDescriptor": sddl, "DefaultConnectSecurityDescriptor": sddl}},
        },
    },
}
print(json.dumps(doc, indent=1))
PY
