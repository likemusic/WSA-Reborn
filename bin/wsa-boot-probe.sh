#!/usr/bin/env bash
# Start WSA and take one status line after boot — for serial runs (WSA build variants,
# docs/variants.md). The subsystem may be stopped or running — the script starts it itself via wsa://.
#
#   bin/wsa-boot-probe.sh [<label>]     — prints "label | field=value …", exit code 0 if boot_completed
#
# Fields: boot — seconds from start to sys.boot_completed; ver/spl — Android version and security patch;
# net — ping 8.8.8.8 loss; gms/play/amazon — packages present; magisk — Magisk mount points;
# bridge/sysapp — processes of our bridge and Microsoft SystemApp; crash — FATAL/Fatal signal in the crash buffer;
# focus — the focused app (mFocusedApp) after opening Settings via wsa://.
set -uo pipefail
. "$(dirname "$0")/wsa-env.sh"
label="${1:-probe}"
ADB="$ADB"
SERIAL="$WSA_ADB"
TIMEOUT="${PROBE_TIMEOUT:-300}"
HOSTLOG=$WSA_DIAG/logcat
a() { timeout 20 "$ADB" -s "$SERIAL" shell "$@" 2>/dev/null | tr -d '\r'; }

t0=$(date +%s)
powershell.exe -NoProfile -Command "Start-Process 'wsa://com.android.settings'" >/dev/null 2>&1
boot=""
while [ $(( $(date +%s) - t0 )) -lt "$TIMEOUT" ]; do
    timeout 10 "$ADB" connect "$SERIAL" >/dev/null 2>&1
    if [ "$(a getprop sys.boot_completed)" = 1 ]; then boot=$(( $(date +%s) - t0 )); break; fi
    sleep 3
done
if [ -z "$boot" ]; then
    # A stock build on a clean /data asks to authorise the adb key (ro.adb.secure=1) — then the boot
    # is confirmed by the host logcat (the logcat channel writes it even without adb).
    state="$(timeout 5 "$ADB" devices | awk -v s="$SERIAL" '$1==s{print $2}')"
    hostlog="$(ls -t "$HOSTLOG"/*.log 2>/dev/null | head -1)"
    bc="no"; [ -n "$hostlog" ] && grep -aq "Finished processing BOOT_COMPLETED" "$hostlog" && bc="yes"
    echo "$label | boot=NONE via adb within ${TIMEOUT}s adb=${state:-none} BOOT_COMPLETED in host logcat=$bc ($(basename "${hostlog:-—}"))"
    exit 1
fi
sleep 20   # let SystemApp, the bridge and the Settings window come up
has() { [ -n "$(a pm path "$1")" ] && echo yes || echo no; }
ver="$(a getprop ro.build.version.release)"
spl="$(a getprop ro.build.version.security_patch)"
loss="$(a ping -c 3 -W 2 8.8.8.8 | sed -n 's/.* \([0-9.]*%\) packet loss.*/\1/p')"
amazon="$(a pm list packages | grep -ci amazon)"
magisk="$(a cat /proc/mounts | grep -cE 'magisk|debug_ramdisk')"
bridge="$(a pidof org.wsalineage.shellbridge)"
sysapp="$(a pidof com.microsoft.windows.systemapp)"
# The whole crash buffer — saved alongside if PROBE_DIR is set (the next boot will erase it).
crashlog="$(a logcat -b crash -d)"
[ -z "${PROBE_DIR:-}" ] || { mkdir -p "$PROBE_DIR"; printf '%s\n' "$crashlog" > "$PROBE_DIR/${label//\//_}.crash.txt"; }
crash="$(printf '%s\n' "$crashlog" | grep -cE 'FATAL EXCEPTION|Fatal signal')"
focus="$(a dumpsys window | sed -n 's/.*mFocusedApp=ActivityRecord{[^ ]* [^ ]* \([^ }]*\).*/\1/p' | head -1)"
echo "$label | boot=${boot}s ver=$ver spl=$spl net=${loss:-?} gms=$(has com.google.android.gms) play=$(has com.android.vending) amazon=$amazon magisk=$magisk bridge=${bridge:+yes} sysapp=${sysapp:+yes} crash=$crash focus=${focus:-—}"
