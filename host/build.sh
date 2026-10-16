#!/usr/bin/env bash
# Builds wsa-reborn-host.exe with Visual Studio 2022 (cl.exe, x64) into <Windows temp>\<dir> (default wsa-reborn-host;
# another name while a previous build is running and locked) and prints the
# Windows path of the directory. 12.10.2026.
set -euo pipefail
here=$(cd "$(dirname "$0")" && pwd)
# A failed WSL interop call prints nothing, and the build would land in the current directory: stop instead.
tmp=$(powershell.exe -NoProfile -Command '[IO.Path]::GetTempPath()' | tr -d '\r')
[ -n "$tmp" ] || { echo "no Windows temp path (WSL interop failed?) — run again" >&2; exit 1; }
out=$(wslpath "$tmp")/${1:-wsa-reborn-host}
mkdir -p "$out"
cp "$here/wsa-reborn-host.cpp" "$out/"
vs=$(ls -d "/mnt/c/Program Files/Microsoft Visual Studio/2022/"*/VC/Auxiliary/Build | head -1)
cd "$out"
printf '@call "%s\\vcvars64.bat" >nul\r\ncl /nologo /EHsc /std:c++17 /O2 /W3 wsa-reborn-host.cpp\r\n' "$(wslpath -w "$vs")" > build.bat
rm -f wsa-reborn-host.exe
cmd.exe /c build.bat | tr -d '\r' | grep -v '^wsa-reborn-host.cpp$' || true
[ -f wsa-reborn-host.exe ] && wslpath -w "$out"
