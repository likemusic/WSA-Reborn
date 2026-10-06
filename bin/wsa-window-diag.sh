#!/usr/bin/env bash
# Snapshot of "where the window really is" — guest and host at the same moment.
#
#   bin/wsa-window-diag.sh [output file]
#
# WHY. On 25.09.2026 the Settings window in windowed (not maximised) mode accepted neither clicks nor
# scrolling, maximised it did, and after a WSA restart it all went away by itself. According to the host logcat
# (input_interaction) events reached the guest but landed in QuickstepLauncher under the window — that
# is, in the guest the task's input window was not where the host showed it. Which of the three places
# diverged cannot be told from the log: the task bounds in WM, the input window in InputDispatcher or the task
# layer (leash) in SurfaceFlinger. This script captures all three plus the WsaClient.exe windows on the host —
# run it IMMEDIATELY while the symptom persists, and do not maximise the window before the snapshot.
#
# Read-only: no clicks, no changes in the guest or on the host.
set -uo pipefail
. "$(dirname "$0")/wsa-env.sh"

ADB_ADDR="${ADB_ADDR:-$WSA_ADB}"
ADB="$ADB"
OUT="${1:-${WSA_WORK:-$HOME/wsa-work}/diag/window-diag-$(date +%Y%m%d-%H%M%S).txt}"
command -v "$ADB" >/dev/null || { echo "adb not found: $ADB (set it with ADB=)" >&2; exit 1; }
mkdir -p "$(dirname "$OUT")"
"$ADB" connect "$ADB_ADDR" >/dev/null 2>&1
A=("$ADB" -s "$ADB_ADDR")

{
    echo "### $(date '+%F %T')  $ADB_ADDR"

    echo; echo "### WM tasks: bounds, mode, visibility (dumpsys activity containers)"
    "${A[@]}" shell dumpsys activity containers 2>&1 \
        | grep -E 'Task=|Task\{|bounds=|mBounds=|mode=|visible' | head -80

    echo; echo "### focus and task order (dumpsys activity activities, top first)"
    "${A[@]}" shell dumpsys activity activities 2>&1 \
        | grep -E '^ *\* Task\{|mResumedActivity|topResumedActivity|mFocusedApp|bounds=' | head -40

    echo; echo "### InputDispatcher input windows: order (top first), frames, touchableRegion"
    "${A[@]}" shell dumpsys input 2>&1 \
        | sed -n '/Windows:/,/^  [A-Z][a-z]*[A-Za-z ]*:$/p' \
        | grep -E '^ *[0-9]+: name=|frame=|touchableRegion|inputConfig|flags=' | head -120

    echo; echo "### SurfaceFlinger: task hierarchy (child order — bottom to top)"
    SF=$("${A[@]}" shell dumpsys SurfaceFlinger 2>&1)
    printf '%s\n' "$SF" | grep -E 'DefaultTaskDisplayArea|Task=|ActivityRecord|InputSink|Decor container' \
        | grep -E '^ *[│├└ ─]+' | head -60
    echo; echo "### SurfaceFlinger: input window list (top first), bounds={left,top,bottom,right}"
    printf '%s\n' "$SF" | sed -n '/^Input list/,/^[A-Z][A-Za-z ]*$/p' \
        | grep -E 'Layer \[|bounds=|input\{' | head -90

    echo; echo "### WsaClient.exe windows on the host (EnumWindows, DPI-aware)"
    powershell.exe -NoProfile -Command '
      [Console]::OutputEncoding = [Text.Encoding]::UTF8
      Add-Type @"
using System; using System.Text; using System.Runtime.InteropServices;
public static class W {
  public delegate bool P(IntPtr h, IntPtr l);
  [DllImport("user32.dll")] public static extern bool EnumWindows(P p, IntPtr l);
  [DllImport("user32.dll")] public static extern uint GetWindowThreadProcessId(IntPtr h, out uint pid);
  [DllImport("user32.dll")] public static extern bool IsWindowVisible(IntPtr h);
  [DllImport("user32.dll")] public static extern bool IsIconic(IntPtr h);
  [DllImport("user32.dll")] public static extern int GetWindowText(IntPtr h, StringBuilder s, int n);
  [DllImport("user32.dll")] public static extern int GetClassName(IntPtr h, StringBuilder s, int n);
  [DllImport("user32.dll")] public static extern bool GetWindowRect(IntPtr h, out R r);
  [DllImport("user32.dll")] public static extern bool SetProcessDPIAware();
  public struct R { public int L, T, Ri, B; }
}
"@
      [W]::SetProcessDPIAware() | Out-Null
      $ids = @(Get-Process WsaClient -ErrorAction SilentlyContinue | ForEach-Object Id)
      if (-not $ids) { "WsaClient.exe is not running"; return }
      $out = New-Object Collections.Generic.List[string]
      [W]::EnumWindows({ param($h, $l)
        $p = 0; [W]::GetWindowThreadProcessId($h, [ref]$p) | Out-Null
        # Task windows — class = package name; hidden ones are printed too (a window created by the host but
        # not shown is a separate symptom). Service windows of the IME and tooltips are skipped.
        $cls = New-Object Text.StringBuilder 256; [W]::GetClassName($h, $cls, 256) | Out-Null
        if ($ids -contains $p -and $cls.ToString() -match "^[a-z][a-z0-9_]*(\.[a-z0-9_]+)+$") {
          $sb = New-Object Text.StringBuilder 256; [W]::GetWindowText($h, $sb, 256) | Out-Null
          $r = New-Object W+R; [W]::GetWindowRect($h, [ref]$r) | Out-Null
          $out.Add(("hwnd={0} vis={1} min={2} rect={3},{4}-{5},{6} pkg={7} title={8}" -f $h, [W]::IsWindowVisible($h), [W]::IsIconic($h), $r.L, $r.T, $r.Ri, $r.B, $cls, $sb))
        }
        $true }, [IntPtr]::Zero) | Out-Null
      if ($out.Count -eq 0) { "no task windows" } else { $out }' 2>&1 | tr -d '\r'

    echo; echo "### latest bridge events (Shell, input)"
    "${A[@]}" logcat -d -s WsaShellBridge WsaShellBridge.Input 2>&1 | tail -40
} > "$OUT"

echo "snapshot: $OUT"
echo "compare: host window rect (screen px) ↔ WM task bounds ↔ input window frame ↔ pos of layer Task=N;"
echo "and in the input window list — whether the launcher or TaskInputSink sits ABOVE the app window."
