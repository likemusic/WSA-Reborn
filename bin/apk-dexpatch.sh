#!/usr/bin/env bash
# Runs a bin/dexpatch.sh patch over an apk and re-signs it — for slots built from a tree when the tree is gone
# (docs/vps-todo.md), and for anything else that needs one bytecode fix in one apk.
#
#   bin/apk-dexpatch.sh <in.apk> <out.apk> <key.pk8> <cert.x509.pem> <PatchClass> [patch args…]
#   example (a17/0024 into the aosp17-vanilla slot, Launcher3 is signed with the AOSP testkey):
#     bin/apk-dexpatch.sh Launcher3QuickStep.apk out.apk $WSA_KEYS/testkey.pk8 $WSA_KEYS/testkey.x509.pem \
#         NullFieldReturnPatch 'Lcom/android/launcher3/taskbar/TaskbarNavButtonController;' onRecentsButtonLayoutChanged mControllers
#
# The patch must match in exactly one classesN.dex. Entries keep their compression (dex/arsc stay STORED), zipalign
# -p 4, apksigner without v4 (a .idsig next to a system apk makes PackageManager reject it). Put the result into a
# slot with bin/patch-slots.sh (it also removes stale oat/x86_64/<apk>.{odex,vdex}). 12.10.2026.
set -euo pipefail
HERE=$(cd "$(dirname "$0")/.." && pwd)
IN="${1:?in.apk}" OUT="${2:?out.apk}" KEY="${3:?key.pk8}" CERT="${4:?cert.x509.pem}" PATCH="${5:?PatchClass}"; shift 5
BT="${BT:-$(ls -d "$HOME"/Android/Sdk/build-tools/* | sort -V | tail -1)}"
W=$(mktemp -d); trap 'rm -rf "${W:?}"' EXIT
mkdir -p "$W/dex" "$W/new"
(cd "$W/dex" && unzip -q -o "$(realpath "$IN")" 'classes*.dex')
hits=0
for d in "$W"/dex/classes*.dex; do
    if "$HERE/bin/dexpatch.sh" "$PATCH" "$d" "$W/new/$(basename "$d")" "$@" >"$d.log" 2>&1; then
        hits=$((hits + 1)); echo "   $PATCH → $(basename "$d"): $(tail -1 "$d.log")"
    else
        rm -f "$W/new/$(basename "$d")"
    fi
done
[ "$hits" = 1 ] || { echo "$PATCH matched in $hits dex files of $(basename "$IN"), expected 1" >&2; exit 1; }
python3 - "$IN" "$W/unaligned.apk" "$W/new" <<'PY'
import os, sys, zipfile
src, dst, repl = sys.argv[1:4]
z = zipfile.ZipFile(src)
with zipfile.ZipFile(dst, 'w') as out:
    for i in z.infolist():
        if i.filename.startswith('META-INF/') and i.filename.rsplit('.', 1)[-1] in ('SF', 'RSA', 'EC', 'DSA', 'MF'):
            continue   # old v1 signature
        p = os.path.join(repl, i.filename)
        data = b'' if i.is_dir() else (open(p, 'rb').read() if os.path.isfile(p) else z.read(i))
        zi = zipfile.ZipInfo(i.filename, date_time=i.date_time)
        zi.compress_type = i.compress_type; zi.external_attr = i.external_attr
        out.writestr(zi, data)
PY
"$BT/zipalign" -f -p 4 "$W/unaligned.apk" "$W/aligned.apk"
"$BT/apksigner" sign --v4-signing-enabled false --key "$KEY" --cert "$CERT" --out "$OUT" "$W/aligned.apk"
echo "-- $OUT"
