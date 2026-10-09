# Platform patches

WSA-Reborn keeps Microsoft's host (`WsaClient.exe`, `WsaService`), kernel and frozen `vendor` image (VNDK 33, Android 13) untouched. Everything that has to change to make Android 14–17 work on top of them is either in the device tree (`device/microsoft/wsa_x86_64`), in the guest bridge (`WsaShellBridge`), or in the small set of platform patches described here.

## Layout

```
patches/<target>/<project>/NNNN-*.patch
patches/talkback/
```

| target | source tree | release config |
| --- | --- | --- |
| `aosp14` | AOSP, tag `android-14.0.0_r75` | `ap2a` |
| `lineage21` | LineageOS 21 (`lineage-21.0`) | — |
| `lineage22` | LineageOS 22.2 (`lineage-22.2`) | `bp1a` |
| `lineage23` | LineageOS 23.2 (`lineage-23.2`) | `bp4a` |
| `aosp15` | AOSP, branch `android15-security-release` | `ap3a` |
| `aosp16` | AOSP, branch `android16-security-release` | `bp2a` |
| `aosp17` | AOSP, branch `android17-security-release` | `cp2a` |

Each `<project>` directory holds one project's series; its repo path is in the `.path` file inside (`frameworks_base` → `frameworks/base`, `system_fs_fs_mgr` → `system/fs/fs_mgr`).

Each target's patches are **squashed commits**: one `git am`-able commit per project usually carries many of the logical fixes listed below (the commit subject names which ones). Fixes that were added later, or that needed version-specific rework, come as separate commits on top. The IDs below (`0003`, `a15/0013`, …) identify logical fixes, not files in `patches/`.

## How to apply

1. Check out the source tree. The security branches move, so for a reproducible build use the pinned manifests: see [manifests/README.md](../manifests/README.md). Without them, `repo init -b <branch or tag>` from the table above gives the current head, on which the patches may need small rebases.
2. Apply the series — `bin/apply-patches.sh` runs `git am` in every project listed under `patches/<target>/`, on a new branch `wsa-<target>`:

```bash
bin/apply-patches.sh aosp15 /path/to/android/tree
```

3. Build the product from the device tree (see [build.md](build.md)).

Target-specific notes:

