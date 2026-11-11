#!/usr/bin/env bash
# Replaces a single jar inside an APEX module without rebuilding the whole module.
#
#   bin/patch-apex-jar.sh <source apex> <jar inside | /path/inside> <replacement> <output path>
#
#   example: bin/patch-apex-jar.sh our/com.android.tethering.apex \
#             service-connectivity.jar ms-service-connectivity.jar out.apex
#
# WHY. The whole module cannot be replaced: it contains framework-connectivity.jar
# from the bootclasspath, against which our services.jar is built, and with a foreign version
# system_server crashes (attempt 19). But we only need the service code — in it
# Microsoft removed a check that prevents their own SystemApp from configuring the network
# on our build:
#   UnsupportedOperationException: updateConfiguration() with non-null capabilities
#
# ABOUT THE SIGNATURE. There are two checks here, and they differ:
#   * apexd mounts the module WITHOUT checking the zip signature (attempt 21: the module
#     mounted and the network worked);
#   * PackageManagerService parses the same file as a regular package and REQUIRES
#     a signature — without it everything crashes:
#       IllegalStateException: Unable to parse: /system/apex/com.android.tethering.apex
#         at ApexManager.scanApexPackagesInternalLocked
# So after editing, the module is re-signed. The key must be the same one it was
# originally signed with (otherwise it mismatches the record in /data): for AOSP modules this is
# the public testkey, fingerprint A40DA80A59D170CAA950CF15.
set -euo pipefail
. "$(dirname "$0")/wsa-env.sh"

SRC="${1:?source apex}"
JAR_NAME="${2:?jar name inside the module}"
NEW_JAR="${3:?replacement jar}"
OUT="${4:?output path}"

command -v debugfs >/dev/null || { echo "debugfs not found" >&2; exit 1; }
WORK="$(mktemp -d)"; trap 'rm -rf "$WORK"' EXIT

( cd "$WORK" && 7z x -y "$SRC" >/dev/null 2>&1 )
[ -f "$WORK/apex_payload.img" ] || { echo "apex has no apex_payload.img" >&2; exit 1; }

