# Own host (experimental): WSA without WsaService and WsaClient

Everything else in WSA-Reborn keeps Microsoft's host: `WsaService.exe` starts the VM, `WsaClient.exe` shows Android windows on the Windows desktop. Microsoft no longer develops either of them, and both have limits that cannot be fixed from the guest (one subsystem per machine, the graphics renderer of 2024). The own host replaces both with our own program, `host/wsa-reborn-host.cpp`, built from source:

- the VM is created through HCS (the Host Compute Service of Windows) directly, the way WSL does it: WSA's own kernel, the six disks in WSA's order, virtio-fs shares, the network adapter, consoles;
- the host ends of the RCM channels are its own: a window per Android task, mouse/keyboard/wheel input, notifications and `Toast`s as Windows toasts, clipboard, files, app shortcuts, launching apps, the app list;
- it runs as WSA does: an elevated half (a Windows service under LocalSystem) runs the VM, the user's half (`--client`, no elevation) serves the desktop, and the VM's life follows the same resources mode as WSA's settings ("as needed", "partially running", "continuous").

**Status: experimental.** It boots Android 16 to the home screen and runs apps in their own windows, alongside a running stock WSA. Rendering is in software: the guest has no GPU (SwiftShader + ANGLE inside Android, frames sent to the host over vsock), so it is fine for ordinary apps and automated tests and slow for games and video. Tested with an Android 16 system image (`aosp16`) on Windows 11 x64.

Hardware graphics is there too, as an experiment: the host's own virtio-gpu runs the guest's OpenGL ES and Vulkan on your GPU through WSA's own renderer (see [Hardware graphics](#hardware-graphics-experimental)). Not done yet: running the GPU from the service (it works from the agent), registering `wsa://` and Start-menu entries (shortcuts are made in the host folder instead). Android's sound plays on Windows' default output device; the microphone is used only when `settings.txt` says so.

## How it fits together

| piece | where | what it does |
| --- | --- | --- |
| `wsa-reborn-host.exe` | `host/wsa-reborn-host.cpp`, built by `host/build.sh` | the host: service, client and the command-line tools below |
| `initrd-own.img` | `host/guest-init/` | a tiny guest init that mounts the system disk and starts Android's `/init` — replaces WSA's `wsainit`, so the host needs no init protocol |
| `vendor-headless.vhdx` | `bin/headless-vendor.sh` | your install's `vendor.vhdx` with the four graphics parts swapped: SwiftShader as the Vulkan driver, `minigbm` as gralloc, our HWC2 (`device/microsoft/wsa_x86_64/headless-hwc`) as the composer, ANGLE as EGL |
| guest graphics parts | `patches/aosp16-headless/` + the device tree | built in an Android 16 tree |
| `config.json` | `wsa-reborn-host.exe --config` or `host/make-config.sh` | the HCS document of the VM |
| `shares.txt` | `host/shares.example.txt` | virtio-fs shares, the network, the client mode |
| `settings.txt` | `host/settings.example.txt` | resources mode, idle time, memory, clipboard |

The kernel, `system.vhdx`, `system_ext.vhdx`, `product.vhdx` and `wsldevicehost.dll` (the virtio-fs device host) are taken from **your own** WSA install at run time; nothing of Microsoft's is copied into this repository.

## 1. Build the guest graphics parts (Android 16 tree)

In the Android 16 tree prepared as in [build.md](build.md) (pinned manifest `manifests/aosp16.xml`, `patches/aosp16`, the device tree), apply the headless series on top and build four modules:

```bash
"$REBORN/bin/apply-patches.sh" aosp16-headless "$TREE"
cd "$TREE" && . build/envsetup.sh && lunch aosp_wsa_x86_64-bp2a-userdebug
m vulkan.pastel hwcomposer.wsa_headless \
  android.hardware.graphics.allocator@4.0-service.minigbm_dmaheap android.hardware.graphics.mapper@4.0-impl.minigbm_dmaheap
```

