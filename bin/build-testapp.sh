#!/usr/bin/env bash
# Build a small test app WITHOUT an AOSP tree, without gradle and without network — using only
# the Android SDK tools already installed on the machine.
#
#   bin/build-testapp.sh testapps/ClipTest            — builds testapps/ClipTest/out/ClipTest.apk
#   bin/build-testapp.sh testapps/ClipTest --install  — and installs it on the connected WSA
#
# The app directory is as simple as it gets: AndroidManifest.xml + src/**/*.java, no resources
# (a test app has nothing to draw — it writes to logcat). Signed with our own key, which
# is created right here on the first build: such an app needs no system permissions.
#
# WHY. RCM channels can only be tested live, and each needs someone on the Android side
# to poke the system service: for "Clipboard" — put something into the clipboard and read it. This cannot be
# done from adb shell (ClipboardService in Android 14 has no shell command — checked in the tree sources,
# frameworks/base/services/core/java/com/android/server/clipboard/ClipboardService.java), and running
# a full tree build on the VPS for twenty lines costs eight minutes per edit.
#
# WHAT IT IS BUILT WITH AND WHY EXACTLY SO (pitfalls of 24.09.2026):
#   * the compiler is ecj (Eclipse), because this machine has a JRE without javac. If javac is present,
#     the script uses it. Download ecj: https://repo1.maven.org/maven2/org/eclipse/jdt/ecj/3.33.0/
#     ecj-3.33.0.jar → $ECJ (default ~/Android/tools/ecj.jar);
#   * -source 8 -target 8: with -source 17 ecj complains "The package java.lang is accessible from
#     more than one module" and fails the build with hundreds of errors;
#   * android.jar goes into -cp, NOT into -bootclasspath: otherwise
#     java.lang.invoke.LambdaMetafactory is not found and lambdas and method references do not compile;
#   * dex is added to the apk with python: the zip utility may be missing, and aapt2 link cannot
#     add classes.dex;
#   * apksigner signs with the v3 scheme; zipalign goes BEFORE signing, otherwise the signature breaks.
set -euo pipefail

APP_DIR="${1:?app directory, e.g. testapps/ClipTest}"
INSTALL=0
[ "${2:-}" = "--install" ] && INSTALL=1

APP_DIR=$(cd "$APP_DIR" && pwd)
NAME=$(basename "$APP_DIR")
[ -r "$APP_DIR/AndroidManifest.xml" ] || { echo "no $APP_DIR/AndroidManifest.xml" >&2; exit 1; }

SDK="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-$HOME/Android/Sdk}}"
API="${API:-34}"
ANDROID_JAR="$SDK/platforms/android-$API/android.jar"
[ -r "$ANDROID_JAR" ] || { echo "no $ANDROID_JAR — install the API $API platform" >&2; exit 1; }