IMG="$WORK/apex_payload.img"
# A bare name is a jar in /javalib; a path with a slash is any file of the module (/lib64/libnativebridge.so in
# com.android.art, patches/common/art/0032, 30.10.2026).
case "$JAR_NAME" in */*) P="$JAR_NAME";; *) P="/javalib/$JAR_NAME";; esac
TMP_NAME=$(basename "$P")
OLD_SIZE=$(debugfs -R "stat $P" "$IMG" 2>/dev/null | grep -oP 'Size: \K[0-9]+' | head -1)
NEW_SIZE=$(stat -c %s "$NEW_JAR")
echo "replacing $P: $OLD_SIZE -> $NEW_SIZE bytes"
# The label of the file being replaced (system_file for javalib, system_lib_file for lib64).
CTX=$(debugfs -R "ea_get $P security.selinux" "$IMG" 2>/dev/null | grep -oP '"\Ku:object_r:[^"\\]*' | head -1)
# Owner and mode too: javalib is 1000:1000 0644, but /bin/linker64 of com.android.runtime is 0:2000 0755 (0033,
# 03.11.2026) — a hardcoded 0644 left the dynamic linker non-executable. stat prints the mode without the file
# type bits; sif needs the full st_mode, hence 0100.
STAT=$(debugfs -R "stat $P" "$IMG" 2>/dev/null)
OLD_UID=$(echo "$STAT" | grep -oP 'User:\s+\K[0-9]+' | head -1)
OLD_GID=$(echo "$STAT" | grep -oP 'Group:\s+\K[0-9]+' | head -1)
OLD_MODE=$(echo "$STAT" | grep -oP 'Mode:\s+\K[0-7]+' | head -1)

debugfs -w -R "rm $P" "$IMG" >/dev/null 2>&1
debugfs -w -R "write $NEW_JAR $TMP_NAME" "$IMG" >/dev/null 2>&1
debugfs -w -R "ln /$TMP_NAME $P" "$IMG" >/dev/null 2>&1
debugfs -w -R "unlink /$TMP_NAME" "$IMG" >/dev/null 2>&1

# debugfs write does not carry over owner, mode and label — restore them per the reference.
debugfs -w -R "sif $P uid ${OLD_UID:-1000}" "$IMG" >/dev/null 2>&1
debugfs -w -R "sif $P gid ${OLD_GID:-1000}" "$IMG" >/dev/null 2>&1
debugfs -w -R "sif $P mode 0100$(printf %03o $(( 8#${OLD_MODE:-644} )))" "$IMG" >/dev/null 2>&1
# The label is set from a file: the string must end with a NUL byte, like its neighbours.
printf '%s\0' "${CTX:-u:object_r:system_file:s0}" > "$WORK/ctx.bin"
debugfs -w -R "ea_set -f $WORK/ctx.bin $P security.selinux" "$IMG" >/dev/null 2>&1

# dm-verity (11.10.2026). Android 15 apexd mounts a pre-installed APEX through dm-verity using the hashtree in
# the payload's AVB footer — after the edit above the old tree no longer matches, and activation fails with
# "Mounting failed ... I/O error" (on Android 13 it did not check). With APEX_PAYLOAD_KEY (the module's own
# payload key: for AOSP modules a public test key from the module's source, e.g.
# packages/modules/Connectivity/Tethering/apex/com.android.tethering.pem; it must match apex_pubkey) the
# footer is rebuilt with the original parameters (size, algorithm, salt, apex.key property, no FEC).
if [ -n "${APEX_PAYLOAD_KEY:-}" ]; then
    AVBTOOL="${AVBTOOL:-${TOOLS:-$HOME/Android/tools}/avbtool.py}"
    INFO=$(python3 "$AVBTOOL" info_image --image "$IMG")
    PSIZE=$(echo "$INFO" | awk -F': *' '/^Image size:/{print $2+0; exit}')
    ALG=$(echo "$INFO" | awk -F': *' '/^Algorithm:/{print $2; exit}')
    HALG=$(echo "$INFO" | awk -F': *' '/Hash Algorithm:/{print $2; exit}')
    SALT=$(echo "$INFO" | awk -F': *' '/Salt:/{print $2; exit}')
    PROPS=$(echo "$INFO" | sed -n "s/^ *Prop: \([^ ]*\) -> '\(.*\)'/--prop \1:\2/p" | tr '\n' ' ')
    python3 "$AVBTOOL" extract_public_key --key "$APEX_PAYLOAD_KEY" --output "$WORK/key.pub"
    cmp -s "$WORK/key.pub" "$WORK/apex_pubkey" || { echo "APEX_PAYLOAD_KEY does not match apex_pubkey" >&2; exit 1; }
    # The new tree may need a few more blocks than the old footer left room for: grow the
    # payload by 1 MiB (its size is not fixed; apexd finds the footer at the end).
    PSIZE=$(( PSIZE + 1048576 ))
    python3 "$AVBTOOL" erase_footer --image "$IMG" || exit 1
    python3 "$AVBTOOL" add_hashtree_footer --image "$IMG" --partition_size "$PSIZE" \
        --key "$APEX_PAYLOAD_KEY" --algorithm "$ALG" --hash_algorithm "$HALG" --salt "$SALT" \
        --do_not_generate_fec $PROPS || exit 1
    echo "payload hashtree rebuilt ($ALG, $HALG, $PROPS)"
fi

python3 - "$SRC" "$WORK" "$OUT" <<'PY'
import zipfile, os, sys
src, work, out = sys.argv[1:4]
orig = zipfile.ZipFile(src)
zf = zipfile.ZipFile(out, 'w')
for info in orig.infolist():
    p = os.path.join(work, info.filename)
    data = open(p, 'rb').read() if os.path.exists(p) else orig.read(info.filename)
    ni = zipfile.ZipInfo(info.filename, date_time=info.date_time)
    ni.compress_type = info.compress_type
    ni.external_attr = info.external_attr
    # Alignment is mandatory for two entries, and their boundaries differ:
    #   apex_payload.img — 4096, it is mounted straight from the file as a loop device;
    #   resources.arsc   — 4, the package parser reads it via mmap.
    # Without the second one PackageManagerService rejects the whole module, answering with the same
    # "Unable to parse" as for a wrong signature — the two cannot be told apart (attempt 23).
    if info.filename in ('apex_payload.img', 'resources.arsc'):
        ni.compress_type = zipfile.ZIP_STORED
        align = 4096 if info.filename == 'apex_payload.img' else 4
        pad = (-(zf.fp.tell() + 30 + len(info.filename.encode()))) % align
        if pad:
            ni.extra = b'\0' * pad
    zf.writestr(ni, data)
zf.close(); orig.close()
z = zipfile.ZipFile(out)
for name, align in (('apex_payload.img', 4096), ('resources.arsc', 4)):
    i = z.getinfo(name)
    off = i.header_offset + 30 + len(i.filename) + len(i.extra)
    assert i.compress_type == 0 and off % align == 0, "%s not aligned to %d" % (name, align)
print("built: %s (%d bytes), payload aligned" % (out, os.path.getsize(out)))
PY

# Re-signing. The key and apksigner are set via variables; the defaults are
# the ones available locally.
KEY="${APEX_KEY:-/mnt/los/keys-13/testkey.pk8}"
CERT="${APEX_CERT:-/mnt/los/keys-13/testkey.x509.pem}"
APKSIGNER="${APKSIGNER:-$(wsa_build_tools)/apksigner}"
if [ -x "$APKSIGNER" ] && [ -r "$KEY" ]; then
    # --alignment-preserved: without it apksigner rewrites the local headers and the payload ends up at an
    # unaligned offset (52 instead of 4096) — the loop device then cannot read even the superblock and
    # apexd reports "Mounting failed ... I/O error" (11.10.2026, Android 15 GSI).
    "$APKSIGNER" sign --key "$KEY" --cert "$CERT" --alignment-preserved \
        --v1-signing-enabled false --v2-signing-enabled false \
        --v3-signing-enabled true --v4-signing-enabled false "$OUT"
    echo "signed with key $(basename "$CERT")"
    python3 - "$OUT" <<'PY2' || exit 1
import zipfile, struct, sys
p = sys.argv[1]; z = zipfile.ZipFile(p); f = open(p, 'rb')
for name, align in (('apex_payload.img', 4096), ('resources.arsc', 4)):
    i = z.getinfo(name); f.seek(i.header_offset); h = f.read(30)
    n, e = struct.unpack('<HH', h[26:30]); off = i.header_offset + 30 + n + e
    assert i.compress_type == 0 and off % align == 0, "after signing %s is at %d, not aligned to %d" % (name, off, align)
print("alignment after signing: ok")
PY2
    "$APKSIGNER" verify --print-certs "$OUT" 2>/dev/null | grep -i 'SHA-256 digest' | head -1 || true
else
    echo "WARNING: not signed — PackageManager will not parse such a module" >&2
fi
