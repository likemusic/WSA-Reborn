# Architecture

This document explains what Windows Subsystem for Android™ (WSA) is made of, which parts WSA-Reborn leaves alone, which parts it replaces, and how a boot proceeds from the Windows side to a running Android 14–17. The channel protocol between the host and the guest is described separately in [protocol.md](protocol.md); building images is in [build.md](build.md), putting them into WSA in [install.md](install.md), and the platform patches in [patches.md](patches.md).

Everything here was established against WSA `2407.40000.4.0`, the last build Microsoft released. All WSABuilds LTS #8 Win11 x64 variants share the same host programs and the same Android partitions; they differ only in `Tools/initrd.img` (plain, Magisk, Magisk + GApps) and in whether `apex/mado_release.apex` (Amazon Appstore) is present.

## The big picture

WSA is a small Hyper-V virtual machine built on the same infrastructure as WSL2. A Windows service starts the VM, a Windows client program draws Android app windows on the desktop, and a set of Android components inside the VM talks to that client over vsock.

```
 Windows (host)                                     Utility VM (guest)
 ─────────────────────────────────────────────      ─────────────────────────────────────────────
 WsaService.exe ── starts VM (WSL core / HCS) ───▶  Tools/kernel (Linux 5.15, Microsoft)
                   attaches SCSI disks, virtiofs     Tools/initrd.img → wsainit → Android init
                                                     ┌─────────────────────────────────────────┐
 WsaClient.exe  ◀── RCM channels (vsock) ─────────▶  │ system / system_ext / product  (ours)   │
   windows, input, clipboard, notifications,         │   WsaShellBridge, SystemApp (BYOB), ... │
   Start menu, files, TSF, media, TTS, ...           ├─────────────────────────────────────────┤
                                                     │ vendor (Microsoft, frozen Android 13)   │
 gfxstream backend + ANGLE/Dozen ◀── graphics ────▶  │   HALs, rcm service, sysprep, gfxstream │
                                                     └─────────────────────────────────────────┘
 %LOCALAPPDATA%\Packages\<WSA>\LocalCache  ◀──────▶  /data, /metadata (userdata.2 / metadata.2)
 %LOCALAPPDATA%\Packages\<WSA>\LocalState  ◀──────▶  logcat channel, /data/tombstones, /data/anr
```

### Host side (Windows)

All of this is Microsoft's and WSA-Reborn does not modify a single byte of it.

