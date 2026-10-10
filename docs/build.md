# Building a system image

This page builds the three WSA guest partitions — `system`, `system_ext`, `product` — for Android 14, 15, 16 or 17 from AOSP or LineageOS 21 sources, and turns them into `.vhdx` files that WSA can boot. Microsoft's `vendor` image, kernel and host stay as they are; installing the result is covered in [install.md](install.md).

The work happens in two places:

1. **a Linux build machine** — sources, patches, device tree, `m` (hours, hundreds of GB);
2. **WSL next to your WSA install** — the BYOB ("bring your own binaries") steps that need files from **your** copy of WSA, and packing to `.vhdx` (minutes).

Both can be the same machine if it is big enough. Nothing from Microsoft is in this repository; every Microsoft or Intel file is taken from your own installation.

## Overview

| target | sources | lunch target | security patch level |
| --- | --- | --- | --- |
| Android 14 (AOSP) | `android-14.0.0_r75` | `aosp_wsa_x86_64-ap2a-userdebug` | 2024-08 |
| Android 14 (LineageOS 21) | `lineage-21.0` | `lineage_wsa_x86_64-ap2a-userdebug` | follows LineageOS |
| Android 15 (LineageOS 22.2) | `lineage-22.2` | `lineage_wsa_x86_64-bp1a-userdebug` | follows LineageOS |
| Android 16 (LineageOS 23.2) | `lineage-23.2` | `lineage_wsa_x86_64-bp4a-userdebug` | follows LineageOS |
| Android 17 (LineageOS 24.0) | `lineage-24.0` | `lineage_wsa_x86_64-cp2a-userdebug` | follows LineageOS |
| Android 15 (AOSP) | `android15-security-release` | `aosp_wsa_x86_64-ap3a-userdebug` | 2024-09 (+ASB fixes) |
| Android 16 (AOSP) | `android16-security-release` | `aosp_wsa_x86_64-bp2a-userdebug` | 2025-08 |
| Android 17 (AOSP) | `android17-security-release` | `aosp_wsa_x86_64-cp2a-userdebug` | 2026-05 |

Steps for every target: sync sources → apply `patches/<target>/` → add `device/microsoft/wsa_x86_64` → extract the ARM translator blobs → `m systemimage systemextimage productimage` → post-process `system_ext`/`product` with components from your WSA → convert to `.vhdx`.

## Requirements

### Build machine

- x86_64 Linux on a case-sensitive filesystem with symlinks (ext4/xfs/btrfs). Not NTFS/exFAT and not a `/mnt/<drive>` path in WSL. `bin/check-env.sh <dir>` checks free space (inside WSL it measures the real Windows drive, not the virtual disk size), filesystem, case sensitivity and symlinks.
- Disk: about 150 GB for an AOSP tree plus about 90 GB for its `out/` directory. LineageOS needs more — `check-env.sh` asks for 400 GB (sources ~150 + out ~250).
- RAM:
  - Android 14–16: 16 GB RAM works only with at least 16 GB of swap; the Soong analysis (`soong_build`) of Android 16 alone peaks at about 40 GB, so it lives on swap. Size jobs by memory, not cores: roughly one job per 3 GB.
  - Android 17: Google recommends 64 GB. With 16 GB RAM the analysis was only completed with zram plus a limit on Go's garbage collector, see [Android 17](#android-17) below, and the build itself with `-j3` (R8 takes about 4 GB per process).
- Time: a clean build is about 8 hours on 6 cores; incremental builds after a patch are minutes to an hour.
- Packages: on a clean Ubuntu, `sudo bash bin/bootstrap-vps.sh [build dir]` installs the AOSP build dependencies (including `git-lfs`, `ccache`, `e2fsprogs`, `qemu-utils`, `android-sdk-libsparse-utils`, OpenJDK 21), installs `repo` into `~/bin` and sets a 50 GB ccache. The JDK and toolchains used by the build itself come with the AOSP tree (`prebuilts/`).

