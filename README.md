# WSA-Reborn — Android 14–17 for Windows Subsystem for Android™

WSA-Reborn replaces the Android 13 guest of Windows Subsystem for Android™ (WSA, last build `2407.40000.4.0`) with Android 14, 15, 16 or 17 built from AOSP or LineageOS sources — keeping Microsoft's host, kernel and frozen `vendor` image untouched.

> **Not affiliated with Microsoft or Google.** Windows and Windows Subsystem for Android are trademarks of Microsoft Corporation. Android is a trademark of Google LLC. This project ships **no Microsoft binaries**: you bring your own WSA installation.

## Status

| system image | base | security patch | state |
| --- | --- | --- | --- |
| Android 14 | AOSP `android-14.0.0_r75` | 2024-08 | works |
| Android 14 | LineageOS 21 (UNOFFICIAL) | 2026-09 | works, daily driver |
| Android 15 | LineageOS 22.2 (UNOFFICIAL) | 2026-09 | boots and works; full checklist not yet run |
| Android 16 | LineageOS 23.2 (UNOFFICIAL) | 2026-09 | boots and works; full checklist not yet run |
| Android 17 | LineageOS 24.0 (UNOFFICIAL) | 2026-10 | boots and works; full checklist not yet run |
| Android 15 | AOSP `android15-security-release` | 2024-09 (+ASB up to 2026-04) | works |
| Android 16 | AOSP `android16-security-release` | 2025-08 | works |
| Android 17 | AOSP `android17-security-release` | 2026-05 | works |

Tested on every Win11 x64 asset of WSABuilds LTS #8 (plain, Magisk, Magisk + GApps; with and without Amazon) — see [docs/variants.md](docs/variants.md). The original WSA checklist (MustardChef/WSABuilds#700) passes on all versions; Vulkan is limited by the host exactly as on stock WSA.

## What works

- app windows in Windows (resize, maximize, F11 fullscreen, PiP), mouse, keyboard, launching by `wsa://`
- clipboard both ways (text, HTML, images, files); Android notifications in the Windows action center (incl. inline reply), `Toast`s
- Android apps in the Start menu and in WSA Settings; install / update / uninstall
- shared Windows user folder at `/sdcard/Windows`, files to Windows `Downloads`, share from Windows
- Windows text services (IME, emoji panel), Windows media flyout, Windows voices as the Android TTS engine
- Subsystem Screen Reader: TalkBack (built from the open-source code) speaking with Windows voices
- network, GPS, camera, sound, OpenGL ES 3.0, ARM translation (`libhoudini`, BYOB), VPN, Google sign-in with the usual GApps Magisk module
- an Android `Geocoder` backed by OpenStreetMap (the stock one is dead: its Azure Maps key was revoked)

## How it works

WSA's host (`WsaClient.exe`, `WsaService`) talks to the guest over ~26 protobuf "RCM" channels on vsock. On Android 13 the guest side lives in Microsoft's `SystemUIApp`/`UserApp`, which do not run on 14+. WSA-Reborn provides:

- **a guest bridge** (`WsaShellBridge`) — a clean-room re-implementation of the guest side of these channels;
- **platform patches** for AOSP 14–17 (SCSI addressing in `fs_mgr`, SurfaceFlinger layer metadata, compatibility fixes for Microsoft's `SystemApp`, first-stage init on 17, …), each documented with why it is needed and on which versions;
- **a device tree** (`device/microsoft/wsa_x86_64`) building against Microsoft's VNDK-33 `vendor`;
- **scripts** to assemble images from your build tree plus components taken from **your own** WSA install, switch between system images ("slots") and `/data` pools, and diagnose boots from host-side logs.

## Requirements

- Windows 11 x64 with a WSA `2407.40000.4.0` install registered from a folder (e.g. a WSABuilds release)
- WSL2 (Ubuntu) for the scripts
- a Linux build machine for AOSP (≥ 64 GB RAM recommended for Android 17; ~150 GB disk per tree)

## Getting started

See [docs/build.md](docs/build.md) (build a system image), [docs/install.md](docs/install.md) (put it into your WSA), [docs/architecture.md](docs/architecture.md) (how it works), [docs/protocol.md](docs/protocol.md) (host channels) and [docs/patches.md](docs/patches.md) (every platform patch and why).

## Legal

- Our code is licensed under the Apache License 2.0 (see [LICENSE](LICENSE)). AOSP/LineageOS patches follow the license of the patched project.
- No Microsoft, Intel or Google binaries are in this repository. Microsoft components the images need (`SystemApp`, the ARM translator, etc.) are copied by the scripts from **your own** WSA install at build time.
- The bridge's protocol schemas (`device/microsoft/wsa_x86_64/WsaShellBridge/proto/`) are our own interoperability descriptions of the host's channels: field numbers and types follow what the host expects on the wire.
- This is an independent interoperability project for a product Microsoft no longer supports.
