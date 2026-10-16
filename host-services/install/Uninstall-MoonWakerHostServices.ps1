#requires -Version 5.1
#requires -RunAsAdministrator
[CmdletBinding()]
param(
    [switch]$PurgeCredentials,
    [switch]$RemoveFiles
)

$ErrorActionPreference = "Stop"
$root = Join-Path ([Environment]::GetFolderPath(
    [Environment+SpecialFolder]::ProgramFiles)) "MoonWaker"
if ($RemoveFiles) {
    $versionPath = Join-Path $root "version.json"
    $product = try {
        [string](Get-Content -LiteralPath $versionPath -Raw | ConvertFrom-Json).product
    } catch { "" }
    if (-not $PurgeCredentials -or $product -ne "MoonWaker Host") {
        throw "Full removal requires a verified MoonWaker installation and -PurgeCredentials."
    }
}
$gatewayUninstall = Join-Path $root "gateway\Uninstall-MoonWakerGatewayService.ps1"
$credentialCleanup = Join-Path $root `
    "windows-login\login-broker\Remove-MoonWakerLoginCredentials.ps1"
$providerUninstall = Join-Path $root `
    "windows-login\credential-provider\Uninstall-MoonWakerCredentialProvider.ps1"
$brokerUninstall = Join-Path $root `
    "windows-login\login-broker\Uninstall-MoonWakerLoginBroker.ps1"
$updaterUninstall = Join-Path $root "updater\Uninstall-MoonWakerHotUpdater.ps1"

if (Test-Path -LiteralPath $updaterUninstall -PathType Leaf) {
    & $updaterUninstall -PurgeState:$PurgeCredentials
}

if (Test-Path -LiteralPath $gatewayUninstall -PathType Leaf) {
    & $gatewayUninstall
}
# Profile processes are children of Host Control's tray (killed with it before uninstall).
Remove-ItemProperty -Path "HKLM:\SOFTWARE\Microsoft\Windows\CurrentVersion\Run" `
    -Name "MoonWaker Host Control" -ErrorAction SilentlyContinue
if ($PurgeCredentials) {
    if (-not (Test-Path -LiteralPath $credentialCleanup -PathType Leaf)) {
        throw "Login Broker credential cleanup is missing; Login Broker and provider were left installed."
    }
    & $credentialCleanup -GatewayConfigPath (Join-Path $root "gateway\gateway.json")
}
if (Test-Path -LiteralPath $providerUninstall -PathType Leaf) {
    & $providerUninstall -Confirm:$false
}
if (Test-Path -LiteralPath $brokerUninstall -PathType Leaf) {
    & $brokerUninstall -InstallDirectory (Split-Path -Parent $brokerUninstall)
}
if ($RemoveFiles) {
    Remove-Item -LiteralPath $root -Recurse -Force
    Write-Host "MoonWaker services, profiles, credentials, and installation files were removed."
} elseif ($PurgeCredentials) {
    Write-Host "MoonWaker machine services and configured Login Broker credentials were removed."
} else {
    Write-Host "MoonWaker machine services were removed. This service-only command preserves profiles, Gateway configuration, and stored Login Broker credentials."
}
