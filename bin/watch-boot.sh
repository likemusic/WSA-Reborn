#!/usr/bin/env bash
# Boot-run watcher: prints one line per WSA boot event.
#
#   bin/watch-boot.sh [seconds to watch]
#
# Meant to run under Monitor: every stdout line is a separate notification.
#
# Why not just adb. On a silent failure adb never comes up, and "no logs" looks
# the same in two completely different cases: the guest did not start, and the
# guest started but died before logd. Indirect traces tell them apart:
#
#   userdata.2.vhdx — grows only if the guest got as far as mounting /data and
#                     wrote something. For the compressed-apex hypothesis this is
#                     a direct indicator: apexd unpacks modules into /data/apex;
#   metadata.2.vhdx — written even earlier, while storage is being prepared;
#   Windows event log — WsaClient.exe crashes with time and exception code.
#
# WHERE THESE FILES ARE. Not in the install directory: the userdata.vhdx and
# metadata.vhdx there are 34 MB templates, unchanged since installation and never
# touched at runtime. The working stores live in the package's LocalCache and
# carry a .2 suffix (userdata.2.vhdx is 7 GB). The first version of this script
# watched the templates and would have stayed silent whatever happened.
# MIND pipefail. Almost every pipeline here ends in grep, and "nothing found" is
# normal, not an error: until the system crashes there is nothing to find. With
# set -e such a pipeline kills the watcher on the very first iteration.
# Hence every search pipeline ends with `|| true`.
set -euo pipefail
. "$(dirname "$0")/wsa-env.sh"

DEADLINE=$(( $(date +%s) + ${1:-600} ))
WSA=$WSA_INSTALL_DIR
CACHE="$WSA_LOCALCACHE"
ADB_ADDR="$WSA_ADB"
# adb is not in PATH: it lives in the Android SDK. Without an explicit path every
# call would end in "command not found", 2>/dev/null would swallow the message,
# and the watcher would ALWAYS be silent about adb, which would read as
# "the device did not answer". Verified 09.09.2026: that is exactly what happened.
ADB="$ADB"
command -v "$ADB" >/dev/null || { echo "adb not found: $ADB (set it via ADB=)" >&2; exit 1; }

[ -r "$CACHE/userdata.2.vhdx" ] || {
    echo "working store NOT FOUND: $CACHE/userdata.2.vhdx" >&2
    echo "set the directory via WSA_CACHE — without it guest write traces are invisible" >&2
    exit 1
}
START=$(date +%s)

stamp() { echo "[$(( $(date +%s) - START ))s]"; }

# Initial disk fingerprints to compare against.
declare -A SEEN
for f in userdata.2 metadata.2; do
    SEEN[$f]="$(stat -c '%Y %s' "$CACHE/$f.vhdx" 2>/dev/null || echo none)"
done

adb_ok=0; boot_seen=0; tick=0
# Event log cutoff: only events after start are of interest.
since="$(date -d '1 minute ago' '+%m/%d/%Y %H:%M:%S')"

while [ "$(date +%s)" -lt "$DEADLINE" ]; do
    # --- disk writes: the guest reached this stage ---
    for f in userdata.2 metadata.2; do
        now="$(stat -c '%Y %s' "$CACHE/$f.vhdx" 2>/dev/null || echo none)"
        if [ "$now" != "${SEEN[$f]}" ]; then
            echo "$(stamp) WRITE: $f.vhdx changed — the guest got as far as using this store"
            SEEN[$f]="$now"
        fi
    done

    # --- adb ---
    if [ "$adb_ok" = 0 ]; then
        if "$ADB" connect "$ADB_ADDR" 2>/dev/null | grep -q "connected"; then
            echo "$(stamp) ADB: device answered — the guest reached at least adbd"
            adb_ok=1
        fi
    else
        # `|| true` is required: with set -e a failing command inside $( ) kills the
        # script, and adb errors out whenever the device blinks between zygote
        # restarts — i.e. exactly in the bootloop we are trying to catch.
        bc="$("$ADB" -s "$ADB_ADDR" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r' || true)"
        if [ "$bc" = "1" ] && [ "$boot_seen" = 0 ]; then
            echo "$(stamp) BOOTED: sys.boot_completed=1"
            boot_seen=1
            # Right away — the thing this was all about.
            # Via mount, not ls: without root `ls /apex` says Permission denied,
            # 2>/dev/null eats the message and wc -l yields a plausible zero.
            echo "$(stamp) apex mounts: $("$ADB" -s "$ADB_ADDR" shell 'mount | grep -c apex' | tr -d '\r')"
            exit 0
        fi
        # Early system_server crashes show up in logcat before the system is ready.
        "$ADB" -s "$ADB_ADDR" logcat -d -b crash -t 40 2>/dev/null \
            | grep -E "FATAL|apexd|Failed to (load|mount)" | head -5 | while read -r l; do
                echo "$(stamp) FAILURE: $l"
            done || true
    fi

    # --- Windows event log: client crashes ---
    # Every 30 seconds, not every iteration: starting PowerShell is expensive, and
    # the boot run happens exactly when the machine is best left unloaded.
    tick=$(( tick + 1 ))
    if [ $(( tick % 6 )) = 1 ]; then
    powershell.exe -NoProfile -Command "
        Get-WinEvent -FilterHashtable @{LogName='Application';StartTime=[datetime]'$since'} -EA SilentlyContinue |
        Where-Object { \$_.Message -match 'WsaClient|WsaService' } |
        Select-Object -First 3 | ForEach-Object { 'WsaClient: ' + \$_.TimeCreated.ToString('HH:mm:ss') }
    " 2>/dev/null | tr -d '\r' | sort -u | while read -r l; do
        [ -n "$l" ] && echo "$(stamp) $l"
    done || true
    since="$(date -d '35 seconds ago' '+%m/%d/%Y %H:%M:%S')"
    fi

    sleep 5
done

echo "$(stamp) END OF WATCH: adb=$adb_ok, boot=$boot_seen"
