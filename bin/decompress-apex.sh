#!/usr/bin/env bash
# Replace compressed apex (.capex) with plain ones (.apex) in a partition tree.
#
#   bin/decompress-apex.sh <dir with apex modules> [where to put the originals]
#
#   example: bin/decompress-apex.sh /mnt/los/new13/system/system/apex /mnt/los/capex-orig
#
# Why. AOSP builds ship some modules compressed: `.capex` is a zip containing
# a ready `original_apex`. `apexd` is supposed to unpack it on first
# boot, putting the result into /data/apex/decompressed.
#
# Microsoft's images have NO compressed modules AT ALL: 26 plain versus our 16 compressed.
# Apparently WSA does not decompress on first boot — and the system is left without
# ART, i.e. without a runtime, before any logs appear. Tested by replacing compressed with
# plain ones: same content, no decompression needed.
set -euo pipefail

DIR="${1:?specify the dir with apex modules}"
# The save dir MUST be outside the partition tree, otherwise the originals end up in the image.
KEEP="${2:-}"
[ -d "$DIR" ] || { echo "no such dir: $DIR" >&2; exit 1; }
if [ -n "$KEEP" ]; then
    case "$(readlink -f "$KEEP")/" in
        "$(readlink -f "$DIR")"/*) echo "the save dir is inside the partition tree — the originals would end up in the image" >&2; exit 1 ;;
    esac
    mkdir -p "$KEEP"
else
    echo "WARNING: no save dir given — .capex files will be deleted irreversibly." >&2
fi
command -v 7z >/dev/null || { echo "7z is missing (package p7zip-full)" >&2; exit 1; }

shopt -s nullglob
files=("$DIR"/*.capex)
[ ${#files[@]} -gt 0 ] || { echo "no compressed modules — nothing to do"; exit 0; }

echo "== decompressing ${#files[@]} compressed modules in $DIR =="
done_n=0; failed=0
for capex in "${files[@]}"; do
    name="$(basename "$capex" .capex)"
    tmp="$(mktemp -d)"
    if 7z x -so "$capex" original_apex > "$tmp/out.apex" 2>/dev/null && [ -s "$tmp/out.apex" ]; then
        # Check that we got a real apex, not a fragment: it must contain a manifest.
        if 7z l "$tmp/out.apex" 2>/dev/null | grep -q "apex_manifest.pb"; then
            mv "$tmp/out.apex" "$DIR/$name.apex"
            if [ -n "$KEEP" ]; then mv "$capex" "$KEEP/"; else rm -f "$capex"; fi
            printf "  %-42s %s\n" "$name" "$(du -h "$DIR/$name.apex" | cut -f1)"
            done_n=$((done_n+1))
        else
            echo "  SKIPPED (does not look like an apex): $name" >&2
            failed=$((failed+1))
        fi
    else
        echo "  SKIPPED (no original_apex inside): $name" >&2
        failed=$((failed+1))
    fi
    rm -rf "$tmp"
done

echo
echo "decompressed: $done_n, skipped: $failed"
# find, not ls over a glob: with nullglob an empty glob collapses, and `ls` without
# arguments lists the CURRENT dir — the counter lies with a plausible number.
echo "compressed left: $(find "$DIR" -maxdepth 1 -name '*.capex' | wc -l), plain: $(find "$DIR" -maxdepth 1 -name '*.apex' | wc -l)"