If you build several Android versions in one tree, give each its own output directory (`export OUT_DIR=out-a15`, `out-a16`, …): one version's build otherwise overwrites another's, and a full rebuild costs hours.

### WSL side (BYOB and packing)

- `e2fsprogs` (`debugfs`, `e2fsck`, `resize2fs`, `dumpe2fs`), `qemu-utils` (`qemu-img`), `android-sdk-libsparse-utils` (`simg2img`), `python3`, `zip`, `unzip`, `xxd`;
- Android SDK build-tools for `zipalign`/`apksigner`. `bin/transplant-a14.sh` uses `$HOME/Android/Sdk/build-tools/34.0.0` unless `BUILD_TOOLS=<dir>` is set; other scripts take the newest installed version;
- a WSA `2407.40000.4.0` installation (see [install.md](install.md)) and the variables from `bin/wsa-env.sh` (`$WSA_INSTALL_DIR`, `$WSA_WORK`, `$WSA_REF`, `$WSA_KEYS`, `$WSA_STORAGE`), configured in `~/.config/wsa-reborn/env`.

## 1. Get the sources

Install `repo` if `bootstrap-vps.sh` did not:

```bash
mkdir -p ~/bin && curl -s https://storage.googleapis.com/git-repo-downloads/repo > ~/bin/repo && chmod +x ~/bin/repo
```

### AOSP 14 / 15 / 16 / 17

```bash
TREE=/build/aosp-15                     # any directory on a big ext4 volume
BRANCH=android15-security-release       # android-14.0.0_r75 | android15-security-release | android16-security-release | android17-security-release
mkdir -p "$TREE" && cd "$TREE"
repo init -u https://android.googlesource.com/platform/manifest -b "$BRANCH" --depth=1
repo sync -c -j8 --no-tags --no-clone-bundle --optimized-fetch --prune
```

