#!/usr/bin/env bash
# Sets the WSA setting "Vulkan driver" (the WsaSettings combo box VulkanDriverComboBox) without clicks: writes
# VulkanDriver into the package's LocalSettings from inside the package context (Invoke-CommandInDesktopPackage),
# the same place WsaSettings writes it. Read by WsaClient when the subsystem starts — restart WSA afterwards.
#
#   bin/wsa-vulkan-driver.sh none|system|d3d12|unset|show
#
# Values (enum WsaSettings.VulkanDriverPreference in WsaSettings.winmd): None=0, SystemDefault=1, D3D12=2 (Mesa Dozen,
# vulkan_dzn.dll next to WsaClient). Not set = the default. 12.10.2026.
set -euo pipefail
. "$(dirname "$0")/wsa-env.sh"
case "${1:-show}" in
    none) V=0;; system) V=1;; d3d12) V=2;; unset) V=-1;; show) V=-2;;
    *) echo "usage: $0 none|system|d3d12|unset|show" >&2; exit 2;;
esac
WIN_TEMP=$(wslpath "$(powershell.exe -NoProfile -Command '[IO.Path]::GetTempPath()' | tr -d '\r')")
PS1="$WIN_TEMP/wsa-vulkan-driver.ps1" LOG="$WIN_TEMP/wsa-vulkan-driver.txt"
cat > "$PS1" <<'PS'
param([int]$Value)
$log = Join-Path $env:TEMP 'wsa-vulkan-driver.txt'
try {
  [void][Windows.Storage.ApplicationData, Windows.Storage, ContentType = WindowsRuntime]
  $vals = [Windows.Storage.ApplicationData]::Current.LocalSettings.Values
  # Windows PowerShell 5 cannot index the projected IPropertySet — go through IDictionary by reflection.
  $it = [System.Collections.Generic.IDictionary[string,object]]
  if ($Value -eq -1) { [void]$it.GetMethod('Remove', [type[]]@([string])).Invoke($vals, @('VulkanDriver')) }
  elseif ($Value -ge 0) { [void]$it.GetMethod('set_Item').Invoke($vals, @('VulkanDriver', [System.Int32]$Value)) }
  $a = @('VulkanDriver', $null); [void]$it.GetMethod('TryGetValue').Invoke($vals, $a)
  "VulkanDriver = " + $(if ($a[1] -eq $null) { '(not set)' } else { $a[1] }) | Out-File -Encoding ascii $log
} catch { "ERR: " + $_ | Out-File -Encoding ascii $log }
PS
rm -f "$LOG"
WPS1=$(wslpath -w "$PS1")
powershell.exe -NoProfile -Command "Invoke-CommandInDesktopPackage -PackageFamilyName MicrosoftCorporationII.WindowsSubsystemForAndroid_8wekyb3d8bbwe -AppId App -Command powershell.exe -Args '-NoProfile -ExecutionPolicy Bypass -File \"$WPS1\" -Value $V' -PreventBreakaway" >/dev/null
for _ in $(seq 1 30); do [ -s "$LOG" ] && break; sleep 1; done
tr -d '\r' < "$LOG"; rm -f "$LOG" "$PS1"
