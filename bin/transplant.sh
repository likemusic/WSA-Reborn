#!/usr/bin/env bash
# Transplant Microsoft guest components into built LineageOS images.
#
#   bin/transplant.sh --wsa <WSA tree> --target <LineageOS tree> \
#                     --secdir <build>/build/target/product/security [--out <dir>]
#
#   the platform key is given in one of three ways:
#     --secdir <...>/security          — takes platform.pk8 + platform.x509.pem (the usual case)
#     --key platform.pk8 --cert ...pem — the same, by hand
#     --key keystore.jks + KEYPASS=... — a separate keystore
#
# Works on UNPACKED trees (see bin/unpack-image.sh), no root needed.
#
# Why it is built this way — from experiments 1 and 2 (docs/experiment-0*.md):
#
#   * signatures are checked per sharedUserId, and the cost of a mistake is not a rejected package but a
#     bootloop of the whole system. So ALL components are re-signed AT ONCE with one
#     key — the same one that signs the platform being built. A partial transplant
#     is impossible in principle;
#   * odex/vdex are invalid after re-signing and are removed: apps will run on JIT;
#   * WSA has no dm-verity, so image contents can be edited freely.
set -euo pipefail

WSA=""; TARGET=""; KEY=""; CERT=""; ALIAS="platform"; OUT=""; KEYPASS="${KEYPASS:-}"
SECDIR=""
while [ $# -gt 0 ]; do
    case "$1" in
        --wsa)    WSA="$2"; shift 2 ;;
        --target) TARGET="$2"; shift 2 ;;
        --key)    KEY="$2"; shift 2 ;;
        --cert)   CERT="$2"; shift 2 ;;
        --secdir) SECDIR="$2"; shift 2 ;;
        --alias)  ALIAS="$2"; shift 2 ;;
        --out)    OUT="$2"; shift 2 ;;
        *) echo "unknown argument: $1" >&2; exit 1 ;;
    esac
done

# The platform key can be given in three ways. The right one for our task is the second
# or the third: we must sign with THE SAME key that signs the built platform,
# otherwise packages in android.uid.system will not join the shared UID and the system will not boot
# (docs/experiment-02-shared-uid.md).
#
#   --secdir <tree>/build/target/product/security   — take platform.pk8 + platform.x509.pem
#   --key platform.pk8 --cert platform.x509.pem       — the same, by hand
#   --key keystore.jks (+ KEYPASS=...)                — keystore, for a separate key
if [ -n "$SECDIR" ]; then
    KEY="$SECDIR/${ALIAS}.pk8"
    CERT="$SECDIR/${ALIAS}.x509.pem"
fi

[ -n "$WSA" ] && [ -n "$TARGET" ] && [ -n "$KEY" ] || {
    sed -n '2,10p' "$0"; exit 1; }
OUT="${OUT:-$TARGET}"

BT="${BUILD_TOOLS:-$HOME/Android/Sdk/build-tools/34.0.0}"
[ -x "$BT/apksigner" ] || { echo "no apksigner in $BT (set BUILD_TOOLS=)" >&2; exit 1; }
[ -r "$KEY" ] || { echo "key is not readable: $KEY" >&2; exit 1; }

# The signing mode is determined by the key itself, not by flags: pk8 goes together with
# a certificate, keystore — with a password.
case "$KEY" in
    *.pk8)
        SIGN_MODE="pk8"
        [ -n "$CERT" ] || CERT="${KEY%.pk8}.x509.pem"
        [ -r "$CERT" ] || { echo "key $KEY needs a certificate; not found: $CERT" >&2; exit 1; }
        # AOSP keys are PKCS#8 DER without a password — apksigner accepts them as is.
        SIGN_ARGS=(--key "$KEY" --cert "$CERT")
        KEY_DESC="$KEY + $(basename "$CERT")"
        ;;
    *)
        SIGN_MODE="keystore"
        [ -n "$KEYPASS" ] || { echo "set the keystore password: KEYPASS=... $0 ..." >&2; exit 1; }
        SIGN_ARGS=(--ks "$KEY" --ks-pass "pass:$KEYPASS" --key-pass "pass:$KEYPASS" --ks-key-alias "$ALIAS")
        KEY_DESC="$KEY (alias $ALIAS)"
        ;;
esac

# What we transplant. The list comes from analysing image 2407 (docs/architecture.md).
# Paths are relative to the partition root.
APKS_SYSTEM_EXT="priv-app/SystemApp/SystemApp.apk
priv-app/UserApp/UserApp.apk
priv-app/SystemUIApp/SystemUIApp.apk
overlay/NetworkStackOverlay.apk
overlay/DocumentsUIOverlay.apk"

