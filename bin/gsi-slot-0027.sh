#!/usr/bin/env bash
# Patch 0027 (a window launched from Windows cascades 75 dp from the topmost one) into an already built path-A slot
# (aosp1x-gsi) without rebuilding it: the same two services.jar steps bin/build-gsi-slot.sh makes (InvokeToStaticPatch
# on TaskLaunchParamsModifier.calculate + helper WsaCascade as the next classesN.dex), applied to the slot's system.vhdx.
# For slots built before 0027 went into build-gsi-slot.sh.
#
#   bin/gsi-slot-0027.sh <slot>
#
# The raw image is unpacked on $WSA_STORAGE (the system drive is usually short of space), only the jar is handled in
# WSL. The previous system.vhdx stays next to it as <file>.pre-0027. Does not touch the live files.
set -euo pipefail
R=$(cd "$(dirname "$0")/.." && pwd); cd "$R"
slot=${1:?slot}
. bin/wsa-env.sh
wsa_require WSA_INSTALL_DIR
P="$WSA_INSTALL_DIR/system.vhdx.$slot"
[ -r "$P" ] || { echo "no slot file $P" >&2; exit 1; }
IMG="$WSA_STORAGE/gsi-slot-0027.$$.img"
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
unzip -Z1 "$WORK/services.jar" | grep classes
# grep -c, not -q: with pipefail an early exit of grep kills unzip with SIGPIPE and the test reads as false.
if [ "$(unzip -p "$WORK/services.jar" 'classes*.dex' | grep -ac 'Lcom/wsa/gsi/WsaCascade;')" != 0 ]; then echo "$slot: 0027 already there"; exit 0; fi
build_helpers >/dev/null
SV="$WORK/svc"; mkdir -p "$SV"
dexpatch_jar "$WORK/services.jar" "$SV/dex" InvokeToStaticPatch 'Lcom/android/server/wm/TaskLaunchParamsModifier;' calculate getTaskBounds 'Lcom/wsa/gsi/WsaCascade;' getTaskBounds
add_helper "$WORK/services.jar" "$SV/dex" WsaCascade
repack_zip "$WORK/services.jar" "$SV/services.jar" "$SV/dex"
unzip -Z1 "$SV/services.jar" | grep classes
bin/patch-system-file.sh "$IMG" "$J" "$SV/services.jar" | tail -1
e2fsck -fy "$IMG" >/dev/null 2>&1 || [ $? -eq 1 ]
timeout 900 qemu-img convert -f raw -O vhdx "$IMG" "$WORK/system.vhdx"
qemu-img compare -f raw -F vhdx "$IMG" "$WORK/system.vhdx" >/dev/null
[ -e "$P.pre-0027" ] || { cp "$P" "$P.pre-0027" && cmp "$P" "$P.pre-0027"; }
for t in 1 2 3; do
    cp -f "$WORK/system.vhdx" "$P.new" 2>/dev/null && cmp -s "$WORK/system.vhdx" "$P.new" && { mv -f "$P.new" "$P"; echo "PUT-OK"; exit 0; }
    rm -f "$P.new"; sleep 10
done
echo "copy failed" >&2; exit 1