| component | role |
| --- | --- |
| `WsaService.exe` | Windows service; creates the VM through its own copy of the WSL core (`wslcore.dll`, HCS for the VM, HNS for NAT or mirrored networking), passes the kernel command line, attaches the partition images as SCSI disks and the virtiofs shares (`wsldevicehost.dll`) |
| `WsaClient.exe` | the user-facing client: one Windows window per Android task, input, clipboard, toasts, Start menu entries, file transfer, text services, media flyout, TTS, adb proxy; it is the host end of every RCM channel and contains the protobuf schemas of all 26 of them |
| `gfxstream_backend.dll`, ANGLE (`libEGL.dll`, `libGLESv2.dll`), Dozen (`vulkan_dzn.dll`, `dxil.dll`) | host half of the graphics stack: guest GL/Vulkan command streams are executed on Direct3D 12 |
| `WSACodecs.dll`, `WsaSettings.exe`, `AppxManifest.xml` | codecs, the WSA Settings app, the package identity |
| `Tools/kernel` | the guest Linux kernel (5.15, built by Microsoft) |
| `Tools/initrd.img` | the guest initramfs; depends on the WSABuilds variant (see [Boot chain](#boot-chain)) |

The package identity (`MicrosoftCorporationII.WindowsSubsystemForAndroid`) is the same for every WSABuilds variant, so only one WSA can be registered per Windows user at a time.

### Guest side (Android)

The guest has four read-only partitions, each a separate `.vhdx` in the install folder, plus two writable ones that live in the package's `LocalCache`:

| image | mount | owner in WSA-Reborn |
| --- | --- | --- |
| `system.vhdx` | `/` (system-as-root) | ours: AOSP or LineageOS built from source |
| `system_ext.vhdx` | `/system_ext` | ours, plus a few components taken from your own install (BYOB) |
| `product.vhdx` | `/product` | ours |
| `vendor.vhdx` | `/vendor` | Microsoft, untouched |
| `userdata.2.vhdx` | `/data` (file-based encryption) | created by the guest on first boot |
| `metadata.2.vhdx` | `/metadata` | created by the guest on first boot |

The `userdata.vhdx` and `metadata.vhdx` files in the install folder are small factory templates; the live `/data` is `userdata.2.vhdx` in `%LOCALAPPDATA%\Packages\<WSA package family>\LocalCache`, which is why replacing the system images never touches user data.

Partitions are addressed in `fstab.1.windows` by SCSI address rather than by device name (`SCSI=0:0:0:1` is `/vendor`, `:2` `/system_ext`, `:3` `/product`, `:4` `/data`, `:5` `/metadata`; LUN 0 is the system disk). The same fstab declares the virtiofs shares the host provides:

| virtiofs tag | guest path | used for |
| --- | --- | --- |
| `shared_mem` | `/mnt/shared_mem` (DAX) | named Windows shared-memory sections: clipboard contents, graphics and camera buffers |
| `shared_tmp` | `/mnt/shared_tmp` | files the host hands to the guest (`content/`, `quarantine/`, `apex/`) |
| `shared_anr` | `/data/anr` | ANR traces, visible on the host in `LocalState/diagnostics/anr` |
| `shared_tombstone` | `/data/tombstones` | native crash dumps, visible in `LocalState/diagnostics/tombstones` |
| `windows_user` | mounted to `/sdcard/Windows` | the Windows user profile folder; only offered when "share user folders" is enabled in WSA Settings, decided when the VM starts |

## What stays Microsoft's

### The frozen `vendor` image

`vendor.vhdx` is an Android 13 vendor image (`target-level` 7, `ro.vndk.version=33`). WSA-Reborn keeps it byte-for-byte. This works because of Treble: the system image carries one framework compatibility matrix per supported vendor level, and a level-7 vendor is checked against matrix 7, which Android 14 through 17 still ship. Every HAL WSA's vendor declares (HIDL composer 2.4, allocator/mapper 4.0, audio 6.0, GNSS 2.1, Bluetooth 1.1, OMX 1.0, DRM 1.4, AIDL camera provider and health) is allowed by matrix 7. The other condition is the VNDK: Microsoft's native services link against VNDK-33 libraries, so the system images must ship the VNDK v33 snapshot (Google keeps `prebuilts/vndk/v33` through Android 17). This is also what sets the version ceiling: a release that drops matrix 7 or the VNDK-33 snapshot needs a new vendor image.

What lives in `vendor`:

| component | what it does |
| --- | --- |
| `hwcomposer.windows`, `graphics.allocator`/`mapper` (`windows`), `windows.services.graphics` (`gskserver`) | composition and buffer management; the composer sends each layer to the host, which turns layers tagged with a task id into the matching Windows window |
| guest GL/Vulkan encoders (gfxstream, goldfish-opengl lineage) | GLES 3.0 and Vulkan command streams to the host over virtio-gpu |
| `windows.services.rcm@1.0-service` (`rcmsvc`) + `librcm.so` | the RCM transport: opens named channels to `WsaClient.exe` over vsock ([protocol.md](protocol.md)) |
| `libapipipe.so` | generated protobuf-lite code for the schemas the vendor HALs use; no transport of its own |
| `audio.primary.windows`, `camera-service.windows`, `gnss-service.windows` | sound, camera and location, each over its own RCM channels |
| `virtualinputservice` | a virtual touchscreen via `/dev/uinput` (channel `UserInputNative`) |
| `sysprep_service` | boot-phase handshake with the host (channel `SysPrep`) |
| keymaster, gatekeeper (software), health, Bluetooth (`rootcanal`) | the usual vendor HALs |
| `vendor_sepolicy.cil`, `vendor_file_contexts`, `vendor_service_contexts` | vendor policy; already lets `system_app`, `priv_app`, `platform_app` and `system_server` use the RCM service and `/mnt/shared_mem` |
| RRO `framework-res__auto_generated_rro_vendor.apk` | framework config overrides, including two class names the system must provide (see below) |

The vendor RRO names `com.android.server.wm.WsaDisplayAreaPolicyProvider` as the display-area policy provider. If the class does not exist, `WindowManagerService` fails to initialize and `system_server` crash-loops, so WSA-Reborn adds an equivalent class to `frameworks/base` ([patches.md](patches.md)). It also names `com.microsoft.windows.service.WsaSystemService` (from Microsoft's `windows-system_server.jar`) as a device-specific system service; that jar is not carried over, `SystemServer` logs a `BOOT FAILURE` for it and continues, with no functional effect observed.

### The ARM translator

`libhoudini` (Intel's ARM-to-x86 translator) is a binary blob taken from your own copy (BYOB). The copy in `2407.40000.4.0` (version 1.2) has a built-in expiry date of 2026-09-01, after which some ARM apps hang; WSA-Reborn puts version 1.3 from Google Play Games for PC into `system_ext` (`bin/extract-houdini-blobs.sh` for tree builds, `bin/add-houdini.sh` and `bin/replace-houdini.sh` for finished images). The WSABuilds LTS #8 releases already ship 1.3.

## Why Microsoft's guest apps do not run on Android 14+

On Android 13 the guest half of the Windows integration lives in three Microsoft apps in `system_ext`:

| app | what it contains | on Android 14+ |
| --- | --- | --- |
| `SystemUIApp.apk` | a complete replacement of `com.android.systemui` with Microsoft's additions: `WsaTaskManager` (channel `Shell`: one Windows window per Android task), notifications to Windows, `Toast` forwarding, PiP controller | crashes on every start with `NoClassDefFoundError` in WM Shell classes: it is compiled against the Android 13 WM Shell, whose internals changed in 14 |
| `UserApp.apk` | handlers for `Intents`, `UserInput`, `Clipboard`, `FileSystem`, `TSF`, `TextToSpeech`, `UIABridge`, `SystemMediaControls`, `Wns`; the package verifier; the input method and TTS engine | not carried over; replaced entirely |
| `HomeApp.apk` | a placeholder HOME activity (WSA has no visible Android home screen) | not needed; the bridge provides an empty HOME |
| `SystemApp.apk` | `Settings`, `Lifetime`, `Telemetry`, `Geocode`, `PermissionUsage`, `Apps`, `Amazon`; Ethernet configuration (`EthernetManager`) | **kept** (BYOB): it is what brings up networking. It runs on 14–17 with a handful of compatibility patches to the platform (see below) |

All of Microsoft's guest code targets API 33. Even where it would load, it relies on behaviour Android 14 changed: `PendingIntent` senders must opt in to background activity starts, `registerReceiver` needs an explicit exported flag, `BigPictureStyle` images may be an `Icon` under a new key, and so on. [protocol.md](protocol.md) lists these per channel.

`SystemApp` is kept rather than rewritten because it works and because its network setup is the only thing standing between the guest and a usable Ethernet link. To run on newer platforms it needs: an activity alias for a Settings class it starts by name; relaxed checks in `EthernetServiceImpl` (`enforceAutomotiveDevice`) and `NetworkCapabilities.setNetworkSpecifier`; the legacy `OnOpNotedListener` path in AppOps (without it every location/camera/microphone `noteOp` crashed `SystemApp`, which then re-created the network); on 15, keyboard layout handling it calls during setup; on 16, a `PlatformCompat` check for uid `system` (without it the host restarted the VM every couple of minutes); on 17, `InputManager.getInstance()`. Details and the per-version necessity of each fix are in [patches.md](patches.md).

## What WSA-Reborn replaces

### System images built from source

`system`, `system_ext` and `product` are built from AOSP (`android-14.0.0_r75`, `android15/16/17-security-release`) or LineageOS 21, as an `x86_64` `userdebug` target from the device tree `device/microsoft/wsa_x86_64`. The device tree builds against the frozen vendor (VNDK v33, matrix 7), installs the bridge and its helpers, ships `fstab.*.windows`, the SELinux pieces needed next to Microsoft's vendor policy, and an init script that skips the setup wizard (WSA never shows the Android home screen, so the wizard would otherwise sit there unseen).

Platform patches, all published as source diffs ([patches.md](patches.md)), fall into a few groups:

- **booting on WSA**: `fs_mgr` resolves `SCSI=H:C:T:L` fstab entries to block devices (Microsoft's own scheme, absent from AOSP); on 17, first-stage mount keeps such entries, and first-stage init is put back into `/system/bin/init` (17 moved it to a separate binary; WSA's initrd and Magisk expect the old layout);
- **windows on the host**: SurfaceFlinger exports the `com.microsoft.wsa.*` layer metadata the host composer uses to map layers to tasks (the composer HAL declares no generic-metadata keys, so stock SurfaceFlinger never sends them); taskless system windows such as the status bar are not composited over the Windows desktop; WM Shell does not draw its own caption, since the host draws the frame; fixes for crop and preview behaviour during resize on some versions;
- **vendor contract**: `WsaDisplayAreaPolicyProvider`;
- **Microsoft's `SystemApp`**: the compatibility fixes listed above;
- **integration**: SystemUI forwards `Toast`s to the bridge instead of drawing them (the guest screen itself is never visible to the user);
- **general fixes** found along the way, e.g. a missing `null` check in `AudioService` that crashed GMS when `BLUETOOTH_CONNECT` was not granted.

### The bridge: `WsaShellBridge`

`WsaShellBridge` (`org.wsalineage.shellbridge`, in `device/microsoft/wsa_x86_64/WsaShellBridge`) is a clean-room re-implementation of the guest side of the RCM channels formerly served by `SystemUIApp` and `UserApp`. It is a persistent, direct-boot-aware `priv-app` in `system_ext`, signed with the platform key and running as `android.uid.system` (SELinux domain `system_app`). That uid is required for the `Shell` channel (task organisation via `WindowOrganizer`, `MANAGE_ACTIVITY_TASKS`) and for input injection; Microsoft's vendor policy already grants `system_app` access to the RCM service, so no new policy is needed for the transport.

It implements `Shell`, `Intents`, `UserInput`, `Clipboard`, `Notification`, `Apps`, `FileSystem`, `TSF`, `SystemMediaControlsChannel`, `TextToSpeech` and `UIABridge`. Besides the channel handlers it contains the pieces Android needs locally: an input method (`WindowsInputMethod`), a TTS engine (`WindowsTtsService`), an accessibility service for UI Automation, content providers for clipboard images and Windows files, the activity that opens links in Windows, an empty HOME activity, and an OpenStreetMap geocoder (`OsmGeocodeService`). The same APK runs on all five system images.

Two small companions are built from the same tree:

- `WsaPackageVerifier` — the required package verifier for install-time scanning by Windows antivirus (AMSI). It is a separate package in the `platform_app` domain because only that domain may read APKs in the installer session directory; it streams the APK to the bridge, which forwards it to the host ([protocol.md](protocol.md), `FileSystem`);
- `wsa_mount_shared_folder` — a static helper started by init that mounts the `windows_user` virtiofs share into `/sdcard/Windows` with the correct SELinux label (replaces Microsoft's `mount_shared_folder`).

TalkBack is built from Google's open-source code with a small patch ([patches.md](patches.md)) and placed in `system_ext`; it is what the WSA "Screen reader" switch and `Win+Ctrl+T` turn on, and it speaks through the bridge's TTS engine, i.e. with Windows voices.

### Components taken from your own install (BYOB)

A few Microsoft guest components are still needed and cannot be distributed. The scripts copy them from **your** WSA install (unpacked reference partitions) into the images you build:

| component | source partition | why | script |
| --- | --- | --- | --- |
| `SystemApp.apk` | `system_ext/priv-app` | networking, settings/time sync with Windows, `Lifetime`, `PermissionUsage`, sysprep readiness | `bin/transplant-a14.sh` |
| `NetworkStackOverlay.apk` | `system_ext/overlay` | resource overlay `SystemApp`'s network setup depends on | `bin/transplant-a14.sh` |
| `privapp-permissions-windows.xml`, `com.microsoft.windows.pdk.notifications.xml` | `system_ext/etc/permissions` | privileged-permission allowlist and permission definitions for `SystemApp`; without the allowlist `system_server` aborts while scanning the package | `bin/transplant-a14.sh` |
| `logcat_forwarder` and its libraries (`librcm.so`, `libapipipe.so`, `windows.services.rcm-V1-ndk.so`, protobuf-lite) | `product` | guest half of the host-side logcat channel | `bin/add-logcat-forwarder.sh` |
| `libhoudini` | `system`/`vendor` (or Google Play Games for PC) | ARM translation | `bin/extract-houdini-blobs.sh`, `bin/add-houdini.sh` |
| stock `talkback.apk` | `product/app` | fallback only, if you do not build TalkBack | `bin/transplant-a14.sh` |

`SystemApp` and `NetworkStackOverlay` declare `sharedUserId="android.uid.system"`, so `transplant-a14.sh` re-signs them with the platform key of **your** build; an APK with a foreign signature in the system uid causes a boot loop. The SELinux side (type mappings for Microsoft's vendor-33 types, the `SystemApp` service types, `service_contexts` lines) is our own CIL in `device/microsoft/wsa_x86_64/sepolicy` that reuses Microsoft's type names.

In a build of our own images, `logcat_forwarder` goes into `system_ext` rather than `/product/bin` as on stock: binaries in `/product/bin` get the VNDK linker namespace only when `ro.product.vndk.version` is set in `product/etc/build.prop`, which our builds do not set, and the binary would fail to link every five seconds.

## Boot chain

1. **VM start.** Opening anything via `wsa://`, or the WSA Settings app, makes `WsaService` create the VM with `Tools/kernel`, `Tools/initrd.img` and the kernel command line (`androidboot.hardware=windows_x86_64`, `androidboot.fstab_suffix=1.windows`, verified-boot properties). Disks are attached as SCSI LUNs in a fixed order, virtiofs shares are exported, and a hvsocket to the host is set up for the init process.
2. **initrd.** `wsainit` is WSL's own init (the open-source `microsoft/WSL` Linux init, built by Microsoft) running in "utility VM" mode: it takes its instructions from the host over the hvsocket and starts the distribution, which here is the Android system image, by executing its `/system/bin/init`. The initrd comes in three flavours depending on the WSABuilds variant:
   - **plain**: `/init` is `wsainit`;
   - **Magisk** (MagiskOnWSALocal): `/init` is `lspinit`, which runs `wsainit` under `ptrace`, patches a copy of `/system/bin/init` so that the second stage is started through `magiskinit`, and lets the boot continue; with GApps the initrd also carries the GApps image as a Magisk module;
   - your own: `bin/switch-build.sh` can pin an initrd per slot (see [Slots and `/data` pools](#slots-and-data-pools)).
   All three work with every WSA-Reborn system image.
3. **First-stage init** (`/system/bin/init` without arguments) reads `fstab.1.windows`, resolves the `SCSI=` addresses (platform patch) and mounts `/vendor`, `/system_ext`, `/product` and `/metadata`. On Android 17 Google moved first-stage init into a separate `init_first_stage` binary; WSA-Reborn builds it back into `/system/bin/init`, because both `wsainit` and Magisk's interception expect the Android 14–16 layout.
4. **SELinux setup and second stage.** The policy is compiled at boot from the platform policy, Microsoft's vendor-33 policy and the `system_ext` CIL, then init re-executes itself for the second stage (through `magiskinit` when Magisk is present).
5. **Sysprep handshake.** Vendor init waits in `post-fs-data` for `vendor.sysprep.start_phase=1`. `sysprep_service` sets it when the host sends `StartPhase1` over the `SysPrep` channel and answers `Phase1Started`. The host sends it when `WsaClient.exe` connects, so after `adb reboot` the guest waits here until something is opened via `wsa://` — unless "Subsystem resources" is set to "Continuous" in WSA Settings, in which case the host starts the client itself.
6. **Android framework.** `/data` is mounted (and formatted on the first boot), `system_server` starts, `SystemApp` configures Ethernet and reports readiness to the host, and the bridge, being persistent, starts and opens its channels. From here on the host creates a Windows window for every task the bridge announces ([protocol.md](protocol.md), `Shell`).

## Slots and `/data` pools

A **slot** is a set of three files next to the live images in the install folder: `system.vhdx.<slot>`, `system_ext.vhdx.<slot>`, `product.vhdx.<slot>`. `bin/switch-build.sh <slot>` stops WSA and copies them over `system.vhdx`, `system_ext.vhdx` and `product.vhdx`; `status` tells which slot the live files match, `list` and `describe` show what is available. An optional `slots.conf` in `~/.config/wsa-reborn/` gives each slot a description and, if needed, an initrd to switch together with it. `WSA_LIVE_DIR` puts a slot into another install folder (for example a different WSABuilds variant) without touching that install's initrd. Typical slots are one per system image: `aosp14-vanilla`, `lineage21`, `aosp15-vanilla`, `aosp16-vanilla`, `aosp17-vanilla`, plus the stock Android 13 images for reference.

A **`/data` pool** is a saved pair `userdata.2.vhdx` + `metadata.2.vhdx`. Running a different major Android version on the same `/data` breaks in a new way every time (AppOps op codes, provider databases refusing to downgrade, privileged-permission grants for packages the other image lacks), so each Android version gets its own pool. `bin/switch-data.sh switch <live-pool> <pool>` saves the live data under one name and makes another live; `fresh` makes a clean `/data` from the install's templates. Because Android apps also exist on the Windows side (Start menu shortcuts, "Installed apps" entries) and the host keeps those per Windows user rather than per `/data`, each pool also stores its Windows-side app entries; `prune-host-apps` removes leftovers that do not belong to the live pool.

Paths (install folder, package folder, storage for pools and work images, adb serial) are detected by `bin/wsa-env.sh` or set in `~/.config/wsa-reborn/env`. Pools are large; keep them on a drive other than the one WSL itself lives on.

## Diagnostics

The guest screen is never shown directly, and early failures happen before adb is up, so most diagnosis is done from host-side artefacts in `LocalState/diagnostics` of the WSA package.

| source | what it contains | how to read |
| --- | --- | --- |
| host-side logcat, `diagnostics/logcat/<timestamp>.log` | full `logcat -b all -v threadtime` of every boot, written by the host half of Microsoft's logcat channel. The guest half (`logcat_forwarder`) must be in the image; without it the host still creates the file but it stays empty | `bin/analyze-boot-log.sh` (no argument: newest non-empty file) — boot milestones, channel openings, stalls, crashes, SELinux denials |
| ETW traces `diagnostics/etw/WsaService-*.etl` | the guest kernel console (provider `Microsoft.WslCore`, task `GuestLog`) from the first second: init, `fs_mgr`, SELinux, service crashes. Available even when nothing else is | `bin/wsa-guest-console.sh` (decodes with `tracerpt.exe`, no admin rights) |
| ETW traces `diagnostics/etw/WsaClient-*.etl` | what the host did with channel messages: window creation results, HRESULTs, media flyout, notification activation | `tracerpt.exe … -of XML` |
| `diagnostics/tombstones`, `diagnostics/anr` | native crash dumps and ANR traces (virtiofs-mounted into the guest's `/data/tombstones` and `/data/anr`) | any text viewer |
| `adb` | the usual; WSA listens on port 58526 on its VM interfaces, so from WSL it is reached through the WSL gateway address | `bin/wsa-boot-probe.sh` gives a one-line status after boot; `bin/wsa-window-diag.sh` snapshots task bounds, input windows and host windows when a window misbehaves |
| offline bench | QEMU with Microsoft's kernel, the vendor image and your images; reproduces the stages up to `/data` decryption with the kernel console in a file. Always do a control run with the stock image | `bin/qemu-boot.sh` |

`bin/check-sepolicy.sh` compiles the combined policy (platform + vendor + our CIL) locally, with the same flags init uses, before an image is tried in WSA, which catches most SELinux mistakes without a boot.

The guest's log buffers are small (`adb logcat -d` loses the start of the boot within a minute), so for anything that happens during boot the host-side logcat file is the reference.

## What comes from where

| component | origin |
| --- | --- |
| `WsaService.exe`, `WsaClient.exe`, WSL core DLLs, graphics DLLs, WSA Settings | Microsoft, your install, untouched |
| `Tools/kernel` | Microsoft, untouched |
| `Tools/initrd.img` | Microsoft's `wsainit`, optionally wrapped by MagiskOnWSALocal (WSABuilds variant), untouched |
| `vendor.vhdx` (HALs, `rcmsvc`/`librcm`, `sysprep_service`, `virtualinputservice`, gfxstream guest libraries, vendor policy and overlay) | Microsoft, untouched |
| `system.vhdx`, `system_ext.vhdx`, `product.vhdx` | built from AOSP / LineageOS source with our device tree and patches |
| platform patches (`fs_mgr`, init, SurfaceFlinger, WM Shell, `WsaDisplayAreaPolicyProvider`, SystemUI `Toast` hook, `SystemApp` compatibility) | ours, source diffs |
| `WsaShellBridge`, `WsaPackageVerifier`, `wsa_mount_shared_folder`, setup-wizard skip, SELinux CIL | ours |
| TalkBack | built from Google's open-source code with our patch |
| `SystemApp.apk`, `NetworkStackOverlay.apk`, their permission XMLs | Microsoft, copied from your install (BYOB), re-signed with your platform key |
| `logcat_forwarder` and its libraries | Microsoft, copied from your install (BYOB) |
| `libhoudini` | Intel/Google binary, from your install or Google Play Games for PC (BYOB) |
| GApps, Magisk | the WSABuilds initrd you chose, unchanged |
| `/data`, `/metadata` | created by the guest; kept in pools by `bin/switch-data.sh` |

## Known limitations

- **Vulkan**: the guest sees zero physical devices, exactly as on stock WSA; the limitation is on the host side. ANGLE is present in the AOSP images but runs on top of Vulkan and so hits the same wall. OpenGL ES 3.0 works.
- **Geocoder**: Microsoft's `Geocode` channel fetches an Azure Maps key from the host, and that key has been revoked (the service answers `InvalidKey`), so the stock geocoder is dead on every WSA. The bridge registers its own `Geocoder` provider backed by OpenStreetMap Nominatim (public server by default, configurable via `settings put global wsa_geocoder_url <url>`, `off` to disable). With GApps installed, Google's provider takes precedence.
- **Amazon Appstore**: discontinued on Windows in 2025. The images contain no `provision_apex`, so `mado_release.apex` is never installed even on variants that ship it; the `Amazon` and `Wns` channels are not implemented. Init logs a harmless "Cannot find `/system_ext/bin/provision_apex`".
- **Notification buttons with non-ASCII titles**: the host puts the button title unescaped into the toast arguments and then fails to parse them, so an inline reply on a button titled e.g. in Cyrillic is silently lost. Stock WSA behaves the same; the bridge does not alter titles. Plain action buttons without inline reply are not shown by the host at all.
- **Narrator / UI Automation**: the `UIABridge` channel is implemented for parity, but the host never passes the tree on to Narrator (stock behaves identically). Use TalkBack (WSA "Screen reader" switch, `Win+Ctrl+T`).
- **`adb reboot`**: the guest waits for the host's `StartPhase1` unless "Subsystem resources" is set to "Continuous".
- **OAuth in an in-app browser**: Microsoft's `UserApp` opened OAuth links in its own `WebView`; the bridge runs as uid `system`, where `WebView` is not allowed, so such links open in the Windows browser. Logins that return to the app via a custom scheme (not an App Link) do not complete.
- **Not implemented `Intents` types**: `Shortcut` and `Install` from the host are answered with failure.
- **Untested**: IME composition (CJK) through TSF, dictation (`Win+H`), the Windows touch keyboard, keyboard media keys, minimizing windows from the host side.
- **First boot on a clean `/data`** (Android 14, LineageOS 21, Android 15): Microsoft's Bluetooth HAL crashes a few times, then Bluetooth stays off. Not a blocker.
- **Android 17**: AOSP Launcher3 crashes in the taskbar code; it is disabled, the bridge's empty HOME is used instead (the home screen is never visible in WSA anyway).
- **No hardware video codecs**, as on stock.
- **Version ceiling**: the frozen vendor needs framework matrix 7 and the VNDK-33 snapshot in the system image; both are present up to Android 17, the newest release checked. A release that drops them would require a vendor image of our own.
- **One instance**: all WSA variants share one package identity, so only one install can be registered at a time.
- **Builds are `userdebug`** on purpose (adb works without a key prompt; `adb root` is available on AOSP images, disabled by a LineageOS setting on LineageOS 21).