APKS_PRODUCT="app/HomeApp/HomeApp.apk"

# A FIFTH mandatory component, not in the lists above and NOT transplanted by this
# script: system/bin/surfaceflinger. It is a native binary (not apk/jar); that it
# carries Microsoft patches was unknown when this list was written
# (found only on 12.09.2026). Without it app windows are not
# shown on the host at all. It is transplanted by a separate tool:
#   bin/patch-system-file.sh <system.img image> /system/bin/surfaceflinger <Microsoft reference>
# For Android 14/LineageOS 21 there is no ready Microsoft binary — there it takes not a
# file transplant but a source patch, see docs/patches.md, fix 0006.

# Overlays in vendor are NOT touched: vendor stays entirely from WSA (Treble, see
# docs/treble-vendor-compatibility.md), so they also keep their own signatures.

FILES_SYSTEM_EXT="etc/permissions/privapp-permissions-windows.xml
etc/permissions/com.microsoft.windows.pdk.notifications.xml
etc/init/init.windows_x86_64.rc
bin/mount_shared_folder"

resign() {  # <apk file> — re-sign in place with the build key
    local apk="$1" tmp
    tmp="$(mktemp -d)"
    cp "$apk" "$tmp/in.apk"
    ( cd "$tmp" && zip -q -d in.apk 'META-INF/*' >/dev/null 2>&1 || true )
    "$BT/zipalign" -f 4 "$tmp/in.apk" "$tmp/aligned.apk"
    "$BT/apksigner" sign "${SIGN_ARGS[@]}" "$tmp/aligned.apk"
    mv "$tmp/aligned.apk" "$apk"
    rm -rf "$tmp"
}

copy_in() { # <partition> <relative path> — copy from WSA into the target tree
    # One declaration per line: in `local a=1 b=$a` the second variable does not yet see the first.
    local part="$1"
    local rel="$2"
    local src="$WSA/$part/$rel"
    local dst="$OUT/$part/$rel"
    [ -e "$src" ] || { echo "  SKIPPED (not in the WSA image): $part/$rel"; return 1; }
    mkdir -p "$(dirname "$dst")"
    cp -a "$src" "$dst"
    echo "  transplanted: $part/$rel"
}

echo "== Transplanting Microsoft components =="
echo "source (WSA):       $WSA"
echo "target (Lineage):   $OUT"
echo "key:                $KEY_DESC"
echo "signing mode:       $SIGN_MODE"
echo

moved=0; resigned=0; skipped=0

echo "-- system_ext: apps and overlays"
while read -r rel; do
    [ -n "$rel" ] || continue
    if copy_in system_ext "$rel"; then
        moved=$((moved+1))
        resign "$OUT/system_ext/$rel" && resigned=$((resigned+1))
        # odex/vdex next to the apk are invalid after re-signing
        oat="$(dirname "$OUT/system_ext/$rel")/oat"
        [ -d "$oat" ] && { rm -rf "$oat"; echo "    removed oat/ (re-signing invalidates odex)"; }
    else skipped=$((skipped+1)); fi
done <<< "$APKS_SYSTEM_EXT"

echo "-- product: apps"
while read -r rel; do
    [ -n "$rel" ] || continue
    if copy_in product "$rel"; then
        moved=$((moved+1))
        resign "$OUT/product/$rel" && resigned=$((resigned+1))
        oat="$(dirname "$OUT/product/$rel")/oat"
        [ -d "$oat" ] && { rm -rf "$oat"; echo "    removed oat/"; }
    else skipped=$((skipped+1)); fi
done <<< "$APKS_PRODUCT"

echo "-- system_ext: permissions, init, auxiliary files"
while read -r rel; do
    [ -n "$rel" ] || continue
    copy_in system_ext "$rel" && moved=$((moved+1)) || skipped=$((skipped+1))
done <<< "$FILES_SYSTEM_EXT"

echo
echo "files transplanted: $moved, apks re-signed: $resigned, skipped: $skipped"

