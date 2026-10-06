#!/usr/bin/env bash
# Re-signs all apks in a partition tree with the platform key.
#
#   bin/resign-tree.sh <tree> <platform.pk8> <platform.x509.pem>
#
# WHY. To check which partition breaks windows, you need the configuration
# "Microsoft tree + our signatures": the policy and apps stay theirs, while
# the signatures match our system. Partitions cannot be checked one by one —
# our system_ext prevents the policy from compiling, and Microsoft's system conflicts
# on the signature in the shared UID (android.uid.system).
#
# ALL apks are signed at once: the price of a partial re-sign is not a package failure
# but a bootloop of the whole system, because sharedUserId requires the same signature
# on all members. odex/vdex are invalid after re-signing and are removed.
set -euo pipefail
. "$(dirname "$0")/wsa-env.sh"

TREE="${1:?specify the partition tree}"
KEY="${2:-/mnt/los/keys-13/platform.pk8}"
CERT="${3:-/mnt/los/keys-13/platform.x509.pem}"
BT="${BUILD_TOOLS:-$(wsa_build_tools)}"

[ -d "$TREE" ] || { echo "no such tree: $TREE" >&2; exit 1; }
[ -x "$BT/apksigner" ] || { echo "no apksigner in $BT" >&2; exit 1; }

n=0; fail=0
while IFS= read -r apk; do
    if "$BT/apksigner" sign --key "$KEY" --cert "$CERT" \
         --v1-signing-enabled true --v2-signing-enabled true "$apk" 2>/dev/null; then
        n=$((n+1))
        # Precompiled code is invalid after the signature changes.
        d="$(dirname "$apk")"
        rm -rf "$d/oat" 2>/dev/null || true
    else
        echo "  not signed: ${apk#$TREE/}" >&2
        fail=$((fail+1))
    fi
done < <(find "$TREE" -name '*.apk' -type f)

echo "re-signed: $n, errors: $fail"
echo "fingerprint check (should be the same for all):"
find "$TREE" -name '*.apk' -type f | head -6 | while read -r a; do
    t="$(mktemp -d)"; 7z x -o"$t" "$a" 'META-INF/*.RSA' >/dev/null 2>&1
    c="$(find "$t" -name '*.RSA' | head -1)"
    [ -n "$c" ] && printf "  %-34s %s\n" "$(basename "$a")" \
        "$(openssl pkcs7 -inform DER -print_certs -in "$c" 2>/dev/null | openssl x509 -noout -fingerprint -sha256 2>/dev/null | cut -d= -f2 | tr -d ':' | cut -c1-16)"
    rm -rf "$t"
done
