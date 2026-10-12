#!/usr/bin/env bash
# Patch 0026 (docs/patches/0026-window-default-geometry.diff) as bytecode: new freeform windows sized and oriented as on
# Microsoft's WSA — 1280x720 dp (portrait apps 480x720), apps without an orientation landscape, no letterbox cap.
#
#   bin/services-0026.sh <services.jar> <out.jar>
#
# Six edits in the dex that holds com.android.server.wm.LaunchParamsUtil (ConstReplacePatch ×5, InvokeRenamePatch ×1),
# then the jar is rewritten with the same entries and compression and zipaligned (framework jars are not signed). Put
# the result into a slot with PART=system bin/patch-slots.sh /system/framework/services.jar=<out.jar>
# /system/framework/oat/x86_64/services.art= -- <slot> (it also drops the stale services.odex/vdex). Android 14–16
# have the same code; on another version a missing literal stops the script. 12.10.2026.
set -euo pipefail
HERE=$(cd "$(dirname "$0")/.." && pwd)
in=$(realpath "${1:?services.jar}") out=${2:?out.jar}
BT="${BT:-$(ls -1d "$HOME"/Android/Sdk/build-tools/* | sort -V | tail -1)}"
W=$(mktemp -d); trap 'rm -rf "${W:?}"' EXIT
(cd "$W" && unzip -q -o "$in" 'classes*.dex')
U='Lcom/android/server/wm/LaunchParamsUtil;' T='Lcom/android/server/wm/TaskLaunchParamsModifier;'
d=
for f in "$W"/classes*.dex; do
    if "$HERE/bin/dexpatch.sh" DumpMethod "$f" "$W/x.dex" "$U" getDefaultFreeformSize >/dev/null 2>&1; then d=$f; break; fi
done
[ -n "$d" ] || { echo "LaunchParamsUtil.getDefaultFreeformSize not found in $in" >&2; exit 1; }
cp "$d" "$W/p.dex"
for r in "1064 1280" "600 720" "412 480" "732 720"; do
    "$HERE/bin/dexpatch.sh" ConstReplacePatch "$W/p.dex" "$W/n.dex" "$U" getDefaultFreeformSize $r | tail -1; mv "$W/n.dex" "$W/p.dex"
done
"$HERE/bin/dexpatch.sh" InvokeRenamePatch "$W/p.dex" "$W/n.dex" "$U" getDefaultFreeformSize 'Ljava/lang/Math;' min max 1 | tail -1; mv "$W/n.dex" "$W/p.dex"
"$HERE/bin/dexpatch.sh" ConstReplacePatch "$W/p.dex" "$W/n.dex" "$T" resolveOrientation/3 1 0 | tail -1; mv "$W/n.dex" "$W/p.dex"
mkdir -p "$W/repl"; mv "$W/p.dex" "$W/repl/$(basename "$d")"
python3 - "$in" "$W/u.jar" "$W/repl" <<'PY'
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
PY
"$BT/zipalign" -f -p 4 "$W/u.jar" "$out"
echo "0026: $(basename "$d") patched -> $out"
