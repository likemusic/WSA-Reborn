#!/usr/bin/env bash
# Runs a bin/dexpatch.sh patch over one jar inside an APEX module and re-signs the module — the path B slots
# have no tree to rebuild a module in any more, and path A needs the same for GSI modules.
#
#   bin/apex-dexpatch.sh <in.apex|in.capex> <jar inside /javalib> <out.apex> <PatchClass> [patch args…]
#   example (a15/0023 (docs/patches.md), Bluetooth on the first boot):
#     APEX_PAYLOAD_KEY=$WSA_KEYS/com.android.btservices.pem APEX_KEY=$WSA_KEYS/com.android.btservices.pk8 \
#     APEX_CERT=$WSA_KEYS/com.android.btservices.x509.pem \
#       bin/apex-dexpatch.sh com.android.btservices.apex service-bluetooth.jar out.apex BtBootNameFetchPatch
#
# The patch must match in exactly one classesN.dex of the jar. Keys — the module's own (AOSP modules are signed
# with public test keys, see bin/fetch-gsi-tools.sh); signing and the AVB footer — bin/patch-apex-jar.sh. A .capex
# comes out as a plain .apex: put it in place of the .capex. 11.10.2026.
set -euo pipefail
. "$(dirname "$0")/wsa-env.sh"
HERE=$(cd "$(dirname "$0")/.." && pwd)
SRC="${1:?in.apex}" JAR="${2:?jar name}" OUT="${3:?out.apex}" PATCH="${4:?PatchClass}"; shift 4
: "${APEX_PAYLOAD_KEY:?module payload key (.pem)}" "${APEX_KEY:?container key (.pk8)}" "${APEX_CERT:?container cert}"
BT="${BT:-$(ls -d "$HOME"/Android/Sdk/build-tools/* | sort -V | tail -1)}"
W=$(mktemp -d); trap 'rm -rf "${W:?}"' EXIT

case "$SRC" in
    *.capex) (cd "$W" && unzip -q -o "$(realpath "$SRC")" original_apex && mv original_apex in.apex);;
    *) cp "$SRC" "$W/in.apex";;
esac
(cd "$W" && unzip -q -o in.apex apex_payload.img)
debugfs -R "dump /javalib/$JAR $W/$JAR" "$W/apex_payload.img" 2>/dev/null
[ -s "$W/$JAR" ] || { echo "no /javalib/$JAR in $SRC" >&2; exit 1; }

mkdir -p "$W/dex" "$W/new"
(cd "$W/dex" && unzip -q -o "../$JAR" 'classes*.dex')
hits=0
for d in "$W"/dex/classes*.dex; do
    if "$HERE/bin/dexpatch.sh" "$PATCH" "$d" "$W/new/$(basename "$d")" "$@" >"$d.log" 2>&1; then
        hits=$((hits + 1)); echo "   $PATCH → $(basename "$d"): $(tail -1 "$d.log")"
    else
        rm -f "$W/new/$(basename "$d")"
    fi
done
[ "$hits" = 1 ] || { echo "$PATCH matched in $hits dex files of $JAR, expected 1" >&2; cat "$W"/dex/*.log >&2; exit 1; }

# Same entries, same compression (dex stays STORED), patched dex swapped in; then aligned.
python3 - "$W/$JAR" "$W/patched.unaligned" "$W/new" <<'PY'
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
"$BT/zipalign" -f -p 4 "$W/patched.unaligned" "$W/patched.jar"
export APEX_PAYLOAD_KEY APEX_KEY APEX_CERT
"$HERE/bin/patch-apex-jar.sh" "$W/in.apex" "$JAR" "$W/patched.jar" "$OUT" | grep -E "alignment|signed" || true
[ -s "$OUT" ] || { echo "patch-apex-jar.sh produced nothing" >&2; exit 1; }
echo "-- $OUT"
