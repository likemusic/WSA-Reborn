#!/usr/bin/env bash
# Set one framework bool in a slot's product RRO (the auto-generated framework-res overlay of the product partition)
# without a rebuild — for slots built before the matching device-tree overlay existed.
#
#   bin/slot-rro-bool.sh <slot> <bool name> <true|false>
#   bin/slot-rro-bool.sh lineage24 config_isDesktopModeSupported false
#
# The overlay apk is taken out of the slot's product.vhdx, the value is changed in resources.arsc
# (bin/arsc-setbool.py), the apk is re-signed with the AOSP test key (the key the build signs it with; RROs on product
# need no particular signature for framework config) and put back (bin/patch-system-file.sh keeps owner, mode and
# label). The slot's previous product.vhdx stays next to it as <file>.pre-<bool name>. Does not touch the live files:
# afterwards bin/switch-build.sh <slot>. Used for the desktop mode of LineageOS 23.2/24.0, see
# device/microsoft/wsa_x86_64/overlay-lineage).
set -euo pipefail
HERE=$(cd "$(dirname "$0")" && pwd)
slot=${1:?slot} name=${2:?bool name} val=${3:?true|false}
case $val in true) v=1 ;; false) v=0 ;; *) echo "value: true or false" >&2; exit 1 ;; esac
. "$HERE/wsa-env.sh"
wsa_require WSA_INSTALL_DIR
P="$WSA_INSTALL_DIR/product.vhdx.$slot"
[ -r "$P" ] || { echo "no slot file $P" >&2; exit 1; }
BT=$(wsa_build_tools)
K="$WSA_KEYS"   # testkey.{pk8,x509.pem} — bin/fetch-gsi-tools.sh
W=$(mktemp -d); trap 'rm -rf "${W:?}"' EXIT

qemu-img convert -f vhdx -O raw "$P" "$W/product.img"
rro=$(debugfs -R "ls /overlay" "$W/product.img" 2>/dev/null | tr -s ' ' '\n' \
      | grep -E '^framework-res__.*auto_generated_rro_product\.apk$' | head -1)
[ -n "$rro" ] || { echo "no framework-res product RRO in $P" >&2; exit 1; }
debugfs -R "dump /overlay/$rro $W/rro.apk" "$W/product.img" 2>/dev/null
id=$("$BT/aapt2" dump resources "$W/rro.apk" | awk -v n="bool/$name" '$1=="resource" && $3==n {print $2}')
[ -n "$id" ] || { echo "$name is not in $rro — nothing to change" >&2; exit 1; }
echo "$rro: $name ($id) before: $("$BT/aapt2" dump resources "$W/rro.apk" | grep -A1 " bool/$name\$" | tail -1 | xargs)"
mkdir "$W/x"; (cd "$W/x" && python3 -I -c 'import zipfile,sys; zipfile.ZipFile(sys.argv[1]).extract("resources.arsc")' "$W/rro.apk")
python3 -I "$HERE/arsc-setbool.py" "$W/x/resources.arsc" $((0x${id:4:2})) $((0x${id:6:4})) $v >/dev/null
# Rebuild the zip without the old signature, keeping each entry's compression (resources.arsc must stay stored).
python3 -I - "$W/rro.apk" "$W/x/resources.arsc" "$W/rro-new.apk" <<'EOF'
import sys, zipfile
src, arsc, out = zipfile.ZipFile(sys.argv[1]), open(sys.argv[2], 'rb').read(), zipfile.ZipFile(sys.argv[3], 'w')
for i in src.infolist():
    if i.filename.startswith('META-INF/'):
        continue
    n = zipfile.ZipInfo(i.filename, i.date_time); n.compress_type = i.compress_type; n.external_attr = i.external_attr
    out.writestr(n, arsc if i.filename == 'resources.arsc' else src.read(i))
out.close()
EOF
"$BT/zipalign" -f -p 4 "$W/rro-new.apk" "$W/rro-al.apk"
"$BT/apksigner" sign --key "$K/testkey.pk8" --cert "$K/testkey.x509.pem" --out "$W/rro-signed.apk" "$W/rro-al.apk"
echo "after: $("$BT/aapt2" dump resources "$W/rro-signed.apk" | grep -A1 " bool/$name\$" | tail -1 | xargs)"
"$HERE/patch-system-file.sh" "$W/product.img" "/overlay/$rro" "$W/rro-signed.apk" >/dev/null
e2fsck -fy "$W/product.img" >/dev/null 2>&1 || [ $? -eq 1 ]
qemu-img convert -f raw -O vhdx "$W/product.img" "$W/product.vhdx"
qemu-img compare -f raw -F vhdx "$W/product.img" "$W/product.vhdx" >/dev/null
[ -e "$P.pre-$name" ] || { cp "$P" "$P.pre-$name" && cmp "$P" "$P.pre-$name"; }
for t in 1 2 3; do
    cp -f "$W/product.vhdx" "$P.new" 2>/dev/null && cmp -s "$W/product.vhdx" "$P.new" && { mv -f "$P.new" "$P"; break; }
    rm -f "$P.new"; [ $t = 3 ] && { echo "copy into $P failed — slot file untouched" >&2; exit 1; }; sleep 10
done
echo "$slot: $name=$val in $P (previous: $P.pre-$name)"