If a project fails with `RESOURCE_EXHAUSTED` (Google's quota), re-run `repo sync` with `-j1`; it resumes.

The `*-security-release` branches keep moving. For a reproducible tree, sync to the pinned manifests in [manifests/](../manifests/README.md) — the exact revisions the patches were made against. On a newer branch head the patches may need `git am --3way` and small manual merges.

`bin/bootstrap-aosp14.sh [tree] [tag]` does steps 1–3 for Android 14 in one go: pinned sync, device tree, patches.

### LineageOS 21

```bash
bin/fetch-sources.sh /build/lineage-21          # LOS_BRANCH defaults to lineage-21.0
LOS_BRANCH=lineage-22.2 bin/fetch-sources.sh /build/lineage-22
```

For LineageOS 22.2 skip the ARM translator blobs in the tree (see [patches.md](patches.md), `lineage22`) and add the translator to the built `system_ext`/`system` with `bin/add-houdini.sh --sysext … --system …`.

`fetch-sources.sh` runs `check-env.sh` first (skip with `LOS_SKIP_CHECK=1`), does `repo init --depth=1 --git-lfs` (WebView prebuilts need Git LFS), retries `repo sync` up to `LOS_ATTEMPTS` times (default 5) with `LOS_JOBS` jobs (default 4), and stops the download if the disk being filled drops below `LOS_GUARD_GB` (default 25 GB) free. An interrupted run resumes when started again.

## 2. Apply the platform patches

Below, `REBORN` is where you cloned this repository (`REBORN=~/WSA-Reborn`) and `TREE` is the source tree.

`patches/<target>/<project>/` holds `git format-patch` series, one directory per project; the project's path in the tree is in the `.path` file inside the directory. `<target>` is `aosp14`, `aosp15`, `aosp16`, `aosp17`, `lineage21`, `lineage22` or `lineage23`. Apply them with:

```bash
git config --global user.email >/dev/null || git config --global user.email "build@localhost"
git config --global user.name  >/dev/null || git config --global user.name  "build"
"$REBORN/bin/apply-patches.sh" aosp15 "$TREE"     # aosp14 | aosp15 | aosp16 | aosp17 | lineage21 | lineage22 | lineage23 | lineage24
```

It runs `git am` in each patched project on a new branch `wsa-<target>` and stops at the first conflict.

What the patches do and on which versions each one is needed is described in the commit messages; the main ones are SCSI addressing for WSA's fstab in `fs_mgr`/first-stage init, SurfaceFlinger layer metadata for host windows, the host-drawn window decor, Ethernet/`NetworkCapabilities` relaxations for Microsoft's `SystemApp`, compatibility shims for `SystemApp` (`AppOpsManager`, `InputManager`, `PlatformCompat`), `Toast` and clipboard forwarding in SystemUI, and `WsaDisplayAreaPolicyProvider` (a new file, included in the `frameworks_base` patch).

### Android 17

Android 17 needs three extra things that are not ordinary platform patches:

1. **`fs_mgr` moved.** In Android 17 `fs_mgr` is its own project, `system/fs/fs_mgr`, and the SCSI-address change (`0010`) lives there. Its patch is `patches/aosp17/system_fs_fs_mgr/` (applied by `apply-patches.sh` like the others); without it first-stage init cannot mount `/vendor` and the guest reboots.
2. **Missing project `system/desktop/cros-codecs`.** `external/rust/cros-libva` imports a module type from `system/desktop/cros-codecs/Android.bp`, but that project is not in the published `android17-security-release` manifest, and Soong analysis fails with `failed to open "system/desktop/cros-codecs/Android.bp"`. Copy the stub from this repository (it declares only the module type; the `protected_content` variable stays unset, so Intel protected content is off):

   ```bash
   mkdir -p system/desktop/cros-codecs
   cp "$REBORN/patches/aosp17-build/stub-system-desktop-cros-codecs/Android.bp" system/desktop/cros-codecs/
   ```

3. **Memory for Soong analysis on small machines** (only if you have less than ~64 GB RAM). `soong_build` is started with a clean environment, so `GOGC`/`GOMEMLIMIT` do not reach it unless `build/soong/ui/build/soong.go` passes them through — apply `patches/aosp17-build/build-soong-pass-gomemlimit.diff` there (`git -C build/soong apply …`).

   Then enable zram as root (`modprobe zram; echo 12G > /sys/block/zram0/disksize; mkswap /dev/zram0; swapon -p 100 /dev/zram0` — repeat after every reboot) and build with `GOMEMLIMIT=30GiB GOGC=50`. This is a build-machine setting only; it does not change the image.

First-stage init on Android 17 is handled by the `system_core` patch `0021`: `/system/bin/init` again runs the first stage when started without arguments, as on 14–16. This is what WSA's `initrd` (and MagiskOnWSA's hijack of the second stage) expects.

## 3. Add the device tree

```bash
mkdir -p "$TREE/device/microsoft"
rsync -a --delete "$REBORN/device/microsoft/wsa_x86_64/" "$TREE/device/microsoft/wsa_x86_64/"
```

The same device tree builds in both the AOSP and the LineageOS tree; everything LineageOS-specific is included only if `vendor/lineage` exists. It contains the products `aosp_wsa_x86_64` and `lineage_wsa_x86_64`, the guest bridge `WsaShellBridge` and `WsaPackageVerifier` (built as part of the image), the shared-folder helper, init scripts, SELinux policy for Microsoft's VNDK-33 `vendor`, and the fstabs. (`bin/setup-target.sh` copies only the top-level `*.mk` files and prints the `lunch` hint for LineageOS; use `rsync` as above to get the whole tree.)

## 4. Extract the ARM translator (BYOB)

ARM-only apps need Intel's `libhoudini`, which is part of your WSA installation, not of this repository. First unpack the stock partitions of your WSA into `$WSA_REF` — this reference is used by several later steps. Do it while the live files are still Microsoft's (before switching to any of our slots):

```bash
. bin/wsa-env.sh
for p in system system_ext product vendor; do
    bin/unpack-image.sh "$WSA_INSTALL_DIR/$p.vhdx" "$WSA_WORK/img"      # vhdx -> raw .img
    mkdir -p "$WSA_REF/$p"
    debugfs -R "rdump / $WSA_REF/$p" "$WSA_WORK/img/$p.img" 2>/dev/null
done
```