Each part is built against the frozen `vendor` of WSA (Android 13, VNDK 33), so all of them link `libc++` statically and need nothing new in `vendor`. Collect them into a parts folder in the layout `bin/headless-vendor.sh` expects:

```bash
V=$ANDROID_PRODUCT_OUT/vendor; P=$WSA_STORAGE/headless-parts
mkdir -p $P/static/lib64 $P/static/lib $P/gralloc/bin/hw $P/gralloc/lib64/hw $P/gralloc/lib/hw
cp $V/lib64/hw/vulkan.pastel.so $P/static/lib64/;  cp $V/lib/hw/vulkan.pastel.so $P/static/lib/
cp $V/bin/hw/android.hardware.graphics.allocator@4.0-service.minigbm_dmaheap $P/gralloc/bin/hw/
cp $V/lib64/hw/android.hardware.graphics.mapper@4.0-impl.minigbm_dmaheap.so $P/gralloc/lib64/hw/
cp $V/lib/hw/android.hardware.graphics.mapper@4.0-impl.minigbm_dmaheap.so $P/gralloc/lib/hw/
cp $V/lib64/hw/hwcomposer.wsa_headless.so $P/gralloc/lib64/hw/
```

## 2. Prepare the host folder

Pick a folder for the host — below `C:\WSA-Host` (`/mnt/c/WSA-Host` in WSL); the WSA install is `C:\WSA`. In WSL:

```bash
H=/mnt/c/WSA-Host; mkdir -p $H/share/{shared_tmp,shared_anr,shared_tombstone} $H/data
bin/headless-vendor.sh /mnt/c/WSA/vendor.vhdx $WSA_STORAGE/headless-parts $H/vendor-headless.vhdx
host/guest-init/build.sh $H/initrd-own.img          # needs gcc with static glibc (Ubuntu: gcc, libc6-dev)
out=$(host/build.sh | tail -1)                      # Visual Studio 2022 with the C++ desktop workload; last line: the build folder
cp "$(wslpath "$out")/wsa-reborn-host.exe" $H/
```

**`/data`.** The VM uses `userdata.2.vhdx` and `metadata.2.vhdx`, the files WSA creates on its first start in `%LOCALAPPDATA%\Packages\MicrosoftCorporationII.WindowsSubsystemForAndroid_8wekyb3d8bbwe\LocalCache`. Two VMs must never use the same pair at once: either point the host at that folder and keep WSA off while the own host runs, or (recommended) start WSA once with the same system image, turn it off, and copy the two files into `C:\WSA-Host\data` — then stock WSA and the own host run side by side.

Then, in the host folder (PowerShell, no elevation):

```powershell
cd C:\WSA-Host
.\wsa-reborn-host.exe --config C:\WSA C:\WSA-Host\data 4096 4    # config.json: memory in MB, processors
copy <WSA-Reborn>\host\shares.example.txt shares.txt              # then edit it, see below
copy <WSA-Reborn>\host\settings.example.txt settings.txt
```

`--config` finds `initrd-own.img` and `vendor-headless.vhdx` in the current folder and uses them instead of the install's initrd and vendor. It writes the current user's SID into the hvsocket policy of the VM, so run it as the user who will use the subsystem. `host/make-config.sh <install> <data> [MB] [cpus] <host folder>` writes the same document from WSL.

**`shares.txt`** — paths are not expanded, write them in full:

```text
dll C:\WSA\wsldevicehost.dll
shared_mem sections 256
shared_tmp files C:\WSA-Host\share\shared_tmp
shared_anr files C:\WSA-Host\share\shared_anr
shared_tombstone files C:\WSA-Host\share\shared_tombstone
nic c08cb7b8-9b3c-408e-8e30-5e16a3aeb444 172.31.250.77/20 172.31.240.1
client separate
```

