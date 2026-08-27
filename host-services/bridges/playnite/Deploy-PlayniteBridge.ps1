#requires -Version 5.1
[CmdletBinding(SupportsShouldProcess = $true)]
param(
    [string]$ProfileRoot = "C:\Tools\WakePlayHost\profiles\default",
    [ValidateRange(5, 120)][int]$TimeoutSeconds = 30,
    [switch]$ForceRestart
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$ProfileRoot = [IO.Path]::GetFullPath($ProfileRoot)
$targetRoot = Join-Path $ProfileRoot "playnite"
$statePath = Join-Path $ProfileRoot "profile-bridge-state.json"
$configPath = Join-Path $targetRoot "config.json"
$runtimeFiles = @(
    "PlayniteBridge.py",
    "GameOperations.py",
    "OperationJournal.py",
    "Confirm-SteamOperation.ps1",
    "Invoke-GameLauncher.ps1"
)

foreach ($path in @($targetRoot, $statePath, $configPath)) {
    if (-not (Test-Path -LiteralPath $path)) {
        throw "Required installed Bridge path was not found: $path"
    }
}

$config = Get-Content -LiteralPath $configPath -Raw | ConvertFrom-Json
$port = [int]$config.listen_port
if ($port -lt 1 -or $port -gt 65535) {
    throw "The installed Bridge listen_port is invalid."
}
$healthUri = "http://127.0.0.1:$port/health"

function Get-ManagedBridgePid {
    $state = Get-Content -LiteralPath $statePath -Raw | ConvertFrom-Json
    return [int]$state.components.playnite.pid
}

function Get-BridgeHealth {
    try {
        return Invoke-RestMethod -Uri $healthUri -TimeoutSec 2
    } catch {
        return $null
    }
}

$changed = @()
foreach ($name in $runtimeFiles) {
    $source = Join-Path $PSScriptRoot $name
    $target = Join-Path $targetRoot $name
    if (-not (Test-Path -LiteralPath $source)) {
        throw "Bridge source file was not found: $source"
    }
    $sourceHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $source).Hash
    $targetHash = if (Test-Path -LiteralPath $target) {
        (Get-FileHash -Algorithm SHA256 -LiteralPath $target).Hash
    } else { "" }
    if ($sourceHash -ne $targetHash) {
        $changed += $name
    }
}

if ($changed.Count -eq 0 -and -not $ForceRestart) {
    [pscustomobject]@{
        changed = $false
        restarted = $false
        pid = Get-ManagedBridgePid
        files = @()
    }
    return
}

$oldPid = Get-ManagedBridgePid
$health = Get-BridgeHealth
if ($oldPid -le 0 -or $null -eq $health -or [int]$health.pid -ne $oldPid -or
    [string]$health.component -ne "playnite") {
    throw "The supervisor state does not match the active Playnite Bridge."
}

if (-not $PSCmdlet.ShouldProcess(
        $targetRoot, "Deploy changed Bridge files and restart managed PID $oldPid")) {
    return
}

foreach ($name in $changed) {
    Copy-Item -LiteralPath (Join-Path $PSScriptRoot $name) `
        -Destination (Join-Path $targetRoot $name) -Force
    $sourceHash = (Get-FileHash -Algorithm SHA256 `
        -LiteralPath (Join-Path $PSScriptRoot $name)).Hash
    $targetHash = (Get-FileHash -Algorithm SHA256 `
        -LiteralPath (Join-Path $targetRoot $name)).Hash
    if ($sourceHash -ne $targetHash) {
        throw "Bridge deployment hash mismatch for $name."
    }
}

Stop-Process -Id $oldPid -Force
$deadline = [DateTimeOffset]::UtcNow.AddSeconds($TimeoutSeconds)
$newPid = 0
$newHealth = $null
do {
    Start-Sleep -Milliseconds 250
    try { $newPid = Get-ManagedBridgePid } catch { $newPid = 0 }
    $newHealth = Get-BridgeHealth
    if ($newPid -gt 0 -and $newPid -ne $oldPid -and $null -ne $newHealth -and
        [int]$newHealth.pid -eq $newPid -and
        [string]$newHealth.component -eq "playnite" -and
        [bool]$newHealth.connector_connected) {
        break
    }
} while ([DateTimeOffset]::UtcNow -lt $deadline)

if ($newPid -le 0 -or $newPid -eq $oldPid -or $null -eq $newHealth -or
    [int]$newHealth.pid -ne $newPid -or
    -not [bool]$newHealth.connector_connected) {
    throw "The Playnite Bridge did not restart healthy within $TimeoutSeconds seconds."
}

[pscustomobject]@{
    changed = $changed.Count -gt 0
    restarted = $true
    previous_pid = $oldPid
    pid = $newPid
    connector_connected = [bool]$newHealth.connector_connected
    files = $changed
}
