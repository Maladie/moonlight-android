#requires -Version 5.1
#requires -RunAsAdministrator
[CmdletBinding()]
param(
    [string]$InstallDirectory = (Join-Path (
        [Environment]::GetFolderPath([Environment+SpecialFolder]::ProgramFiles)) "MoonWaker"),
    [switch]$PurgeCredentials,
    [switch]$RemoveFiles
)

$ErrorActionPreference = "Stop"
$root = [IO.Path]::GetFullPath($InstallDirectory).TrimEnd('\')
if ($RemoveFiles) {
    $versionPath = Join-Path $root "version.json"
    $product = try {
        [string](Get-Content -LiteralPath $versionPath -Raw | ConvertFrom-Json).product
    } catch { "" }
    if (-not $PurgeCredentials -or $root -eq [IO.Path]::GetPathRoot($root) -or
        $product -ne "MoonWaker Host") {
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

if (Test-Path -LiteralPath $gatewayUninstall -PathType Leaf) {
    & $gatewayUninstall
}
if ($PurgeCredentials) {
    $profilesRoot = Join-Path $root "profiles"
    if (Test-Path -LiteralPath $profilesRoot -PathType Container) {
        Get-ChildItem -LiteralPath $profilesRoot -Directory | ForEach-Object {
            $stopProfile = Join-Path $_.FullName "Stop-MoonWakerProfileBridge.ps1"
            if (Test-Path -LiteralPath $stopProfile -PathType Leaf) {
                & $stopProfile -ProfileRoot $_.FullName
            }
        }
    }
    if (-not (Test-Path -LiteralPath $credentialCleanup -PathType Leaf)) {
        throw "Login Broker credential cleanup is missing; Login Broker and provider were left installed."
    }
    & $credentialCleanup -GatewayConfigPath (Join-Path $root "gateway\gateway.json")

    $taskPrefixes = @(
        "MoonWaker Profile Bridge (",
        "MoonWaker Gateway Supervisor",
        "Wake & Play Host Gateway",
        "Wake & Play Discord Bridge (",
        "Wake & Play Vibepollo Bridge (",
        "Wake & Play Game Provider Bridge (",
        "Wake & Play Playnite Bridge ("
    )
    Get-ScheduledTask -ErrorAction SilentlyContinue | Where-Object {
        $taskName = [string]$_.TaskName
        @($taskPrefixes | Where-Object {
            $taskName.StartsWith($_, [StringComparison]::OrdinalIgnoreCase)
        }).Count -gt 0
    } | ForEach-Object {
        Stop-ScheduledTask -TaskName $_.TaskName -ErrorAction SilentlyContinue
        Unregister-ScheduledTask -TaskName $_.TaskName -Confirm:$false `
            -ErrorAction SilentlyContinue
    }

    $startupDirectories = @(
        (Join-Path $env:APPDATA "Microsoft\Windows\Start Menu\Programs\Startup"),
        (Join-Path $env:ProgramData "Microsoft\Windows\Start Menu\Programs\Startup")
    )
    foreach ($startup in $startupDirectories) {
        if (-not (Test-Path -LiteralPath $startup -PathType Container)) { continue }
        Get-ChildItem -LiteralPath $startup -File -ErrorAction SilentlyContinue |
            Where-Object {
                $_.Name -in @("MoonWaker Host Control.lnk", "MoonWaker Gateway.lnk") -or
                ($_.Name.StartsWith("MoonWaker Profile Bridge (",
                    [StringComparison]::OrdinalIgnoreCase) -and $_.Extension -eq ".lnk")
            } | Remove-Item -Force -ErrorAction SilentlyContinue
    }
    $runKey = "HKCU:\Software\Microsoft\Windows\CurrentVersion\Run"
    $runValues = Get-ItemProperty -Path $runKey -ErrorAction SilentlyContinue
    if ($null -ne $runValues) {
        foreach ($property in $runValues.PSObject.Properties) {
            if ($property.Name.StartsWith("MoonWaker Profile Bridge (",
                    [StringComparison]::OrdinalIgnoreCase) -or
                $property.Name -in @("MoonWaker Host Control", "MoonWaker Gateway")) {
                Remove-ItemProperty -Path $runKey -Name $property.Name `
                    -ErrorAction SilentlyContinue
            }
        }
    }
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
