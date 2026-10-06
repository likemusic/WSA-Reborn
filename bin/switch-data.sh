#!/usr/bin/env bash
# Switch the live /data (userdata.2.vhdx / metadata.2.vhdx in the WSA package LocalCache) between
# named pools — directories under $WSA_POOLS, ideally on a drive other than C:.
#
# WHY POOLS. Keep one /data per Android version. Running a different major version on the same /data
# hits a new incompatibility every time (appops.xml op codes, "Can't downgrade database" in providers,
# privapp-permissions grants for packages the other image does not have, …). Pools also keep test
# data apart from the /data you actually use.
#
# COST. Every switch copies userdata.2.vhdx (several GB) over the WSL drvfs mount.
#
#   bin/switch-data.sh status                       — which pool the live files look like (by size, a hint only)
#   bin/switch-data.sh save <pool>                  — save the LIVE files into a pool (stops WSA)
#   bin/switch-data.sh restore <pool>               — make a pool live WITHOUT saving the current one (careful)
#   bin/switch-data.sh switch <live-pool> <pool>    — save the live data as <live-pool>, make <pool> live
#                                                     (the safe variant; you must know what is live now)
#   bin/switch-data.sh fresh [<install dir>]        — make a CLEAN /data live from the install's factory templates
#                                                     (userdata.vhdx / metadata.vhdx), WITHOUT saving the current one
#   bin/switch-data.sh prune-host-apps              — remove Start menu / "Installed apps" entries of Android apps
#                                                     that are not in the live pool (WSA running, adb connected)
#
# Android apps also exist on the Windows side (Start menu shortcuts, "Installed apps" entries) and the host
# keeps them per user, not per /data — so each pool stores them too (<pool>/windows-apps).
set -euo pipefail
. "$(dirname "$0")/wsa-env.sh"
wsa_require WSA_LOCALCACHE

DATA_DIR="$WSA_LOCALCACHE"
POOL_ROOT="$WSA_POOLS"
FILES=(userdata.2.vhdx metadata.2.vhdx)

pool_dir() { echo "$POOL_ROOT/$1"; }

# The pool's apps on the Windows side — "Programs and Features" entries and Start menu shortcuts (the registry is one for
# all pools, otherwise the list shows "ghosts" of another pool, WSABuilds#664). Stored in <pool>/windows-apps.
host_apps() {
    powershell.exe -NoProfile -ExecutionPolicy Bypass -File "$(wslpath -w "$(dirname "$0")/wsa-host-apps.ps1")" "$@" | tr -d '\r'
}
host_apps_save() { mkdir -p "$(pool_dir "$1")/windows-apps"; echo "-- Windows apps -> $1: $(host_apps save "$(wslpath -w "$(pool_dir "$1")/windows-apps")")"; }
host_apps_restore() {
    if [ -d "$(pool_dir "$1")/windows-apps/reg" ]; then
        echo "-- Windows apps <- $1: $(host_apps restore "$(wslpath -w "$(pool_dir "$1")/windows-apps")")"
    else
        echo "-- pool $1 has no saved Windows apps — entries and shortcuts left as they are"
    fi
}

stop_wsa() {
    echo "-- stopping WsaService/WsaClient"
    powershell.exe -NoProfile -Command \
        "Stop-Service WsaService -Force -ErrorAction SilentlyContinue; Stop-Process -Name WsaClient -Force -ErrorAction SilentlyContinue" >/dev/null 2>&1 || true
    local vm=""
    for _ in $(seq 1 15); do
        vm="$(powershell.exe -NoProfile -Command "Get-Process vmmemWSA -ErrorAction SilentlyContinue | Select-Object -ExpandProperty Id" 2>/dev/null | tr -d '\r')" || true
        [ -z "$vm" ] && break
        sleep 1
    done
    if [ -n "$vm" ]; then
        echo "-- warning: vmmemWSA (pid $vm) is still alive after 15s" >&2
    fi
    # Safety margin: the file is not released immediately after
    # the process disappears from the list.
    sleep 8
}

cmd_status() {
    echo "Live /data files ($DATA_DIR):"
    for f in "${FILES[@]}"; do
        live_size="$(stat -c%s "$DATA_DIR/$f" 2>/dev/null || echo '?')"
        match="unknown"
        for pool in $(ls "$POOL_ROOT" 2>/dev/null); do
            p="$(pool_dir "$pool")/$f"
            [ -r "$p" ] || continue
            if [ "$(stat -c%s "$p" 2>/dev/null)" = "$live_size" ]; then
                match="$pool (by size — NOT a guarantee of identical content)"
                break
            fi
        done
        printf '  %-16s %12s bytes -> %s\n' "$f" "$live_size" "$match"
    done
}

