#!/usr/bin/env bash
# Split a GSI image into three partitions for WSA.
#
#   bin/split-gsi.sh <system.img from a GSI> [destination dir]
#
# A GSI puts product and system_ext INSIDE system.img (in system/product and
# system/system_ext), while WSA mounts them as separate disks — SCSI 0:0:0:1..3 in
# vendor/etc/fstab.1.windows. The script pulls the nested directories out and returns
# three trees ready for bin/pack-vhdx.sh.
#
# Why this way rather than building separate partitions: the GSI target declares the requirement
# "all artifacts in system/", and in Android 13 this requirement is checked
# unconditionally — it cannot be lifted by a flag or an allowlist (verified, three
# attempts). Splitting the result turned out simpler than arguing with the build system.
#
# No root needed: reading via debugfs.
set -euo pipefail

SRC="${1:?specify system.img from a GSI build}"
DEST="${2:-$(dirname "$SRC")/split}"

[ -r "$SRC" ] || { echo "not readable: $SRC" >&2; exit 1; }
command -v debugfs >/dev/null || { echo "no debugfs (e2fsprogs package)" >&2; exit 1; }

mkdir -p "$DEST"
WORK="$DEST/.unpacked"

if [ ! -d "$WORK" ]; then
    echo "== unpacking $SRC =="
    mkdir -p "$WORK"
    debugfs -R "rdump / $WORK" "$SRC" 2>/dev/null >/dev/null || true
fi

# A GSI has a nested structure: the root contains system/, with system/product and
# system/system_ext inside. Check both layout variants.
ROOT="$WORK"
[ -d "$WORK/system/system" ] && ROOT="$WORK/system"

echo "== layout =="
for p in system product system_ext; do
    case "$p" in
        system)     src="$ROOT" ;;
        *)          src="$ROOT/system/$p"; [ -d "$src" ] || src="$ROOT/$p" ;;
    esac
    if [ -d "$src" ]; then
        printf "  %-11s found: %s\n" "$p" "${src#$WORK}"
    else
        printf "  %-11s NOT FOUND (%s)\n" "$p" "${src#$WORK}"
    fi
done

echo
echo "== extracting partitions =="

# product and system_ext are moved out whole and removed from system: in WSA they
# are mounted separately, and a duplicate inside system would confuse PackageManager.
for p in product system_ext; do
    src="$ROOT/system/$p"; [ -d "$src" ] || src="$ROOT/$p"
    dst="$DEST/$p"
    if [ -d "$src" ]; then
        rm -rf "$dst"
        mv "$src" "$dst"
        # In place of the moved directory an empty one MUST be left: it is a
        # mount point. Without it init cannot mount the partition at the first
        # stage — "failed to realpath(/system_ext)" — and fails before
        # there is anything to log with.
        #
        # Separately about the root: a GSI puts SYMLINKS /product -> /system/product
        # and /system_ext -> /system/system_ext there, because its partitions live
        # inside system. After moving them out the links dangle, and init
        # trips over them just as over a missing directory. Microsoft has
        # real directories there, mode 0755.
        rm -rf "$ROOT/$p"
        mkdir -p "$ROOT/$p"
        chmod 755 "$ROOT/$p"
        # And in the place the directory was moved from (/system/product, /system/system_ext)
        # a SYMLINK to the new mount point is needed — exactly how a real
        # AOSP build with separate partitions is laid out (verified on our system.img:
        # /system/system_ext -> /system_ext, /system/product -> /product, next to
        # the GSI's own ready /system/vendor -> /vendor).
        #
        # Without it .rc files cannot find their binaries: in Android 15
        # hwservicemanager.rc declares the service as /system/system_ext/bin/
        # hwservicemanager, and the service simply does not start. From outside this looks like
        # an endless "vold: Waited for hwservicemanager.ready for a second, waiting
        # another..." — boot hangs for good without a single error message.
        # Found 23.09.2026 on the Android 15 probe, offline stand bin/qemu-boot.sh.
        rm -rf "$ROOT/system/$p"
        ln -s "/$p" "$ROOT/system/$p"
        printf "  %-11s %s (mount point + symlink /system/%s)\n" "$p" "$(du -sh "$dst" 2>/dev/null | cut -f1)" "$p"
    else
        printf "  %-11s skipped: nothing to extract\n" "$p"
    fi
done

# A GSI puts UNPACKED copies of apex modules into system_ext — 37 of them, 1.2 GB,
# duplicating those that lie as files in /system/apex. Microsoft has none in system_ext.
# Remove the duplicates: otherwise apexd sees one module twice.
if [ -d "$DEST/system_ext/apex" ]; then
    n=$(ls "$DEST/system_ext/apex" 2>/dev/null | wc -l)
    rm -rf "$DEST/system_ext/apex"
    echo "  apex duplicates removed from system_ext: $n"
fi

# The rest is system. Moved last so as not to disturb the selection above.
rm -rf "$DEST/system"
mv "$ROOT" "$DEST/system"
printf "  %-11s %s\n" "system" "$(du -sh "$DEST/system" 2>/dev/null | cut -f1)"
rm -rf "$WORK"

echo
echo "Next — pack each partition:"
for p in system system_ext product; do
    echo "  bin/pack-vhdx.sh $DEST/$p $p <destination>"
done
echo
echo "Volume labels and SELinux labels are set by pack-vhdx.sh; for SELinux labels it"
echo "needs MKUSERIMG and FILE_CONTEXTS from the build machine."