- **`aosp17/system_core`** contains `0001`, `0002` and `0005`. The numbers `0003`/`0004` were `a17/0020` and its revert; both were dropped from the series, so the gap is intentional and `0005` applies directly after `0002`.
- **`aosp17`, `0010` fs_mgr part:** in Android 17 `fs_mgr` moved out of `system/core` into its own project `system/fs/fs_mgr`. The `block_dev_initializer.cpp` half of `0010` is in `aosp17/system_core`, the `fs_mgr.cpp` half in `aosp17/system_fs_fs_mgr`. Without the latter `/vendor` does not mount (see `0010` below).
- **`aosp17`, manifest hole:** `external/rust/cros-libva` in the `android17-security-release` manifest imports a module type from `system/desktop/cros-codecs/Android.bp`, a project that is not in the manifest. The build fails in Soong analysis until a stub `Android.bp` declaring that `rust_defaults` module type is placed at that path (protected-content support stays disabled). This is a build-tree workaround, not a patch: the stub is in [patches/aosp17-build/](../patches/aosp17-build/), together with an optional `build/soong` tweak for machines with ~16 GB of RAM.
- **`lineage21`** additionally patches `system/vold` and `vendor/lineage` (see [LineageOS-only changes](#lineageos-only-changes)).
- **`lineage22`** is the `aosp15` series ported to LineageOS 22.2 (Android 15 QPR2), one commit per project; `0006`/`0007` follow the Android 16 shape, because QPR2 already has the Android 16 window-decoration code. Two calls were adapted to the QPR2 API (`WebViewUpdateServiceImpl2.getPackageInfoForProviderAllUsers` without `Context`, logging in `AppOpsManager`). `system/vold` and `vendor/lineage` are carried over from `lineage21`. `build/make` fixes a bug of LineageOS itself: `target/product/gsi_release.mk` lacks a blank line after the `PRODUCT_PACKAGES` list, so Kati reads the following `PRODUCT_COPY_FILES` as package names and fails with "includes non-existent modules". Do not put the ARM translator blobs into a LineageOS 22.2 tree: the QPR2 `fsgen` turns `lib/` copy files into modules installed to `lib64`, the 32- and 64-bit `libhoudini.so` collide and the build fails; add the translator to the built image with `bin/add-houdini.sh` instead.
- **`lineage23`** is the `aosp16` series ported to LineageOS 23.2 (Android 16 QPR2) by hand, plus two build fixes: `build/make` drops the undefined `Calendar` dependency of `aosp_system_image` (LineageOS ships Etar), and `build/soong` skips `verifyGenericConfig` (a LineageOS product on top of `aosp_x86_64` builds its `system.img` from `aosp_system_image` and installs `system_ext`/`product` apps into it). `frameworks/base` `0002` restores `InputManager.getInstance()`: Android 16 QPR2 removed it, but Microsoft's `SystemApp` (API 33) still calls it at startup, crash-loops and RescueParty reboots the system. On a clean `/data` the very first boot takes longer than the host waits (`apexd` decompresses the compressed APEXes) and the host restarts the VM once; the second boot comes up normally.

## Logical fixes

Legend: **yes** — included and needed (observed live or confirmed from the target's source); **inc.** — included, but necessity on that version not separately verified (same code path as the versions where it is needed); **no** — not needed and not included; **feature** — not a fix but an integration feature. "Ported" means the fix had to be rewritten for that version.

| ID | project / file | what | why (symptom without it) | A14 | L21 | A15 | A16 | A17 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| `0003` | `packages/apps/Settings` `AndroidManifest.xml` | `activity-alias` `Settings$DevelopmentSettingsDashboardActivity` → `Settings$DevelopmentSettingsActivity` | Microsoft `SystemApp` throws `IllegalArgumentException` (component does not exist) on every developer-mode sync and never reaches network setup | yes | yes | yes | yes | inc. |
| `0004` | `packages/modules/Connectivity` `EthernetServiceImpl.java` | drop the automotive-only check and the single-Ethernet-transport validation in `updateConfiguration` | `SystemApp` gets `UnsupportedOperationException` / `IllegalArgumentException` configuring Ethernet: no network | yes | yes | yes | yes | inc. (ported) |
| `0005` | `packages/modules/Connectivity` `NetworkCapabilities.java` | relax the "single non-test transport" check in `setNetworkSpecifier` | `IllegalStateException` from `SystemApp`'s network manager: no network | yes | yes | yes (ported) | yes (ported) | inc. (ported) |
| `0006` | `frameworks/native` SurfaceFlinger `LayerSnapshotBuilder.cpp`, `HWComposer.cpp` | always export `com.microsoft.wsa.taskid` / `windowtype` layer metadata, inherited from parent layers; log the HWC-declared keys | no app window ever appears on the Windows desktop | yes | yes | yes | yes (ported) | inc. |
| `0007` | `frameworks/base` WM Shell `WindowDecoration.java` | with `ro.wsa.host_window_decor=true`, hide the guest caption, its insets and shadow; keep task geometry | a second (Android) title bar inside every Windows window | yes | yes | yes (ported) | yes (ported) | inc. (ported) |
| `0008` | `frameworks/native` `LayerSnapshotBuilder.cpp` | do not export task-less `TYPE_STATUS_BAR` layers to the host | the Android status bar is drawn as an overlay strip on top of the Windows desktop | yes | yes | yes | yes | inc. |
| `0009` | `frameworks/base` `AppOpsManager.java` | catch `AbstractMethodError` from API-33 `OnOpNotedListener`s and call the legacy signature | any location/camera/microphone op crashes the persistent `SystemApp`; its restart drops the network for ~0.3 s | yes | yes | yes (ported) | yes (ported) | inc. |
| `0010` | `system/core` `fs_mgr/fs_mgr.cpp`, `init/block_dev_initializer.cpp` | resolve `SCSI=H:C:T:L` fstab addresses to `/dev/block/sdX` | `Failed to mount /vendor` → `InitFatalReboot`, boot loop | yes | yes | yes | yes (ported) | yes (partly missing, see above) |
| `0011` | `frameworks/base` new `services/core/.../wm/WsaDisplayAreaPolicyProvider.java` | provide the display-area policy class named by Microsoft's `vendor` overlay | `system_server` crash loop: `ClassNotFoundException` for `config_deviceSpecificDisplayAreaPolicyProvider` | yes | yes | yes | yes | yes (ported) |
| `0012` | `frameworks/base` SystemUI `ToastUI.java`, new `WsaToastForwarder.java` | forward `Toast`s to the bridge instead of drawing them | `Toast`s are drawn on the invisible guest screen and never seen | feature | feature | feature | feature | feature |
| `a15/0013` | `frameworks/base` `InputManager.java` | `getKeyboardLayoutsForInputDevice` returns the real layouts again | `SystemApp` waits forever for a keyboard layout and never sets up the network | no | no | yes | yes | inc. |
| `a15/0014` | `frameworks/base` `WebViewUpdateServiceImpl2.java` | fall back to the first valid installed WebView provider when the default one is missing | with typical GApps: `Current WebView package is null`, Google sign-in hangs, GMS UI crashes every second | no | no (not verified) | yes | yes (ported) | inc. |
| `a16/0015` | `frameworks/base` `PlatformCompat.java` | uid `system` keeps the lowest `targetSdk` of its packages instead of the platform SDK | `SystemApp` (`targetSdk` 33) crashes on `registerReceiver`; host restarts the VM about every 2 minutes | no | no | no | yes | inc. |
| `a16/0016` | `frameworks/base` SystemUI `ClipboardListener.java` | never show the SystemUI clipboard preview | every copy inside Android pops a preview card as a separate window on the Windows desktop | inc. | inc. | yes | yes | inc. |
| `a16/0017` | `frameworks/base` `ViewRootImpl.java` | apply the drag-resize crop immediately and clear it when resizing ends | after a mouse resize or maximize/restore, `F11` fullscreen shows only the old-size part of the frame | inc. | inc. | yes | yes | no |
| `a17/0018` | `system/core` `init/first_stage_mount.cpp` | first stage keeps fstab entries whose `blk_device` is not a `/dev/` path | `/metadata` and `/vendor` not mounted in first stage → `InitFatalReboot` | no | no | no | no | yes |
| `a17/0019` | `frameworks/base` `InputManager.java` | restore static `InputManager.getInstance()` | `SystemApp` crash loop on `NoSuchMethodError`, no network | no | no | no | no | yes |
| `a17/0020` | — | superseded by `0021`, not in the series | — | — | — | — | — | — |
| `a17/0021` | `system/core` `init/Android.bp`, `init/main.cpp` | `/system/bin/init` can run the first stage again when started without arguments | `init` aborts silently at ~2 s (`Second-stage init requires an argument`); kernel panic | no | no | no | no | yes |
| `a15/0022` | `frameworks/base` `AudioService.java` | `null` check in `anonymizeAudioDeviceAttributesUnchecked` (backport) | `com.google.android.gms.persistent` crashes at every start (NPE) when GMS lacks `BLUETOOTH_CONNECT` | yes | yes | yes | no | no |

`0001`/`0002` were early SurfaceFlinger experiments (`SurfaceFlinger.cpp` key map and `Layer.cpp` metadata) written before `0006`. They are superseded by `0006` but still present in the LineageOS `frameworks_native` patch, because they remained in that build tree; they are not part of any AOSP series.

## Notes on individual fixes

### 0003 — Developer options activity alias

Microsoft's `SystemApp` mirrors the WSA "developer mode" switch by starting `Settings$DevelopmentSettingsDashboardActivity`, the Android 13 name. AOSP renamed the activity to `Settings$DevelopmentSettingsActivity` in Android 14 (this is an AOSP change, not a LineageOS one), so the alias is needed on every base ≥ 14. Without it `SystemApp` fails before it gets to network setup.

### 0004, 0005 — Network configuration by `SystemApp`

WSA's network is configured from the guest side by Microsoft's `SystemApp` through `EthernetManager.updateConfiguration` with explicit capabilities and a network specifier. Android 14+ restricts that call to automotive devices and requires a single transport for a specifier; `SystemApp` was built against Android 13 and trips both checks. On the original Android 13 image the same checks were bypassed by bytecode patching; here they are relaxed in source. On Android 15+ the specifier check was rewritten (`specifierAcceptableForMultipleTransports`), hence the ported variant; on Android 17 `enforceAutomotiveDevice` lost its parameter and the body is simply emptied.

### 0006 — Layer metadata for the host compositor

The host's composer turns guest layers into Windows windows by reading the `com.microsoft.wsa.taskid` and `com.microsoft.wsa.windowtype` generic layer metadata. AOSP's SurfaceFlinger only exports keys the HWC declares as supported, and Microsoft's `vendor` HWC declares zero (`WSA: HWC layer generic metadata keys: 0` in the log, on every version). The patch exports these two keys unconditionally and lets child layers inherit them, so app windows reach the host at all.

### 0007 — No guest window decoration

Windows draws the frame, title bar and shadow of every app window itself. The freeform caption that WM Shell adds would appear as a second title bar inside the window. With `ro.wsa.host_window_decor=true` (set by the device tree) the caption surface is not shown, its insets are removed and the shadow is zeroed, while task positioning and cropping still happen in WM Shell. Android 17 also has a second decoration implementation (`WindowDecoration2`); if it becomes the default, the caption may come back — see patch.

### 0008 — Status bar must not reach the desktop

A guest layer without a task but with a "valid" window type is shown by the host as an overlay on top of the Windows desktop. Microsoft's Android 13 guest had no status bar; AOSP SystemUI does. The patch strips `windowtype` from task-less `TYPE_STATUS_BAR` layers, so the HAL treats them as invalid and leaves them in the (invisible) guest framebuffer. A control build of Android 15 without it showed the Android status bar strip over Windows.

### 0009 — `OnOpNotedListener` compatibility

`SystemApp` registers app-op listeners compiled against API 33, which implement only the old `onOpNoted(int op, …)` signature. Android 14's dispatcher calls the new abstract method and gets `AbstractMethodError`, which kills the persistent `SystemApp`; its restart re-runs Ethernet configuration and drops the network for about 300 ms, so apps see sporadic network errors right when they ask for location. On Android 15+ the device-aware 7-argument branch also ends up in the same abstract method, so the `try` was widened to cover both branches.

### 0010 — `SCSI=H:C:T:L` block device addresses

Microsoft's `vendor` fstab addresses partitions by SCSI address (`SCSI=0:0:0:1`), a scheme AOSP does not know, and WSA does not pass `androidboot.partition_map`/`boot_devices` on the kernel command line. The `fs_mgr.cpp` half resolves such an address to `/dev/block/sdX` via sysfs when mounting; the `block_dev_initializer.cpp` half makes first-stage init create the right device nodes by matching the address in the uevent's sysfs path. The fix is needed for as long as the fstab uses `SCSI=` (explicit `/dev/block/sdX` would also work, since the LUN-to-`sdX` mapping on WSA was measured to be stable).

### 0011 — `WsaDisplayAreaPolicyProvider`

The value of `config_deviceSpecificDisplayAreaPolicyProvider` comes from Microsoft's runtime resource overlay in `vendor`, not from our device tree, and it names `com.android.server.wm.WsaDisplayAreaPolicyProvider`. AOSP throws when the configured class cannot be instantiated, and `system_server` crash-loops in `WindowManagerService` init. The patch adds our own implementation of that class. On Android 17 `DisplayAreaPolicy.Provider.instantiate` takes an `ImeContainer` instead of `DisplayArea.Tokens`, hence the extra commit.

### 0012 — `Toast` to Windows

The guest screen is not visible in WSA, only app windows are, so a `Toast` drawn by SystemUI is never seen. `ToastUI` hands the text to the bridge with an explicit broadcast (receiver protected by `STATUS_BAR_SERVICE`), and the bridge sends it to the host on the Notification channel, where Windows shows it as a notification. Microsoft's Android 13 SystemUI did the same in its own way; this implementation is independent.

### a15/0013 — Keyboard layouts

In Android 15 `InputManager.getKeyboardLayoutsForInputDevice` became a stub that always returns an empty array. `SystemApp` looks up the `keyboard_layout_english_us` layout for its virtual keyboard there and, not finding it, waits forever — its initialization stops before network setup (DHCP never answers). The patch returns all system layouts; setting the current layout stays a stub. Android 14 still has the real method.

### a15/0014 — WebView fallback

Since Android 15, `WebViewUpdateServiceImpl2` picks only the user's choice or the configured default provider and no longer falls back to another valid package. Common GApps packages built for Android 13 install an overlay whose default is `com.google.android.webview`, which may not be installed; the result is no WebView at all and a Google sign-in that never completes. The patch falls back to the first valid provider installed for all users (AOSP `com.android.webview`). Without rebuilding, `cmd webviewupdate set-webview-implementation com.android.webview` works around it per `/data`.

### a16/0015 — `targetSdk` of uid `system`

Android 16 (read-only flag `system_uid_target_system_sdk`, enabled) treats every package of uid `system` as targeting the platform SDK. `SystemApp` shares uid `system` but targets 33, so its `registerReceiver` without `RECEIVER_EXPORTED` throws `SecurityException` during init. The host never gets "network ready", considers the boot failed and restarts the VM roughly every two minutes. The patch restores the Android 15 behaviour (lowest `targetSdk` among the uid's packages).

### a16/0016 — No clipboard preview

SystemUI shows a clipboard preview card on every copy. In WSA that card is a task-less layer and the host shows it as a separate window on the Windows desktop. Copies already go to the Windows clipboard through the bridge, and stock WSA has no such preview, so `onPrimaryClipChanged` returns immediately. On Android 14/LineageOS 21 the preview path is identical and the patch is included, but the symptom was confirmed live only on 15 and 16.

### a16/0017 — Drag-resize crop

During drag-resizing the client crops its surface to the frame, but puts the crop into a transaction applied at some later point and never removes it. After resizing, the next size change without drag (for example `F11` fullscreen) renders the full frame while only the old-size crop is visible. The patch applies the crop immediately and clears it when resizing ends. Android 17 clears the crop itself, so the fix is not ported there.

### a17/0018, a17/0021 — First-stage init on Android 17

WSA boots by running `/init` (a symlink to `/system/bin/init`) without arguments. Up to Android 16 that binary could run the first stage; Android 17 moved the first stage into a separate `init_first_stage` binary and `/system/bin/init` aborts with `Second-stage init requires an argument` before logging is up. `0021` builds the first-stage sources into `/system/bin/init` again under `WSA_FIRST_STAGE_IN_SYSTEM_INIT`. The earlier approach, `0020` (pointing `/init` at a separately installed `init_first_stage`), booted but broke Magisk-based setups, which hijack the second stage by patching the `/system/bin/init` string inside that exact binary; it was therefore replaced by `0021`. `0018` is needed because Android 17's first-stage mount skips fstab entries whose device is not a `/dev/` path, which drops every `SCSI=` entry before `0010` can resolve it.

### a17/0019 — `InputManager.getInstance()`

The static `InputManager.getInstance()` was removed in Android 17, and `SystemApp` calls it while setting up the keyboard. It then crash-loops with `NoSuchMethodError` and never configures the network. The patch restores the method with its Android 16 body (placed so that it does not change the public API surface checked by metalava).

### a15/0022 — `AudioService` null check

`AudioService.anonymizeAudioDeviceAttributesUnchecked` does not check for `null` on Android 14 and 15, while `getMutingExpectedDevice()` returns `null` to callers without `BLUETOOTH_CONNECT`. Google Play services call it at startup (Nearby `DiscoveryService`) and `com.google.android.gms.persistent` crashes every time. Android 16 and 17 already contain the same check; the patch backports it. It only triggers when GMS lacks `BLUETOOTH_CONNECT`, so on some `/data` images it is invisible; `pm grant com.google.android.gms android.permission.BLUETOOTH_CONNECT` is the no-rebuild workaround.

## LineageOS-only changes

The `lineage21` series carries a few changes that are not logical fixes of their own:

- `vendor/lineage` `config/common.mk`: `ro.control_privapp_permissions` becomes an optional assignment (`?=`), so the device makefile can set `disable` without a duplicate-sysprop build error.
- `system/vold` `Checkpoint.cpp`, `VoldNativeServiceValidation.cpp`: `vdc checkpoint` accepts and resolves `SCSI=` addresses. Not needed: `/data` uses `checkpoint=block` and `fs_mgr` ignores the result of the checkpoint restore call, so with or without it the boot proceeds identically (WSA has no OTA checkpoints). Kept as harmless; not ported to AOSP targets.
- `system/core` `fs_mgr/libfstab/fstab.cpp`: diagnostic logging (`WSADIAG`) of the fstab lookup, left from early bring-up; no functional change.
- `frameworks/native` `Layer.cpp`, `SurfaceFlinger.cpp`: the early `0001`/`0002` experiments mentioned above.

## TalkBack

`patches/talkback/0001-wsa-application-id-and-x86_64.diff` applies to [google/talkback](https://github.com/google/talkback) (built at `TfPu_release_16_2`, revision `229212fdf584`) and changes only `shared.gradle`:

- `applicationId` becomes `com.google.android.accessibility.talkback`, the package name of the TalkBack shipped with stock WSA. That is the component Microsoft's `SystemApp` enables when the user turns on the subsystem screen reader (WSA Settings or `Win+Ctrl+T`), so the host-side flow works without changes.
- `abiFilters` is `x86_64` instead of the ARM ABIs; TalkBack loads its Braille native libraries at startup and ARM builds fail with `UnsatisfiedLinkError`.

`patches/talkback/run-talkback-build.sh` runs `gradle assemblePhoneRelease`. It needs JDK 17, Gradle 8.13+ and an Android SDK with `platforms;android-36`, `build-tools;36.0.0` and `ndk;21.4.7075529`. R8 is not enabled (the open-source tree has no ProGuard rules and TalkBack uses reflection). In the image the APK goes to `/system_ext/app/talkback/` and its native libraries to `/system_ext/lib64`, because a system app's ABI is only re-derived from `lib/<abi>` on a fresh `/data` or a fingerprint change.
