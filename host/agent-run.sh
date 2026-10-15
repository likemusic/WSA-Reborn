#!/usr/bin/env bash
# Asks the elevated agent (agent.ps1) to run a host build and waits for it to finish.
#
#   host/agent-run.sh <dir> [seconds=120]   — dir: folder name under Windows %TEMP% (wsa-reborn-host*)
#   host/agent-run.sh stop                   — end the running host
#   host/agent-run.sh install                — copy the agent to %TEMP%\wsa-reborn-host-agent\agent.ps1
#
# Prints the agent's responses; afterwards out.txt and console.log of the folder hold the result. 12.10.2026.
set -euo pipefail
wt=$(wslpath "$(powershell.exe -NoProfile -Command '[IO.Path]::GetTempPath()' | tr -d '\r')")
ag=$wt/wsa-reborn-host-agent
case "${1:?dir|stop|install}" in
install) mkdir -p "$ag"; { printf '\357\273\277'; sed 's/$/\r/'; } < "$(dirname "$0")/agent.ps1" > "$ag/agent.ps1"; echo "powershell -ExecutionPolicy Bypass -File $(wslpath -w "$ag")\\agent.ps1"; exit;;
esac
[ -d "$ag" ] || { echo "agent folder missing — install first" >&2; exit 1; }
rm -f "$ag/response.txt"
# Written aside and renamed, so the agent never sees a half-written request.
if [ "$1" = stop ]; then echo stop > "$ag/request.tmp"; else echo "$1 ${2:-120}" > "$ag/request.tmp"; fi
mv -f "$ag/request.tmp" "$ag/request.txt"
for _ in $(seq 1 50); do [ -s "$ag/response.txt" ] && break; sleep 0.2; done
r=$(tr -d '\r\357\273\277' < "$ag/response.txt" 2>/dev/null || true)
[ -n "$r" ] || { echo "no response — is the agent running?" >&2; exit 1; }
echo "$r"
case "$r" in STARTED*) ;; *) exit 0;; esac
rm -f "$ag/response.txt"
until [ -s "$ag/response.txt" ]; do sleep 1; done
tr -d '\r\357\273\277' < "$ag/response.txt"
