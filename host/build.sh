#!/usr/bin/env bash
# Builds wsa-reborn-host.exe with the newest Visual Studio (cl.exe, x64) into <Windows temp>\<dir> (default wsa-reborn-host;
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
# The newest Visual Studio with the C++ x64 tools (vswhere ships with every edition since 2017); VS_BUILD=<WSL path to
# VC/Auxiliary/Build> picks another one.
vswhere="/mnt/c/Program Files (x86)/Microsoft Visual Studio/Installer/vswhere.exe"
vs=${VS_BUILD:-$(wslpath "$("$vswhere" -latest -requires Microsoft.VisualStudio.Component.VC.Tools.x86.x64 -property installationPath | tr -d '\r')")/VC/Auxiliary/Build}
[ -f "$vs/vcvars64.bat" ] || { echo "no vcvars64.bat in $vs" >&2; exit 1; }
cd "$out"
printf '@call "%s\\vcvars64.bat" >nul\r\ncl /nologo /utf-8 /EHsc /std:c++20 /O2 /W3 wsa-reborn-host.cpp\r\n' "$(wslpath -w "$vs")" > build.bat
rm -f wsa-reborn-host.exe
# The messages are in the console's code page (cp866 on a Russian Windows): -a, or grep calls them binary and hides them.
cmd.exe /c build.bat | tr -d '\r' | iconv -f cp866 -t utf-8 -c | grep -av '^wsa-reborn-host.cpp$' || true
[ -f wsa-reborn-host.exe ] || { echo "no wsa-reborn-host.exe: the build failed (errors above)" >&2; exit 1; }
wslpath -w "$out"