`debugfs` complains that it cannot change ownership when not run as root; that is harmless. Expected result: `$WSA_REF/system/system/…` (system-as-root layout), `$WSA_REF/system_ext/priv-app/SystemApp/SystemApp.apk`, `$WSA_REF/product/bin/logcat_forwarder`, `$WSA_REF/vendor/lib64/libhoudini.so`.

Then put the translator into the build tree as prebuilt blobs (on the build machine, or extract into a directory and copy it there):

```bash
bin/extract-houdini-blobs.sh "$TREE"     # defaults: --wsa-vendor $WSA_REF/vendor --wsa-system $WSA_REF/system/system
```

This creates `device/microsoft/wsa-houdini/` (files, `houdini.mk`, `Android.bp`); `device.mk` picks it up automatically. Without the directory the image simply has no ARM translation.

Which `libhoudini` you get depends on your WSA. The one in the original `2407.40000.4.0` (1.2) has an expiry date of 2026-09-01, after which some ARM apps hang; WSABuilds LTS #8 ships 1.3 (expires 2028-07-01). If yours is the old one, either take the reference from an LTS #8 package, or swap the translator in a built image later with `bin/replace-houdini.sh <system_ext.img> <set dir>` (a directory with `bin/houdini{,64}`, `lib{,64}/libhoudini.so`, `lib/arm`, `lib64/arm64`).

`bin/add-houdini.sh` does the same for an image that was built without the blobs; for tree builds prefer `extract-houdini-blobs.sh` (inode counts in tree-built images are too tight to add ~650 files afterwards).

## 5. Build

```bash
cd "$TREE"
source build/envsetup.sh
lunch aosp_wsa_x86_64-ap3a-userdebug          # see the table at the top
m -j6 systemimage systemextimage productimage # size -j by memory, not cores
```

The images appear in `$OUT_DIR/target/product/wsa_x86_64/` (default `out/target/product/wsa_x86_64/`): `system.img`, `system_ext.img`, `product.img`. `vendor`, `boot` and `super` are not built — WSA keeps its own `vendor` and kernel.

Copy the three images and the build's platform key to the WSL side. The key signs Microsoft's `SystemApp` (it runs with `sharedUserId android.uid.system`, so it must carry the same signature as the platform) and the bridge:

```bash
mkdir -p "$WSA_KEYS"
cp "$TREE"/build/target/product/security/platform.{pk8,x509.pem} "$WSA_KEYS/"
```

All AOSP trees and an unofficial LineageOS 21 build use the same test platform key, so one `$WSA_KEYS` serves every slot. If you build with your own release keys, keep a separate key directory per key.

## 6. TalkBack from source (optional)

