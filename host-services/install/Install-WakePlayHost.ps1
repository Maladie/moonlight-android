#requires -Version 5.1
[CmdletBinding()]
param(
    [string]$InstallDirectory = "C:\Tools\WakePlayHost",
    [string]$GatewayDirectory = "",
    [int]$GatewayPort = 8785,
    [switch]$SkipFirewall,
    [switch]$SkipScheduledTask,
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
$gatewaySource = Join-Path $hostServicesRoot "gateway"
$bridgeSource = Join-Path $hostServicesRoot "bridges"
$profileAgentSource = Join-Path $hostServicesRoot "profile-agent"
$controlSource = Join-Path $hostServicesRoot "control"
if (-not (Test-Path -LiteralPath $gatewaySource) -or
    -not (Test-Path -LiteralPath $bridgeSource) -or
    -not (Test-Path -LiteralPath $profileAgentSource) -or
    -not (Test-Path -LiteralPath $controlSource)) {
    throw "Run this installer from the versioned host-services package."
}

if ([string]::IsNullOrWhiteSpace($GatewayDirectory)) {
    $GatewayDirectory = Join-Path $InstallDirectory "gateway"
}
$sourceDirectory = Join-Path $InstallDirectory "bridge-source"
$profileAgentDirectory = Join-Path $InstallDirectory "profile-agent"
$controlDirectory = Join-Path $InstallDirectory "control"
$installScripts = Join-Path $InstallDirectory "install"
New-Item -ItemType Directory -Path $InstallDirectory, $sourceDirectory, $profileAgentDirectory, `
    $controlDirectory, $installScripts -Force | Out-Null

Copy-Item -LiteralPath (Join-Path $bridgeSource "discord") `
    -Destination $sourceDirectory -Recurse -Force
Copy-Item -LiteralPath (Join-Path $bridgeSource "vibepollo") `
    -Destination $sourceDirectory -Recurse -Force
Copy-Item -LiteralPath (Join-Path $bridgeSource "playnite") `
    -Destination $sourceDirectory -Recurse -Force
Copy-Item -Path (Join-Path $profileAgentSource "*") `
    -Destination $profileAgentDirectory -Recurse -Force
Copy-Item -Path (Join-Path $controlSource "*") `
    -Destination $controlDirectory -Recurse -Force
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
