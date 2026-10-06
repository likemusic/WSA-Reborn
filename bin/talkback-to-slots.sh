#!/usr/bin/env bash
# Put the open-source TalkBack into the WORKING slots: /system_ext/app/talkback/talkback.apk and its
# native libraries (Braille) into /system_ext/lib64 — from the layout made by bin/talkback-prepare.sh.
#
# Why /system_ext/lib64 and not app/talkback/lib/x86_64 as AOSP lays it out. PackageManager derives a system
# app's ABI from the lib/<abi> directory only on the pool's first boot or when the build fingerprint
# changes; in lived-in pools (the stock TalkBack had no libraries) primaryCpuAbi stays null,
# and the directory is not picked up (06.10.2026). /system_ext/lib64, on the other hand, is always in library_path and permitted_path
# of the classloader namespace for an app from /system_ext — independent of the ABI. There are no name conflicts
# (libc++_shared, liblouis*, libbrlttywrap, libandroidx.graphics.path — none of them are in the images).
#
#   bin/talkback-to-slots.sh <slot> [<slot>…]          — default layout $WSA_STORAGE/wsa-talkback/system
#   TALKBACK_DIR=<dir> bin/talkback-to-slots.sh …
#
# Same package as Microsoft's stock build (com.google.android.accessibility.talkback), same service
# (com.google.android.marvin.talkback.TalkBackService) — the WSA toggle and Win+Ctrl+T via SystemApp
# enable it without changes. Mechanics — bin/patch-slots.sh.
set -euo pipefail
. "$(dirname "$0")/wsa-env.sh"
HERE=$(cd "$(dirname "$0")/.." && pwd)
SRC="${TALKBACK_DIR:-$WSA_STORAGE/wsa-talkback/system}"
[ -r "$SRC/talkback.apk" ] || { echo "missing $SRC/talkback.apk — run bin/talkback-prepare.sh" >&2; exit 1; }
PAIRS=("/app/talkback/talkback.apk=$SRC/talkback.apk")
for so in "$SRC"/lib/x86_64/*.so; do
    # remove first: a replaced file would keep its old label, while a new one gets system_lib_file
    PAIRS+=("/lib64/$(basename "$so")=" "/lib64/$(basename "$so")=$so")
    PAIRS+=("/app/talkback/lib/x86_64/$(basename "$so")=")   # layout of the first attempt on 06.10.2026, if present
done
PAIRS+=("/app/talkback/lib/x86_64=" "/app/talkback/lib=")
WORK="${WORK:-$WSA_STORAGE/wsa-talkback/work-$(date +%Y%m%d)}" exec "$HERE/bin/patch-slots.sh" "${PAIRS[@]}" -- "$@"
