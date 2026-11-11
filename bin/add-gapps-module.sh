#!/usr/bin/env bash
# Bakes the GApps Magisk module of the WSABuilds initrd into raw product/system_ext images, for the own host
# (docs/own-host.md), which boots its own initrd without Magisk (03.11.2026).
#
#   bin/add-gapps-module.sh <module dir> <product.img> <system_ext.img> [<extra module dir>...]
#
#   module dir — lsp_gapps.img unpacked (fsck.erofs --extract=DIR overlay.d/sbin/lsp_gapps.img of Tools/initrd.img):
#   product/ and system_ext/ go to the images of the same name. An extra module (lsp_cust) adds only
#   vendor/etc/permissions/*.xml (features device_admin, managed_users), written to product /etc/permissions, where
#   SystemConfig reads features too; its system/priv-app/VpnDialogs replaces the system one and is skipped.
#
# WHY. On the main install GApps come from Magisk: its post-fs-data.sh copies the module into /data/adb/modules and
# Magisk mounts it over /system/{product,system_ext}. The own host boots initrd-own.img (our init.c), which execs
# Android's /init directly — no magiskinit, no overlay.d, so no Play and no GMS. The module is only files, so they
# can go straight into the partitions; the paths stay the same (/product/priv-app/Phonesky…), so a /data pool made
# on the main install with this module keeps its packages. Not reproduced: model.prop (Magisk's system.prop sets
# ro.build.fingerprint with brand google — read-only, system's build.prop wins over product's).
#
# Labels: a new directory or file takes its parent's label (system_file here), except under /lib and /lib64 —
# system_lib_file, as the image's own lib64. Owner 0:0, modes 0755/0644. The images grow when needed (shared_blocks
# removed first: debugfs writing next to deduplicated blocks corrupts neighbours, see bin/replace-houdini.sh).
set -euo pipefail
MOD=${1:?module dir}; PRODUCT=${2:?product.img}; SYSEXT=${3:?system_ext.img}; shift 3
for i in "$PRODUCT" "$SYSEXT"; do [ -w "$i" ] || { echo "not writable: $i" >&2; exit 1; }; done

TMP=$(mktemp -d); trap 'rm -rf "${TMP:?}"' EXIT
label_file() { local f="$TMP/label-${1//:/_}"; [ -f "$f" ] || printf '%s\0' "$1" > "$f"; echo "$f"; }
exists() { debugfs -R "stat $2" "$1" 2>/dev/null | grep -q '^Inode:'; }
label_of() { debugfs -R "ea_get $2 security.selinux" "$1" 2>/dev/null | sed -n 's/.*= "\([^"\\]*\).*/\1/p'; }

# fill <image> <source dir> <destination dir in image> [only *.xml]
fill() {
    local img=$1 src=$2 dst=$3 need free cmds="" d f rel parent lab
    need=$(du -sb "$src" | cut -f1)
    if dumpe2fs -h "$img" 2>/dev/null | grep -q '^Filesystem features:.*shared_blocks'; then
        echo "== $img: shared_blocks — removing deduplication"
        until e2fsck -fy -E unshare_blocks "$img" >/dev/null 2>&1 && ! dumpe2fs -h "$img" 2>/dev/null | grep -q '^Filesystem features:.*shared_blocks'; do
            truncate -s +128M "$img"; resize2fs "$img" >/dev/null 2>&1
        done
    fi
    free=$(( $(dumpe2fs -h "$img" 2>/dev/null | grep -oP 'Free blocks:\s*\K[0-9]+') * 4096 ))
    if [ "$free" -lt $(( need + 64 * 1048576 )) ]; then
        echo "== $img: $free bytes free, $need needed — growing"
        e2fsck -fy "$img" >/dev/null 2>&1 || true
        truncate -s +$(( (need - free) / 1048576 + 128 ))M "$img"; resize2fs "$img" >/dev/null 2>&1
    fi
    declare -A made=()
    while IFS= read -r d; do
        rel=${d#"$src"}; rel="$dst${rel}"; rel=${rel%/}; [ -n "$rel" ] || continue
        exists "$img" "$rel" && continue
        parent=$(dirname "$rel")
        case "$rel" in /lib|/lib/*|/lib64|/lib64/*) lab=u:object_r:system_lib_file:s0;; *) lab=${made[$parent]:-$(label_of "$img" "$parent")};; esac
        made[$rel]=$lab
        cmds+="mkdir $rel"$'\n'"sif $rel mode 040755"$'\n'"sif $rel uid 0"$'\n'"sif $rel gid 0"$'\n'"ea_set -f $(label_file "$lab") $rel security.selinux"$'\n'
    done < <(find "$src" -type d | sort)
    while IFS= read -r f; do
        rel=${f#"$src"}; rel="$dst$rel"; parent=$(dirname "$rel")
        exists "$img" "$rel" && { echo "   already in image, kept: $rel"; continue; }
        case "$rel" in /lib/*|/lib64/*) lab=u:object_r:system_lib_file:s0;; *) lab=${made[$parent]:-$(label_of "$img" "$parent")};; esac
        cmds+="cd $parent"$'\n'"write $f $(basename "$rel")"$'\n'"sif $rel mode 0100644"$'\n'"sif $rel uid 0"$'\n'"sif $rel gid 0"$'\n'"ea_set -f $(label_file "$lab") $rel security.selinux"$'\n'"cd /"$'\n'
        echo "   + $rel ($lab)"
    done < <(find "$src" -type f | sort)
    debugfs -w -f <(printf '%s' "$cmds") "$img" 2>&1 | grep -v -E '^debugfs|^Allocated inode|^$' || true
    e2fsck -fn "$img" >/dev/null 2>&1 || { echo "ERROR: e2fsck reports problems in $img" >&2; e2fsck -fn "$img" | tail -5 >&2; exit 1; }
    # Contents check, file by file.
    while IFS= read -r f; do
        rel=${f#"$src"}; rel="$dst$rel"
        debugfs -R "dump $rel /dev/stdout" "$img" 2>/dev/null | cmp -s - "$f" || { echo "ERROR: $rel differs in $img" >&2; exit 1; }
    done < <(find "$src" -type f)
    echo "ok: $src → $img:$dst"
}

# A loose system_ext/priv-app/GoogleServicesFramework.apk sits next to its own directory in the module (a leftover of
# the module build); PackageManager scans only directories there, so it is left out.
mkdir -p "$TMP/system_ext"; cp -a "$MOD/system_ext/." "$TMP/system_ext/"; rm -f "$TMP/system_ext/priv-app/"*.apk
fill "$PRODUCT" "$MOD/product" ""
fill "$SYSEXT" "$TMP/system_ext" ""
for extra in "$@"; do
    [ -d "$extra/vendor/etc/permissions" ] || continue
    mkdir -p "$TMP/extra-perm"; cp "$extra/vendor/etc/permissions/"*.xml "$TMP/extra-perm/"
    fill "$PRODUCT" "$TMP/extra-perm" "/etc/permissions"
    rm -rf "${TMP:?}/extra-perm"
done
