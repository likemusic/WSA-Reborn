#!/usr/bin/env bash
# TalkBack from open source (github.com/google/talkback) — turns the unsigned build apk into a
# system-app layout: apk without native libraries + lib/x86_64/*.so next to it.
#
#   bin/talkback-prepare.sh <talkback-…-release-unsigned.apk> <output dir>
#   -> <output>/talkback.apk, <output>/lib/x86_64/*.so
#
# WHY libraries next to the apk, not inside it. For a system app PackageManager determines the ABI from
# the lib/<abi> directory next to the apk, not from the apk contents: without the directory primaryCpuAbi=null, and
# TalkBack crashes at startup while loading Braille (UnsatisfiedLinkError: libbrlttywrap.so, 06.10.2026).
# The AOSP build lays out prebuilt apps the same way.
#
# Building the apk itself (06.10.2026, build VPS, ~2.5 min): docs/patches/talkback/README.md.
# Signed with our own key $WSA_KEYS/talkback.jks (not the platform key: TalkBack is a regular
# app in /system_ext/app, as in Microsoft's image).
set -euo pipefail
. "$(dirname "$0")/wsa-env.sh"
SRC="${1:?unsigned TalkBack build apk}"
OUT="${2:?output directory}"
KS="${TALKBACK_KS:-$WSA_KEYS/talkback.jks}"
BT="$(ls -d "${ANDROID_HOME:-$HOME/Android/Sdk}"/build-tools/* | sort -V | tail -1)"
[ -r "$KS" ] || { echo "no keystore $KS (password and alias — see talkback.README next to it)" >&2; exit 1; }

mkdir -p "$OUT/lib/x86_64"
TMP="$(mktemp -d -p "$OUT")"
trap 'rm -rf "$TMP"' EXIT
unzip -q -o "$SRC" 'lib/x86_64/*' -d "$TMP"
cp -f "$TMP"/lib/x86_64/*.so "$OUT/lib/x86_64/"
cp -f "$SRC" "$TMP/nolib.apk"
python3 - "$TMP/nolib.apk" <<'EOF'
import sys, zipfile, shutil
src = sys.argv[1]; dst = src + ".new"
with zipfile.ZipFile(src) as zi, zipfile.ZipFile(dst, "w") as zo:
    for item in zi.infolist():
        if item.filename.startswith("lib/") or item.filename.startswith("META-INF/"):
            continue
        zo.writestr(item, zi.read(item.filename), compress_type=item.compress_type)
shutil.move(dst, src)
EOF
"$BT/zipalign" -f -p 4 "$TMP/nolib.apk" "$TMP/aligned.apk"
"$BT/apksigner" sign --ks "$KS" --ks-pass pass:wsa-talkback --key-pass pass:wsa-talkback \
    --ks-key-alias talkback --v4-signing-enabled false --out "$OUT/talkback.apk" "$TMP/aligned.apk"
"$BT/apksigner" verify "$OUT/talkback.apk"
ls -la "$OUT/talkback.apk" "$OUT"/lib/x86_64/