cmd_save() {
    local pool="$1" dst
    dst="$(pool_dir "$pool")"
    mkdir -p "$dst"
    stop_wsa
    for f in "${FILES[@]}"; do
        echo "-- saving $f -> $dst/$f"
        cp -f "$DATA_DIR/$f" "$dst/$f"
    done
    host_apps_save "$pool"
    echo "Pool '$pool' updated with the current state of the live /data."
}

cmd_restore() {
    local pool="$1" src
    src="$(pool_dir "$pool")"
    for f in "${FILES[@]}"; do
        [ -r "$src/$f" ] || { echo "pool file not found: $src/$f" >&2; exit 1; }
    done
    stop_wsa
    for f in "${FILES[@]}"; do
        echo "-- restoring $src/$f -> $DATA_DIR/$f"
        cp -f "$src/$f" "$DATA_DIR/$f"
    done
    host_apps_restore "$pool"
    echo "Done. Start WSA manually — this script does not start it."
}

# Clean /data: the installation templates become the live files, the guest formats them itself on first boot.
# Needed for runs on WSA build variants (docs/variants.md): every boot starts from scratch.
cmd_fresh() {
    local inst="${1:-$WSA_INSTALL_DIR}"
    for t in userdata metadata; do
        [ -r "$inst/$t.vhdx" ] || { echo "no template $inst/$t.vhdx" >&2; exit 1; }
    done
    stop_wsa
    for t in userdata metadata; do
        echo "-- template $inst/$t.vhdx -> $DATA_DIR/$t.2.vhdx"
        cp -f "$inst/$t.vhdx" "$DATA_DIR/$t.2.vhdx"
    done
    echo "Done: the live /data is clean. Start WSA manually — this script does not start it."
}

cmd_switch() {
    local from="$1" to="$2"
    [ "$from" != "$to" ] || { echo "from and to are the same ($from) — nothing to do" >&2; exit 1; }
    for f in "${FILES[@]}"; do
        [ -r "$(pool_dir "$to")/$f" ] || { echo "target pool file not found: $(pool_dir "$to")/$f" >&2; exit 1; }
    done
    echo "== switching /data: saving live as '$from', bringing up '$to' =="
    stop_wsa
    local src_dst
    src_dst="$(pool_dir "$from")"
    mkdir -p "$src_dst"
    for f in "${FILES[@]}"; do
        echo "-- saving live $f -> $src_dst/$f (current content, labelled '$from')"
        cp -f "$DATA_DIR/$f" "$src_dst/$f"
    done
    host_apps_save "$from"
    local from_src
    from_src="$(pool_dir "$to")"
    for f in "${FILES[@]}"; do
        echo "-- bringing up $from_src/$f -> $DATA_DIR/$f"
        cp -f "$from_src/$f" "$DATA_DIR/$f"
    done
    host_apps_restore "$to"
    # Move host logs (logcat, ETW) from C: to the storage drive, otherwise C: fills up within days (29.09.2026).
    "$(dirname "$0")/wsa-diag-archive.sh"
    echo "Done. Start WSA manually — this script does not start it."
}

# Ghosts in "Programs and Features" and Start: packages that are not in the live pool (the first pool snapshots
# of 29.09.2026 were taken from a mixed registry). The package list comes from the subsystem itself.
cmd_prune_host_apps() {
    local adb="$ADB" serial="$WSA_ADB" list
    list="$(mktemp -p "$(wslpath "$(powershell.exe -NoProfile -Command '$env:TEMP' | tr -d '\r')")")"
    "$adb" -s "$serial" shell pm list packages | tr -d '\r' | sed -n 's/^package://p' > "$list"
    [ -s "$list" ] || { rm -f "$list"; echo "adb returned no package list — is WSA running? ($serial)" >&2; exit 1; }
    echo "-- packages in the pool: $(wc -l < "$list")"
    host_apps prune "$(wslpath -w "$list")"
    rm -f "$list"
}

case "${1:-}" in
    status) cmd_status ;;
    save)   [ -n "${2:-}" ] || { echo "specify a pool: save <pool>" >&2; exit 1; }; cmd_save "$2" ;;
    restore) [ -n "${2:-}" ] || { echo "specify a pool: restore <pool>" >&2; exit 1; }; cmd_restore "$2" ;;
    prune-host-apps) cmd_prune_host_apps ;;
    fresh) cmd_fresh "${2:-}" ;;
    switch)
        [ -n "${2:-}" ] && [ -n "${3:-}" ] || { echo "specify both pools: switch <currently_live_from> <to>, e.g.: switch android14 android15" >&2; exit 1; }
        cmd_switch "$2" "$3"
        ;;
    *) sed -n '2,27p' "$0"; exit 1 ;;
esac
