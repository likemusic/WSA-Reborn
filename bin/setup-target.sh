#!/usr/bin/env bash
# Installs the build target configuration into the LineageOS tree.
#
#   bin/setup-target.sh [tree directory]     (default: $LOS_DIR or /mnt/los/lineage-21)
#
# The configuration is kept in this repository (device/microsoft/wsa_x86_64), not in the tree: the tree
# gets recreated and moved, and the configuration must survive that and end up in a PR.
set -euo pipefail

LOS="${1:-${LOS_DIR:-/mnt/los/lineage-21}}"
SRC="$(cd "$(dirname "$0")/.." && pwd)/device/microsoft/wsa_x86_64"
DST="$LOS/device/microsoft/wsa_x86_64"

[ -d "$LOS/build/make" ] || { echo "does not look like an AOSP tree: $LOS" >&2; exit 1; }
[ -d "$SRC" ] || { echo "no configuration: $SRC" >&2; exit 1; }

mkdir -p "$DST"
cp -v "$SRC"/*.mk "$DST/"

echo
echo "Target installed. Next:"
echo "  cd $LOS"
echo "  source build/envsetup.sh"
# Target format depends on the version: Android 13 has no release component.
if [ -d "$LOS/build/release/release_configs" ] || [ -f "$LOS/build/release/release_config_map.mk" ]; then
    echo "  lunch lineage_wsa_x86_64-ap2a-userdebug     # Android 14+"
    echo "     (if it fails, list available releases: lunch without arguments)"
else
    echo "  lunch lineage_wsa_x86_64-userdebug          # Android 13"
fi
echo "  m -j\$(nproc)          # but size by memory, not by cores: about one job per 3 GB"
echo
echo "The result will appear in out/target/product/wsa_x86_64/ :"
echo "  system.img, system_ext.img, product.img"
