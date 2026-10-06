#!/usr/bin/env bash
# Replaces a single jar inside an APEX module without rebuilding the whole module.
#
#   bin/patch-apex-jar.sh <source apex> <jar inside> <replacement> <output path>
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
OLD_SIZE=$(debugfs -R "stat /javalib/$JAR_NAME" "$IMG" 2>/dev/null | grep -oP 'Size: \K[0-9]+' | head -1)
NEW_SIZE=$(stat -c %s "$NEW_JAR")
echo "replacing /javalib/$JAR_NAME: $OLD_SIZE -> $NEW_SIZE bytes"

debugfs -w -R "rm /javalib/$JAR_NAME" "$IMG" >/dev/null 2>&1
debugfs -w -R "write $NEW_JAR $JAR_NAME" "$IMG" >/dev/null 2>&1
debugfs -w -R "ln /$JAR_NAME /javalib/$JAR_NAME" "$IMG" >/dev/null 2>&1
debugfs -w -R "unlink /$JAR_NAME" "$IMG" >/dev/null 2>&1

# debugfs write does not carry over owner, mode and label — restore them per the reference.
debugfs -w -R "sif /javalib/$JAR_NAME uid 1000" "$IMG" >/dev/null 2>&1
debugfs -w -R "sif /javalib/$JAR_NAME gid 1000" "$IMG" >/dev/null 2>&1
debugfs -w -R "sif /javalib/$JAR_NAME mode 0100644" "$IMG" >/dev/null 2>&1
# The label is set from a file: the string must end with a NUL byte, like its neighbours.
printf 'u:object_r:system_file:s0\0' > "$WORK/ctx.bin"
debugfs -w -R "ea_set -f $WORK/ctx.bin /javalib/$JAR_NAME security.selinux" "$IMG" >/dev/null 2>&1

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
    "$APKSIGNER" sign --key "$KEY" --cert "$CERT" \
        --v1-signing-enabled false --v2-signing-enabled false \
        --v3-signing-enabled true --v4-signing-enabled false "$OUT"
    echo "signed with key $(basename "$CERT")"
    "$APKSIGNER" verify --print-certs "$OUT" 2>/dev/null | grep -i 'SHA-256 digest' | head -1 || true
else
    echo "WARNING: not signed — PackageManager will not parse such a module" >&2
fi
