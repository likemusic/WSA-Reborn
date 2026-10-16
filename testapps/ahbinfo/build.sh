#!/usr/bin/env bash
# Builds ahbinfo for x86_64 Android with the newest NDK in ~/Android/Sdk/ndk. 16.10.2026.
set -euo pipefail
here=$(cd "$(dirname "$0")" && pwd)
ndk=$(ls -d "$HOME"/Android/Sdk/ndk/* | sort -V | tail -1)
mkdir -p "$here/out"
"$ndk"/toolchains/llvm/prebuilt/linux-x86_64/bin/x86_64-linux-android30-clang -O2 -o "$here/out/ahbinfo" "$here/ahbinfo.c" -lnativewindow -ldl
echo "$here/out/ahbinfo"