`nic` puts the VM on a Hyper-V network: `c08cb7b8-…` is "Default Switch" on every machine; `wsa-reborn-host.exe --networks` (as administrator) lists the others. The address is moved into the network's current subnet at every start (the subnet of "Default Switch" changes across reboots), so any host part works. `client separate` is required for the service.

**`settings.txt`:**

```text
lifecycle asneeded      # continuous | asneeded | partial — as WSA's "Memory and performance"
idle 10                 # minutes without an app window before "asneeded" stops the VM and "partial" restarts it
memory default          # GB, or default: 6 GB from 12 GB of RAM, 4 from 8, 3 above 4, else 2
clipboard on            # shared clipboard with Windows; off when the line is missing
microphone on           # Android's recording from Windows' default microphone; off (silence) when the line is missing
```

The client re-reads the file every 5 seconds; `lifecycle`, `idle`, `clipboard` and `microphone` apply at once, `memory` at the next start of the VM.

## 3. Install and run

As administrator, once (and again after editing `config.json` or `shares.txt`):

```powershell
C:\WSA-Host\wsa-reborn-host.exe --install
```

This copies the exe, `config.json` and `shares.txt` to `%ProgramFiles%\WsaReborn` and registers the service `WsaRebornHost` for the folder. The service reads its configuration only from Program Files (writable by administrators only) and writes its files — `out.txt`, the guest consoles, `runtime.txt` — to `%ProgramData%\WsaReborn`, closed to users' writes; from the user's folder it only reads `settings.txt` and what the client hands over. The installing user may start and stop it without elevation.

Then, as the normal user:

| command | what it does |
| --- | --- |
| `wsa-reborn-host.exe --client` | starts the desktop half; with the VM off it starts the service, as `WsaClient` asks `WsaService` |
| `wsa-reborn-host.exe --launch <package>` | starts an app; with the VM off starts the client (and the VM) first and waits for it |
| `wsa-reborn-host.exe --lifecycle continuous\|asneeded\|partial` | writes the mode into `settings.txt`; `continuous` and `partial` also start the client at sign-in (a `Run` value of the user), as WSA's startup tasks do |
| `wsa-reborn-host.exe --settings` | the settings window: resources mode, idle time, memory, clipboard, microphone, the subsystem's state and "Turn off" — it writes `settings.txt` and the start at sign-in, like `--lifecycle` |
| `wsa-reborn-host.exe --stop` | stops the service and the VM |
| `wsa-reborn-host.exe --uninstall` | (administrator) stops and removes the service and the Program Files copy |

