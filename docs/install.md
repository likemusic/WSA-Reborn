# Installing a system image into WSA

This page puts the images built in [build.md](build.md) into an existing Windows Subsystem for Android™ installation and boots them. The WSA host, kernel, `vendor` image and `initrd` stay the ones you installed; only `system`, `system_ext` and `product` are swapped.

Terms used below:

- **slot** — a set of three files next to the live ones in the install folder: `system.vhdx.<slot>`, `system_ext.vhdx.<slot>`, `product.vhdx.<slot>`. Switching to a slot copies them over `system.vhdx`, `system_ext.vhdx`, `product.vhdx`;
- **pool** — a saved `/data` (`userdata.2.vhdx` + `metadata.2.vhdx`), kept under `$WSA_POOLS/<pool>/`. `/data` does not live in the install folder but in the WSA package's `LocalCache`, and it is switched separately.

All commands run in WSL from the root of this repository.

## 1. Prerequisites

### Windows

- Windows 11 x64 with Hyper-V/Virtual Machine Platform, as for any WSA.
- WSA `2407.40000.4.0` **registered from a folder** (developer-mode registration), for example a [MustardChef/WSABuilds](https://github.com/MustardChef/WSABuilds) release extracted to a folder and installed with its installer. All Windows 11 x64 variants of WSABuilds LTS #8 were tested: no Magisk, Magisk, Magisk + GApps, with and without Amazon. A Microsoft Store install does not work: its folder is read-only.
- In WSA Settings → Advanced settings, **Developer mode on** (for adb). Optional: "Share user folders" (makes the Windows user folder appear at `/sdcard/Windows`).
- Free space. Each slot is ~1–2 GB in the install folder, each `/data` pool several GB on the storage drive, and the host writes logs under `%LOCALAPPDATA%` (see [C: drive space](#c-drive-runs-full)).

Do not uninstall WSA through Windows Settings or plain `Remove-AppxPackage`: unregistering a package erases its `LocalCache`, i.e. your `/data`.

### WSL

- WSL2 with a Linux distribution (Ubuntu tested) and Windows interop enabled (the scripts call `powershell.exe`);
- packages: `e2fsprogs`, `qemu-utils`, `python3`; `adb` from Android SDK platform-tools (in `PATH`, or at `$ANDROID_HOME/platform-tools/adb`).

## 2. Configure the scripts

All scripts source `bin/wsa-env.sh`. It finds the WSA install folder and the package data folder by asking Windows (`Get-AppxPackage`, `%LOCALAPPDATA%`) and caches the answer in `~/.config/wsa-reborn/env.detected` (delete that file after moving the install). Everything can be overridden in `~/.config/wsa-reborn/env` — plain shell assignments:

```bash
mkdir -p ~/.config/wsa-reborn
cat > ~/.config/wsa-reborn/env <<'EOF'
# big storage for /data pools, work images and archived logs — preferably NOT C:, WSL itself lives there
WSA_STORAGE=/mnt/d/wsa-reborn
# working directory for the unpacked stock partitions and signing keys
WSA_WORK=$HOME/wsa-work
EOF
```

| variable | meaning | default |
| --- | --- | --- |
| `WSA_INSTALL_DIR` | install folder (`AppxManifest.xml`, live `*.vhdx`, slots) | detected |
| `WSA_PACKAGE_DIR` | `%LOCALAPPDATA%\Packages\MicrosoftCorporationII.WindowsSubsystemForAndroid_8wekyb3d8bbwe` | detected |
| `WSA_LOCALCACHE` | live `/data`: `userdata.2.vhdx`, `metadata.2.vhdx` | `$WSA_PACKAGE_DIR/LocalCache` |
| `WSA_DIAG` | host logs: `logcat/`, `etw/`, tombstones | `$WSA_PACKAGE_DIR/LocalState/diagnostics` |
| `WSA_WORK` | working directory | `~/wsa-work` |
| `WSA_REF` | unpacked stock partitions (BYOB reference) | `$WSA_WORK/backup-full` |
| `WSA_KEYS` | `platform.{pk8,x509.pem}` of your build, `talkback.jks` | `$WSA_WORK/keys` |
| `WSA_STORAGE` | big storage | `$WSA_WORK/storage` |
| `WSA_POOLS` | `/data` pools | `$WSA_STORAGE/wsa-data-pools` |
| `WSA_VARIANTS_DIR` | other WSA installs (variants), registration agent | `WSA-variants` next to the install folder |
| `WSA_ADB` | adb serial of the subsystem | `<WSL default gateway>:58526` |
| `ADB` | adb binary | `adb` from `PATH`, else `$ANDROID_HOME/platform-tools/adb` |

If the storage drive is another Windows drive that WSL did not mount, mount it first, e.g. `sudo mount -t drvfs D: /mnt/d`.

Check what was detected:

```bash
bash -c '. bin/wsa-env.sh; env | grep -E "^(WSA_|ADB=)" | sort'
```

## 3. Keep a way back to stock

Before replacing anything, save Microsoft's partitions as a slot, the `initrd` your variant ships, and your current `/data` as a pool. None of this is automated beyond the commands shown.

```bash
. bin/wsa-env.sh
powershell.exe -NoProfile -Command "Stop-Service WsaService -Force"     # make sure WSA is not running
for p in system system_ext product; do cp "$WSA_INSTALL_DIR/$p.vhdx" "$WSA_INSTALL_DIR/$p.vhdx.ms13-stock"; done
cp "$WSA_INSTALL_DIR/Tools/initrd.img" "$WSA_INSTALL_DIR/Tools/initrd.img.stock"
bin/switch-data.sh save ms13        # current /data -> $WSA_POOLS/ms13
```

If you have not done it yet, also unpack the stock partitions into `$WSA_REF` now ([build.md, step 4](build.md#4-extract-the-arm-translator-byob)) — the build's BYOB steps need them.

## 4. Add a slot

Copy the three `.vhdx` files of a built image into the install folder, named `<part>.vhdx.<slot>`. Any slot name works; below, `aosp15-vanilla`.

```bash
S="$WSA_STORAGE/slot-aosp15"        # where build.md step 8 left the .vhdx files
for p in system system_ext product; do cp "$S/$p.vhdx" "$WSA_INSTALL_DIR/$p.vhdx.aosp15-vanilla"; done
bin/switch-build.sh list
```

Optionally describe slots and pin an `initrd` to them in `~/.config/wsa-reborn/slots.conf`, one slot per line — `<slot> <initrd> <description…>`. `<initrd>` is a file name in `$WSA_INSTALL_DIR/Tools` that becomes `Tools/initrd.img` when switching to this slot, or `-` to keep whatever is live:

```
ms13-stock       initrd.img.stock  Microsoft 2407.40000.4.0, Android 13
aosp15-vanilla   -                 AOSP 15, android15-security-release
lineage21        -                 LineageOS 21
```

The `initrd` decides Magisk and GApps (next section). A slot without a line in `slots.conf` keeps the live `initrd`.

## 5. GApps and Magisk

Our images contain no Google apps and no root. Both come, as on stock WSA, from the `initrd` of your WSABuilds variant (`Tools/initrd.img`): MagiskOnWSA's `lspinit` → `magiskinit` → `wsainit` chain, with GApps shipped as a Magisk module. This works unchanged with our Android 14–17 images (on Android 17 thanks to the first-stage `init` patch), so pick the WSABuilds variant you want:

| variant (`Tools/initrd.img`) | result in our slots |
| --- | --- |
| NoGApps (plain `wsainit`) | no root, no Google apps |
| Magisk, NoGApps | Magisk |
| Magisk + GApps | Magisk, Google Play services, Play Store, Google sign-in |

Amazon Appstore is never present in our slots, whatever the variant (the store was shut down in 2025).

To use several `initrd`s with one install, keep them as files in `Tools/` and name them per slot in `slots.conf`.

## 6. One `/data` pool per Android version

Never boot a different major Android version on the same `/data`: every downgrade or upgrade hits something new (app-op codes, provider databases refusing to downgrade, privileged-permission grants for packages the other image lacks). Keep a pool per version.

```bash
bin/switch-data.sh status                    # which pool the live files look like (by size — a hint only)
bin/switch-data.sh save <pool>               # save live /data into <pool>
bin/switch-data.sh restore <pool>            # make <pool> live WITHOUT saving the current one
bin/switch-data.sh switch <live> <pool>      # save live as <live>, then make <pool> live (the safe way)
bin/switch-data.sh fresh [<install dir>]     # make a clean /data live from the install's userdata.vhdx/metadata.vhdx templates, WITHOUT saving
bin/switch-data.sh prune-host-apps           # remove Start menu / "Installed apps" entries of Android apps not in the live pool (WSA running, adb connected)
```

Every command stops WSA first and never starts it. Each switch copies a several-GB file. Android apps also exist on the Windows side (Start menu shortcuts, "Installed apps" entries) and Windows keeps them per user, not per `/data`, so `save`/`switch` store them with the pool (`<pool>/windows-apps`) and restore them.

There is no command that creates an empty named pool. A new pool starts as `fresh` and gets its name the first time it is saved:

```bash
bin/switch-data.sh fresh                     # after step 3 the stock /data is safe in pool ms13
# ... boot, use ...
bin/switch-data.sh save aosp15               # now pool aosp15 exists
```

## 7. Switch and boot

```bash
bin/switch-build.sh aosp15-vanilla           # stops WSA, copies the slot over the live files, initrd per slots.conf
bin/switch-build.sh status                   # which slot each live file matches (and the initrd md5)
powershell.exe -NoProfile -Command "Start-Process 'wsa://com.android.settings'"
```

`switch-build.sh` does not start WSA. Any `wsa://<package>` link starts it and opens that app in a window; Settings is a convenient first app. The first boot on a clean `/data` takes longer (formatting, dexopt) — from about 20 s to 2 minutes.

To boot a slot in a different WSA install (e.g. another WSABuilds variant in its own folder) while keeping the slots in the main one: `WSA_LIVE_DIR=<folder> bin/switch-build.sh <slot>`; that install's `initrd` is never replaced.

### adb

```bash
adb connect "$WSA_ADB"          # the WSL default gateway, port 58526 — not 127.0.0.1
adb -s "$WSA_ADB" shell getprop ro.build.version.release
```

WSA listens on all interfaces of its VM, so from WSL it is reached through the gateway address; no adb server in Windows is needed. Our images are `userdebug` builds: adb is accepted without a prompt and `adb root` works.

## 8. Verify

```bash
bin/wsa-boot-probe.sh aosp15-vanilla
```

It starts WSA itself (via `wsa://com.android.settings`), waits up to `PROBE_TIMEOUT` seconds (default 300) for `sys.boot_completed`, and prints one line:

```
aosp15-vanilla | boot=26s ver=15 spl=2024-09-05 net=0% gms=no play=no amazon=0 magisk=0 bridge=yes sysapp=yes crash=4 focus=com.android.settings/.Settings
```

- `boot` — seconds to `sys.boot_completed`; `net` — packet loss of `ping 8.8.8.8`;
- `gms`/`play`/`magisk` — what the `initrd` brought;
- `bridge` — our guest bridge (`org.wsalineage.shellbridge`) is running; `sysapp` — Microsoft's `SystemApp` is running (it sets up networking); both should be `yes`;
- `crash` — `FATAL EXCEPTION`/`Fatal signal` lines in the crash buffer (`PROBE_DIR=<dir>` saves the buffer); a few on the first boot are expected, see below;
- `focus` — the focused app after opening Settings.

The exit code is 0 if the boot completed. Then check by hand what matters to you: a window opens and takes mouse and keyboard, clipboard in both directions, a notification reaches the Windows action center.

## 9. Troubleshooting

### Where to look

- **Guest `logcat`, on the host.** Every subsystem start writes `$WSA_DIAG/logcat/<date>.log` — the full `logcat -b all` of that boot, written even if adb never comes up (the slot needs the channel added by `bin/add-logcat-forwarder.sh`, see build.md). `bin/analyze-boot-log.sh` without arguments takes the newest non-empty file and prints boot milestones, stalls, channels opened to Windows, crashes and SELinux denials; `bin/analyze-boot-log.sh <file>` analyses a given `logcat` file. If the newest file is empty, the guest half did not start — the slot lacks it, or the guest died early.
- **Guest kernel console, on the host.** `bin/wsa-guest-console.sh [session] [output file]` decodes WSA's ETW traces (`$WSA_DIAG/etw/WsaService-*.etl`, provider `Microsoft.WslCore`, task `GuestLog`) with `tracerpt.exe` — the guest's whole `printk` from the first second: `init`, `fs_mgr`, SELinux, service crashes. No admin rights needed. Default output: `$WSA_WORK/diag/guest-console-<time>.log`. Use it when `logcat` is empty: a failure before `logd` (fstab, policy compilation) shows only here.
- **Native crashes**: `$WSA_DIAG/tombstones` (the guest's `/data/tombstones` is mounted there).

### `WsaClient.exe` crashes on every start, before the VM starts

Symptom: WSA does not start at all, `WsaClient.exe` crashes with `c0000409`, the ETW trace of `WsaClient` shows `ActivityError 0x80004002` (`E_NOINTERFACE`) in `InstanceManagerStartVM`, and no `WsaService` trace is created. Changing the slot, `/data` or restarting services does not help; re-registering the package does (its COM registrations got corrupted, cause unknown).

Registering a package that contains a service needs administrator rights, so the registration is done by a small agent that you start once in an **elevated** PowerShell window and that then serves requests from WSL:

```powershell
powershell -ExecutionPolicy Bypass -File \\wsl.localhost\<distro>\<path to this repository>\bin\wsa-register-agent.ps1
```

Then, in WSL:

```bash
bin/wsa-register.sh --refresh
```

`--refresh` makes a hard-link copy of the install folder in `$WSA_VARIANTS_DIR/shim` (no extra space), registers the package there and back. Unregistering uses `Remove-AppxPackage -PreserveApplicationData`, so `/data` and logs survive. The agent accepts only the main install folder and folders directly under `WSA-variants` next to it, only manifests whose identity is WSA, and only packages registered from a folder. Stop it by creating a file named `stop` in `WSA-variants\agent\`, or with Ctrl+C. (The same agent serves `bin/wsa-register.sh <folder>` — registering another WSA variant — and `bin/wsa-register.sh` without arguments, which returns to `$WSA_INSTALL_DIR`.)

### adb: `unauthorized`, or no answer on port 58526

- `unauthorized` on the **stock** Android 13 slot with a fresh `/data`: stock is a `user` build with `ro.adb.secure=1`, it shows a prompt to authorize the adb key — click it in the WSA window. Until then, `wsa-boot-probe.sh` confirms the boot from the host `logcat` (`BOOT_COMPLETED in host logcat=yes`). Our `userdebug` slots and the Magisk + GApps variants accept adb without a prompt.
- Authorization is per key: if you allowed the key of the Windows adb, WSL's adb (its own key) is still unauthorized — allow it too, or copy `%USERPROFILE%\.android\adbkey*` to `~/.android/`.
- Developer mode is on, WSA runs, but nothing listens on 58526 (`adb connect` fails with 10061): the port may fall into a range reserved by Hyper-V. From an elevated prompt: `net stop winnat`, `netsh int ipv4 add excludedportrange protocol=tcp startport=58526 numberofports=1`, `net start winnat`. Check with `netsh int ipv4 show excludedportrange protocol=tcp`.

### Crashes on the first boot

On a clean `/data`, Android 14, LineageOS 21 and Android 15 slots log two or three `SIGABRT`s of Microsoft's Bluetooth HAL (`rootcanal`) and of `com.android.bluetooth`, after which Bluetooth switches itself off. This is harmless and is what `crash=4` in `wsa-boot-probe.sh` usually counts. On Android 17, AOSP's `launcher3` may crash once (taskbar NPE); it is not visible in WSA, the home screen is our bridge.

### Google sign-in does not complete

With a Magisk + GApps `initrd`, check that the GApps modules are not disabled in this pool — a `disable` file in `/data/adb/modules/lsp_gapps` or `/data/adb/modules/lsp_cust`. Remove it (`adb root`, then `adb shell rm /data/adb/modules/lsp_gapps/disable`) and restart WSA.

### C: drive runs full

The host never deletes its diagnostics: one `logcat` file per start plus ETW traces under `$WSA_DIAG`, several GB in a few days of frequent reboots. When `C:` is full, `WsaClient.exe` crashes at start. `bin/wsa-diag-archive.sh` moves all `logcat` files except the newest and ETW traces older than a day to `$WSA_STORAGE/wsa-diag-archive` (deleting from `C:` only after a verified copy); `switch-build.sh` and `switch-data.sh` run it automatically, it does nothing if `$WSA_STORAGE` is not mounted. Also delete slots you no longer use (three `*.vhdx.<slot>` files each), and remember that WSL's own virtual disk usually lives on `C:` too — keep pools and work images on `$WSA_STORAGE`.

### After `adb reboot` nothing comes back

Start WSA again with any `wsa://` link; the host has to restart its client for the guest to continue booting.

## 10. Going back to stock

With the stock slot, `initrd` and pool from [step 3](#3-keep-a-way-back-to-stock):

```bash
bin/switch-build.sh ms13-stock               # with slots.conf: also restores Tools/initrd.img.stock
cp "$WSA_INSTALL_DIR/Tools/initrd.img.stock" "$WSA_INSTALL_DIR/Tools/initrd.img"   # only if slots.conf does not do it
bin/switch-data.sh switch aosp15 ms13        # save the Android 15 /data as pool aosp15, bring back the stock /data
powershell.exe -NoProfile -Command "Start-Process 'wsa://com.android.settings'"
```

Without a saved stock slot, take the three `.vhdx` files from the original release archive of your WSA variant instead. To remove our slots, delete `*.vhdx.<slot>` from the install folder; the live files are then whatever you last switched to.
