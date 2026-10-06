#!/usr/bin/env bash
# Build a WSA initrd without Magisk's GApps wiring.
#
#   bin/strip-gapps-initrd.sh <source initrd.img> <where to put the new one>
#
# WHY. The owner's WSA installation is a WSABuilds build with Magisk and LSPosed:
# the boot chain is lspinit → magiskinit → wsainit, and overlay.d/sbin holds
# a 274 MB GApps image that post-fs-data.sh mounts at boot.
#
# GMS from it stalls the LOCKED_BOOT_COMPLETED broadcast — three timeouts in a row.
# If WsaClient.exe waits for the system to be fully ready and fails on a timeout, removing
# GApps will show it. The check is reversible: the source initrd is kept intact.
#
# Permissions inside the archive matter to Magisk (images and scripts have 000, binaries
# 750), so they are restored explicitly rather than inherited from unpacking.
set -euo pipefail

SRC="${1:?specify the source initrd.img}"
DST="${2:?specify where to put the new one}"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

command -v 7z >/dev/null || { echo "no 7z" >&2; exit 1; }
command -v cpio >/dev/null || { echo "no cpio (apt-get download cpio && dpkg -x)" >&2; exit 1; }

7z x -y -o"$WORK" "$SRC" >/dev/null 2>&1
[ -d "$WORK/overlay.d/sbin" ] || { echo "does not look like a WSA initrd with Magisk: no overlay.d/sbin" >&2; exit 1; }

chmod -R u+rwX "$WORK"
before=$(du -sm "$WORK" | cut -f1)

rm -f "$WORK/overlay.d/sbin/lsp_gapps.img" "$WORK/overlay.d/gapps.rc"
echo "removed: lsp_gapps.img and gapps.rc"

# Restore permissions as in the original.
chmod 700 "$WORK/.backup" 2>/dev/null || true
chmod 750 "$WORK/overlay.d" "$WORK/overlay.d/sbin"
chmod 755 "$WORK/wsainit"
chmod 750 "$WORK/lspinit" "$WORK/magiskinit"
chmod 644 "$WORK/overlay.d/sbin/init-ld.xz" "$WORK/overlay.d/sbin/magisk.xz" "$WORK/overlay.d/sbin/stub.xz" 2>/dev/null || true
# In the original these files have mode 000 — that is how Magisk hides them from others.
# An archive cannot be built with such permissions: cpio reads files with a plain open(), and
# 000 forbids that even to the owner. We set 600: everything inside is read by root, for
# whom permissions are no restriction, and functionality does not change.
chmod 600 "$WORK/overlay.d/sbin/lsp_cust.img" "$WORK/overlay.d/sbin/post-fs-data.sh" "$WORK/overlay.d/init.lsp.magisk.rc" 2>/dev/null || true

( cd "$WORK" && find . | cpio -o -H newc --quiet ) > "$DST"
echo "was $before MB, now $(( $(stat -c %s "$DST") / 1048576 )) MB"
echo "done: $DST"
