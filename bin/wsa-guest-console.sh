#!/usr/bin/env bash
# WSA guest kernel console — from the host, without adb and without admin rights.
#
#   bin/wsa-guest-console.sh [session] [output file]
#
#   session — run stamp in the .etl name (e.g. 09-23-2026__22_58_27);
#             defaults to the newest one.
#
# WHY. It used to be assumed that an early failure in WSA itself is silent: the host
# logcat (LocalState/diagnostics/logcat) stays empty, adb does not come up, and the
# Windows event log only shows the VM being created. To get a console the boot had
# to be repeated on the offline bench bin/qemu-boot.sh.
#
# It turns out the console is always there. WSA writes ETW traces of the
# Microsoft.WslCore provider, and the GuestLog task in them is the guest's entire
# printk from the first second: init, fs_mgr, SELinux, servicemanager, avc denials,
# service crashes. Files are in LocalState/diagnostics/etw/WsaService-<pid>-<date>-<N>.etl,
# rotated every 1 MB (36 minutes of booting produced 27 files and 14,288 lines).
#
# WHAT IS NOT HERE: logcat, i.e. Java exceptions. Google's stock GSI is a user build
# (ro.debuggable=0), there is no su domain; WSA mounts /data/tombstones and /data/anr
# via virtiofs straight into the host LocalState/diagnostics/, but only NATIVE
# crashes land there. A Java crash of system_server shows here only by its traces:
# zygote killed, sys.init.updatable_crashing=1, restart loop.
set -euo pipefail
. "$(dirname "$0")/wsa-env.sh"

ETW="${ETW_DIR:-$WSA_DIAG/etw}"
SESSION="${1:-}"
OUT="${2:-${WSA_WORK:-$HOME/wsa-work}/diag/guest-console-$(date +%Y%m%d-%H%M%S).log}"
WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT

[ -d "$ETW" ] || { echo "no trace directory: $ETW" >&2; exit 1; }
command -v wslpath >/dev/null || { echo "WSL required: decoding is done by tracerpt.exe from Windows" >&2; exit 1; }

if [ -z "$SESSION" ]; then
    # the newest WsaService-*.etl file defines the session
    NEWEST=$(ls -t "$ETW"/WsaService-*.etl 2>/dev/null | head -1) || true
    [ -n "$NEWEST" ] || { echo "no WsaService-*.etl files in $ETW" >&2; exit 1; }
    SESSION=$(basename "$NEWEST" | sed -E 's/^WsaService-[0-9]+-(.*)-[0-9]+\.etl$/\1/')
fi

FILES=$(ls "$ETW"/WsaService-*"$SESSION"-*.etl 2>/dev/null | sort -t- -k5 -n) || true
[ -n "$FILES" ] || { echo "no traces for session $SESSION" >&2; exit 1; }
echo "== session $SESSION, files: $(echo "$FILES" | wc -l) =="

# tracerpt.exe cannot read WSL paths or a locked file — copy them locally.
i=0
for f in $FILES; do
    i=$((i+1))
    cp "$f" "$WORK/p$i.etl" 2>/dev/null || continue
    powershell.exe -NoProfile -Command \
        "tracerpt.exe '$(wslpath -w "$WORK/p$i.etl")' -o '$(wslpath -w "$WORK/p$i.xml")' -of XML -y" \
        >/dev/null 2>&1 || true
    printf '.' >&2
done
printf '\n' >&2

python3 - "$WORK" "$OUT" <<'PY'
import glob, html, io, os, re, sys
work, out = sys.argv[1], sys.argv[2]
lines = []
for f in sorted(glob.glob(os.path.join(work, '*.xml'))):
    x = io.open(f, encoding='utf-8', errors='replace').read()
    lines += [html.unescape(v).rstrip() for v in re.findall(r'<Data Name="text">([^<]*)</Data>', x)]

def uptime(l):
    m = re.match(r'\[\s*([\d.]+)\]', l)
    return float(m.group(1)) if m else -1.0

# Rotation shuffles the files, while the kernel timestamp gives the true order;
# lines without a stamp (a handful) are dropped, otherwise sorting lies.
lines = sorted((l for l in lines if uptime(l) >= 0), key=uptime)
os.makedirs(os.path.dirname(out) or '.', exist_ok=True)
io.open(out, 'w', encoding='utf-8').write('\n'.join(lines) + '\n')
print("lines: %d" % len(lines))
if lines:
    print("uptime: %.1f .. %.1f s" % (uptime(lines[0]), uptime(lines[-1])))
PY
echo "done: $OUT"
echo
echo "What to look at first:"
echo "  grep -E '__mount|Failed to mount'          — fs_mgr: which partitions from which devices"
echo "  grep -E 'Unable to open SELinux|secilc'    — policy"
echo "  grep -E 'updatable_crashing|received signal 9' — system_server/zygote crash loop"
echo "  grep -c 'updatable_crashing=1'             — how many loops have already happened"
