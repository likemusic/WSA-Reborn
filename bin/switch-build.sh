#!/usr/bin/env bash
# Switch the live WSA install between system images ("slots") without touching /data.
#
#   bin/switch-build.sh status           — which slot the live files match
#   bin/switch-build.sh list             — slots found next to the install
#   bin/switch-build.sh describe <slot>  — the slot's description from slots.conf
#   bin/switch-build.sh <slot>           — switch to it (stops WSA; does not start it)
#
# A slot is three files next to the live ones in $WSA_INSTALL_DIR:
#   system.vhdx.<slot>  system_ext.vhdx.<slot>  product.vhdx.<slot>
# Switching copies them over system.vhdx / system_ext.vhdx / product.vhdx. /data (userdata.2.vhdx,
# metadata.2.vhdx) lives elsewhere — in the package LocalCache — and is switched by bin/switch-data.sh:
# keep a separate /data pool per Android version, a major-version change on the same /data breaks it.
#
# Optional ${XDG_CONFIG_HOME:-~/.config}/wsa-reborn/slots.conf, one slot per line:
#   <slot> <initrd> <description…>
# <initrd> is a file in $WSA_INSTALL_DIR/Tools to use as Tools/initrd.img with this slot, or "-" to keep
# whatever initrd is live (bare wsainit, Magisk, Magisk + GApps — whatever your WSABuilds variant ships).
#
# Another install (e.g. a WSABuilds variant in its own folder): WSA_LIVE_DIR=<folder> puts the live files
# there while the slots are still taken from $WSA_INSTALL_DIR; that install's initrd is never replaced.
set -euo pipefail
. "$(dirname "$0")/wsa-env.sh"
wsa_require WSA_INSTALL_DIR

SLOTS_DIR="$WSA_INSTALL_DIR"
LIVE_DIR="${WSA_LIVE_DIR:-$WSA_INSTALL_DIR}"
PARTS=(system system_ext product)
CONF="${XDG_CONFIG_HOME:-$HOME/.config}/wsa-reborn/slots.conf"

conf_field() {  # conf_field <slot> <n> — field n (2 = initrd, 3 = description) from slots.conf
    [ -r "$CONF" ] || return 0
    awk -v s="$1" -v n="$2" '$1 == s && $0 !~ /^[[:space:]]*#/ {
        if (n == 3) { $1 = ""; $2 = ""; sub(/^[[:space:]]+/, ""); print } else print $n; exit }' "$CONF"
}
slot_file() { echo "$SLOTS_DIR/$2.vhdx.$1"; }
slot_present() { local p; for p in "${PARTS[@]}"; do [ -r "$(slot_file "$1" "$p")" ] || return 1; done; }
list_slots() {
    local f s
    for f in "$SLOTS_DIR"/system.vhdx.*; do
        [ -e "$f" ] || continue
        s="${f##*/system.vhdx.}"
        slot_present "$s" && echo "$s"
    done | sort
}

# Fingerprint instead of a full md5: size + md5 of the first/middle/last MiB. A full md5 of
# gigabyte vhdx files over the WSL drvfs mount takes minutes per file; for our own builds a
# collision of all four is not a practical concern.
CHUNK=1048576
fingerprint() {
    local f="$1" size
    [ -r "$f" ] || return 0
    size=$(stat -c%s "$f")
    {
        echo "$size"
        head -c "$CHUNK" "$f" | md5sum
        dd if="$f" bs="$CHUNK" skip=$(( size / 2 / CHUNK )) count=1 2>/dev/null | md5sum
        tail -c "$CHUNK" "$f" | md5sum
    } | md5sum | cut -d' ' -f1
}

cmd_status() {
    local p live s match
    echo "Live files in $LIVE_DIR:"
    for p in "${PARTS[@]}"; do
        live="$(fingerprint "$LIVE_DIR/$p.vhdx")"
        match="unknown (matches no slot)"
        for s in $(list_slots); do
            if [ -n "$live" ] && [ "$(fingerprint "$(slot_file "$s" "$p")")" = "$live" ]; then match="$s"; break; fi
        done
        printf '  %-11s %s\n' "$p" "$match"
    done
    printf '  %-11s md5 %s\n' "initrd" "$(md5sum "$LIVE_DIR/Tools/initrd.img" 2>/dev/null | cut -c1-12)"
}

cmd_list() {
    local s
    echo "Slots in $SLOTS_DIR:"
    for s in $(list_slots); do printf '  %-24s %s\n' "$s" "$(conf_field "$s" 3)"; done
}

stop_wsa() {
    # Stop the service through SCM, not just the processes — so the VM is really gone before the files
    # are replaced, not still running in memory.
    powershell.exe -NoProfile -Command \
        "Stop-Service WsaService -Force -ErrorAction SilentlyContinue; Stop-Process -Name WsaClient -Force -ErrorAction SilentlyContinue" >/dev/null 2>&1 || true
    local vm=""
    for _ in $(seq 1 15); do
        vm="$(powershell.exe -NoProfile -Command "Get-Process vmmemWSA -ErrorAction SilentlyContinue | Select-Object -ExpandProperty Id" 2>/dev/null | tr -d '\r')" || true
        [ -z "$vm" ] && break
        sleep 1
    done
    [ -z "$vm" ] || echo "-- warning: vmmemWSA (pid $vm) still alive after 15 s, copying anyway" >&2
}

cmd_switch() {
    local slot="$1" p initrd
    slot_present "$slot" || { echo "no slot '$slot' (expected $(slot_file "$slot" system) and friends)" >&2; cmd_list >&2; exit 1; }
    echo "== switching to $slot $(conf_field "$slot" 3)"
    stop_wsa
    for p in "${PARTS[@]}"; do
        echo "-- $p.vhdx <- $(basename "$(slot_file "$slot" "$p")")"
        cp -f "$(slot_file "$slot" "$p")" "$LIVE_DIR/$p.vhdx"
    done
    initrd="$(conf_field "$slot" 2)"
    if [ -n "$initrd" ] && [ "$initrd" != "-" ]; then
        if [ "$LIVE_DIR" != "$SLOTS_DIR" ]; then
            echo "-- initrd: another install ($LIVE_DIR), keeping its own"
        elif ! cmp -s "$SLOTS_DIR/Tools/$initrd" "$LIVE_DIR/Tools/initrd.img"; then
            echo "-- initrd <- Tools/$initrd"
            cp -f "$SLOTS_DIR/Tools/$initrd" "$LIVE_DIR/Tools/initrd.img"
        fi
    fi
    cmd_status
    # Host-side logs (logcat, ETW) grow fast on C: — move old ones to the storage drive.
    "$(dirname "$0")/wsa-diag-archive.sh"
    echo "Done. Start WSA yourself, e.g.: powershell.exe -NoProfile -Command \"Start-Process 'wsa://com.android.settings'\""
}

case "${1:-}" in
    status) cmd_status ;;
    list|"") cmd_list ;;
    describe) [ -n "${2:-}" ] || { echo "usage: $0 describe <slot>" >&2; exit 1; }; echo "$2: $(conf_field "$2" 3)" ;;
    *) cmd_switch "$1" ;;
esac
