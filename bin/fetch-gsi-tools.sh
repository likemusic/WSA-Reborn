#!/usr/bin/env bash
# Download what path A (bin/build-gsi-slot.sh, bin/dexpatch.sh, bin/patch-apex-jar.sh) needs from public sources,
# into $TOOLS (default ~/Android/tools) and $WSA_KEYS. Files that are already there are not downloaded again.
#
#   bin/fetch-gsi-tools.sh
#
# Into $TOOLS:
#   smali3/smali-dexlib2-3.0.10.jar, smali3/smali-util-3.0.10.jar   Google Maven (dexlib2 3.x: 2.x crashes on the
#                                                                     hidden-API flags of Android 15+ dex files)
#   guava-31.1-jre.jar                                               Maven Central
#   apktool.jar (3.0.3)                                              github.com/iBotPeaches/Apktool
#   avbtool.py                                                       AOSP platform/external/avb
# Into $WSA_KEYS — the PUBLIC AOSP test keys (build/target/product/security and the module's own key); Google's
# GSI is signed with exactly these, which is why its Settings, SystemUI and APEX can be re-signed at all:
#   platform.pk8, platform.x509.pem, testkey.pk8, testkey.x509.pem, com.android.tethering.pem,
#   com.android.btservices.{pem,pk8,x509.pem} (a15/0023 re-signs that module on Android 14/15)
#
# Not downloadable, take them from a build tree once (docs/build.md, "Path A: a ready Google GSI"):
#   $TOOLS/hostbin/ (mkuserimg_mke2fs and what it calls), $TOOLS/jdk21 (or any JDK 17+ via JAVA/JDK).
set -euo pipefail
. "$(dirname "$0")/wsa-env.sh"
TOOLS="${TOOLS:-$HOME/Android/tools}"
KEYS="${KEYS:-$WSA_KEYS}"
mkdir -p "$TOOLS/smali3" "$KEYS"

# fetch <url> <destination> [sha256]
fetch() {
    local url=$1 dst=$2 sum=${3:-}
    if [ -s "$dst" ]; then echo "  have $dst"; return; fi
    curl -fsSL -o "$dst.part" "$url"
    if [ -n "$sum" ] && [ "$(sha256sum "$dst.part" | cut -d' ' -f1)" != "$sum" ]; then
        echo "checksum mismatch for $url" >&2; rm -f "$dst.part"; exit 1
    fi
    mv "$dst.part" "$dst"; echo "  got  $dst"
}
# gitiles serves raw files base64-encoded with ?format=TEXT
fetch_aosp() {
    local path=$1 dst=$2
    if [ -s "$dst" ]; then echo "  have $dst"; return; fi
    curl -fsSL "https://android.googlesource.com/$path?format=TEXT" | base64 -d > "$dst.part"
    [ -s "$dst.part" ] || { echo "empty download: $path" >&2; exit 1; }
    mv "$dst.part" "$dst"; echo "  got  $dst"
}

echo "== $TOOLS"
G=https://dl.google.com/android/maven2/com/android/tools/smali
fetch "$G/smali-dexlib2/3.0.10/smali-dexlib2-3.0.10.jar" "$TOOLS/smali3/smali-dexlib2-3.0.10.jar" \
    688dee6a46962a4dd1774b64be926534b0b6504bfd5d5c9f89c0f5af284f50c1
fetch "$G/smali-util/3.0.10/smali-util-3.0.10.jar" "$TOOLS/smali3/smali-util-3.0.10.jar" \
    cc2ef8349765644e4b15371f2c9f77d69b5412a094a38fe5fd992a124b7322fe
fetch https://repo1.maven.org/maven2/com/google/guava/guava/31.1-jre/guava-31.1-jre.jar "$TOOLS/guava-31.1-jre.jar" \
    a42edc9cab792e39fe39bb94f3fca655ed157ff87a8af78e1d6ba5b07c4a00ab
fetch https://github.com/iBotPeaches/Apktool/releases/download/v3.0.3/apktool_3.0.3.jar "$TOOLS/apktool.jar" \
    dbf930b076c6b9be08d57c449cacefc3bdd6b71ebd59b3066fc0e1f5b14f9423
fetch_aosp platform/external/avb/+/refs/heads/main/avbtool.py "$TOOLS/avbtool.py"

echo "== $KEYS"
for k in platform.pk8 platform.x509.pem testkey.pk8 testkey.x509.pem; do
    fetch_aosp "platform/build/+/refs/heads/main/target/product/security/$k" "$KEYS/$k"
done
fetch_aosp platform/packages/modules/Connectivity/+/refs/heads/main/Tethering/apex/com.android.tethering.pem \
    "$KEYS/com.android.tethering.pem"
for k in com.android.btservices.pem com.android.btservices.pk8 com.android.btservices.x509.pem; do
    fetch_aosp "platform/packages/modules/Bluetooth/+/refs/heads/main/apex/$k" "$KEYS/$k"
done

echo
for f in "$TOOLS/hostbin/mkuserimg_mke2fs" "${JAVA:-$TOOLS/jdk21/bin/java}"; do
    [ -x "$f" ] || echo "still missing (from a build tree, docs/build.md): $f"
done