# The newest installed build-tools version.
BT=$(ls -1d "$SDK"/build-tools/* 2>/dev/null | sort -V | tail -1)
[ -n "$BT" ] && [ -x "$BT/aapt2" ] || { echo "build-tools not found in $SDK" >&2; exit 1; }

ECJ="${ECJ:-$HOME/Android/tools/ecj.jar}"
OUT="$APP_DIR/out"
rm -rf "$OUT"; mkdir -p "$OUT/classes"

echo "-- compiling (API $API, $(basename "$BT"))"
mapfile -t SRCS < <(find "$APP_DIR/src" -name '*.java')
[ "${#SRCS[@]}" -gt 0 ] || { echo "no sources in $APP_DIR/src" >&2; exit 1; }
if command -v javac >/dev/null; then
    javac -nowarn -source 8 -target 8 -cp "$ANDROID_JAR" -d "$OUT/classes" "${SRCS[@]}"
else
    [ -r "$ECJ" ] || { echo "neither javac nor ecj ($ECJ) found — see the script header" >&2; exit 1; }
    java -jar "$ECJ" -nowarn -source 8 -target 8 -cp "$ANDROID_JAR" -d "$OUT/classes" "${SRCS[@]}"
fi

echo "-- dex"
"$BT/d8" --release --min-api "$API" --output "$OUT" $(find "$OUT/classes" -name '*.class')

echo "-- manifest and resources"
"$BT/aapt2" link -o "$OUT/base.apk" --manifest "$APP_DIR/AndroidManifest.xml" \
    -I "$ANDROID_JAR" --min-sdk-version "$API" --target-sdk-version "$API"

# Native library (optional): jni/*.c are built with the NDK into lib/<abi>/lib<name>.so for each
# ABI from jni/ABIS (one per line; default arm64-v8a) — this is how an "ARM-only" app is made
# to test libhoudini translation. The library name is the app directory name in lower case.
if [ -d "$APP_DIR/jni" ]; then
    NDK="${NDK:-$(ls -1d "$SDK"/ndk/* 2>/dev/null | sort -V | tail -1)}"
    LIBNAME=$(echo "$NAME" | tr '[:upper:]' '[:lower:]')
    ABIS=$(cat "$APP_DIR/jni/ABIS" 2>/dev/null || echo arm64-v8a)
    for abi in $ABIS; do
        case "$abi" in
            arm64-v8a) triple=aarch64-linux-android ;; armeabi-v7a) triple=armv7a-linux-androideabi ;;
            x86_64) triple=x86_64-linux-android ;; x86) triple=i686-linux-android ;;
            *) echo "unknown ABI: $abi" >&2; exit 1 ;;
        esac
        echo "-- native: $abi"
        mkdir -p "$OUT/lib/$abi"
        "$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/clang" --target="${triple}${API}" -shared -fPIC -O2 \
            -o "$OUT/lib/$abi/lib$LIBNAME.so" "$APP_DIR"/jni/*.c -llog
    done
fi

python3 - "$OUT" <<'PY'
import os, shutil, sys, zipfile
out = sys.argv[1]
shutil.copy(f"{out}/base.apk", f"{out}/unsigned.apk")
with zipfile.ZipFile(f"{out}/unsigned.apk", "a", zipfile.ZIP_DEFLATED) as z:
    z.write(f"{out}/classes.dex", "classes.dex")
    # .so — uncompressed (zipalign -p page-aligns them) so they load straight from the apk.
    for root, _, files in os.walk(f"{out}/lib"):
        for f in files:
            path = os.path.join(root, f)
            z.write(path, os.path.relpath(path, out), compress_type=zipfile.ZIP_STORED)
PY

KEY="$HOME/Android/tools/testapp.jks"
if [ ! -r "$KEY" ]; then
    echo "-- creating signing key $KEY"
    mkdir -p "$(dirname "$KEY")"
    keytool -genkeypair -keystore "$KEY" -storepass android -keypass android -alias testapp \
        -keyalg RSA -keysize 2048 -validity 3650 -dname "CN=WSA-Reborn testapp" >/dev/null
fi

"$BT/zipalign" -f -p 4 "$OUT/unsigned.apk" "$OUT/$NAME.apk"
# PLATFORM_KEYS=<dir with platform.pk8 + platform.x509.pem> — sign with the platform key (for
# system modules like device/.../WsaPackageVerifier; public APIs are enough for them).
if [ -n "${PLATFORM_KEYS:-}" ]; then
    "$BT/apksigner" sign --key "$PLATFORM_KEYS/platform.pk8" --cert "$PLATFORM_KEYS/platform.x509.pem" \
        "$OUT/$NAME.apk"
else
    "$BT/apksigner" sign --ks "$KEY" --ks-pass pass:android --key-pass pass:android "$OUT/$NAME.apk"
fi
echo "-- done: $OUT/$NAME.apk ($(stat -c%s "$OUT/$NAME.apk") bytes)"

if [ "$INSTALL" = 1 ]; then
    ADB=$(command -v adb || echo "$SDK/platform-tools/adb")
    "$ADB" install -r "$OUT/$NAME.apk"
fi
