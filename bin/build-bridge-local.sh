#!/usr/bin/env bash
# Build WsaShellBridge.apk LOCALLY — without an AOSP tree and without the build VPS, in a few seconds.
#
#   bin/build-bridge-local.sh                    — build and sign the apk
#   bin/build-bridge-local.sh --out <file.apk>   — put it at the given location
#
# WHY. The canonical way is `m WsaShellBridge` in the tree (that is how every slot is built). But on 24.09.2026
# editing a single java file cost twenty minutes: soong on the VPS analyses the whole tree before building,
# took 12 GB out of 15 GB of RAM and went into swap, spending a third of the time waiting on disk. For
# work on RCM channels, where the edit-test loop repeats dozens of times, that is unacceptable.
#
# The local build is NOT a home-made imitation of the tree: it uses the tree's own tools and artifacts
# — javac from prebuilts/jdk/jdk21, protoc (aprotoc) from out/host, framework.jar (that is
# out/target/common/obj/JAVA_LIBRARIES/framework-minus-apex_intermediates/classes.jar, the platform's INTERNAL
# API, not android.jar from the SDK), libprotobuf-java-lite.jar from the same out and
# platform.pk8/x509 from build/target/product/security. All of this is downloaded once from a machine that
# has the tree (see below), after which the build runs offline.
#
# WHAT TO PUT INTO $TOOLS ONCE (default ~/Android/tools):
#   jdk21/                        — prebuilts/jdk/jdk21/linux-x86 from the tree (337 MB);
#   aosp14/framework.jar          — that very classes.jar, RENAMED;
#   aosp14/libprotobuf-java-lite.jar
#                                 — out/soong/.intermediates/external/protobuf/
#                                   libprotobuf-java-lite/android_common/javac/…;
#   aosp14/framework-location.jar — out/soong/.intermediates/frameworks/base/location/
#                                   framework-location/android_common/<hash>/javac/framework-location.jar
#                                   (android.location.provider.* — geocoder, OsmGeocodeService; 06.10.2026);
#   aosp14/aprotoc                — out/host/linux-x86/bin/aprotoc (the version MUST match the
#                                   runtime: both are 3.21.12 here).
# Plus the Android SDK (build-tools and platforms/android-34) and keys $WSA_KEYS/platform.{pk8,x509.pem}.
#
# PITFALLS, each cost an attempt on 24.09.2026:
#   * d8 from build-tools 34.0.0 crashes on the bridge's anonymous classes with an internal error
#     ("NullPointerException: Cannot invoke String.length()"); d8 from 36.0.0 builds them silently —
#     so the NEWEST installed build-tools version is chosen, not just any;
#   * ecj cannot be used: generated protobuf-javalite code contains a cast
#     that javac accepts (unchecked) but ecj considers impossible — 48 errors out of nowhere.
#     It has to be javac, which is why the JDK is taken from the tree;
#   * android.jar from the SDK goes into the classpath AFTER framework.jar: framework.jar lacks
#     android.system.Os/OsConstants/ErrnoException (they come from libcore), android.jar has them;
#   * classes.dex is added to the apk with python: the zip utility may be missing, and aapt2 link cannot add dex;
#   * the apk version (--version-code) must match what the tree uses (34), otherwise
#     installing over the system version fails as a downgrade.
#
# Verified: an apk built this way works live in a slot (slot aosp14-vanilla-bridge, 24.09.2026) —
# clipboard in both directions, windows, input.
set -euo pipefail
. "$(dirname "$0")/wsa-env.sh"

HERE=$(cd "$(dirname "$0")/.." && pwd)
BRIDGE="$HERE/device/microsoft/wsa_x86_64/WsaShellBridge"
TOOLS="${TOOLS:-$HOME/Android/tools}"
SDK="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-$HOME/Android/Sdk}}"
KEYS="${KEYS:-$WSA_KEYS}"
API=34
OUT_APK=""
[ "${1:-}" = "--out" ] && OUT_APK="${2:?path to apk}"

