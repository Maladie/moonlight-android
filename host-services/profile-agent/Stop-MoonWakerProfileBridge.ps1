#requires -Version 5.1
[CmdletBinding()]
param([ValidateNotNullOrEmpty()][string]$ProfileRoot = $PSScriptRoot)
$ErrorActionPreference = "SilentlyContinue"
$ProfileRoot = [IO.Path]::GetFullPath($ProfileRoot).TrimEnd('\')
$statePath = Join-Path $ProfileRoot "profile-bridge-state.json"
$stopPath = Join-Path $ProfileRoot "profile-bridge-stop"
New-Item -ItemType File -Path (Join-Path $ProfileRoot "profile-bridge-manually-stopped") -Force | Out-Null
New-Item -ItemType File -Path $stopPath -Force | Out-Null
$stopScripts = @(
    (Join-Path $ProfileRoot "discord\Stop-DiscordBridge.ps1"),
    (Join-Path $ProfileRoot "vibepollo\Stop-VibepolloBridge.ps1"),
    (Join-Path $ProfileRoot "game-provider\Stop-PlayniteBridge.ps1"))
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
    @{ name = "Game Provider"; directory = "game-provider"; config = "config.json"; property = "listen_port" })) {
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
            $verifiedProvider = $false
            if ($component.name -eq "Game Provider" -and
                [string]::IsNullOrWhiteSpace($commandLine)) {
                try {
                    $observation = Invoke-RestMethod `
                        -Uri "http://127.0.0.1:$port/observation" -TimeoutSec 2
                    $verifiedProvider = [string]$observation.producer.component -eq "game-provider" -and
                        [int]$observation.producer.process_id -eq [int]$ownerPid -and
                        -not [string]::IsNullOrWhiteSpace([string]$observation.producer.version)
                } catch {}
            }
            if ($commandLine -like "*$ProfileRoot*" -or $commandLine -like "*Bridge*" -or
                $commandLine -like "*GameProviderBridge.py*" -or $verifiedProvider) {
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
