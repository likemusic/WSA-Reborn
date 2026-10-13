#!/usr/bin/env bash
# Window cascade patches 0027 (a window launched from Windows cascades 75 dp from the topmost one) and 0028 (a window
# launched by a trampoline takes the trampoline task's place) into an already built path-A slot (aosp1x-gsi) without
# rebuilding it: the services.jar steps of bin/build-gsi-slot.sh (InvokeToStaticPatch on TaskLaunchParamsModifier.
# calculate for getTaskBounds and cascadeBounds, helper com.wsa.gsi.WsaCascade as an extra classesN.dex), applied to
# the slot's system.vhdx. Whatever the slot already has is kept: the helper dex is replaced by the current one, a call
# already redirected is left alone.
#
#   bin/gsi-slot-cascade.sh <slot>
#
# The raw image is unpacked on $WSA_STORAGE (the system drive is usually short of space), only the jar is handled in
# WSL. The previous system.vhdx stays next to it as <file>.pre-cascade (once). Does not touch the live files.
set -euo pipefail
R=$(cd "$(dirname "$0")/.." && pwd); cd "$R"
slot=${1:?slot}
. bin/wsa-env.sh
wsa_require WSA_INSTALL_DIR
P="$WSA_INSTALL_DIR/system.vhdx.$slot"
[ -r "$P" ] || { echo "no slot file $P" >&2; exit 1; }
IMG="$WSA_STORAGE/gsi-slot-cascade.$$.img"
WORK=$(mktemp -d)
trap 'rm -rf "${WORK:?}" "$IMG"' EXIT
HERE=$R
TOOLS="${TOOLS:-$HOME/Android/tools}"
JAVA="${JAVA:-$TOOLS/jdk21/bin/java}"
BT="${BT:-$(wsa_build_tools)}"
ANDROID_JAR="${ANDROID_JAR:-$(dirname "$(dirname "$BT")")/platforms/android-34/android.jar}"
eval "$(sed -n '/^repack_zip() {/,/^}/p;/^dexpatch_jar() {/,/^}/p;/^build_helpers() {/,/^}/p;/^add_helper() {/,/^}/p' bin/build-gsi-slot.sh)"

timeout 900 qemu-img convert -f vhdx -O raw "$P" "$IMG"
J=/system/framework/services.jar
debugfs -R "ls -l /system/framework" "$IMG" 2>/dev/null | grep -q ' services.jar$' || { echo "no $J in image" >&2; exit 1; }
echo "odex for services: $(debugfs -R "ls /system/framework/oat/x86_64" "$IMG" 2>/dev/null | tr -s ' ' '\n' | grep -c '^services' || true)"
debugfs -R "dump $J $WORK/services.jar" "$IMG" 2>/dev/null
TCLS='Lcom/android/server/wm/TaskLaunchParamsModifier;'
mkdir -p "$WORK/jar" && (cd "$WORK/jar" && unzip -q "$WORK/services.jar" 'classes*.dex')
helper_n=""; redirected=""
for d in "$WORK"/jar/classes*.dex; do
    # Streamed (the dump of a services dex runs to hundreds of MB); grep without -q reads to the end — no SIGPIPE.
    hits=$("$BT/dexdump" -d "$d" 2>/dev/null | grep -oE "Class descriptor  : 'Lcom/wsa/gsi/WsaCascade;'|Lcom/wsa/gsi/WsaCascade;\.(cascadeBounds|getTaskBounds|forAllRootTasks|forAllLeafTasks):" | sort -u || true)
    case "$hits" in *"Class descriptor"*) helper_n=$(basename "$d") ;; esac
    case "$hits" in *".cascadeBounds:"*) redirected="$redirected cascadeBounds" ;; esac
    case "$hits" in *".getTaskBounds:"*) redirected="$redirected getTaskBounds" ;; esac
    case "$hits" in *".forAllRootTasks:"*|*".forAllLeafTasks:"*) redirected="$redirected forAllTasks" ;; esac
done
echo "$slot: helper ${helper_n:-none}; redirected:${redirected:- none}"
build_helpers >/dev/null
SV="$WORK/svc"; mkdir -p "$SV/dex"
for m in getTaskBounds cascadeBounds; do
    case "$redirected" in *"$m"*) continue ;; esac
    dexpatch_jar "$WORK/services.jar" "$SV/dex" InvokeToStaticPatch "$TCLS" calculate "$m" 'Lcom/wsa/gsi/WsaCascade;' "$m"
done
# Conflict check: forAllRootTasks up to Android 16, forAllLeafTasks on 17.
case "$redirected" in *forAllTasks*) ;; *)
    dexpatch_jar "$WORK/services.jar" "$SV/dex" InvokeToStaticPatch "$TCLS" adjustBoundsToAvoidConflictInDisplayArea '*;.forAllRootTasks' 'Lcom/wsa/gsi/WsaCascade;' forAllRootTasks \
        || dexpatch_jar "$WORK/services.jar" "$SV/dex" InvokeToStaticPatch "$TCLS" adjustBoundsToAvoidConflictInDisplayArea '*;.forAllLeafTasks' 'Lcom/wsa/gsi/WsaCascade;' forAllLeafTasks ;;
esac
if [ -n "$helper_n" ]; then
    cmp -s "$HB/WsaCascade/classes.dex" "$WORK/jar/$helper_n" && [ -z "$(ls "$SV/dex")" ] && { echo "$slot: up to date"; exit 0; }
    cp "$HB/WsaCascade/classes.dex" "$SV/dex/$helper_n"; echo "   helper com.wsa.gsi.WsaCascade → $helper_n (replaced)"
else
    add_helper "$WORK/services.jar" "$SV/dex" WsaCascade
fi
repack_zip "$WORK/services.jar" "$SV/services.jar" "$SV/dex"
bin/patch-system-file.sh "$IMG" "$J" "$SV/services.jar" | tail -1
e2fsck -fy "$IMG" >/dev/null 2>&1 || [ $? -eq 1 ]
timeout 900 qemu-img convert -f raw -O vhdx "$IMG" "$WORK/system.vhdx"
qemu-img compare -f raw -F vhdx "$IMG" "$WORK/system.vhdx" >/dev/null
# Copies of 1.2 GB onto F:/G: hang over drvfs now and then (13.10.2026: cmp of the backup stood for 30 min while
# another session wrote to the same drive) — done by Windows itself and checked by MD5 on its side.
win() { timeout 1200 powershell.exe -NoProfile -Command "$1" | tr -d '\r'; }
md5w() { win "(Get-FileHash -Algorithm MD5 '$(wslpath -w "$1")').Hash"; }
copyw() {   # <src> <dst>
    win "Copy-Item -Force '$(wslpath -w "$1")' '$(wslpath -w "$2")'" >/dev/null
    local a b; a=$(md5w "$1"); b=$(md5w "$2")
    [ -n "$a" ] && [ "$a" = "$b" ] || { echo "copy $1 -> $2 does not match ($a / $b)" >&2; return 1; }
}
[ -e "$P.pre-cascade" ] || [ -e "$P.pre-0027" ] || copyw "$P" "$P.pre-cascade"
copyw "$WORK/system.vhdx" "$P.new" && mv -f "$P.new" "$P" && { echo "PUT-OK"; exit 0; }
rm -f "$P.new"; echo "copy into $P failed — slot file untouched" >&2; exit 1
