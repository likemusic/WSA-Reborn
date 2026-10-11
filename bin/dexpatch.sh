#!/bin/bash
# Path A bytecode patches for ready GSI images (dexlib2 3.x).
#   bin/dexpatch.sh <PatchClass> <in.dex> <out.dex> [args...]
# Patch classes — bin/dexpatch/*.java (each file says what it does and which docs/patches diff it stands for);
# they are compiled on first use into a cache outside the repository. Libraries — in $TOOLS (bin/fetch-gsi-tools.sh):
# smali3/smali-dexlib2-3.0.10.jar, smali3/smali-util-3.0.10.jar, guava-31.1-jre.jar; JDK — $TOOLS/jdk21 or JDK.
# dexlib2 2.x does not do: it does not know the hidden-API flags of Android 15+ dex files and crashes rewriting them.
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
TOOLS="${TOOLS:-$HOME/Android/tools}"
JDK="${JDK:-$TOOLS/jdk21}"
CP="$TOOLS/smali3/smali-dexlib2-3.0.10.jar:$TOOLS/smali3/smali-util-3.0.10.jar:$TOOLS/guava-31.1-jre.jar"
for j in ${CP//:/ }; do [ -r "$j" ] || { echo "missing $j — run bin/fetch-gsi-tools.sh" >&2; exit 1; }; done
OUT="${XDG_CACHE_HOME:-$HOME/.cache}/wsa-reborn/dexpatch"
if [ ! -d "$OUT" ] || [ -n "$(find "$HERE/dexpatch" -maxdepth 1 -name '*.java' -newer "$OUT" 2>/dev/null)" ]; then
    rm -rf "${OUT:?}"; mkdir -p "$OUT"
    "$JDK/bin/javac" -cp "$CP" -d "$OUT" "$HERE"/dexpatch/*.java
fi
exec "$JDK/bin/java" -cp "$OUT:$CP" "$@"
