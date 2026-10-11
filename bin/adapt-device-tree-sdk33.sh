#!/bin/bash
# Adapt the COPY of device/microsoft/wsa_x86_64 inside an Android 13 tree (AOSP 13, LineageOS 20) to
# Soong of Android 13, which knows neither the install_symlink module nor the privapp_allowlist
# property. The allowlists are installed by device.mk (SDK < 34 block) instead; ARM translation
# (houdini) is put into the slot by bin/add-houdini.sh anyway. 11.10.2026.
#   bin/adapt-device-tree-sdk33.sh <tree root>
set -euo pipefail
tree="${1:?usage: $0 <tree root>}"
dev="$tree/device/microsoft/wsa_x86_64"
[ -d "$dev" ] || { echo "no $dev" >&2; exit 1; }
rm -f "$dev/houdini/Android.bp"
for bp in "$dev/WsaShellBridge/Android.bp" "$dev/WsaPackageVerifier/Android.bp"; do
    sed -i '/^\s*privapp_allowlist:/d' "$bp"
done
echo "adapted for SDK 33: $dev"
# The geocoder (OsmGeocodeService) is built on android.location.provider.GeocodeProviderBase (API 34).
# Leave it out on Android 13 — the class AND its <service>: the intent action is the same in 13, so
# system_server would bind to a service whose class does not exist and crash the persistent bridge.
python3 - "$dev/WsaShellBridge" <<'PY'
import re, sys
d = sys.argv[1]
bp = d + "/Android.bp"; s = open(bp).read()
if "exclude_srcs" not in s:
    assert "    platform_apis: true,\n" in s
    s = s.replace("    platform_apis: true,\n", '    exclude_srcs: ["src/org/wsalineage/shellbridge/OsmGeocodeService.java"],\n    platform_apis: true,\n', 1)
    open(bp, "w").write(s)
m = d + "/AndroidManifest.xml"; s = open(m).read()
s = re.sub(r'\s*<service\s+android:name="\.OsmGeocodeService".*?</service>', '', s, count=1, flags=re.S)
open(m, "w").write(s)
PY
echo "geocoder left out (no GeocodeProviderBase before API 34)"
