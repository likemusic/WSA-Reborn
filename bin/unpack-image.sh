#!/usr/bin/env bash
# Unpack a WSA partition from vhdx to raw and detect the FS type.
# Works without root: conversion with qemu-img, reading with debugfs (ext4) or fsck.erofs (erofs).
#
#   bin/unpack-image.sh <path to .vhdx> [destination dir]
#
# Originals are not touched: they are read but not modified.
set -euo pipefail
. "$(dirname "$0")/wsa-env.sh"

SRC="${1:?specify the path to .vhdx}"
DEST="${2:-$WSA_WORK/img}"

[ -r "$SRC" ] || { echo "not readable: $SRC" >&2; exit 1; }
command -v qemu-img >/dev/null || { echo "no qemu-img — install the qemu-utils package" >&2; exit 1; }

name="$(basename "$SRC" .vhdx)"
mkdir -p "$DEST"
out="$DEST/$name.img"

if [ -s "$out" ]; then
    echo "already unpacked: $out ($(du -h "$out" | cut -f1))"
else
    echo "converting $name.vhdx -> $name.img ..."
    qemu-img convert -f vhdx -O raw "$SRC" "$out"
    echo "done: $out ($(du -h "$out" | cut -f1))"
fi

# The FS type is detected by signatures: ext4 — 0x53EF at offset 0x438,
# erofs — 0xE0F5E1E2 at offset 0x400.
ext4=$(dd if="$out" bs=1 skip=1080 count=2 2>/dev/null | xxd -p)
erofs=$(dd if="$out" bs=1 skip=1024 count=4 2>/dev/null | xxd -p)

if [ "$ext4" = "53ef" ]; then
    echo "FS: ext4 — read with debugfs, no root needed:"
    echo "    debugfs -R 'ls -l /' $out"
    echo "    debugfs -R 'rdump / <dir>' $out    # dump everything"
elif [ "$erofs" = "e2e1f5e0" ]; then
    echo "FS: EROFS — read with fsck.erofs (erofs-utils package), no root needed:"
    echo "    fsck.erofs --extract=<dir> $out"
else
    echo "FS not recognised (ext4=$ext4 erofs=$erofs) — inspect the start of the image by hand" >&2
fi
