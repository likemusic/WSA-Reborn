#!/usr/bin/env bash
# Turns a fresh system_ext.img from a LineageOS 21 / AOSP 14 build (lineage_wsa_x86_64 or
# aosp_wsa_x86_64) into an image ready for a WSA slot, in one command and without "remembering what
# was done by hand". Replaces the series of manual patch-system-file.sh/add-image-file.sh calls used
# to assemble slots lineage21netfix … lineage21nostatusbar (18–21.09.2026); the list of actions was
# obtained by comparing the working slot image with a fresh build file by file and by size
# (docs/findings.md, 21.09.2026, "Reproducibility of system_ext").
#
#   bin/transplant-a14.sh --img <build system_ext.img> --out <result.img> \
#       --wsa-sysext <unpacked system_ext of the Microsoft 2407.40000.4.0 reference> \
#       --secdir <directory with platform.pk8 + platform.x509.pem of the same build> [--grow 64]
#
#   example:
#     bin/transplant-a14.sh --img out/system_ext.img --out slot/system_ext.img \
#         --wsa-sysext $WSA_REF/system_ext \
#         --secdir /build/lineage-21/build/target/product/security
#
# What it does (and why exactly this):
#   1. unpacks the sparse image if needed and grows the FS by --grow MiB (default 64):
#      the build sizes the image tightly, and we need ~2 MB for the Microsoft apks plus headroom; if
#      fewer than 32 inodes are free (AOSP variant: 64 total, 3 free), keeps growing by 128 MiB until
#      resize2fs adds a block group with new inodes;
#   2. re-signs SystemApp.apk and NetworkStackOverlay.apk with the platform key of THE BUILD
#      (sharedUserId android.uid.system — a foreign signature = bootloop, see bin/transplant.sh)
#      and puts them into system_ext together with TalkBack (our own build from open source, fallback:
#      the stock apk) and privapp-permissions-windows.xml (without the allowlist —
#      "Signature|privileged permissions not in privapp-permissions allowlist", system_server
#      crash) and com.microsoft.windows.pdk.notifications.xml;
#   3. appends to system_ext_sepolicy.cil the Microsoft version-compat types
#      (device/microsoft/wsa_x86_64/sepolicy/wsa_vendor_compat.cil — otherwise avc denied on
#      gskserver_exec/rcmsvc and an init reboot at second 3), the SystemApp service type
#      (wsa_systemapp.cil), the type of its IWsaNotifications service (wsa_notification_service.cil),
#      and the sepolicy/service_contexts lines into system_ext_service_contexts. The regular build path
#      (SYSTEM_EXT_PRIVATE_SEPOLICY_DIRS) does not pick up raw CIL — found 15.09.2026;
#   4. relabels /bin/wsa-no-setupwizard.sh to shell_exec: PRODUCT_COPY_FILES installs the
#      file as system_file, and the shell domain is entered only via shell_exec.
#
# Microsoft files are NOT in the repository (legal restriction, BYOB) — only paths to them.
set -euo pipefail
. "$(dirname "$0")/wsa-env.sh"

IMG=""; OUT=""; WSA=""; SECDIR=""; GROW=128   # 128: our own TalkBack build is ~57 MB (06.10.2026)
while [ $# -gt 0 ]; do
    case "$1" in
        --img)        IMG="$2"; shift 2 ;;
        --out)        OUT="$2"; shift 2 ;;
        --wsa-sysext) WSA="$2"; shift 2 ;;
        --secdir)     SECDIR="$2"; shift 2 ;;
        --grow)       GROW="$2"; shift 2 ;;
        *) echo "unknown argument: $1" >&2; exit 1 ;;
    esac
done
[ -n "$IMG" ] && [ -n "$OUT" ] && [ -n "$WSA" ] && [ -n "$SECDIR" ] || { sed -n '2,16p' "$0"; exit 1; }

