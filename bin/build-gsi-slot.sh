#!/usr/bin/env bash
# Path A in one command: a ready Google GSI (aosp_x86_64) → the three files of a WSA slot.
# Path A for Android 14-17 (docs/build.md, "Path A: a ready Google GSI"); every step below is a fix described
# in docs/patches.md, applied to the files of a prebuilt image instead of a source tree.
#
#   bin/build-gsi-slot.sh --gsi <GSI .zip or system.img> --work <dir> --slot-dir <dir> --suffix <slot suffix> \
#       [--surfaceflinger <binary>] [--init <binary>] [--no-bridge] [--keep-work]
#
#   example:
#     bin/build-gsi-slot.sh --gsi aosp_x86_64-exp-AP3A.241005.015-12366759-96716f9b.zip \
#         --work ~/gsi-work/a15 --slot-dir $WSA_STORAGE/slots --suffix .aosp15-gsi \
#         --surfaceflinger <system.img of your aosp15 build>:/system/bin/surfaceflinger
#
# --work must be on a Linux file system (the WSL disk), not on drvfs: the split GSI holds symlinks.
# It needs ~3x the GSI size. Slot files go to --slot-dir as <part>.vhdx<suffix>; register the slot in
# slots.conf yourself (docs/install.md).
#
# What goes into the image (path A: the GSI itself is not rebuilt, fixes are applied to its files):
#   * bin/split-gsi.sh — product and system_ext as separate partitions;
#   * VNDK v33 apex in system_ext/apex: Microsoft's vendor is Android 13 (ro.vndk.version=33), the GSI
#     ships no VNDK snapshot since Android 15 but linkerconfig still looks for it (VNDK_APEX, built by us
#     in the aosp14-vanilla tree, kept outside git);
#   * fstab with explicit /dev/block/sdX — our fstab from the device tree with SCSI=0:0:0:N replaced
#     (a stock GSI init has no patch 0010 that resolves SCSI addresses), in /odm/etc and /system/etc;
#   * system_ext_service_contexts from the device tree, isolated_compilation_enabled=false (the split
#     drops com.android.compos, and system_server dies on IsolatedCompilationService otherwise);
#   * framework.jar: a15/0013 (KeyboardLayoutsPatch) and 0009 (LegacyOnOpNotedPatch — forwards to the API 33 method);
#   * A15/A16: framework.jar a16/0017 (ViewRootCropPatch + helper bin/dexpatch/platform, an extra classesN.dex);
#   * Android 17+: framework.jar a17/0019 (InputManagerGetInstancePatch — InputManager.getInstance() added back);
#   * services.jar: a15/0014 (WebViewFallbackPatch + helper WsaWebView); on Android 15 a15/0022 (ReturnIfNullPatch on
#     AudioService.anonymizeAudioDeviceAttributesUnchecked); on Android 16+ a16/0015 (ReturnParamPatch on
#     PlatformCompat.fixTargetSdk);
#   * com.android.tethering: 0004 (ReturnVoidPatch) and 0005 (NetworkSpecifierPatch), the module
#     re-signed by bin/patch-apex-jar.sh with the open AOSP keys;
#   * Android 15: com.android.btservices a15/0023 (BtBootNameFetchPatch, bin/apex-dexpatch.sh) — no Bluetooth HAL
#     crash on the first boot;
#   * Settings.apk: the 0003 activity-alias (apktool), stale oat removed;
#   * SystemUI.apk: 0007 (WindowDecorPatch — no guest caption bar, insets and shadow inside the host window),
#     a16/0016 (ReturnVoidPatch on ClipboardListener.onPrimaryClipChanged — no clipboard preview),
#     0012 (ToastUiPatch + helper WsaToast — Toasts shown by Windows through the bridge);
#   * Android 17+: Launcher3QuickStep.apk a17/0024 (NullFieldReturnPatch — taskbar NPE after onDestroy);
#   * RRO WsaGsiFrameworkFix built from device/microsoft/wsa_x86_64/WsaGsiFrameworkFix;
#   * our WsaShellBridge with its allowlist (out/WsaShellBridge.apk — bin/build-bridge-local.sh);
#   * on Android 17, our /system/bin/init with a17/0021 (--init, same form) — the stock one has no first stage;
#   * optionally our surfaceflinger with patch 0006 (--surfaceflinger <file> or <image>:<path>) —
#     it must come from a build of the SAME Android branch as the GSI (libgui ABI);
#   * libhoudini 1.3 (HOUDINI, BYOB set from Google Play Games, see bin/replace-houdini.sh): copies in
#     system_ext, links from system, as bin/add-houdini.sh does;
#   * product: ro.control_privapp_permissions=disable (GApps Magisk modules), adb_keys (user build);
#   * bin/transplant-a14.sh (Microsoft SystemApp and friends, CIL) and bin/add-logcat-forwarder.sh.
set -euo pipefail
HERE=$(cd "$(dirname "$0")/.." && pwd)
. "$HERE/bin/wsa-env.sh"

