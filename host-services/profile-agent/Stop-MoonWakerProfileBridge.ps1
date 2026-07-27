#requires -Version 5.1
[CmdletBinding()]
param([string]$ProfileRoot = $PSScriptRoot)
$ErrorActionPreference = "SilentlyContinue"
$statePath = Join-Path $ProfileRoot "profile-bridge-state.json"
$stopPath = Join-Path $ProfileRoot "profile-bridge-stop"
New-Item -ItemType File -Path $stopPath -Force | Out-Null
$stopScripts = @(
    (Join-Path $ProfileRoot "discord\Stop-DiscordBridge.ps1"),
    (Join-Path $ProfileRoot "vibepollo\Stop-VibepolloBridge.ps1"),
    (Join-Path $ProfileRoot "playnite\Stop-PlayniteBridge.ps1"))
foreach ($script in $stopScripts) {
    if (Test-Path -LiteralPath $script) { try { & $script | Out-Null } catch {} }
}
$supervisorPid = 0
try { $supervisorPid = [int](Get-Content -LiteralPath $statePath -Raw | ConvertFrom-Json).supervisor_pid } catch {}
for ($attempt = 0; $attempt -lt 20 -and $supervisorPid -gt 0; $attempt++) {
    if (-not (Get-Process -Id $supervisorPid -ErrorAction SilentlyContinue)) { break }
    Start-Sleep -Milliseconds 250
}
if ($supervisorPid -gt 0 -and (Get-Process -Id $supervisorPid -ErrorAction SilentlyContinue)) {
    $commandLine = [string](Get-CimInstance Win32_Process -Filter "ProcessId=$supervisorPid").CommandLine
    if ($commandLine -like "*MoonWakerProfileBridge.ps1*" -and $commandLine -like "*$ProfileRoot*") {
        Stop-Process -Id $supervisorPid -Force
    }
}

$stuck = @()
foreach ($component in @(
    @{ name = "Discord"; directory = "discord"; config = "discord_bridge_config.json"; property = "port" },
    @{ name = "Vibepollo"; directory = "vibepollo"; config = "config.json"; property = "listen_port" },
    @{ name = "Playnite"; directory = "playnite"; config = "config.json"; property = "listen_port" })) {
    $configPath = Join-Path (Join-Path $ProfileRoot $component.directory) $component.config
    if (-not (Test-Path -LiteralPath $configPath)) { continue }
    try {
        $config = Get-Content -LiteralPath $configPath -Raw | ConvertFrom-Json
        $port = [int]$config.($component.property)
        $owners = @(Get-NetTCPConnection -State Listen -LocalPort $port -ErrorAction SilentlyContinue |
            Select-Object -ExpandProperty OwningProcess -Unique)
        foreach ($ownerPid in $owners) {
            $process = Get-CimInstance Win32_Process -Filter "ProcessId=$ownerPid" -ErrorAction SilentlyContinue
            $commandLine = if ($process) { [string]$process.CommandLine } else { "" }
            if ($commandLine -like "*$ProfileRoot*" -or $commandLine -like "*Bridge*" -or
                $commandLine -like "*PlayniteBridge.py*") {
                Stop-Process -Id $ownerPid -Force -ErrorAction SilentlyContinue
            }
        }
        Start-Sleep -Milliseconds 250
        if (Get-NetTCPConnection -State Listen -LocalPort $port -ErrorAction SilentlyContinue) {
            $stuck += "$($component.name) (port $port)"
        }
    } catch { $stuck += $component.name }
}
if ($stuck.Count) {
    throw "Unable to stop: $($stuck -join ', '). Administrator permission is required to remove the stale Bridge process."
}
