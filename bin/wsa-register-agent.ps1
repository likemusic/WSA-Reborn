# Elevated agent that re-registers the WSA package — for batch runs over build variants
# (docs/wsa-variants.md). Windows registers a package with a service (WsaService) only as administrator
# (Add-AppxPackage -Register -> 0x80073D28), and the WSL session does not have those rights. The owner starts the agent ONCE
# in an elevated PowerShell window:
#
#   powershell -ExecutionPolicy Bypass -File \\wsl.localhost\<distro>\...\bin\wsa-register-agent.ps1
#
# then bin/wsa-register.sh puts a request into <WSA-variants>\agent\request.txt (one line — an install
# directory), the agent carries it out and writes the reply to response.txt. The agent can do exactly one thing:
# unregister the registered WSA package preserving its data and register AppxManifest.xml from
# <WSA-variants>\<name> or $WSA_INSTALL_DIR, and only if the manifest's Identity Name is WSA.
# To stop — a file named stop in the same directory, or Ctrl+C.
param(
    # Main WSA install; default — wherever the package is registered when the agent starts.
    [string]$InstallDir = (Get-AppxPackage MicrosoftCorporationII.WindowsSubsystemForAndroid).InstallLocation
)
$ErrorActionPreference = 'Stop'
if (-not $InstallDir) { throw 'WSA is not registered — pass -InstallDir <main WSA install folder>' }
$InstallDir = $InstallDir.TrimEnd('\')
$Variants = Join-Path (Split-Path $InstallDir -Parent) 'WSA-variants'
$Dir = Join-Path $Variants 'agent'
$Name = 'MicrosoftCorporationII.WindowsSubsystemForAndroid'
New-Item -ItemType Directory -Force -Path $Dir | Out-Null
Remove-Item "$Dir\request.txt","$Dir\response.txt","$Dir\stop" -ErrorAction SilentlyContinue
Write-Output "WSA agent: install $InstallDir, variants $Variants; waiting for requests in $Dir\request.txt (stop — file $Dir\stop)"
while (-not (Test-Path "$Dir\stop")) {
    if (-not (Test-Path "$Dir\request.txt")) { Start-Sleep -Milliseconds 500; continue }
    $target = (Get-Content "$Dir\request.txt" -Raw).Trim()
    Remove-Item "$Dir\request.txt"
    $out = try {
        $allowed = ((Split-Path $target -Parent) -ieq $Variants -and (Split-Path $target -Leaf) -match '^[A-Za-z0-9._-]+$') -or ($target -ieq $InstallDir)
        if (-not $allowed) { throw "directory not allowed: $target" }
        [xml]$m = Get-Content "$target\AppxManifest.xml"
        if ($m.Package.Identity.Name -ne $Name) { throw "manifest is not WSA: $($m.Package.Identity.Name)" }
        Stop-Service WsaService -Force -ErrorAction SilentlyContinue
        Stop-Process -Name WsaClient -Force -ErrorAction SilentlyContinue
        $p = Get-AppxPackage $Name
        if ($p -and -not $p.IsDevelopmentMode) { throw 'package is not installed from a directory — data would not survive unregistering' }
        if ($p -and $p.InstallLocation -ine $target) { Remove-AppxPackage -PreserveApplicationData -Package $p.PackageFullName }
        if (-not $p -or $p.InstallLocation -ine $target) {
            Add-AppxPackage -ForceApplicationShutdown -ForceUpdateFromAnyVersion -Register "$target\AppxManifest.xml"
        }
        $p = Get-AppxPackage $Name
        "OK $($p.InstallLocation) $($p.Version)"
    } catch { "ERR $($_.Exception.Message)" }
    Write-Output "$(Get-Date -Format HH:mm:ss) $target -> $out"
    Set-Content -Path "$Dir\response.txt" -Value $out -Encoding UTF8
}
Remove-Item "$Dir\stop" -ErrorAction SilentlyContinue
Write-Output 'WSA agent: stopped'
