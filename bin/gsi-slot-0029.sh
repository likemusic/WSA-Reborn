#!/usr/bin/env bash
# 0029 (a freeform task that became visible without a transition gets its leash position and crop — otherwise an app
# with a cold start over 5 s draws in its window but input misses it) into an already built path-A slot (aosp1x-gsi)
# without rebuilding it: the SystemUI.apk step of bin/build-gsi-slot.sh (FreeformSettlePatch on FreeformTaskListener,
# helper com.wsa.gsi.WsaSettle as an extra classesN.dex), applied to the slot's system_ext.vhdx through
# bin/patch-slots.sh (previous files on $WORK, stale oat removed). A slot that already has WsaSettle is left alone.
#
#   bin/gsi-slot-0029.sh <slot> [<slot>…]
#
# Does not touch the live files: afterwards bin/switch-build.sh <slot>.
set -euo pipefail
R=$(cd "$(dirname "$0")/.." && pwd); cd "$R"
[ $# -ge 1 ] || { echo "usage: $0 <slot>…" >&2; exit 2; }
. bin/wsa-env.sh
wsa_require WSA_INSTALL_DIR
HERE=$R
TOOLS="${TOOLS:-$HOME/Android/tools}"
JAVA="${JAVA:-$TOOLS/jdk21/bin/java}"
BT="${BT:-$(wsa_build_tools)}"
ANDROID_JAR="${ANDROID_JAR:-$(dirname "$(dirname "$BT")")/platforms/android-34/android.jar}"
KEYS="${KEYS:-$WSA_KEYS}"
eval "$(sed -n '/^repack_zip() {/,/^}/p;/^dexpatch_jar() {/,/^}/p;/^build_helpers() {/,/^}/p;/^add_helper() {/,/^}/p' bin/build-gsi-slot.sh)"
WORK=$(mktemp -d "$WSA_STORAGE/gsi-slot-0029.XXXX")
trap 'rm -rf "${WORK:?}"' EXIT
build_helpers >/dev/null

for slot in "$@"; do
    f="$WSA_INSTALL_DIR/system_ext.vhdx.$slot"
    [ -r "$f" ] || { echo "no slot file $f" >&2; exit 1; }
    W="$WORK/$slot"; mkdir -p "$W"
    timeout 900 qemu-img convert -f vhdx -O raw "$f" "$W/system_ext.img"
    debugfs -R "dump /priv-app/SystemUI/SystemUI.apk $W/SystemUI.apk" "$W/system_ext.img" 2>/dev/null
    rm -f "$W/system_ext.img"
    [ -s "$W/SystemUI.apk" ] || { echo "$slot: no /priv-app/SystemUI/SystemUI.apk" >&2; exit 1; }
    mkdir -p "$W/jar" && (cd "$W/jar" && unzip -q "$W/SystemUI.apk" 'classes*.dex')
    if grep -qa 'Lcom/wsa/gsi/WsaSettle;' "$W"/jar/classes*.dex; then echo "$slot: already has 0029 (WsaSettle)"; continue; fi
    SU="$W/sysui"; mkdir -p "$SU/dex"
    dexpatch_jar "$W/SystemUI.apk" "$SU/dex" FreeformSettlePatch
    add_helper "$W/SystemUI.apk" "$SU/dex" WsaSettle
    repack_zip "$W/SystemUI.apk" "$SU/aligned.apk" "$SU/dex"
    "$BT/apksigner" sign --v4-signing-enabled false --key "$KEYS/platform.pk8" --cert "$KEYS/platform.x509.pem" \
        --out "$W/SystemUI.new.apk" "$SU/aligned.apk"
    bin/patch-slots.sh "/priv-app/SystemUI/SystemUI.apk=$W/SystemUI.new.apk" -- "$slot" | tail -2
done