HERE="$(cd "$(dirname "$0")" && pwd)"
REPO="$(cd "$HERE/.." && pwd)"
SEPOLICY_DIR="$REPO/device/microsoft/wsa_x86_64/sepolicy"
INIT_DIR="$REPO/device/microsoft/wsa_x86_64/overlay-init"
BT="${BUILD_TOOLS:-$(wsa_build_tools)}"
KEY="$SECDIR/platform.pk8"; CERT="$SECDIR/platform.x509.pem"

for t in debugfs e2fsck resize2fs dumpe2fs; do command -v $t >/dev/null || { echo "$t not found (package e2fsprogs)" >&2; exit 1; }; done
[ -x "$BT/apksigner" ] && [ -x "$BT/zipalign" ] || { echo "apksigner/zipalign not found in $BT (set BUILD_TOOLS=)" >&2; exit 1; }
[ -r "$IMG" ] || { echo "cannot read image: $IMG" >&2; exit 1; }
[ -r "$KEY" ] && [ -r "$CERT" ] || { echo "platform.pk8/platform.x509.pem not found in $SECDIR" >&2; exit 1; }
for f in priv-app/SystemApp/SystemApp.apk overlay/NetworkStackOverlay.apk \
         etc/permissions/privapp-permissions-windows.xml etc/permissions/com.microsoft.windows.pdk.notifications.xml; do
    [ -r "$WSA/$f" ] || { echo "$f missing from the Microsoft reference (expected the system_ext tree of 2407.40000.4.0)" >&2; exit 1; }
done
for f in wsa_vendor_compat.cil wsa_systemapp.cil; do
    [ -r "$SEPOLICY_DIR/$f" ] || { echo "$SEPOLICY_DIR/$f not found" >&2; exit 1; }
done

TMP="$(mktemp -d)"; trap 'rm -rf "$TMP"' EXIT
SYSTEM_FILE_CTX="u:object_r:system_file:s0"

echo "== 1. image: $IMG -> $OUT (+${GROW} MiB) =="
# The AOSP build produces a sparse image (magic 0xed26ff3a), which debugfs does not understand.
if [ "$(od -An -tx4 -N4 "$IMG" | tr -d ' ')" = "ed26ff3a" ]; then
    command -v simg2img >/dev/null || { echo "image is sparse, simg2img is required (android-sdk-libsparse-utils)" >&2; exit 1; }
    simg2img "$IMG" "$OUT"
else
    cp -f "$IMG" "$OUT"
fi
e2fsck -fy "$OUT" >/dev/null 2>&1 || true
truncate -s "+$((GROW * 1024 * 1024))" "$OUT"
resize2fs "$OUT" >/dev/null 2>&1
# Free inodes: the build (use_dynamic_partition_size) gives "used + 0.2 %" — the AOSP variant has
# 64 inodes with 3 free, and we need ~8 (two apks with directories, two xml). BOARD_*_EXTFS_INODE_COUNT
# is ignored in this mode, resize2fs adds inodes only with a new block group (128 MiB with
# 4K blocks) — grow by groups until there is enough headroom.
free_inodes() { dumpe2fs -h "$1" 2>/dev/null | grep -oP 'Free inodes:\s*\K[0-9]+'; }
for _ in 1 2 3 4; do
    [ "$(free_inodes "$OUT")" -ge 32 ] && break
    truncate -s "+$((128 * 1024 * 1024))" "$OUT"
    resize2fs "$OUT" >/dev/null 2>&1
done
[ "$(free_inodes "$OUT")" -ge 32 ] || { echo "only $(free_inodes "$OUT") free inodes in the image after growing — not enough" >&2; exit 1; }
echo "  free inodes: $(free_inodes "$OUT")"
e2fsck -fy "$OUT" >/dev/null 2>&1 || true