GSI= WORK= SLOT_DIR= SUFFIX= SF= INIT= BRIDGE=1 KEEP=0
while [ $# -gt 0 ]; do
    case "$1" in
        --gsi) GSI=$2; shift 2;;
        --work) WORK=$2; shift 2;;
        --slot-dir) SLOT_DIR=$2; shift 2;;
        --suffix) SUFFIX=$2; shift 2;;
        --surfaceflinger) SF=$2; shift 2;;
        --init) INIT=$2; shift 2;;
        --no-bridge) BRIDGE=0; shift;;
        --keep-work) KEEP=1; shift;;
        *) echo "unknown argument: $1" >&2; exit 2;;
    esac
done
[ -n "$GSI" ] && [ -n "$WORK" ] && [ -n "$SLOT_DIR" ] && [ -n "$SUFFIX" ] || { sed -n 2,14p "$0" >&2; exit 2; }
case "$SUFFIX" in .*) ;; *) SUFFIX=".$SUFFIX";; esac

DEV="$HERE/device/microsoft/wsa_x86_64"
KEYS="${KEYS:-$WSA_KEYS}"   # platform.*, testkey.*, com.android.tethering.pem — bin/fetch-gsi-tools.sh
VNDK_APEX="${VNDK_APEX:-$WSA_STORAGE/wsa-gsi-inputs/com.android.vndk.v33.apex}"
WSA_SYSEXT="${WSA_SYSEXT:-$WSA_REF/system_ext}"
WSA_PRODUCT="${WSA_PRODUCT:-$WSA_REF/product}"
TOOLS="${TOOLS:-$HOME/Android/tools}"
MKUSERIMG="${MKUSERIMG:-$TOOLS/hostbin/mkuserimg_mke2fs}"
JAVA="${JAVA:-$TOOLS/jdk21/bin/java}"
APKTOOL="${APKTOOL:-$TOOLS/apktool.jar}"
BT="${BT:-$(wsa_build_tools)}"
ANDROID_JAR="${ANDROID_JAR:-$(dirname "$(dirname "$BT")")/platforms/android-34/android.jar}"
BRIDGE_APK="$HERE/out/WsaShellBridge.apk"
HOUDINI="${HOUDINI:-$WSA_STORAGE/wsa-houdini/gpg-26.8.237.1}"
for f in "$VNDK_APEX" "$MKUSERIMG" "$JAVA" "$APKTOOL" "$KEYS/platform.pk8" "$KEYS/testkey.pk8" "$KEYS/com.android.btservices.pk8" \
         "$KEYS/com.android.tethering.pem" "$ANDROID_JAR" "$HOUDINI/lib64/libhoudini.so" "$WSA_SYSEXT" "$WSA_PRODUCT" "$HOME/.android/adbkey.pub"; do
    [ -e "$f" ] || { echo "missing: $f" >&2; exit 1; }
