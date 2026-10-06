#!/usr/bin/env bash
# Put a fresh bridge (out/WsaShellBridge.apk + allowlist) into the WORKING slots — into their
# system_ext.vhdx.<suffix> files. Build the bridge beforehand: bin/build-bridge-local.sh.
#
#   bin/bridge-to-slots.sh <slot> [<slot>…]      — e.g. aosp14-vanilla lineage21 aosp15-vanilla
#
# The allowlist is always included: a forgotten allowlist with a new privileged permission crashes
# system_server while parsing the package. A stale bridge oat (happens in Lineage tree builds) is removed.
# Mechanics — bin/patch-slots.sh. On 03.10 and 05.10.2026 the bridge was put into slots by hand; since 06.10.2026 — this way.
set -euo pipefail
. "$(dirname "$0")/wsa-env.sh"
HERE=$(cd "$(dirname "$0")/.." && pwd)
APK="$HERE/out/WsaShellBridge.apk"
[ -r "$APK" ] || { echo "no $APK — run bin/build-bridge-local.sh first" >&2; exit 1; }
WORK="${WORK:-$WSA_STORAGE/wsa-bridge-$(date +%Y%m%d)}" exec "$HERE/bin/patch-slots.sh" \
    "/priv-app/WsaShellBridge/WsaShellBridge.apk=$APK" \
    "/etc/permissions/WsaShellBridge.xml=$HERE/device/microsoft/wsa_x86_64/WsaShellBridge/privapp-permissions-wsashellbridge.xml" \
    -- "$@"