echo "== 2. Microsoft components (re-signing with $(basename "$KEY")) =="
resign() {  # <reference apk> <result apk>
    cp "$1" "$TMP/in.apk"
    ( cd "$TMP" && zip -q -d in.apk 'META-INF/*' >/dev/null 2>&1 || true )
    "$BT/zipalign" -f -p 4 "$TMP/in.apk" "$TMP/aligned.apk"
    "$BT/apksigner" sign --key "$KEY" --cert "$CERT" "$TMP/aligned.apk"
    mv "$TMP/aligned.apk" "$2"; rm -f "$TMP/in.apk"
}
resign "$WSA/priv-app/SystemApp/SystemApp.apk" "$TMP/SystemApp.apk"
resign "$WSA/overlay/NetworkStackOverlay.apk" "$TMP/NetworkStackOverlay.apk"
"$HERE/add-image-file.sh" "$OUT" "$TMP/SystemApp.apk" /priv-app/SystemApp/SystemApp.apk "$SYSTEM_FILE_CTX" 0644 0:0
"$HERE/add-image-file.sh" "$OUT" "$TMP/NetworkStackOverlay.apk" /overlay/NetworkStackOverlay.apk "$SYSTEM_FILE_CTX" 0644 0:0
for x in privapp-permissions-windows.xml com.microsoft.windows.pdk.notifications.xml; do
    "$HERE/add-image-file.sh" "$OUT" "$WSA/etc/permissions/$x" "/etc/permissions/$x" "$SYSTEM_FILE_CTX" 0644 0:0