The "Subsystem Screen Reader" of WSA enables the TalkBack service `com.google.android.accessibility.talkback/com.google.android.marvin.talkback.TalkBackService`. We build it from [google/talkback](https://github.com/google/talkback) (Apache 2.0) with one change, `patches/talkback/0001-wsa-application-id-and-x86_64.diff` (`shared.gradle`: the application ID of the Microsoft build, and `x86_64` native libraries for Braille). Tested revision: `229212fdf584` (`TfPu_release_16_2`).

Needs JDK 17 (not 21), Gradle 8.13 or newer, Android SDK `platforms;android-36`, `build-tools;36.0.0`, `ndk;21.4.7075529` — about 6 GB of caches, so it is better built away from the drive WSA lives on.

```bash
git clone https://github.com/google/talkback.git && cd talkback
git checkout 229212fdf584
git apply "$REBORN/patches/talkback/0001-wsa-application-id-and-x86_64.diff"
echo "sdk.dir=$ANDROID_HOME" > local.properties
gradle --no-daemon -Dorg.gradle.jvmargs=-Xmx6g assemblePhoneRelease
```

`patches/talkback/run-talkback-build.sh` is the script used for this, with the build machine's paths. The result is an unsigned apk under `build/outputs/apk/phone/release/`. Sign it with your own key (TalkBack is a regular app, not platform-signed) and split out its native libraries:

```bash
keytool -genkeypair -keystore "$WSA_KEYS/talkback.jks" -alias talkback -storepass wsa-talkback -keypass wsa-talkback \
    -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=TalkBack for WSA"      # once
bin/talkback-prepare.sh <…-release-unsigned.apk> "$WSA_STORAGE/wsa-talkback/system"
```

`talkback-prepare.sh` expects the store and key password `wsa-talkback` and alias `talkback` (or set `TALKBACK_KS=<file>` with the same password and alias). The next step picks up `$WSA_STORAGE/wsa-talkback/system` automatically (override with `WSA_TALKBACK_DIR`). Without it, `transplant-a14.sh` falls back to the stock TalkBack apk from your reference (`$WSA_REF/product/app/talkback/talkback.apk`).

## 7. Post-process: Microsoft components from your WSA

Run on the WSL side. Work on copies in a slot work directory on the storage drive:

```bash
. bin/wsa-env.sh
IMGS=/path/to/copied/images           # system.img system_ext.img product.img from step 5
S="$WSA_STORAGE/slot-aosp15"          # work directory for this slot
mkdir -p "$S"
to_raw() { if [ "$(od -An -tx4 -N4 "$1" | tr -d ' ')" = ed26ff3a ]; then simg2img "$1" "$2"; else cp "$1" "$2"; fi; }
to_raw "$IMGS/system.img"  "$S/system.img"
to_raw "$IMGS/product.img" "$S/product.img"
```

(Build output is often in Android's sparse format, magic `0xed26ff3a`; `debugfs` and `qemu-img` need raw ext4.)

**`system_ext`** — `bin/transplant-a14.sh` (named after Android 14, used for 14–17 and LineageOS):

```bash
bin/transplant-a14.sh --img "$IMGS/system_ext.img" --out "$S/system_ext.img" \
    --wsa-sysext "$WSA_REF/system_ext" --secdir "$WSA_KEYS" --grow 192
```

It unsparses the image and grows it by `--grow` MiB (default 64; extra room makes later bridge/TalkBack updates possible), adds Microsoft's `SystemApp.apk` and `NetworkStackOverlay.apk` re-signed with your platform key, `privapp-permissions-windows.xml` and `com.microsoft.windows.pdk.notifications.xml`, TalkBack (step 6), appends the CIL policy the prebuilt `vendor` needs (`wsa_vendor_compat.cil`, `wsa_systemapp.cil`, `wsa_notification_service.cil`) and the matching `service_contexts` lines, relabels `/bin/wsa-no-setupwizard.sh`, and finishes with `e2fsck`. `SystemApp` is what configures networking, settings redirection and several host channels; without it the guest has no network.

**Host logcat channel** — `bin/add-logcat-forwarder.sh` adds Microsoft's `logcat_forwarder` (binary, four libraries, init script, policy rules). With it, the guest's full `logcat` of every boot lands on Windows in `LocalState/diagnostics/logcat/`, which is the main diagnostic tool (see install.md). It edits `.img` files in place:

```bash
bin/add-logcat-forwarder.sh --product "$S/product.img" --sysext "$S/system_ext.img" --wsa-product "$WSA_REF/product"
```

For our own builds it puts the channel into `system_ext` (our `product` has no `ro.product.vndk.version`, so a binary in `/product/bin` would not link). With `--out <dir>` it instead reads existing `.vhdx` files read-only and writes `product.vhdx`/`system_ext.vhdx` into `<dir>` — useful to add the channel to an existing slot.

**Optional preflight of the SELinux policy.** WSA's `vendor` has no precompiled policy matching our partitions, so `init` compiles it at boot and panics silently if that fails. `bin/check-sepolicy.sh` compiles the same file set on the host in under a second (always also compiling Microsoft's policy as a control):

```bash
for p in system system_ext product; do mkdir -p "$S/tree/$p"; debugfs -R "rdump / $S/tree/$p" "$S/$p.img" 2>/dev/null; done
bin/check-sepolicy.sh --system "$S/tree/system" --system-ext "$S/tree/system_ext" --product "$S/tree/product"
```

It does not check `neverallow` rules (neither does `init`).

## 8. Pack to `.vhdx`

```bash
for p in system system_ext product; do
    e2fsck -fy "$S/$p.img" >/dev/null 2>&1 || true
    qemu-img convert -f raw -O vhdx "$S/$p.img" "$S/$p.vhdx"
done
```

These three `.vhdx` files are a slot. Continue with [install.md](install.md#4-add-a-slot).

`bin/pack-vhdx.sh <partition dir> <name> [dest]` is for the other direction — packing an unpacked directory tree into ext4 and `.vhdx`. It needs `mkuserimg_mke2fs` and a `file_contexts.bin` from the **same** tree as the contents (`MKUSERIMG`/`FILE_CONTEXTS`, or `ANDROID_HOST_OUT`/`ANDROID_PRODUCT_OUT` from `lunch`); without SELinux labels the system will not boot. For tree builds the steps above are simpler.

## The bridge without a tree build

`WsaShellBridge` is built into `system_ext` by step 5. For iterating on it you can build the apk locally in seconds, without a tree, and swap it into existing slots.

One-time setup: copy from a built **Android 14** tree into `$TOOLS` (default `~/Android/tools`):

| file in `$TOOLS` | from the tree |
| --- | --- |
| `jdk21/` | `prebuilts/jdk/jdk21/linux-x86` |
| `aosp14/framework.jar` | `out/target/common/obj/JAVA_LIBRARIES/framework-minus-apex_intermediates/classes.jar` (renamed) |
| `aosp14/libprotobuf-java-lite.jar` | `out/soong/.intermediates/external/protobuf/libprotobuf-java-lite/android_common/javac/…` |
| `aosp14/framework-location.jar` | `out/soong/.intermediates/frameworks/base/location/framework-location/android_common/<hash>/javac/framework-location.jar` |
| `aosp14/aprotoc` | `out/host/linux-x86/bin/aprotoc` |

Plus the Android SDK (`platforms/android-34` and build-tools — d8 from 34.0.0 crashes on the bridge, use 36.0.0 or newer) and `$WSA_KEYS/platform.{pk8,x509.pem}`.

```bash
bin/build-bridge-local.sh                         # -> out/WsaShellBridge.apk in this repository
bin/build-bridge-local.sh --out /tmp/bridge.apk
bin/bridge-to-slots.sh aosp14-vanilla aosp15-vanilla   # replace the bridge in these slots' system_ext.vhdx.<slot>
```

`bridge-to-slots.sh` also replaces the privapp allowlist (a new privileged permission without it crashes `system_server`) and removes a stale precompiled `oat` of the bridge. It is a wrapper around `bin/patch-slots.sh </path/in/image>=<file>… -- <slot>…`, which edits `system_ext` of slot files in place (`GROW_MIB=<n>` grows the image first) and does not touch the live files — run `bin/switch-build.sh <slot>` afterwards. The same mechanism puts a new TalkBack into slots: `bin/talkback-to-slots.sh <slot>…`. A persistent system app cannot be updated with `adb install`.

## Test apps

`testapps/` holds small apps used to exercise each host channel (clipboard, notifications, files, TTS, media, camera, sound, GL, VPN, ARM translation, …). They build without a tree, Gradle or network, from the Android SDK alone:

```bash
bin/build-testapp.sh testapps/ClipTest             # -> testapps/ClipTest/out/ClipTest.apk
bin/build-testapp.sh testapps/ClipTest --install   # and `adb install -r` it (run `adb connect $WSA_ADB` first)
```

They compile with `javac` if present, otherwise with ecj (`ECJ=<jar>`, default `~/Android/tools/ecj.jar`), against `platforms/android-$API` (default 34), and are signed with a key generated on first use (`PLATFORM_KEYS=<dir>` signs with the platform key instead).
