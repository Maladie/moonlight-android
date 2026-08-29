#requires -Version 5.1
[CmdletBinding()]
param([string]$GatewayDirectory = $PSScriptRoot)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function Get-ListeningOwnerPids([int]$Port) {
    $values = @(Get-NetTCPConnection -State Listen -LocalPort $Port `
        -ErrorAction SilentlyContinue | Select-Object -ExpandProperty OwningProcess -Unique)
    if ($values.Count -gt 0) { return $values }
    $pattern = "^\s*TCP\s+\S+:$Port\s+\S+\s+LISTENING\s+(\d+)\s*$"
    return @(& netstat.exe -ano -p TCP | ForEach-Object {
        if ($_ -match $pattern) { [int]$matches[1] }
    } | Sort-Object -Unique)
}

$GatewayDirectory = [IO.Path]::GetFullPath($GatewayDirectory)
New-Item -ItemType File -Path (Join-Path $GatewayDirectory "gateway-manually-stopped") -Force | Out-Null
New-Item -ItemType File -Path (Join-Path $GatewayDirectory "gateway-supervisor-stop") -Force | Out-Null
$port = 8785
try { $port = [int](Get-Content -LiteralPath (Join-Path $GatewayDirectory "gateway.json") -Raw | ConvertFrom-Json).listen_port } catch {}
$runtimePid = 0
$runtimeStartedAt = 0L
try {
    $runtime = Get-Content -LiteralPath (Join-Path $GatewayDirectory "gateway-runtime.json") -Raw |
        ConvertFrom-Json
    $runtimePid = [int]$runtime.pid
    $runtimeStartedAt = [int64]$runtime.started_at
} catch {}
$supervisorPid = 0
try {
    $supervisorPid = [int](Get-Content -LiteralPath (
        Join-Path $GatewayDirectory "gateway-supervisor-state.json") -Raw | ConvertFrom-Json).pid
} catch {}

$owners = @(Get-ListeningOwnerPids $port)
$stoppedOwnerPids = @()
foreach ($ownerPid in $owners) {
    $verified = $false
    if ($ownerPid -eq $runtimePid -and $runtimeStartedAt -gt 0) {
        try {
            $process = Get-Process -Id $ownerPid -ErrorAction Stop
            $startedAt = [DateTimeOffset]::new($process.StartTime).ToUnixTimeSeconds()
            $verified = [Math]::Abs($startedAt - $runtimeStartedAt) -le 5
        } catch {}
    }
    if (-not $verified) {
        $process = Get-CimInstance Win32_Process -Filter "ProcessId=$ownerPid" -ErrorAction SilentlyContinue
        $verified = $process -and [string]$process.CommandLine -like "*wakeplay_gateway.py*"
    }
    if (-not $verified) {
        throw "Refusing to stop uncorrelated process PID $ownerPid on Gateway port $port."
    }
    Stop-Process -Id $ownerPid -Force -ErrorAction Stop
    $stoppedOwnerPids += $ownerPid
}

$deadline = [DateTime]::UtcNow.AddSeconds(12)
while ([DateTime]::UtcNow -lt $deadline) {
    $listener = @(Get-ListeningOwnerPids $port)
    $supervisor = if ($supervisorPid -gt 0) {
        Get-Process -Id $supervisorPid -ErrorAction SilentlyContinue
    } else { $null }
    $runningOwners = @($stoppedOwnerPids | Where-Object {
        Get-Process -Id $_ -ErrorAction SilentlyContinue
    })
    if (-not $listener -and -not $supervisor -and $runningOwners.Count -eq 0) { return }
    Start-Sleep -Milliseconds 200
}
if (@(Get-ListeningOwnerPids $port).Count -gt 0) {
    throw "MoonWaker Gateway did not release port $port."
}
if ($supervisorPid -gt 0 -and (Get-Process -Id $supervisorPid -ErrorAction SilentlyContinue)) {
    throw "MoonWaker Gateway supervisor PID $supervisorPid did not stop."
}
$runningOwners = @($stoppedOwnerPids | Where-Object {
    Get-Process -Id $_ -ErrorAction SilentlyContinue
})
if ($runningOwners.Count -gt 0) {
    throw "MoonWaker Gateway process PID $($runningOwners -join ', ') did not stop."
}
