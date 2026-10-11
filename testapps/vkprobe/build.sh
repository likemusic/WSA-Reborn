#!/usr/bin/env bash
# Builds testapps/vkprobe/vkprobe (x86_64, API 33) with the newest NDK in ~/Android/Sdk/ndk into out/vkprobe.
set -euo pipefail
HERE=$(cd "$(dirname "$0")/../.." && pwd)
NDK=$(ls -d "$HOME"/Android/Sdk/ndk/* | sort -V | tail -1)
mkdir -p "$HERE/out"
"$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/x86_64-linux-android33-clang" -O2 -o "$HERE/out/vkprobe" \
    "$HERE/testapps/vkprobe/vkprobe.c" -lvulkan
echo "$HERE/out/vkprobe"