done
# TalkBack ("Screen reader for the subsystem"): since 06.10.2026 our own build from open source
# (docs/patches/talkback/README.md): apk in /app/talkback, Braille libraries in /lib64 (not in
# app/talkback/lib: a system app's ABI is not re-derived in an already used pool). Layout is made by
# bin/talkback-prepare.sh, default $WSA_STORAGE/wsa-talkback/system (WSA_TALKBACK_DIR=). If missing,
# fallback: the stock Microsoft apk from the reference product (WSA_TALKBACK=), as before 06.10.2026.
# Own signature, not the platform one. READ_PHONE_STATE comes from default-permissions-wsa-talkback.xml of the build.
TB_DIR="${WSA_TALKBACK_DIR:-$WSA_STORAGE/wsa-talkback/system}"
TALKBACK="${WSA_TALKBACK:-$(dirname "$WSA")/product/app/talkback/talkback.apk}"
if [ -r "$TB_DIR/talkback.apk" ]; then
    "$HERE/add-image-file.sh" "$OUT" "$TB_DIR/talkback.apk" /app/talkback/talkback.apk "$SYSTEM_FILE_CTX" 0644 0:0
    for so in "$TB_DIR"/lib/x86_64/*.so; do
        "$HERE/add-image-file.sh" "$OUT" "$so" "/lib64/$(basename "$so")" u:object_r:system_lib_file:s0 0644 0:0
    done
elif [ -r "$TALKBACK" ]; then
    echo "  no own TalkBack build ($TB_DIR) — installing the stock Microsoft apk" >&2
    "$HERE/add-image-file.sh" "$OUT" "$TALKBACK" /app/talkback/talkback.apk "$SYSTEM_FILE_CTX" 0644 0:0
else
    echo "  TalkBack not found ($TB_DIR, $TALKBACK) — skipping" >&2
fi

echo "== 3. sepolicy: Microsoft types + SystemApp service =="
debugfs -R "dump /etc/selinux/system_ext_sepolicy.cil $TMP/sepolicy.cil" "$OUT" 2>/dev/null
debugfs -R "dump /etc/selinux/system_ext_service_contexts $TMP/service_contexts" "$OUT" 2>/dev/null
[ -s "$TMP/sepolicy.cil" ] && [ -s "$TMP/service_contexts" ] || { echo "image has no system_ext_sepolicy.cil / service_contexts — build without a single .te in SYSTEM_EXT_PRIVATE_SEPOLICY_DIRS (see sepolicy/wsa_systemapp.te)" >&2; exit 1; }
# The files are checked separately: since 22.09.2026 the SystemApp service comes from the build (wsa_systemapp.te),
# while the vendor version-compat types (wsa_vendor_compat.cil) still come only from here.
ADD=""
grep -q gskserver_exec_33_0 "$TMP/sepolicy.cil" && echo "  version-compat already in sepolicy — skipping" || ADD="$ADD $SEPOLICY_DIR/wsa_vendor_compat.cil"
grep -q wsa_systemapp_service "$TMP/sepolicy.cil" && echo "  type wsa_systemapp_service already in sepolicy — skipping" || ADD="$ADD $SEPOLICY_DIR/wsa_systemapp.cil"
grep -q wsa_notification_service "$TMP/sepolicy.cil" && echo "  type wsa_notification_service already in sepolicy — skipping" || ADD="$ADD $SEPOLICY_DIR/wsa_notification_service.cil"
if [ -n "$ADD" ]; then
    # Comments (;) and blank lines are not copied: CIL allows them, but the diff against the reference is cleaner this way.
    { cat "$TMP/sepolicy.cil"; grep -hv -E '^\s*(;|$)' $ADD; } > "$TMP/sepolicy.new"
    "$HERE/patch-system-file.sh" "$OUT" /etc/selinux/system_ext_sepolicy.cil "$TMP/sepolicy.new" >/dev/null
    echo "  + $(( $(grep -c . "$TMP/sepolicy.new") - $(grep -c . "$TMP/sepolicy.cil") )) CIL lines ($(basename -a $ADD | tr '\n' ' '))"
fi
# Lines come from our sepolicy/service_contexts; only names missing from the image are appended.
cp "$TMP/service_contexts" "$TMP/service_contexts.new"
while read -r name ctx; do
    [ -n "$name" ] || continue
    if grep -q "^$name[[:space:]]" "$TMP/service_contexts"; then
        echo "  service_contexts already contains $name — skipping"
    else
        printf '%s   %s\n' "$name" "$ctx" >> "$TMP/service_contexts.new"
        echo "  + line $name in service_contexts"
    fi
done < "$SEPOLICY_DIR/service_contexts"
cmp -s "$TMP/service_contexts" "$TMP/service_contexts.new" || \
    "$HERE/patch-system-file.sh" "$OUT" /etc/selinux/system_ext_service_contexts "$TMP/service_contexts.new" >/dev/null

echo "== 4. shell_exec label for /bin/wsa-no-setupwizard.sh =="
if debugfs -R "stat /bin/wsa-no-setupwizard.sh" "$OUT" 2>/dev/null | grep -q '^Inode:'; then
    # Content is taken from the repository (the same place PRODUCT_COPY_FILES takes it from), so
    # editing the script does not require rebuilding the image; the label is set after replacement,
    # since patch-system-file keeps the previous one (system_file).
    "$HERE/patch-system-file.sh" "$OUT" /bin/wsa-no-setupwizard.sh "$INIT_DIR/wsa-no-setupwizard.sh" >/dev/null
    printf 'u:object_r:shell_exec:s0\0' > "$TMP/ctx"
    debugfs -w -R "ea_set -f $TMP/ctx /bin/wsa-no-setupwizard.sh security.selinux" "$OUT" 2>&1 | grep -v '^debugfs' || true
    debugfs -R "stat /bin/wsa-no-setupwizard.sh" "$OUT" 2>/dev/null | grep -q 'shell_exec' && echo "  label set" || { echo "  FAILED to set the label" >&2; exit 1; }
else
    echo "  image has no /bin/wsa-no-setupwizard.sh (build without the setup wizard bypass — AOSP?) — skipping"
fi

echo "== check =="
e2fsck -fn "$OUT" >/dev/null 2>&1 && echo "  e2fsck: clean" || { echo "  e2fsck found errors in $OUT" >&2; exit 1; }
FREE=$(( $(dumpe2fs -h "$OUT" 2>/dev/null | grep -oP 'Free blocks:\s*\K[0-9]+') * $(dumpe2fs -h "$OUT" 2>/dev/null | grep -oP 'Block size:\s*\K[0-9]+') ))
echo "  free in image: $((FREE / 1048576)) MiB"
echo "done: $OUT — next: qemu-img convert -f raw -O vhdx and a slot in bin/switch-build.sh"