A launchable app gets a shortcut with its icon in `C:\WSA-Host\apps\` (it runs `--launch <package>`), and the client puts `Subsystem settings` there too (it runs `--settings`); pin the ones you use to Start or the taskbar. `adb connect 127.0.0.1:58600` works from Windows; from WSL run `host/adb-wsl-tunnel.sh` and use `adb connect 127.0.0.1:58601` (WSL in NAT mode cannot reach Windows' loopback).

## Hardware graphics (experimental)

The host can give the VM its own virtio-gpu (a PCI device emulated through the documented HDV API of Windows, `hypervdevicevirtualization.h`) and feed its 3D commands into `gfxstream_backend.dll` from **your** WSA folder — the renderer `WsaClient` uses, loaded the way `wsldevicehost.dll` is. The guest then runs Microsoft's own graphics stack from `vendor.vhdx` (gralloc, EGL and Vulkan of gfxstream) on the host GPU; only the composer is ours, because Microsoft's sends its frames to a host renderer that only `WsaClient` has.

1. Build our HWC2 with the NDK (no Android tree needed; a dozen AOSP headers are fetched into `~/.cache/wsa-reborn`):
   ```bash
   device/microsoft/wsa_x86_64/headless-hwc/ndk-build.sh            # -> device/.../headless-hwc/out/hwcomposer.wsa_headless.so
   ```
2. Make `vendor-gpu.vhdx` — your `vendor.vhdx` with that composer in place of `hwcomposer.windows.so`:
   ```bash
   bin/hwc-vendor.sh /mnt/c/WSA/vendor.vhdx device/microsoft/wsa_x86_64/headless-hwc/out/hwcomposer.wsa_headless.so \
       /mnt/c/Users/you/AppData/Local/Temp/wsa-reborn-host/vendor-gpu.vhdx
   ```
3. Point the vendor disk of `config.json` at `vendor-gpu.vhdx` and add to `shares.txt`:
   ```
   hdv gpu External gfxstream=C:\WSA
   ```
   The renderer flags default to `31` — EGL, GLES and Vulkan, what `WsaClient` uses with WSA's Vulkan driver setting at "system": the guest's Vulkan sees your GPU through its own driver, and ANGLE can run apps' OpenGL ES over it. `flags=11` leaves Vulkan out (stock WSA's default).

Frames and task windows then arrive exactly as with the software vendor; an app reports `GL_VENDOR=Microsoft, GL_RENDERER=Subsystem OpenGL ES Translator` (OpenGL ES 3.0), `testapps/vkprobe` lists your GPU with Vulkan 1.3. The composer reads every frame back from the GPU (`TRANSFER_FROM_HOST` of virtio-gpu), which costs a copy per frame — fine for apps and tests, not tuned for games. Fenced commands are answered when the renderer reports them done, as crosvm does (`nofences` on the `hdv` line answers at once, as `WsaClient` does). `testapps/ahbinfo` prints what a gralloc buffer of this stack looks like. So far tested from the agent (development mode), not from the service.

## Command port

The client listens on `127.0.0.1:58610` (Windows only) for one line per connection — handy for scripts and tests:

| line | effect |
| --- | --- |
| `tasks` | the task windows: id, size, position |
| `tclose <task>`, `tmin <task>`, `trestore <task>`, `tmove <task> <x> <y>` | close, minimize, restore, move a task's window |
| `tclick <task> <x> <y>`, `tkey <task> <vk>` | a click or a key into a task's window |
| `apps`, `launch <package>`, `uninstall <package>` | the app list, start, uninstall |
| `clip on\|off` | the clipboard, until the `clipboard` line of `settings.txt` changes |
| `sound [<0-100>]` | the volume of Android's sound (this output only, until the client restarts); prints frames, bytes played and dropped, and the microphone's reads and bytes |
| `nclick`, `naction <button> [reply]`, `ndismiss` | act on the last notification |
| `snap` | write `frame.bmp` and `task-<id>.bmp` (the display and each task window as the host shows them) now |

## Development mode: the agent

Instead of the service, the elevated half can run from an agent: a PowerShell window started once as administrator, which runs the host from folders `%TEMP%\wsa-reborn-host*` on request from WSL — useful while changing the host, as no `--install` is needed after each build.

```bash
host/agent-run.sh install         # prints the command to start the agent in an elevated PowerShell
host/agent-run.sh wsa-reborn-host-dev 900   # run %TEMP%\wsa-reborn-host-dev\wsa-reborn-host.exe for up to 900 s
host/agent-run.sh stop
```

Then start `wsa-reborn-host.exe --client` in the same folder. While the agent window is open, whatever exe lies in such a folder runs with administrator rights — close it when done. The agent and the service must not run at the same time (the VM has one fixed id).

## Troubleshooting

- The elevated half writes `out.txt` (HCS calls, disks, shares, network) and `console.log` (the guest kernel and Android's init) — to `%ProgramData%\WsaReborn` under the service, to its own folder under the agent. The client writes `logcat.log` (the guest's logcat, previous run in `logcat.prev.log`) and `rcm.log` (the RCM channels) into the host folder and prints windows, apps and notifications to its console.
- HCS `0x8007000d` "invalid JSON document": `config.json` is broken — regenerate it with `--config`.
- The client says the VM is not running and nothing starts: the service is not installed (`--install`) or the agent is not running.
- Only one client runs per user session (a second one says "client: already running" and exits); `--stop` ends the VM, and the client exits with it.
