#!/usr/bin/env bash
# adb of the host VM from WSL. The host relays the guest's adbd to 127.0.0.1:58600 of WINDOWS only, WSL runs in NAT
# mode and the Windows firewall drops connections from WSL to Windows (checked 15.10.2026), so the tunnel goes the
# other way: WSL listens, a PowerShell loop on Windows dials into WSL through localhost forwarding and into the relay.
#
#   host/adb-wsl-tunnel.sh [wsl-port=58601] [relay-port=58600]    — runs until killed
#   adb connect 127.0.0.1:58601                                          — then maestro/adb from WSL as usual
#
# One adb connection at a time (adb keeps one TCP connection per device). 15.10.2026.
set -euo pipefail
port=${1:-58601} relay=${2:-58600} back=$((${1:-58601} + 1))
powershell.exe -NoProfile -Command "while(\$true){ try { \$a=[Net.Sockets.TcpClient]::new('localhost',$back);
  \$b=[Net.Sockets.TcpClient]::new('127.0.0.1',$relay); \$sa=\$a.GetStream(); \$sb=\$b.GetStream();
  [void][Threading.Tasks.Task]::WaitAny(\$sa.CopyToAsync(\$sb), \$sb.CopyToAsync(\$sa)); \$a.Close(); \$b.Close() }
  catch { Start-Sleep -Milliseconds 500 } }" &
win=$!
trap 'kill $win 2>/dev/null' EXIT
while true; do
    socat TCP-LISTEN:"$port",bind=127.0.0.1,reuseaddr TCP-LISTEN:"$back",reuseaddr || sleep 1
done
