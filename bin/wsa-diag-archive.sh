#!/usr/bin/env bash
# Moves old WSA host diagnostic logs from C: to the storage drive — so they do not fill up C:.
#
#   bin/wsa-diag-archive.sh
#
# The host writes into LocalState/diagnostics by itself and deletes nothing: the logcat of every subsystem start
# (logcat channel, bin/add-logcat-forwarder.sh) and ETW traces. On 29.09.2026, five days produced
# 4.9 GB, C: ran out, and WsaClient.exe crashed at start in ucrtbase.dll (docs/findings.md).
#
# What is moved: all logcat logs except the newest (it may be being written right now), and
# ETW traces older than a day (fresh ones are needed by bin/wsa-guest-console.sh). A file is deleted from C: only
# after the copy is verified with cmp. If $WSA_STORAGE is not mounted — does nothing. Called from
# bin/switch-build.sh and bin/switch-data.sh; can also be run by hand.
set -uo pipefail
. "$(dirname "$0")/wsa-env.sh"

DIAG="$WSA_DIAG"
ARCHIVE="${WSA_DIAG_ARCHIVE:-$WSA_STORAGE/wsa-diag-archive}"

[ -d "$DIAG" ] || exit 0
# A Windows drive under /mnt/<letter> that is not mounted is just an empty WSL directory — writing there
# would silently fill the WSL disk (which lives on C:), so require the mount.
case "$WSA_STORAGE" in
    /mnt/?|/mnt/?/*)
        if ! mountpoint -q "/mnt/$(echo "$WSA_STORAGE" | cut -d/ -f3)" || ! ls "$WSA_STORAGE" >/dev/null 2>&1; then
            echo "wsa-diag-archive: storage $WSA_STORAGE is not mounted, logs not moved" >&2
            exit 0
        fi ;;
esac

move() {  # move <file> <archive dir>
    local f="$1" dst="$2/$(basename "$1")"
    mkdir -p "$2"
    cp "$f" "$dst" && cmp -s "$f" "$dst" && rm "$f" && return 0
    echo "wsa-diag-archive: failed to move $f" >&2
    return 1
}

n=0
if [ -d "$DIAG/logcat" ]; then
    while read -r f; do
        move "$DIAG/logcat/$f" "$ARCHIVE/logcat" && n=$((n + 1))
    done < <(ls -t "$DIAG/logcat" 2>/dev/null | grep '\.log$' | tail -n +2)
fi
if [ -d "$DIAG/etw" ]; then
    while read -r f; do
        move "$f" "$ARCHIVE/etw" && n=$((n + 1))
    done < <(find "$DIAG/etw" -maxdepth 1 -name '*.etl' -mtime +1)
fi
[ "$n" -gt 0 ] && echo "wsa-diag-archive: files moved to $ARCHIVE: $n"
exit 0
