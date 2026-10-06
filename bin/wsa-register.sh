#!/usr/bin/env bash
# Re-register the WSA package to another install directory (a WSABuilds build variant, docs/variants.md).
#
#   bin/wsa-register.sh <install directory in WSL>   — e.g. $WSA_VARIANTS_DIR/NoGApps
#   bin/wsa-register.sh                              — return to the working install $WSA_INSTALL_DIR
#
# All variants share one Identity Name (MicrosoftCorporationII.WindowsSubsystemForAndroid), so
# only one is registered at a time. The old one is removed with Remove-AppxPackage -PreserveApplicationData —
# the same as MagiskOnWSALocal's Install.ps1 does for a developer-mode package: the package's LocalCache (/data) and
# LocalState (logs) are kept (verified 06.10.2026). Without the switch Windows erases them together with the registration.
#
# Registering a package with a service requires administrator rights (0x80073D28), removing does not. So the
# re-registration itself is done by the agent bin/wsa-register-agent.ps1, started once by the owner as
# administrator; this script drops a request for it and waits for the reply.
#
#   bin/wsa-register.sh --refresh                    — re-register the working install "from scratch": via
#                                                      a hard-link copy $WSA_VARIANTS_DIR/shim and back
#
# WHY --refresh (06.10.2026). Once, in the middle of a series of boots, WsaClient.exe started crashing on every launch
# before the VM even started: c0000409 at offset 0x2eea0b, in ETW — ActivityError 0x80004002 (E_NOINTERFACE) in
# \InstanceManagerRun\StartNewSession\InstanceManagerStartVM, the WsaService trace is not created at all (the client
# never reaches the service). The slot, data and Hyper-V services were not the cause; restarting the service did not help,
# re-registering the package to another directory and back did — i.e. the package COM registrations
# got corrupted. What corrupted them is unknown. Re-registering the same directory via the agent is a no-op,
# hence the detour; a hard-link copy takes no space on C:.
set -euo pipefail
. "$(dirname "$0")/wsa-env.sh"
if [ "${1:-}" = "--refresh" ]; then
    main=$WSA_INSTALL_DIR shim=$WSA_VARIANTS_DIR/shim
    [ -e "$shim" ] || cp -al "$main" "$shim"
    "$0" "$shim"
    "$0" "$main"
    rm -rf "$shim" 2>/dev/null || echo "part of $shim is held by a process — delete it later (it only has hard links)" >&2
    exit 0
fi
dir="${1:-$WSA_INSTALL_DIR}"
[ -r "$dir/AppxManifest.xml" ] || { echo "no $dir/AppxManifest.xml" >&2; exit 1; }
AGENT=$WSA_VARIANTS_DIR/agent
mkdir -p "$AGENT"
cp -f "$(dirname "$0")/wsa-register-agent.ps1" "$AGENT/wsa-register-agent.ps1"
rm -f "$AGENT/response.txt"
wslpath -w "$dir" > "$AGENT/request.txt"
for _ in $(seq 1 600); do
    if [ -s "$AGENT/response.txt" ]; then
        r="$(tr -d '\r\357\273\277' < "$AGENT/response.txt")"
        echo "$r"
        [[ "$r" == OK* ]] && exit 0 || exit 1
    fi
    sleep 0.5
done
rm -f "$AGENT/request.txt"
echo "the agent did not respond within 300 s — run as administrator:" >&2
echo "  powershell -ExecutionPolicy Bypass -File $(wslpath -w "$AGENT/wsa-register-agent.ps1")" >&2
exit 1
