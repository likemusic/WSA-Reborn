#!/usr/bin/env bash
# Builds hwcomposer.wsa_headless.so with the NDK, without an Android tree (Android.bp is the tree build, the build machine):
#   device/microsoft/wsa_x86_64/headless-hwc/ndk-build.sh [out dir]   (default: ./out)
# The module needs only bionic, liblog and a dozen AOSP headers (libhardware, libcutils' native_handle.h, libsystem's
# graphics*.h), fetched once from android.googlesource.com into ~/.cache/wsa-reborn/aosp-headers-<tag>; ndk/include
# stands in for log/log.h and cutils/properties.h. Static libc++ and --exclude-libs, as the tree build: the vendor
# partition is frozen at VNDK v33. 16.10.2026.
set -euo pipefail
here=$(cd "$(dirname "$0")" && pwd)
out=${1:-$here/out}
tag=android-13.0.0_r83
cache=$HOME/.cache/wsa-reborn/aosp-headers-$tag
fetch() {  # <project> <path in project>
    local f=$cache/$1/$2
    [ -s "$f" ] && return
    mkdir -p "$(dirname "$f")"
    curl -fsSL "https://android.googlesource.com/platform/$1/+/refs/tags/$tag/$2?format=TEXT" | base64 -d > "$f.tmp"
    mv "$f.tmp" "$f"
}
for h in hardware.h hwcomposer2.h hwcomposer_defs.h gralloc.h fb.h; do fetch hardware/libhardware include/hardware/$h; done
fetch system/core libcutils/include/cutils/native_handle.h
for h in graphics.h graphics-base.h graphics-base-v1.0.h graphics-base-v1.1.h graphics-base-v1.2.h graphics-sw.h; do
    fetch system/core libsystem/include/system/$h
done
ndk=$(ls -d "$HOME"/Android/Sdk/ndk/* | sort -V | tail -1)
mkdir -p "$out"
"$ndk"/toolchains/llvm/prebuilt/linux-x86_64/bin/x86_64-linux-android30-clang++ -std=c++17 -O2 -fPIC -shared \
    -Wall -Werror -Wno-unused-parameter -fvisibility=hidden -fvisibility-inlines-hidden \
    -I"$here/ndk/include" -I"$cache/hardware/libhardware/include" -I"$cache/system/core/libcutils/include" \
    -I"$cache/system/core/libsystem/include" \
    -static-libstdc++ -Wl,--exclude-libs,ALL \
    -o "$out/hwcomposer.wsa_headless.so" "$here/headless_hwc.cpp" -llog
echo "$out/hwcomposer.wsa_headless.so"
