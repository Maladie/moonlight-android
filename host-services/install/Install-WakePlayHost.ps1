#requires -Version 5.1
[CmdletBinding()]
param(
    [string]$InstallDirectory = "C:\Tools\WakePlayHost",
    [string]$GatewayDirectory = "",
    [int]$GatewayPort = 8785,
    [switch]$SkipFirewall,
    [switch]$SkipScheduledTask,
    [switch]$SkipEpicLegendary,
    [switch]$SkipStart
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$principal = [Security.Principal.WindowsPrincipal]::new(
    [Security.Principal.WindowsIdentity]::GetCurrent())
if (-not $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
    throw "Install-WakePlayHost.ps1 must run from an elevated PowerShell prompt."
}

$hostServicesRoot = Split-Path -Parent $PSScriptRoot
$versionSource = Join-Path $hostServicesRoot "version.json"
$gatewaySource = Join-Path $hostServicesRoot "gateway"
$bridgeSource = Join-Path $hostServicesRoot "bridges"
$profileAgentSource = Join-Path $hostServicesRoot "profile-agent"
$controlSource = Join-Path $hostServicesRoot "control"
$toolsSource = Join-Path $hostServicesRoot "tools"
if (-not (Test-Path -LiteralPath $gatewaySource) -or
    -not (Test-Path -LiteralPath $bridgeSource) -or
    -not (Test-Path -LiteralPath $profileAgentSource) -or
    -not (Test-Path -LiteralPath $controlSource)) {
    throw "Run this installer from the versioned host-services package."
}
if (-not (Test-Path -LiteralPath $versionSource)) {
    throw "The MoonWaker version manifest is missing from the host package."
}

if ([string]::IsNullOrWhiteSpace($GatewayDirectory)) {
    $GatewayDirectory = Join-Path $InstallDirectory "gateway"
}
$sourceDirectory = Join-Path $InstallDirectory "bridge-source"
$profileAgentDirectory = Join-Path $InstallDirectory "profile-agent"
$controlDirectory = Join-Path $InstallDirectory "control"
$toolsDirectory = Join-Path $InstallDirectory "tools"
$installScripts = Join-Path $InstallDirectory "install"

function Stop-ExistingGatewayForUpdate {
    param([string]$Directory)
    $stopScript = Join-Path $Directory "Stop-MoonWakerGateway.ps1"
    if (-not (Test-Path -LiteralPath $stopScript)) { return }
    Write-Host "Stopping the existing MoonWaker Gateway before update..."
    & $stopScript -GatewayDirectory $Directory
    $deadline = [DateTime]::UtcNow.AddSeconds(15)
    while ([DateTime]::UtcNow -lt $deadline) {
        $statePath = Join-Path $Directory "gateway-supervisor-state.json"
        $running = $false
        try {
            $state = Get-Content -LiteralPath $statePath -Raw | ConvertFrom-Json
            $process = Get-Process -Id ([int]$state.pid) -ErrorAction SilentlyContinue
            $running = $process -and -not $process.HasExited
        } catch {}
        if (-not $running) { return }
        Start-Sleep -Milliseconds 200
    }
    throw "The existing MoonWaker Gateway supervisor did not stop in time."
}

function Stop-MoonWakerHostControlForUpdate {
    # Host Control can be started independently from more than one Windows
    # profile. Stop every running copy immediately before replacing its files;
    # closing only the instance seen by the GUI leaves a short restart race.
    $deadline = [DateTime]::UtcNow.AddSeconds(12)
    do {
        $running = @(Get-Process -Name "MoonWakerHostControl" -ErrorAction SilentlyContinue)
        if ($running.Count -eq 0) { return }
        foreach ($process in $running) {
            try { Stop-Process -Id $process.Id -Force -ErrorAction Stop } catch {}
        }
        Start-Sleep -Milliseconds 250
    } while ([DateTime]::UtcNow -lt $deadline)
    $remaining = @(Get-Process -Name "MoonWakerHostControl" -ErrorAction SilentlyContinue)
    if ($remaining.Count -gt 0) {
        throw "MoonWaker Host Control did not close in time. Close it on every Windows profile and try again."
    }
}

if (Test-Path -LiteralPath (Join-Path $GatewayDirectory "gateway.json")) {
    Stop-ExistingGatewayForUpdate $GatewayDirectory
}
New-Item -ItemType Directory -Path $InstallDirectory, $sourceDirectory, $profileAgentDirectory, `
    $controlDirectory, $toolsDirectory, $installScripts -Force | Out-Null
Copy-Item -LiteralPath $versionSource -Destination (Join-Path $InstallDirectory "version.json") -Force

Copy-Item -LiteralPath (Join-Path $bridgeSource "discord") `
    -Destination $sourceDirectory -Recurse -Force
Copy-Item -LiteralPath (Join-Path $bridgeSource "vibepollo") `
    -Destination $sourceDirectory -Recurse -Force
Copy-Item -LiteralPath (Join-Path $bridgeSource "playnite") `
    -Destination $sourceDirectory -Recurse -Force
Copy-Item -Path (Join-Path $profileAgentSource "*") `
    -Destination $profileAgentDirectory -Recurse -Force
# Keep existing profile supervisors on the current safety logic. These scripts
# contain no credentials; profile-specific tokens and configuration stay put.
$profilesDirectory = Join-Path $InstallDirectory "profiles"
if (Test-Path -LiteralPath $profilesDirectory) {
    Get-ChildItem -LiteralPath $profilesDirectory -Directory | ForEach-Object {
        foreach ($name in @("MoonWakerProfileBridge.ps1", "Start-MoonWakerProfileBridge.ps1", "Stop-MoonWakerProfileBridge.ps1")) {
            Copy-Item -LiteralPath (Join-Path $profileAgentSource $name) `
                -Destination (Join-Path $_.FullName $name) -Force
        }
    }
}
# Do this at the last possible moment. A profile's Startup entry can relaunch
# Host Control after the installer GUI originally closed it.
Stop-MoonWakerHostControlForUpdate
Copy-Item -Path (Join-Path $controlSource "*") `
    -Destination $controlDirectory -Recurse -Force
if (-not $SkipEpicLegendary -and
        (Test-Path -LiteralPath (Join-Path $toolsSource "legendary\legendary.exe"))) {
    Copy-Item -LiteralPath (Join-Path $toolsSource "legendary") `
        -Destination $toolsDirectory -Recurse -Force
}
Copy-Item -LiteralPath (Join-Path $PSScriptRoot "Install-WakePlayProfile.ps1") `
    -Destination $installScripts -Force

$gatewayInstaller = Join-Path $gatewaySource "Install-WakePlayGateway.ps1"
& $gatewayInstaller -InstallDirectory $GatewayDirectory -Port $GatewayPort `
    -SkipFirewall:$SkipFirewall `
    -SkipStart:$SkipStart

# Scheduled tasks created by earlier versions were bound to the elevated
# installer account.  Their failures block normal users from controlling the
# host, so migrate them to per-user Startup shortcuts during the next profile
# update.
Get-ScheduledTask -ErrorAction SilentlyContinue | Where-Object {
    $_.TaskName -like "Wake & Play Host Gateway*" -or $_.TaskName -like "MoonWaker Profile Bridge*" -or
    $_.TaskName -like "Wake & Play * Bridge*"
} | ForEach-Object {
    try { Unregister-ScheduledTask -TaskName $_.TaskName -Confirm:$false -ErrorAction Stop } catch {}
}

$controlExe = Join-Path $controlDirectory "MoonWakerHostControl.exe"
if (Test-Path -LiteralPath $controlExe) {
    $startMenu = Join-Path $env:ProgramData "Microsoft\Windows\Start Menu\Programs\MoonWaker"
    New-Item -ItemType Directory -Path $startMenu -Force | Out-Null
    $shell = New-Object -ComObject WScript.Shell
    $shortcut = $shell.CreateShortcut((Join-Path $startMenu "MoonWaker Host Control.lnk"))
    $shortcut.TargetPath = $controlExe
    $shortcut.WorkingDirectory = $controlDirectory
    $shortcut.Description = "Sterowanie Gatewayem i profilami MoonWaker"
    $shortcut.Save()
}

Write-Host "Wake & Play host components installed in $InstallDirectory" -ForegroundColor Green
Write-Host "Next, run install\Install-WakePlayProfile.ps1 as each target Windows user."
Write-Host "Gateway configuration: $(Join-Path $GatewayDirectory 'gateway.json')"
