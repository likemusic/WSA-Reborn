# Running on every WSABuilds LTS #8 variant

Our images and scripts were developed against one install: WSA `2407.40000.4.0` built with MagiskOnWSALocal (Magisk + GApps, with Amazon). This page records how they behave on every Windows 11 x64 asset of the [MustardChef/WSABuilds](https://github.com/MustardChef/WSABuilds) `LTS #8` release (`2407.40000.4.0`), checked on 2026-10-06.

## What differs between the variants

The seven Win11 x64 archives were compared file by file: CRC of every file in the archives, and checksums of every file inside `system`, `system_ext`, `product` and `vendor`.

| what | across all seven |
| --- | --- |
| host programs (`WsaClient`, `WsaService`, DLLs, `Tools/kernel`), `AppxManifest.xml` | byte-identical |
| `system`, `system_ext`, `product`, `vendor` | identical file contents; the `.vhdx` files themselves differ only in metadata (UUIDs, timestamps) |
| package `Identity Name` | the same, `MicrosoftCorporationII.WindowsSubsystemForAndroid`, so only one variant can be registered at a time |

Exactly two things differ:

1. `Tools/initrd.img`, in three kinds:
   - **Magisk + GApps** (`lspinit` → `magiskinit` → `wsainit`, GApps as the `lsp_gapps.img` module in `overlay.d`): `GApps-13.0`, `GApps-13.0-NoAmazon`, `with-magisk-…-canary-GApps-13.0-NoAmazon`, `with-magisk-…-stable-GApps-13.0`. The "GApps" variants without "magisk" in the name also carry Magisk, and "canary" and "stable" are the same file (Magisk 30.6 in both);
   - **plain `wsainit`**, no Magisk: `NoGApps`, `NoGApps-NoAmazon`;
   - **Magisk without GApps**: `with-magisk-…-stable-NoGApps-NoAmazon`.
2. `apex/mado_release.apex` (Amazon Appstore, handed to the guest by the host through `provision_apex`): present in the variants without `NoAmazon`, absent in the `NoAmazon` ones. Amazon shows up nowhere else.

Identical pairs: `GApps-13.0` ≡ `with-magisk-…-stable-GApps-13.0`, and `GApps-13.0-NoAmazon` ≡ `with-magisk-…-canary-GApps-13.0-NoAmazon`. So there are five distinct variants.

The ARM translator in `LTS #8` is already `libhoudini` 1.3 (in `system` and `vendor`); older installs may still carry 1.2, which stops working after 2026-09-01 (WSABuilds #793). Our images bring 1.3 in `system_ext` themselves.

The offline scripts do not depend on the variant: the Microsoft reference files that `bin/transplant-a14.sh` (`system_ext`) and `bin/add-logcat-forwarder.sh` (`product`) take are identical in all variants. The variant only affects booting, through `initrd` and Amazon, and that is what was tested live.

## How the runs were done

- each variant is extracted into its own folder under `$WSA_VARIANTS_DIR` (about 1.9 GB each);
- the package is re-registered with `bin/wsa-register.sh <folder>`: `Remove-AppxPackage -PreserveApplicationData` (as MagiskOnWSALocal's `Install.ps1` does for a developer-mode package), then `Add-AppxPackage -Register`. Registering a package with a service requires administrator rights (`0x80073D28`), removing it does not; so registration is done by `bin/wsa-register-agent.ps1`, started once as administrator. Removing with `-PreserveApplicationData` was verified to keep `LocalCache` (`/data`) and `LocalState` (logs);
- every boot starts from a clean `/data`: `bin/switch-data.sh fresh <folder>` (the install's factory templates);
- our images go into the variant's folder with `WSA_LIVE_DIR=<folder> bin/switch-build.sh <slot>`; the variant's own `initrd` is left untouched;
- the state after boot is collected by `bin/wsa-boot-probe.sh <label>`.

Back to the working install: `bin/wsa-register.sh` without arguments, then restore your `/data` pool with `bin/switch-data.sh`.

## Results

Every boot is a first boot on a clean `/data` (formatting, dexopt). Time is from `Start-Process wsa://com.android.settings` to `sys.boot_completed=1`. In every cell Settings ended up in focus, the network had 0 % packet loss, Microsoft's `SystemApp` was alive, and in all our images the bridge was running. Stock is Microsoft's own Android 13 image of the variant; the other columns are our images.

| variant (`initrd`, Amazon) | stock 13 | `aosp14` | `lineage21` | `aosp15` | `aosp16` | `aosp17` |
| --- | --- | --- | --- | --- | --- | --- |
| `NoGApps` (plain `wsainit`, Amazon) | ✅ 231 s, Amazon ² | ✅ 56 s | ✅ 64 s | ✅ 26 s | ✅ 90 s | ✅ 85 s |
| `NoGApps-NoAmazon` (plain `wsainit`) | ✅ by host logcat ¹ | ✅ 23 s | ✅ 71 s | ✅ 19 s | ✅ 82 s | ✅ 95 s ³ |
| `with-magisk-…-stable-NoGApps-NoAmazon` (Magisk) | ✅ by host logcat ¹ | ✅ 72 s, Magisk | ✅ 45 s, Magisk | ✅ 22 s, Magisk | ✅ 84 s, Magisk | ✅ 91 s, Magisk ³ |
| `GApps-13.0-NoAmazon` ≡ `…-canary-GApps-13.0-NoAmazon` (Magisk + GApps) | ✅ 128 s, GMS/Play | ✅ 37 s, GMS/Play | ✅ 51 s, GMS/Play | ✅ 65 s, GMS/Play | ✅ 66 s, GMS/Play | ✅ 103 s, GMS/Play |
| `GApps-13.0` ≡ `with-magisk-…-stable-GApps-13.0` (Magisk + GApps, Amazon) | ✅ 179 s, GMS/Play, Amazon | ✅ 56 s, GMS/Play | ✅ 41 s, GMS/Play | ✅ 48 s, GMS/Play | ✅ 84 s, GMS/Play | ✅ 78 s, GMS/Play |

¹ On a fresh `/data` the stock image (a `user` build, `ro.adb.secure=1`) asks to authorize the adb key; until someone clicks it, adb stays `unauthorized`. The boot is then confirmed by `Finished processing BOOT_COMPLETED` in the host-side logcat. In the `NoGApps` row the key was authorized by hand. The GApps variants (Magisk + `lsp_cust`) let adb in immediately, and so do our `userdebug` images.

² Amazon Appstore (`mado_release.apex`) is installed only on stock. Our images never install it on any variant: the store was discontinued in 2025 and our images have no `provision_apex`.

³ A one-off `launcher3` crash (NPE in `cueBarController`); it happens occasionally on Android 17 regardless of the variant.

First-boot crashes common to all variants: on `aosp14`, `lineage21` and `aosp15`, Microsoft's Bluetooth HAL (`rootcanal`, `stoi`) and `com.android.bluetooth` abort two or three times, after which Bluetooth stays off. This is a property of our images on a clean `/data`, not of the variant.

## Conclusion

Our Android 14–17 images and the scripts work on every LTS #8 variant: plain `wsainit` (no Magisk), Magisk without GApps, Magisk + GApps, with and without Amazon. Not a single boot failed. A variant's Magisk and GApps come up in our images too (Magisk mounts present, GMS and Play installed): `lspinit` patches `/system/bin/init` of our builds the same way as stock (on Android 17 thanks to `a17/0021`, see [patches.md](patches.md)).

What does depend on the variant:

- `bin/switch-build.sh` may replace the live `Tools/initrd.img` with the one configured for a slot; with `WSA_LIVE_DIR` pointing at another install it never touches that install's `initrd`;
- registering the package needs administrator rights (`0x80073D28`); unregistering with `-PreserveApplicationData` keeps `/data`.
