# Elevated agent for the own host (wsa-reborn-host.cpp): HCS needs an administrator, the WSL session is not one.
# The owner starts it ONCE in an elevated PowerShell window (decision of 12.10.2026):
#
#   powershell -ExecutionPolicy Bypass -File %TEMP%\wsa-reborn-host-agent\agent.ps1
#
# then host/agent-run.sh writes a request to %TEMP%\wsa-reborn-host-agent\request.txt - one line
# "<dir> <seconds>", where <dir> is a folder %TEMP%\wsa-reborn-host* with wsa-reborn-host.exe and config.json. The agent runs
#   wsa-reborn-host.exe config.json 58600 console.log > out.txt
# in that folder, stops it after <seconds> (the VM is terminated with the last HCS handle) and writes the exit
# status to response.txt. A request "stop" ends a running host early. The agent runs nothing else, but the exe is
# built by the session - while the window is open, that is the session's code with administrator rights.
# To stop the agent - a file named stop next to request.txt, or close the window.
$ErrorActionPreference = 'Stop'
$Dir = Join-Path $env:TEMP 'wsa-reborn-host-agent'
New-Item -ItemType Directory -Force -Path $Dir | Out-Null
Remove-Item "$Dir\request.txt","$Dir\response.txt","$Dir\stop" -ErrorAction SilentlyContinue
Write-Output "wsa-reborn-host agent: waiting for requests in $Dir\request.txt (stop - file $Dir\stop)"
$proc = $null; $deadline = $null; $target = $null
function Finish([string]$why) {
    if ($script:proc -and -not $script:proc.HasExited) { Stop-Process -Id $script:proc.Id -Force -ErrorAction SilentlyContinue; $script:proc.WaitForExit(10000) | Out-Null }
    $code = if ($script:proc) { $script:proc.ExitCode } else { '' }
    $out = "DONE $why exit=$code $script:target"
    Write-Output "$(Get-Date -Format HH:mm:ss) $out"
    Set-Content -Path "$Dir\response.txt" -Value $out -Encoding UTF8
    $script:proc = $null; $script:deadline = $null
}
while (-not (Test-Path "$Dir\stop")) {
    if ($proc -and $proc.HasExited) { Finish 'exited' }
    elseif ($proc -and (Get-Date) -gt $deadline) { Finish 'timeout' }
    if (-not (Test-Path "$Dir\request.txt")) { Start-Sleep -Milliseconds 300; continue }
    # The file may still be empty — the writer creates it first and fills it a moment later (seen 15.10.2026: the agent
    # died on .Trim() of $null); read it again on the next round.
    $req = Get-Content "$Dir\request.txt" -Raw -ErrorAction SilentlyContinue
    if (-not $req -or -not $req.Trim()) { Start-Sleep -Milliseconds 200; continue }
    $req = $req.Trim()
    Remove-Item "$Dir\request.txt"
    try {
        if ($req -eq 'stop') { if ($proc) { Finish 'stopped' } else { Set-Content "$Dir\response.txt" 'DONE nothing running' }; continue }
        if ($proc) { throw 'a host is already running - send stop first' }
        $name, $secs = $req -split '\s+'
        if ($name -notmatch '^wsa-reborn-host[A-Za-z0-9_-]*$' -or $name -eq 'wsa-reborn-host-agent') { throw "folder not allowed: $name" }
        $target = Join-Path $env:TEMP $name
        if (-not (Test-Path "$target\wsa-reborn-host.exe") -or -not (Test-Path "$target\config.json")) { throw "no wsa-reborn-host.exe/config.json in $target" }
        Remove-Item "$target\out.txt","$target\console.log","$target\console.log.com1","$target\init.log" -ErrorAction SilentlyContinue
        $proc = Start-Process -FilePath "$target\wsa-reborn-host.exe" -ArgumentList 'config.json','58600','console.log' -WorkingDirectory $target `
            -RedirectStandardOutput "$target\out.txt" -RedirectStandardError "$target\err.txt" -NoNewWindow -PassThru
        $deadline = (Get-Date).AddSeconds([int]($(if ($secs) { $secs } else { 120 })))
        Write-Output "$(Get-Date -Format HH:mm:ss) started $target (pid $($proc.Id)) until $($deadline.ToString('HH:mm:ss'))"
        Set-Content -Path "$Dir\response.txt" -Value "STARTED $($proc.Id) $target" -Encoding UTF8
    } catch {
        Write-Output "$(Get-Date -Format HH:mm:ss) $req -> ERR $($_.Exception.Message)"
        Set-Content -Path "$Dir\response.txt" -Value "ERR $($_.Exception.Message)" -Encoding UTF8
    }
}
if ($proc) { Finish 'agent stopped' }
Remove-Item "$Dir\stop" -ErrorAction SilentlyContinue
Write-Output 'wsa-reborn-host agent: stopped'
