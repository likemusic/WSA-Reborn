#!/usr/bin/env bash
# Where and how big new WSA windows are born — the same scenario on any slot, for comparing ours with Microsoft's
# stock image (docs/patches.md, 0026–0028). No clicks: apps are opened through wsa://, the host windows of
# WsaClient.exe are read with EnumWindows (DPI-aware, screen pixels) together with the Windows work area, and the
# guest's task bounds with dumpsys.
#
#   bin/wsa-window-geometry.sh [output file] [package…]
#
# Default packages: com.android.settings com.android.documentsui (both exist on the stock image and on our slots). They
# are force-stopped first, then opened one by one, 8 s apart; a snapshot follows each step. Run on a booted WSA.
# Expected with 0026–0028 at 200 % scaling: Settings 1618×947 px centred, Files one cascade step (+94,+94 px) off it.
set -euo pipefail
. "$(dirname "$0")/wsa-env.sh"
mkdir -p "$WSA_WORK/diag"
OUT=${1:-$WSA_WORK/diag/wsa-window-geometry-$(date +%Y%m%d-%H%M%S).txt}
shift || true
PKGS=("$@"); [ ${#PKGS[@]} -gt 0 ] || PKGS=(com.android.settings com.android.documentsui)
A=("$ADB" -s "$WSA_ADB")
"$ADB" connect "$WSA_ADB" >/dev/null

host() {
    powershell.exe -NoProfile -Command '
      [Console]::OutputEncoding = [Text.Encoding]::UTF8
      Add-Type @"
using System; using System.Text; using System.Runtime.InteropServices;
public static class W {
  public delegate bool P(IntPtr h, IntPtr l);
  [DllImport("user32.dll")] public static extern bool EnumWindows(P p, IntPtr l);
  [DllImport("user32.dll")] public static extern uint GetWindowThreadProcessId(IntPtr h, out uint pid);
  [DllImport("user32.dll")] public static extern bool IsWindowVisible(IntPtr h);
  [DllImport("user32.dll")] public static extern bool IsZoomed(IntPtr h);
  [DllImport("user32.dll")] public static extern int GetClassName(IntPtr h, StringBuilder s, int n);
  [DllImport("user32.dll")] public static extern bool GetWindowRect(IntPtr h, out R r);
  [DllImport("user32.dll")] public static extern bool SetProcessDPIAware();
  [DllImport("user32.dll")] public static extern uint GetDpiForWindow(IntPtr h);
  [DllImport("user32.dll")] public static extern bool SystemParametersInfo(int a, int b, ref R r, int c);
  public struct R { public int L, T, Ri, B; }
}
"@
      [W]::SetProcessDPIAware() | Out-Null
      $wa = New-Object W+R; [W]::SystemParametersInfo(0x30, 0, [ref]$wa, 0) | Out-Null
      "workarea {0},{1}-{2},{3}" -f $wa.L, $wa.T, $wa.Ri, $wa.B
      $ids = @(Get-Process WsaClient -ErrorAction SilentlyContinue | ForEach-Object Id)
      # Output of the callback does not reach the pipeline — collect it.
      $out = New-Object Collections.Generic.List[string]
      [W]::EnumWindows({ param($h, $l)
        $p = 0; [W]::GetWindowThreadProcessId($h, [ref]$p) | Out-Null
        $cls = New-Object Text.StringBuilder 256; [W]::GetClassName($h, $cls, 256) | Out-Null
        if ($ids -contains $p -and $cls.ToString() -match "^[a-z][a-z0-9_]*(\.[a-z0-9_]+)+$" -and [W]::IsWindowVisible($h)) {
          $r = New-Object W+R; [W]::GetWindowRect($h, [ref]$r) | Out-Null
          $out.Add(("window {0} {1},{2} {3}x{4} dpi={5} max={6}" -f $cls, $r.L, $r.T, ($r.Ri - $r.L), ($r.B - $r.T), [W]::GetDpiForWindow($h), [W]::IsZoomed($h)))
        }
        $true }, [IntPtr]::Zero) | Out-Null
      $out' 2>&1 | tr -d '\r'
}

guest() {
    # One line per app task: "task #<id> <package> <mode> bounds L,T-R,B" (the first mBounds after the Task line).
    "${A[@]}" shell dumpsys activity activities 2>/dev/null | tr -d '\r' | awk '
        /^ *\* Task\{/ { if (match($0, /A=[0-9]+:[^ ]+/)) { pkg = substr($0, RSTART, RLENGTH); sub(/A=[0-9]+:/, "", pkg) } else pkg = "";
                          match($0, /#[0-9]+/); id = substr($0, RSTART, RLENGTH); match($0, /mode=[a-z]+/); mode = substr($0, RSTART, RLENGTH); want = (pkg != ""); next }
        want && /mBounds=Rect\(/ { match($0, /mBounds=Rect\([^)]*\)/); print "task " id " " pkg " " mode " " substr($0, RSTART, RLENGTH); want = 0 }'
}

{
    echo "# $(date '+%F %T')  slot: $("${A[@]}" shell getprop ro.build.fingerprint | tr -d '\r')"
    echo "# density $("${A[@]}" shell wm density | tr -d '\r' | tr '\n' ' ')  size $("${A[@]}" shell wm size | tr -d '\r' | tr '\n' ' ')"
    for p in "${PKGS[@]}"; do "${A[@]}" shell am force-stop "$p"; done
    sleep 3
    n=0
    for p in "${PKGS[@]}"; do
        n=$((n + 1))
        powershell.exe -NoProfile -Command "Start-Process 'wsa://$p'"
        sleep 8
        echo; echo "## step $n: opened $p"
        host
        guest
    done
} | tee "$OUT"
echo "saved: $OUT"
