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
$gatewayDirectories = @($GatewayDirectory)
foreach ($legacyDirectory in @("C:\Tools\WakePlayHost\gateway", "C:\Tools\WakePlayGateway")) {
    if (-not $legacyDirectory.Equals($GatewayDirectory, [StringComparison]::OrdinalIgnoreCase) -and
        (Test-Path -LiteralPath (Join-Path $legacyDirectory "gateway.json") -PathType Leaf)) {
        $gatewayDirectories += $legacyDirectory
        New-Item -ItemType File -Path (Join-Path $legacyDirectory "gateway-manually-stopped"),
            (Join-Path $legacyDirectory "gateway-supervisor-stop") -Force | Out-Null
    }
}
$service = Get-Service -Name "MoonWakerGateway" -ErrorAction SilentlyContinue
if ($null -ne $service) {
    if ($service.Status -ne [ServiceProcess.ServiceControllerStatus]::Stopped) {
        Stop-Service -Name $service.Name
        $service.WaitForStatus(
            [ServiceProcess.ServiceControllerStatus]::Stopped,
            [TimeSpan]::FromSeconds(15))
    }
    Write-Host "MoonWaker Gateway service is stopped."
}
New-Item -ItemType File -Path (Join-Path $GatewayDirectory "gateway-manually-stopped") -Force | Out-Null
New-Item -ItemType File -Path (Join-Path $GatewayDirectory "gateway-supervisor-stop") -Force | Out-Null
$port = 8785
try { $port = [int](Get-Content -LiteralPath (Join-Path $GatewayDirectory "gateway.json") -Raw | ConvertFrom-Json).listen_port } catch {}
$runtimes = @()
foreach ($directory in $gatewayDirectories) {
    try {
        $runtime = Get-Content -LiteralPath (Join-Path $directory "gateway-runtime.json") -Raw |
            ConvertFrom-Json
        $runtimes += [pscustomobject]@{
            pid = [int]$runtime.pid
            started_at = [int64]$runtime.started_at
        }
    } catch {}
}

$owners = @(Get-ListeningOwnerPids $port)
$stoppedOwnerPids = @()
foreach ($ownerPid in $owners) {
    $verified = $false
    $runtime = @($runtimes | Where-Object { $_.pid -eq $ownerPid } | Select-Object -First 1)
    if ($runtime.Count -eq 1 -and $runtime[0].started_at -gt 0) {
        try {
            $process = Get-Process -Id $ownerPid -ErrorAction Stop
            $startedAt = [DateTimeOffset]::new($process.StartTime).ToUnixTimeSeconds()
            $verified = [Math]::Abs($startedAt - $runtime[0].started_at) -le 5
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
    $runningOwners = @($stoppedOwnerPids | Where-Object {
        Get-Process -Id $_ -ErrorAction SilentlyContinue
    })
    if (-not $listener -and $runningOwners.Count -eq 0) { break }
    Start-Sleep -Milliseconds 200
}
if (@(Get-ListeningOwnerPids $port).Count -gt 0) {
    throw "MoonWaker Gateway did not release port $port."
}
$runningOwners = @($stoppedOwnerPids | Where-Object {
    Get-Process -Id $_ -ErrorAction SilentlyContinue
})
if ($runningOwners.Count -gt 0) {
    throw "MoonWaker Gateway process PID $($runningOwners -join ', ') did not stop."
}

$workerStopper = Join-Path $PSScriptRoot "Stop-MoonWakerGatewayWorkers.ps1"
if (Test-Path -LiteralPath $workerStopper) {
    & $workerStopper -GatewayDirectory $GatewayDirectory
}