JAVAC="$TOOLS/jdk21/bin/javac"
JAR="$TOOLS/jdk21/bin/jar"
APROTOC="$TOOLS/aosp14/aprotoc"
FRAMEWORK="$TOOLS/aosp14/framework.jar"
LOCATION="$TOOLS/aosp14/framework-location.jar"
PROTOLITE="$TOOLS/aosp14/libprotobuf-java-lite.jar"
ANDROID_JAR="$SDK/platforms/android-$API/android.jar"
BT=$(ls -1d "$SDK"/build-tools/* 2>/dev/null | sort -V | tail -1)

for f in "$JAVAC" "$APROTOC" "$FRAMEWORK" "$LOCATION" "$PROTOLITE" "$ANDROID_JAR" \
         "$KEYS/platform.pk8" "$KEYS/platform.x509.pem"; do
    [ -r "$f" ] || { echo "missing: $f — see the script header" >&2; exit 1; }
done
[ -n "$BT" ] && [ -x "$BT/aapt2" ] || { echo "build-tools not found in $SDK" >&2; exit 1; }

WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT
mkdir -p "$WORK/gen" "$WORK/classes" "$WORK/out"

echo "-- protobuf schemas ($("$APROTOC" --version))"
"$APROTOC" --proto_path="$BRIDGE/proto" --java_out=lite:"$WORK/gen" "$BRIDGE"/proto/*.proto

echo "-- aidl"
"$BT/aidl" -I"$BRIDGE/aidl" -o"$WORK/gen" "$BRIDGE/aidl/windows/services/rcm/IRemoteConnectionManager.aidl"

echo "-- javac ($("$JAVAC" -version 2>&1))"
"$JAVAC" -nowarn -source 17 -target 17 \
    -cp "$FRAMEWORK:$LOCATION:$PROTOLITE:$ANDROID_JAR" -d "$WORK/classes" \
    $(find "$BRIDGE/src" "$WORK/gen" -name '*.java')

echo "-- dex ($(basename "$BT"))"
"$JAR" cf "$WORK/out/bridge-classes.jar" -C "$WORK/classes" .
"$BT/d8" --release --min-api "$API" --lib "$FRAMEWORK" --lib "$LOCATION" --lib "$ANDROID_JAR" \
    --output "$WORK/out" "$WORK/out/bridge-classes.jar" "$PROTOLITE"

echo "-- resources, manifest and signing"
# Resources (res/xml/method.xml of the "TSF" channel input method) — as in the tree: resource_dirs defaults
# to "res". The bridge does not need R classes — only the manifest references resources.
RES=()
if [ -d "$BRIDGE/res" ]; then
    "$BT/aapt2" compile --dir "$BRIDGE/res" -o "$WORK/res.zip"
    RES=("$WORK/res.zip")
fi
"$BT/aapt2" link -o "$WORK/out/base.apk" --manifest "$BRIDGE/AndroidManifest.xml" \
    -I "$ANDROID_JAR" --min-sdk-version "$API" --target-sdk-version "$API" \
    --version-code "$API" --version-name 14 "${RES[@]}"
python3 - "$WORK/out" <<'PY'
import shutil, sys, zipfile
out = sys.argv[1]
shutil.copy(f"{out}/base.apk", f"{out}/unsigned.apk")
with zipfile.ZipFile(f"{out}/unsigned.apk", "a", zipfile.ZIP_DEFLATED) as z:
    z.write(f"{out}/classes.dex", "classes.dex")
PY
"$BT/zipalign" -f -p 4 "$WORK/out/unsigned.apk" "$WORK/out/WsaShellBridge.apk"
"$BT/apksigner" sign --key "$KEYS/platform.pk8" --cert "$KEYS/platform.x509.pem" \
    "$WORK/out/WsaShellBridge.apk"
"$BT/apksigner" verify "$WORK/out/WsaShellBridge.apk"

DEST="${OUT_APK:-$HERE/out/WsaShellBridge.apk}"
mkdir -p "$(dirname "$DEST")"
cp -f "$WORK/out/WsaShellBridge.apk" "$DEST"
echo "-- done: $DEST ($(stat -c%s "$DEST") bytes)"
echo
echo "IF PERMISSIONS CHANGED — also install the allowlist, otherwise system_server will not survive"
echo "parsing the package: bin/patch-system-file.sh <system_ext.img> /etc/permissions/WsaShellBridge.xml \\"
echo "                 device/microsoft/wsa_x86_64/WsaShellBridge/privapp-permissions-wsashellbridge.xml"
echo
echo "Put it into a slot (the image must be writable):"
echo "  bin/patch-system-file.sh <system_ext.img> /priv-app/WsaShellBridge/WsaShellBridge.apk $DEST"
