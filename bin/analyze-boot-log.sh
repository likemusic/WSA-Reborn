#!/usr/bin/env bash
# Guest boot log analysis: milestones, stalls, channels to Windows.
#
#   bin/analyze-boot-log.sh <logcat-file>
#   bin/analyze-boot-log.sh              — take the newest file of the STOCK logcat-to-host channel
#
# Gathers in one place what you otherwise hunt for across the whole log when
# debugging WSA: when Microsoft services came up, which channels opened, where the
# system stalled for more than a second, and what crashed.
#
# Without an argument the newest NON-EMPTY file from the host directory
# LocalState/diagnostics/logcat is used — the host half of the channel writes there,
# the guest half is installed by bin/add-logcat-forwarder.sh. Same threadtime format
# as adb logcat: the guest runs `logcat -b all -v threadtime`. An empty newest file
# means the guest half did not start — check the ETW console (bin/wsa-guest-console.sh).
set -euo pipefail
. "$(dirname "$0")/wsa-env.sh"

HOSTLOG="${WSA_LOGCAT_DIR:-$WSA_DIAG/logcat}"

if [ $# -eq 0 ]; then
    [ -d "$HOSTLOG" ] || { echo "no channel directory: $HOSTLOG" >&2; exit 1; }
    L=$(find "$HOSTLOG" -maxdepth 1 -name '*.log' -size +0 -printf '%T@ %p\n' 2>/dev/null | sort -rn | head -1 | cut -d' ' -f2-)
    [ -n "$L" ] || {
        echo "no NON-EMPTY file in $HOSTLOG." >&2
        echo "Total files: $(find "$HOSTLOG" -maxdepth 1 -name '*.log' | wc -l); newest: $(find "$HOSTLOG" -maxdepth 1 -name '*.log' -printf '%T@ %p\n' | sort -rn | head -1 | cut -d' ' -f2- | xargs -r basename)." >&2
        echo "The host half of the channel creates a file on every subsystem start regardless of the guest," >&2
        echo "so an empty file = the guest logcat_forwarder is not running (or the slot does not include it)." >&2
        exit 1
    }
    echo "== channel file: $L ($(stat -c %s "$L") bytes, $(date -d "@$(stat -c %Y "$L")" '+%d.%m %H:%M')) =="
    echo
else
    L="$1"
fi
[ -r "$L" ] || { echo "not readable: $L" >&2; exit 1; }

# What follows are `… | head -N` passes. On a large log (the host channel yields megabytes)
# head closes the pipe before the source finishes reading, the source gets SIGPIPE, and with
# pipefail the whole script silently stops there — half of the sections are just not printed.
# Caught 24.09.2026 on a 12.8 MB file: output ended at the stalls section.
set +o pipefail

echo "== channels to Windows (in opening order) =="
# The actual librcm line is "createChannel called with channel name: <name>"; the old pattern
# ("called for") matched nothing and silently gave an empty section. Caught 24.09.2026 on the
# first live log from the stock channel.
grep -oE 'createChannel called with channel name: [A-Za-z]+' "$L" | awk '{print $NF}' | awk '!s[$0]++' | sed 's/^/  /'
grep -q 'createChannel called with channel name:' "$L" || echo "  none"

echo
echo "== Microsoft services =="
grep -ohiE 'gskserver|rcmsvc|sysprep_service|hwcomposer-service\.windows|health-service\.windows|WsaSystemApp' "$L" | tr 'A-Z' 'a-z' | awk '!s[$0]++' | sed 's/^/  /'
grep -oE '[A-Za-z]+: (WsaSystemApp service added|waitForInitialized - exit)' "$L" | awk '!s[$0]++' | sed 's/^/  /' || true

echo
echo "== stalls: adjacent lines more than a second apart =="
# logcat time is MM-DD HH:MM:SS.mmm — compute the difference between lines.
awk '$1 ~ /^[0-9][0-9]-[0-9][0-9]$/ && $2 ~ /^[0-9][0-9]:[0-9][0-9]:[0-9][0-9][.][0-9]+$/ {
    split($2, t, /[:.]/);
    now = t[1]*3600 + t[2]*60 + t[3] + t[4]/1000;
    if (prev > 0 && now - prev > 1.0) printf "  +%.1fs before: %s\n", now - prev, substr($0, 1, 110);
    prev = now
}' "$L" | head -12

echo
echo "== crashes =="
# Only the AndroidRuntime crash header ("Process: <package>, PID: <n>"), otherwise any line
# with the word Process gets in — on a real log that produced "Process: done".
grep -oE 'Process: [a-z][a-z0-9._]+, PID: [0-9]+' "$L" | sed -E 's/, PID: [0-9]+//' | sort | uniq -c | sort -rn | head -5 | sed 's/^/ /'
grep -qE 'Process: [a-z][a-z0-9._]+, PID: [0-9]+' "$L" || echo "  none"

echo
echo "== explicit hang reports =="
grep -iE 'hang detected|timeout of broadcast|not responding|watchdog' "$L" | awk '!s[$0]++' | head -5 | cut -c1-140 | sed 's/^/  /' || echo "  none"

echo
echo "== SELinux denials (avc: denied), by domain =="
grep -oE 'avc: +denied +\{ [a-z_ ]+ \} for .*scontext=u:r:[a-z_]+' "$L" 2>/dev/null \
    | sed -E 's/.*\{ ([a-z_ ]+) \}.*scontext=u:r:([a-z_]+)/  \2: \1/' | sort | uniq -c | sort -rn | head -12 || echo "  none"

echo
echo "== guest half of the logcat channel =="
grep -m3 -E 'logcat_forwarder|Starting logcat forwarder|Stopping logcat forwarder' "$L" | cut -c1-140 | sed 's/^/  /' || echo "  no mentions (normal: the forwarder hardly logs anything)"

echo
echo "== Java exceptions (first of each kind) =="
grep -E 'FATAL EXCEPTION|ClassNotFoundException|^.*E AndroidRuntime' "$L" 2>/dev/null \
    | grep -oE '(FATAL EXCEPTION[^:]*|Caused by: [A-Za-z.]+|java\.lang\.[A-Za-z]+Exception|[A-Za-z.]+ClassNotFoundException)' \
    | awk '!s[$0]++' | head -8 | sed 's/^/  /' || echo "  none"

echo
echo "== boot milestone =="
grep -m1 -E 'Boot is finished|sys\.boot_completed|Starting phase 1000' "$L" | cut -c1-140 | sed 's/^/  /' || echo "  the log does not reach the end of boot"