# Microsoft components replace the stock ones rather than add to them: SystemUIApp
# declares the same package name com.android.systemui as SystemUI from LineageOS.
# PackageManager will not accept two apks with one name in a system partition, so
# the stock version must be removed — otherwise the WSA windowed mode will not work.
echo
echo "== Resolving package name conflicts =="
if [ -x "$BT/aapt2" ]; then
    tmp_pkgs="$(mktemp)"
    find "$OUT" -name '*.apk' 2>/dev/null | while read -r a; do
        n=$("$BT/aapt2" dump packagename "$a" 2>/dev/null)
        [ -n "$n" ] && echo "$n|$a"
    done | sort > "$tmp_pkgs"

    removed=0
    for rel in $APKS_SYSTEM_EXT $APKS_PRODUCT; do
        base="$(basename "$rel")"
        mine="$(grep "|.*/$base\$" "$tmp_pkgs" | head -1 | cut -d'|' -f2)"
        [ -n "$mine" ] || continue
        name="$(grep -F "|$mine" "$tmp_pkgs" | head -1 | cut -d'|' -f1)"
        [ -n "$name" ] || continue
        grep "^$name|" "$tmp_pkgs" | cut -d'|' -f2 | while read -r other; do
            [ "$other" = "$mine" ] && continue
            echo "  conflict on $name:"
            echo "    keeping   ${mine#$OUT}"
            echo "    removing  ${other#$OUT}"
            rm -rf "$(dirname "$other")"
        done
        removed=$((removed+1))
    done
    rm -f "$tmp_pkgs"
    echo "  transplanted packages checked: $removed"
else
    echo "  SKIPPED: no aapt2 in $BT — name conflicts not checked."
    echo "  This is dangerous: a duplicate package name in a system partition breaks boot."
fi

# Signature check. NOT the whole image must be checked: Android normally has
# different keys side by side (platform, media, shared), and a mismatch between different UIDs is safe.
# What is dangerous is a mismatch INSIDE one sharedUserId — exactly that brought down system_server in
# experiment 2. So we compare the members of android.uid.system and android.uid.systemui.
echo
echo "== Checking signatures inside shared UIDs =="
fps="$(mktemp)"; mtmp="$(mktemp)"
find "$OUT" -name '*.apk' 2>/dev/null | while read -r a; do
    7z x -so "$a" AndroidManifest.xml 2>/dev/null > "$mtmp" || continue
    uid=$(strings -el "$mtmp" 2>/dev/null | grep -m1 -E "^android\.uid\.(system|systemui)$")
    [ -n "$uid" ] || continue
    fp=$("$BT/apksigner" verify --print-certs "$a" 2>/dev/null \
         | awk '/SHA-256 digest/{print $NF; exit}')
    echo "${fp:-NO_SIGNATURE} $a"
done > "$fps"
rm -f "$mtmp"
awk '{print $1}' "$fps" | sort | uniq -c | sort -rn | while read -r n fp; do
    printf '  %4d apk  %s\n' "$n" "$fp"
done
distinct=$(awk '{print $1}' "$fps" | sort -u | wc -l)
if [ "$distinct" -le 1 ]; then
    echo "  all members of the shared UIDs are signed alike — as it should be"
else
    echo "  WARNING: $distinct fingerprints. Packages in a shared UID with different signatures = bootloop."
    echo "  Mismatch (one example per fingerprint):"
    awk '!seen[$1]++ {print "    " $1 "  " $2}' "$fps"
fi
rm -f "$fps"

echo
echo "Next: pack the partitions into vhdx and swap them into the WSA installation."


# --- the LineageOS launcher is redundant and harmful in WSA ------------------
# In WSA the home screen is drawn by Microsoft HomeApp; there is no navigation bar at all.
# Launcher3/Trebuchet meanwhile keeps crashing on navbar setup and
# steals focus, causing flicker and windows being created twice.
for d in "$DEST/system_ext/priv-app/TrebuchetQuickStep" \
         "$DEST/system_ext/priv-app/Launcher3QuickStep" \
         "$DEST/product/priv-app/TrebuchetQuickStep" \
         "$DEST/product/priv-app/Launcher3QuickStep"; do
    [ -d "$d" ] && { rm -rf "$d"; echo "removed LineageOS launcher: ${d#$DEST/}"; }
done

# --- Microsoft overlays go exactly where Microsoft keeps them ----------------
# NetworkStackOverlay and DocumentsUIOverlay live in system_ext/overlay. If
# they are also put into product/overlay, we get two RROs with one package
# name — PackageManager takes one and complains about the other, and the behaviour
# of the overlaid package becomes unpredictable. Remove duplicate overlays.
for f in NetworkStackOverlay.apk DocumentsUIOverlay.apk; do
    [ -f "$DEST/product/overlay/$f" ] && [ -f "$DEST/system_ext/overlay/$f" ] && {
        rm -f "$DEST/product/overlay/$f"
        echo "removed duplicate overlay: product/overlay/$f"
    }
done