done
[ "$BRIDGE" = 0 ] || [ -r "$BRIDGE_APK" ] || { echo "no $BRIDGE_APK — run bin/build-bridge-local.sh (or --no-bridge)" >&2; exit 1; }
case "$WORK" in /mnt/[a-z]/*) echo "--work must not be on drvfs ($WORK): symlinks" >&2; exit 1;; esac

step() { echo; echo "== $* =="; }

# Rewrites a zip with some entries replaced, keeping each entry's compression (dex/arsc stay STORED),
# then aligns it. $1 — source, $2 — result, $3 — directory with the replacement files (same names).
repack_zip() {
    mkdir -p "$3"   # no patch matched on this version — the archive is rewritten as is
    python3 - "$1" "$2.unaligned" "$3" <<'PY'
import os, sys, zipfile
src, dst, repl = sys.argv[1:4]
z = zipfile.ZipFile(src)
with zipfile.ZipFile(dst, 'w') as out:
    for i in z.infolist():
        p = os.path.join(repl, i.filename)
        data = b'' if i.is_dir() else (open(p, 'rb').read() if os.path.isfile(p) else z.read(i))
        zi = zipfile.ZipInfo(i.filename, date_time=i.date_time)
        zi.compress_type = i.compress_type; zi.external_attr = i.external_attr
        out.writestr(zi, data)
    # Files of the replacement directory the archive does not have yet (an extra classesN.dex) are added, STORED.
    have = {i.filename for i in z.infolist()}
    for name in sorted(os.listdir(repl)):
        if name not in have and os.path.isfile(os.path.join(repl, name)):
            zi = zipfile.ZipInfo(name, date_time=(2009, 1, 1, 0, 0, 0)); zi.compress_type = zipfile.ZIP_STORED
            zi.external_attr = 0o644 << 16
            out.writestr(zi, open(os.path.join(repl, name), 'rb').read())
PY
    "$BT/zipalign" -f -p 4 "$2.unaligned" "$2" && rm -f "$2.unaligned"
}

# Runs a bin/dexpatch.sh patch over every classesN.dex of a jar; the patched ones land in $2.
# The patch must match in exactly one dex (it exits 1 where it finds nothing).
dexpatch_jar() {
    local jar=$1 outdir=$2; shift 2
    local tmp; tmp=$(mktemp -d "$WORK/dex.XXXX")
    (cd "$tmp" && unzip -q -o "$jar" 'classes*.dex')
    local hits=0 d src
    for d in "$tmp"/classes*.dex; do
        src="$outdir/$(basename "$d")"; [ -f "$src" ] || src=$d
        if "$HERE/bin/dexpatch.sh" "$1" "$src" "$d.out" "${@:2}" >"$d.log" 2>&1; then
            mkdir -p "$outdir"; mv "$d.out" "$outdir/$(basename "$d")"; hits=$((hits + 1))
            echo "   $1 → $(basename "$d"): $(tail -1 "$d.log")"
        fi
    done
    rm -rf "${tmp:?}"
    [ "$hits" = 1 ] || { echo "$1 matched in $hits dex files of $(basename "$jar"), expected 1" >&2; return 1; }
}

# Our helper classes (bin/dexpatch/platform, package com.wsa.gsi) for patches that redirect a platform call
# instead of inserting code. Compiled once against android.jar plus compile-only stubs of hidden APIs
# (bin/dexpatch/platform-stubs), one dex per class; add_helper puts one into a jar as its next classesN.dex.
build_helpers() {
    HB="$WORK/helper"; rm -rf "${HB:?}"; mkdir -p "$HB/stub" "$HB/cls"
    local javac; javac="$(dirname "$JAVA")/javac"
    "$javac" --release 11 -cp "$ANDROID_JAR" -d "$HB/stub" "$HERE"/bin/dexpatch/platform-stubs/android/view/*.java
    "$javac" --release 11 -cp "$HB/stub:$ANDROID_JAR" -d "$HB/cls" "$HERE"/bin/dexpatch/platform/com/wsa/gsi/*.java
    local c
    for c in "$HB"/cls/com/wsa/gsi/*.class; do
        case "$c" in *\$*.class) continue;; esac
        local name; name=$(basename "$c" .class); mkdir -p "$HB/$name"
        "$BT/d8" --min-api 33 --lib "$ANDROID_JAR" --output "$HB/$name" "$HB"/cls/com/wsa/gsi/"$name"*.class
    done
}
add_helper() {
    local jar=$1 outdir=$2 name=$3 n
    [ -s "$HB/$name/classes.dex" ] || { echo "no helper $name" >&2; return 1; }
    n=$(unzip -Z1 "$jar" | grep -c '^classes[0-9]*\.dex$')
    mkdir -p "$outdir"; cp "$HB/$name/classes.dex" "$outdir/classes$((n + 1)).dex"
    echo "   helper com.wsa.gsi.$name → $(basename "$jar") classes$((n + 1)).dex"
}

mkdir -p "$WORK" "$SLOT_DIR"
SPLIT="$WORK/split"; OUT="$WORK/out"
rm -rf "${SPLIT:?}" "${OUT:?}"; mkdir -p "$OUT"

step "GSI system.img"
case "$GSI" in
    *.zip) unzip -o -q "$GSI" system.img -d "$WORK"; IMG="$WORK/system.img";;
    *) IMG=$GSI;;
esac
"$HERE/bin/split-gsi.sh" "$IMG" "$SPLIT" | tail -3
S="$SPLIT/system/system"; SE="$SPLIT/system_ext"; P="$SPLIT/product"
grep -E "^ro.build.(id|version.release|version.security_patch)=" "$S/build.prop"
VER=$(grep -oP '^ro.build.version.release=\K[0-9]+' "$S/build.prop")
[ "$VER" -ge 14 ] || { echo "Android $VER: this script covers GSI 14+ (the A13 GSI slot was built by hand)" >&2; exit 1; }

step "VNDK v33, fstab, service_contexts, isolated compilation"
mkdir -p "$SE/apex" && cp "$VNDK_APEX" "$SE/apex/"
ODM="$SPLIT/system/odm/etc"
[ -L "$ODM" ] && rm "$ODM"; mkdir -p "$ODM"
for n in 1 2; do
    sed -E 's|^SCSI=0:0:0:([1-5])( *)|/dev/block/sd\1\2|; s|^/dev/block/sd1|/dev/block/sdb|; s|^/dev/block/sd2|/dev/block/sdc|; s|^/dev/block/sd3|/dev/block/sdd|; s|^/dev/block/sd4|/dev/block/sde|; s|^/dev/block/sd5|/dev/block/sdf|' \
        "$DEV/fstab.$n.windows" > "$ODM/fstab.$n.windows"
    cp "$ODM/fstab.$n.windows" "$S/etc/fstab.$n.windows"
done
! grep -q "SCSI=" "$ODM"/fstab.*.windows || { echo "fstab: SCSI= left" >&2; exit 1; }
# Some GSIs (AP3A) ship no system_ext policy at all, others (AP4A) an empty skeleton. Our CIL
# (transplant-a14.sh) needs the file to exist, and init loads it only together with the mapping for
# the vendor's version (33.0); empty files are what the skeleton has.
mkdir -p "$SE/etc/selinux/mapping"
[ -s "$SE/etc/selinux/system_ext_sepolicy.cil" ] || echo > "$SE/etc/selinux/system_ext_sepolicy.cil"
[ -e "$SE/etc/selinux/mapping/33.0.cil" ] || : > "$SE/etc/selinux/mapping/33.0.cil"
cp "$DEV/sepolicy/service_contexts" "$SE/etc/selinux/system_ext_service_contexts"
sed -i 's/^ro.config.isolated_compilation_enabled=true$/ro.config.isolated_compilation_enabled=false/' "$SE/etc/build.prop"

build_helpers
step "framework.jar: a15/0013 + 0009 (+ a16/0017 on A15/A16, a17/0019 on A17+)"
FW="$WORK/fw"; rm -rf "${FW:?}"; mkdir -p "$FW"
# a15/0013: Android 15 made getKeyboardLayoutsForInputDevice a stub; on 14 the method is real.
[ "$VER" -ge 15 ] && dexpatch_jar "$S/framework/framework.jar" "$FW/dex" KeyboardLayoutsPatch
dexpatch_jar "$S/framework/framework.jar" "$FW/dex" LegacyOnOpNotedPatch
if [ "$VER" -ge 15 ] && [ "$VER" -le 16 ]; then
    # a16/0017 (A15 and A16; A17 removes the crop itself): ViewRootCropPatch redirects two calls of
    # ViewRootImpl.relayoutWindow to our helper class, which goes into framework.jar as the next classesN.dex.
    dexpatch_jar "$S/framework/framework.jar" "$FW/dex" ViewRootCropPatch
    add_helper "$S/framework/framework.jar" "$FW/dex" WsaViewRootCrop
fi
if [ "$VER" -ge 17 ]; then
    # a17/0019: Android 17 removed InputManager.getInstance(), which Microsoft's SystemApp calls at start.
    dexpatch_jar "$S/framework/framework.jar" "$FW/dex" InputManagerGetInstancePatch
fi
repack_zip "$S/framework/framework.jar" "$FW/framework.jar" "$FW/dex"
cp "$FW/framework.jar" "$S/framework/framework.jar"

step "services.jar: a15/0014 (WebView when the default provider is missing)$([ "$VER" = 15 ] && echo ', a15/0022 (AudioService null check)')$([ "$VER" -ge 16 ] && echo ', a16/0015 (uid system keeps the lowest targetSdk)')"
SV="$WORK/svc"; rm -rf "${SV:?}"; mkdir -p "$SV"
if [ "$VER" -ge 15 ]; then   # a15/0014: Android 14 still takes any valid WebView provider
    dexpatch_jar "$S/framework/services.jar" "$SV/dex" WebViewFallbackPatch
    add_helper "$S/framework/services.jar" "$SV/dex" WsaWebView
fi
if [ "$VER" = 15 ]; then
    # a15/0022 (the A14 GSI is QPR1 and has no device anonymisation in AudioService yet): Google added "ada == null ||" in Android 16; without it GMS Nearby crashes on a pool without BLUETOOTH_CONNECT.
    dexpatch_jar "$S/framework/services.jar" "$SV/dex" ReturnIfNullPatch 'Lcom/android/server/audio/AudioService;' anonymizeAudioDeviceAttributesUnchecked 0
fi
if [ "$VER" -ge 16 ]; then
    dexpatch_jar "$S/framework/services.jar" "$SV/dex" ReturnParamPatch 'Lcom/android/server/compat/PlatformCompat;' fixTargetSdk 1
fi
repack_zip "$S/framework/services.jar" "$SV/services.jar" "$SV/dex"
cp "$SV/services.jar" "$S/framework/services.jar"

step "com.android.tethering: 0004 + 0005"
T="$WORK/teth"; rm -rf "${T:?}"; mkdir -p "$T/a"
if [ -f "$S/apex/com.android.tethering.capex" ]; then
    (cd "$T" && unzip -q -o "$S/apex/com.android.tethering.capex" original_apex && mv original_apex tethering.apex)
else
    cp "$S/apex/com.android.tethering.apex" "$T/tethering.apex"
fi
(cd "$T/a" && unzip -q -o ../tethering.apex apex_payload.img)
for j in service-connectivity framework-connectivity; do
    debugfs -R "dump /javalib/$j.jar $T/$j.jar" "$T/a/apex_payload.img" 2>/dev/null
done
ETH=
for c in 'Landroid/net/connectivity/com/android/server/ethernet/EthernetServiceImpl;' 'Lcom/android/server/ethernet/EthernetServiceImpl;'; do
    if dexpatch_jar "$T/service-connectivity.jar" "$T/sc" ReturnVoidPatch "$c" enforceAutomotiveDevice maybeValidateEthernetTransport 2>/dev/null; then ETH=$c; break; fi
done
[ -n "$ETH" ] || { echo "EthernetServiceImpl not found in service-connectivity.jar" >&2; exit 1; }
dexpatch_jar "$T/framework-connectivity.jar" "$T/fc" NetworkSpecifierPatch
repack_zip "$T/service-connectivity.jar" "$T/service-connectivity.patched.jar" "$T/sc"
repack_zip "$T/framework-connectivity.jar" "$T/framework-connectivity.patched.jar" "$T/fc"
export APEX_PAYLOAD_KEY="$KEYS/com.android.tethering.pem" APEX_KEY="$KEYS/testkey.pk8" APEX_CERT="$KEYS/testkey.x509.pem"
"$HERE/bin/patch-apex-jar.sh" "$T/tethering.apex" service-connectivity.jar "$T/service-connectivity.patched.jar" "$T/step1.apex" | grep -E "alignment|signed"
"$HERE/bin/patch-apex-jar.sh" "$T/step1.apex" framework-connectivity.jar "$T/framework-connectivity.patched.jar" "$T/patched.apex" | grep -E "alignment|signed"
rm -f "$S/apex/com.android.tethering.capex" "$S/apex/com.android.tethering.apex"
cp "$T/patched.apex" "$S/apex/com.android.tethering.apex"

if [ "$VER" -le 15 ]; then
    step "com.android.btservices: a15/0023 (no Bluetooth HAL crash on the first boot)"
    B="$S/apex/com.android.btservices.apex"; [ -f "$B" ] || B="$S/apex/com.android.btservices.capex"
    APEX_PAYLOAD_KEY="$KEYS/com.android.btservices.pem" APEX_KEY="$KEYS/com.android.btservices.pk8" \
    APEX_CERT="$KEYS/com.android.btservices.x509.pem" \
        "$HERE/bin/apex-dexpatch.sh" "$B" service-bluetooth.jar "$WORK/btservices.apex" BtBootNameFetchPatch | grep -v '^--'
    rm -f "$S/apex/com.android.btservices.capex" "$S/apex/com.android.btservices.apex"
    mv "$WORK/btservices.apex" "$S/apex/com.android.btservices.apex"
fi

step "Settings.apk: 0003 activity-alias"
ST="$WORK/settings"; rm -rf "${ST:?}"; mkdir -p "$ST"
"$JAVA" -jar "$APKTOOL" if -p "$ST/fw" "$S/framework/framework-res.apk" >/dev/null
"$JAVA" -jar "$APKTOOL" d -f -p "$ST/fw" -o "$ST/dec" "$SE/priv-app/Settings/Settings.apk" >/dev/null
python3 - "$ST/dec/AndroidManifest.xml" <<'PY'
import sys
p = sys.argv[1]; s = open(p).read()
if 'Settings$DevelopmentSettingsDashboardActivity' in s:
    print("   alias already present"); sys.exit(0)
key = 'android:name="com.android.settings.Settings$DevelopmentSettingsActivity"'
i = s.index(key); j = s.index('</activity>', i) + len('</activity>')
alias = '''
        <!-- WSA path A (docs/patches.md `0003`): Microsoft's SystemApp toggles this component name. -->
        <activity-alias android:exported="true" android:icon="@drawable/ic_settings_development" android:label="@string/development_settings_title" android:name="com.android.settings.Settings$DevelopmentSettingsDashboardActivity" android:targetActivity="com.android.settings.Settings$DevelopmentSettingsActivity"/>'''
open(p, 'w').write(s[:j] + alias + s[j:]); print("   alias added")
PY
"$JAVA" -jar "$APKTOOL" b -p "$ST/fw" -o "$ST/unsigned.apk" "$ST/dec" >/dev/null
"$BT/zipalign" -f -p 4 "$ST/unsigned.apk" "$ST/aligned.apk"
"$BT/apksigner" sign --v4-signing-enabled false --key "$KEYS/platform.pk8" --cert "$KEYS/platform.x509.pem" --out "$SE/priv-app/Settings/Settings.apk" "$ST/aligned.apk"
rm -rf "${SE:?}/priv-app/Settings/oat"

step "SystemUI.apk: 0007 (no guest caption, insets, shadow — the host draws the frame), a16/0016, 0012 (Toast → Windows)"
SU="$WORK/sysui"; rm -rf "${SU:?}"; mkdir -p "$SU"
# 0007: templates for A14 (QPR1) and A15–A17. Without our surfaceflinger of the same branch (A14: r17, QPR1) app
# windows do not reach Windows anyway, but the patch is harmless.
dexpatch_jar "$SE/priv-app/SystemUI/SystemUI.apk" "$SU/dex" WindowDecorPatch
# a16/0016 (needed on A15 too): no SystemUI clipboard preview — it pops up as a separate window on the
# Windows desktop, and copying already reaches the Windows clipboard through the bridge.
dexpatch_jar "$SE/priv-app/SystemUI/SystemUI.apk" "$SU/dex" ReturnVoidPatch \
    'Lcom/android/systemui/clipboardoverlay/ClipboardListener;' onPrimaryClipChanged
# 0012: ToastUI.showToast/hideToast hand the Toast to the bridge (helper WsaToast), which shows it in Windows;
# without the bridge in the image the helper declines and ToastUI draws it as usual.
dexpatch_jar "$SE/priv-app/SystemUI/SystemUI.apk" "$SU/dex" ToastUiPatch
add_helper "$SE/priv-app/SystemUI/SystemUI.apk" "$SU/dex" WsaToast
repack_zip "$SE/priv-app/SystemUI/SystemUI.apk" "$SU/aligned.apk" "$SU/dex"
"$BT/apksigner" sign --v4-signing-enabled false --key "$KEYS/platform.pk8" --cert "$KEYS/platform.x509.pem" \
    --out "$SE/priv-app/SystemUI/SystemUI.apk" "$SU/aligned.apk"
rm -rf "${SE:?}/priv-app/SystemUI/oat"

if [ "$VER" -ge 17 ]; then
    # a17/0024: the taskbar reports the recents button layout after onDestroy() cleared mControllers — NPE, launcher3 crashes.
    step "Launcher3QuickStep.apk: a17/0024 (taskbar after onDestroy)"
    LA="$WORK/launcher"; rm -rf "${LA:?}"; mkdir -p "$LA"
    L3="$SE/priv-app/Launcher3QuickStep/Launcher3QuickStep.apk"
    dexpatch_jar "$L3" "$LA/dex" NullFieldReturnPatch \
        'Lcom/android/launcher3/taskbar/TaskbarNavButtonController;' onRecentsButtonLayoutChanged mControllers
    repack_zip "$L3" "$LA/aligned.apk" "$LA/dex"
    # The GSI signs Launcher3 with the AOSP testkey (not the platform key).
    "$BT/apksigner" sign --v4-signing-enabled false --key "$KEYS/testkey.pk8" --cert "$KEYS/testkey.x509.pem" --out "$L3" "$LA/aligned.apk"
    rm -rf "${SE:?}/priv-app/Launcher3QuickStep/oat"
fi

step "RRO WsaGsiFrameworkFix"
R="$WORK/rro"; rm -rf "${R:?}"; mkdir -p "$R"
"$BT/aapt2" compile -o "$R/compiled.zip" --dir "$DEV/WsaGsiFrameworkFix/res"
"$BT/aapt2" link -o "$R/unsigned.apk" --manifest "$DEV/WsaGsiFrameworkFix/AndroidManifest.xml" -I "$ANDROID_JAR" \
    "$R/compiled.zip" --min-sdk-version 33 --target-sdk-version 34
"$BT/zipalign" -f 4 "$R/unsigned.apk" "$R/aligned.apk"
mkdir -p "$SE/overlay"
"$BT/apksigner" sign --v4-signing-enabled false --key "$KEYS/platform.pk8" --cert "$KEYS/platform.x509.pem" --out "$SE/overlay/WsaGsiFrameworkFix.apk" "$R/aligned.apk"
CFG="$SE/overlay/config/config.xml"
if [ -f "$CFG" ] && ! grep -q com.wsa.gsi.frameworkfix "$CFG"; then
    # A static RRO listed nowhere is still picked up; one listed under a wrong name kills the zygote
    # (WsaGsiFrameworkFix/README.md). Where the GSI has a config, register ours in it — as IMMUTABLE and
    # before the mutable ones: zygote applies only immutable overlays to framework-res, and an entry
    # without mutable="false" left the AP3A slot crash-looping on WsaDisplayAreaPolicyProvider (11.10.2026).
    sed -i '0,/<config>/s|<config>|<config>\n    <overlay package="com.wsa.gsi.frameworkfix" mutable="false" enabled="true" />|' "$CFG"
fi

if [ "$BRIDGE" = 1 ]; then
    step "WsaShellBridge"
    mkdir -p "$SE/priv-app/WsaShellBridge"
    cp "$BRIDGE_APK" "$SE/priv-app/WsaShellBridge/WsaShellBridge.apk"
    cp "$DEV/WsaShellBridge/privapp-permissions-wsashellbridge.xml" "$SE/etc/permissions/WsaShellBridge.xml"
fi

# Our own native binary from a build of the SAME Android branch as the GSI: <file> or <image>:<path>.
our_binary() {
    local spec=$1 name=$2 out="$WORK/$2"
    case "$spec" in
        *.img:/*) debugfs -R "dump ${spec#*.img:} $out" "${spec%%:/*}" 2>/dev/null;;
        *) cp "$spec" "$out";;
    esac
    [ -s "$out" ] || { echo "no $name in $spec" >&2; return 1; }
    # Every strong symbol it needs must resolve in this GSI's libraries (the ABI check that rules out a foreign
    # branch); weak ones (w) may stay unresolved, the GSI's own binaries have the same.
    local missing
    missing=$(comm -23 <(nm -D --undefined-only "$out" | awk '$1 == "U" {print $2}' | grep -v @ | sort -u) \
        <(for l in $(readelf -d "$out" | awk '/NEEDED/{print $5}' | tr -d '[]'); do
              f=$(find "$SPLIT" -path "*lib64/$l" | head -1); [ -n "$f" ] && nm -D --defined-only "$f" 2>/dev/null | awk '{print $3}'
          done | sort -u))
    [ -z "$missing" ] || { echo "$name does not fit this GSI, unresolved:" >&2; echo "$missing" | c++filt >&2; return 1; }
    echo "$out"
}

if [ -n "$SF" ]; then
    step "surfaceflinger (0006)"
    SFBIN=$(our_binary "$SF" surfaceflinger)
    grep -aq com.microsoft.wsa.taskid "$SFBIN" || { echo "$SF has no patch 0006" >&2; exit 1; }
    cp "$SFBIN" "$S/bin/surfaceflinger"; chmod 0755 "$S/bin/surfaceflinger"
fi

if [ -n "$INIT" ]; then
    # Android 17 moved the first stage out of /system/bin/init (a separate binary in the ramdisk), but WSA runs
    # /init -> /system/bin/init as PID 1 straight from the system disk: the stock one aborts at once (11.10.2026,
    # GSI CP2A, with and without Magisk). Ours carries a17/0021 (first stage back in /system/bin/init).
    step "init (a17/0021: first stage in /system/bin/init)"
    INITBIN=$(our_binary "$INIT" init)
    cp "$INITBIN" "$S/bin/init"; chmod 0755 "$S/bin/init"
fi

step "ARM translation (libhoudini $(basename "$HOUDINI"))"
# Same layout as bin/add-houdini.sh (copies in system_ext, links from system — see why there), with
# version 1.3 from the start (bin/replace-houdini.sh, #793). The vendor's binfmt_misc and properties are Microsoft's.
for d in lib/arm lib64/arm64; do
    [ ! -e "$S/$d" ] || { echo "the GSI already has /system/$d" >&2; exit 1; }
    mkdir -p "$SE/$d" && cp "$HOUDINI/$d"/* "$SE/$d/"
done
mkdir -p "$SE/bin"
cp "$HOUDINI/lib/libhoudini.so" "$SE/lib/"; cp "$HOUDINI/lib64/libhoudini.so" "$SE/lib64/"
cp "$HOUDINI/bin/houdini" "$HOUDINI/bin/houdini64" "$SE/bin/"
find "$SE/lib/arm" "$SE/lib64/arm64" -type f -exec chmod 0644 {} +
chmod 0644 "$SE/lib/libhoudini.so" "$SE/lib64/libhoudini.so"; chmod 0755 "$SE/bin/houdini" "$SE/bin/houdini64"
for l in lib64/libhoudini.so lib/libhoudini.so lib64/arm64 lib/arm bin/houdini bin/houdini64; do
    ln -s "/system_ext/$l" "$S/$l"
done

step "product: privapp, adb_keys"
printf '\n# WSA path A: Magisk GApps modules put Google apps into /product whose privapp-permissions are not in this image\nro.control_privapp_permissions=disable\n' >> "$P/etc/build.prop"
mkdir -p "$P/etc/security" && cp "$HOME/.android/adbkey.pub" "$P/etc/security/adb_keys"
# /product/etc/security/adb_keys is read only by newer adbd (AP4A has it, AP3A does not); /adb_keys in the
# system root is read by every version and plat_file_contexts labels it adb_keys_file.
# Newer GSIs (A16) ship /adb_keys as a link to the product path already — then nothing to do.
[ -L "$SPLIT/system/adb_keys" ] || cp "$HOME/.android/adbkey.pub" "$SPLIT/system/adb_keys"

step "pack"
# apksigner from build-tools 33+ writes a v4 signature next to the apk; an .idsig inside a partition makes
# PackageManager reject the package ("Failed to collect certificates … APK Signature Scheme v4",
# 11.10.2026: the RRO was dropped and system_server crash-looped on WsaDisplayAreaPolicyProvider).
idsig=$(find "$SE/overlay" "$SE/priv-app" -name '*.idsig')
[ -z "$idsig" ] || { echo "v4 signature files in the tree:" >&2; echo "$idsig" >&2; exit 1; }
FC="$S/etc/selinux/plat_file_contexts"
for p in system system_ext product; do
    MKUSERIMG=$MKUSERIMG FILE_CONTEXTS=$FC "$HERE/bin/pack-vhdx.sh" "$SPLIT/$p" "$p" "$OUT" 2>&1 | grep -E "^==|rror" || true
    [ -s "$OUT/$p.vhdx" ] || { echo "packing $p failed" >&2; exit 1; }
done
for p in system_ext product; do qemu-img convert -f vhdx -O raw "$OUT/$p.vhdx" "$OUT/$p.img"; done
# The houdini binaries are the binfmt_misc interpreters run in the app's domain: 0755 root:shell, as
# bin/add-houdini.sh sets them (packing takes owners/modes from its own defaults, not from the tree).
for b in houdini houdini64; do
    debugfs -w -R "sif /bin/$b mode 0100755" "$OUT/system_ext.img" >/dev/null 2>&1
    debugfs -w -R "sif /bin/$b gid 2000" "$OUT/system_ext.img" >/dev/null 2>&1
    debugfs -R "stat /bin/$b" "$OUT/system_ext.img" 2>/dev/null | grep -q "Mode:  0755" \
        || { echo "/system_ext/bin/$b mode not set" >&2; exit 1; }
done
# plat_file_contexts of a GSI has no rule for /product/etc/security/adb_keys, so packing labels it
# system_file — adbd cannot read it and asks for authorization (11.10.2026). Set the label explicitly.
printf 'u:object_r:adb_keys_file:s0\0' > "$OUT/adb_keys.ctx"
debugfs -w -R "ea_set -f $OUT/adb_keys.ctx /etc/security/adb_keys security.selinux" "$OUT/product.img" 2>&1 | grep -v '^debugfs' || true
debugfs -R "ea_get /etc/security/adb_keys security.selinux" "$OUT/product.img" 2>/dev/null | grep -aq adb_keys_file \
    || { echo "adb_keys label not set" >&2; exit 1; }

step "transplant (Microsoft SystemApp, CIL) + logcat channel"
"$HERE/bin/transplant-a14.sh" --img "$OUT/system_ext.img" --out "$OUT/system_ext.slot.img" \
    --wsa-sysext "$WSA_SYSEXT" --secdir "$KEYS" 2>&1 | grep -E "^==|rror|WARN" || true
e2fsck -fy "$OUT/system_ext.slot.img" >/dev/null 2>&1 || true
e2fsck -fn "$OUT/system_ext.slot.img" >/dev/null || { echo "system_ext.slot.img is not clean" >&2; exit 1; }
rm -rf "${OUT:?}/slot"
"$HERE/bin/add-logcat-forwarder.sh" --out "$OUT/slot" --product "$OUT/product.img" --sysext "$OUT/system_ext.slot.img" \
    --wsa-product "$WSA_PRODUCT" 2>&1 | grep -aE "^--|rror|partition" || true

step "slot files → $SLOT_DIR (*$SUFFIX)"
cp "$OUT/system.vhdx" "$SLOT_DIR/system.vhdx$SUFFIX"
cp "$OUT/slot/system_ext.vhdx" "$SLOT_DIR/system_ext.vhdx$SUFFIX"
cp "$OUT/slot/product.vhdx" "$SLOT_DIR/product.vhdx$SUFFIX"
cmp "$OUT/system.vhdx" "$SLOT_DIR/system.vhdx$SUFFIX"
cmp "$OUT/slot/system_ext.vhdx" "$SLOT_DIR/system_ext.vhdx$SUFFIX"
cmp "$OUT/slot/product.vhdx" "$SLOT_DIR/product.vhdx$SUFFIX"
ls -la "$SLOT_DIR"/*"$SUFFIX"
if [ "$KEEP" = 0 ]; then
    rm -rf "${SPLIT:?}" "${FW:?}" "${T:?}" "${ST:?}" "${SU:?}" "${R:?}" "${OUT:?}" "$WORK/svc" "$WORK/helper"
    [ "$IMG" = "$WORK/system.img" ] && rm -f "$WORK/system.img"
fi
echo "done: slot files *$SUFFIX in $SLOT_DIR"
