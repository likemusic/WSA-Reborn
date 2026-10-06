# Windows-side state of Android apps — "Programs and Features" entries and Start menu shortcuts
# that the WSA host creates on Apps channel events. The registry and Start menu are SHARED by all slots and /data pools,
# so after switching pools the list keeps "ghost" apps of the other pool (WSABuilds#664,
# found 29.09.2026). switch-data.sh stores this state together with the pool.
#
#   powershell.exe -NoProfile -ExecutionPolicy Bypass -File wsa-host-apps.ps1 save    <dir>
#   powershell.exe -NoProfile -ExecutionPolicy Bypass -File wsa-host-apps.ps1 clear
#   powershell.exe -NoProfile -ExecutionPolicy Bypass -File wsa-host-apps.ps1 restore <dir>
#   powershell.exe -NoProfile -ExecutionPolicy Bypass -File wsa-host-apps.ps1 prune   <package list file>
#
# save — HKCU\...\Uninstall\<package> entries with WsaClient.exe in UninstallString (reg export one by one),
# top-level Start menu shortcuts targeting WsaClient.exe and LocalState\<package>.ico icons — into the directory
# (its previous contents are replaced). clear — remove these entries and shortcuts (icons stay).
# restore — clear, then bring back what was saved. prune — remove entries and shortcuts of packages not in the list
# (one package per line; switch-data.sh prune-host-apps takes it from pm list packages of the live pool): the host
# sends the full app list only when the WSA version changes, so ghosts do not go away by themselves.
param(
    [Parameter(Mandatory = $true)][ValidateSet('save', 'clear', 'restore', 'prune')][string]$Action,
    [string]$Dir
)
$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [Text.Encoding]::UTF8  # output is read by WSL
$Uninstall = 'HKCU:\Software\Microsoft\Windows\CurrentVersion\Uninstall'
$Programs = Join-Path $env:APPDATA 'Microsoft\Windows\Start Menu\Programs'
$LocalState = Join-Path $env:LOCALAPPDATA 'Packages\MicrosoftCorporationII.WindowsSubsystemForAndroid_8wekyb3d8bbwe\LocalState'
$Shell = New-Object -ComObject WScript.Shell
# The script is run from WSL via a UNC path, and cmd.exe (reg below) does not accept a UNC current directory.
Set-Location $env:SystemRoot

function Get-WsaKeys {
    Get-ChildItem $Uninstall | Where-Object { (Get-ItemProperty $_.PSPath).UninstallString -like '*WsaClient.exe*' }
}
function Get-WsaLinks {
    Get-ChildItem $Programs -Filter *.lnk | Where-Object { $Shell.CreateShortcut($_.FullName).TargetPath -like '*WsaClient.exe' }
}
function Clear-Wsa {
    $k = @(Get-WsaKeys); $l = @(Get-WsaLinks)
    $k | ForEach-Object { Remove-Item $_.PSPath -Recurse }
    $l | ForEach-Object { Remove-Item -LiteralPath $_.FullName }
    "removed: entries $($k.Count), shortcuts $($l.Count)"
}

switch ($Action) {
    'save' {
        if (-not $Dir) { throw 'directory required' }
        foreach ($d in 'reg', 'lnk', 'ico') {
            $p = Join-Path $Dir $d
            if (Test-Path $p) { Remove-Item $p -Recurse }
            New-Item -ItemType Directory -Path $p -Force | Out-Null
        }
        $keys = @(Get-WsaKeys)
        foreach ($k in $keys) {
            $pkg = $k.PSChildName
            & cmd.exe /c "reg.exe export `"HKCU\Software\Microsoft\Windows\CurrentVersion\Uninstall\$pkg`" `"$(Join-Path $Dir "reg\$pkg.reg")`" /y >nul 2>&1"
            if ($LASTEXITCODE -ne 0) { throw "reg export $pkg" }
            $ico = Join-Path $LocalState "$pkg.ico"
            if (Test-Path -LiteralPath $ico) { Copy-Item -LiteralPath $ico (Join-Path $Dir 'ico') }
        }
        $links = @(Get-WsaLinks)
        $links | ForEach-Object { Copy-Item -LiteralPath $_.FullName (Join-Path $Dir 'lnk') }
        "saved: entries $($keys.Count), shortcuts $($links.Count)"
    }
    'clear' { Clear-Wsa }
    'prune' {
        if (-not $Dir -or -not (Test-Path -LiteralPath $Dir)) { throw "no package list file: $Dir" }
        $keep = @{}; Get-Content -LiteralPath $Dir | ForEach-Object { if ($_.Trim()) { $keep[$_.Trim()] = 1 } }
        if ($keep.Count -eq 0) { throw 'package list is empty — not pruning' }
        $k = @(Get-WsaKeys | Where-Object { -not $keep.ContainsKey($_.PSChildName) })
        $l = @(Get-WsaLinks | Where-Object {
            $a = $Shell.CreateShortcut($_.FullName).Arguments
            $a -match 'wsa://([^\s/"]+)' -and -not $keep.ContainsKey($Matches[1])
        })
        $k | ForEach-Object { Remove-Item $_.PSPath -Recurse }
        $l | ForEach-Object { Remove-Item -LiteralPath $_.FullName }
        "ghosts removed: entries $($k.Count) ($(($k | ForEach-Object PSChildName) -join ', ')), shortcuts $($l.Count) ($(($l | ForEach-Object BaseName) -join ', '))"
    }
    'restore' {
        if (-not $Dir -or -not (Test-Path (Join-Path $Dir 'reg'))) { throw "no saved state: $Dir" }
        Clear-Wsa
        $regs = @(Get-ChildItem (Join-Path $Dir 'reg') -Filter *.reg)
        foreach ($r in $regs) {
            # reg.exe writes "operation completed successfully" to stderr — with Stop that would become an exception.
            & cmd.exe /c "reg.exe import `"$($r.FullName)`" >nul 2>&1"
            if ($LASTEXITCODE -ne 0) { throw "reg import $($r.Name)" }
        }
        Get-ChildItem (Join-Path $Dir 'ico') -Filter *.ico | ForEach-Object {
            $t = Join-Path $LocalState $_.Name
            if (-not (Test-Path -LiteralPath $t)) { Copy-Item -LiteralPath $_.FullName $t }
        }
        $links = @(Get-ChildItem (Join-Path $Dir 'lnk') -Filter *.lnk)
        $links | ForEach-Object { Copy-Item -LiteralPath $_.FullName $Programs }
        "restored: entries $($regs.Count), shortcuts $($links.Count)"
    }
}
