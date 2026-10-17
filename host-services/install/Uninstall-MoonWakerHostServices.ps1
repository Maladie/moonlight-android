#requires -Version 5.1
#requires -RunAsAdministrator
[CmdletBinding()]
param(
    [switch]$PurgeCredentials,
    [switch]$RemoveFiles,
    # Components the host installer adds next to MoonWaker; each leaves through its own
    # uninstaller after MoonWaker's services stop. Legendary lives in the installation
    # directory (with each profile's Epic sign-in) and leaves with -RemoveFiles.
    [ValidateSet("Vibepollo", "ViGEmBus", "Python", "OpenSSL")][string[]]$RemoveComponents = @()
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
Get-NetFirewallRule -DisplayName "Wake & Play Host Gateway (Private LAN)" -ErrorAction SilentlyContinue |
    Remove-NetFirewallRule -ErrorAction SilentlyContinue
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
    # Trays, Agents, bridges and their supervisors hold files of the installation.
    & (Join-Path $PSScriptRoot "Stop-MoonWakerInstalledProcesses.ps1") -Root $root
    Remove-Item -LiteralPath $root -Recurse -Force
    $startMenu = Join-Path $env:ProgramData "Microsoft\Windows\Start Menu\Programs\MoonWaker"
    if (Test-Path -LiteralPath $startMenu) { Remove-Item -LiteralPath $startMenu -Recurse -Force }
    $stateRoot = Join-Path $env:ProgramData "MoonWaker"
    if ((Test-Path -LiteralPath $stateRoot) -and -not (Get-ChildItem -LiteralPath $stateRoot -Force)) {
        Remove-Item -LiteralPath $stateRoot -Force
    }
    Write-Host "MoonWaker services, profiles, Legendary, credentials, and installation files were removed."
} elseif ($PurgeCredentials) {
    Write-Host "MoonWaker machine services and configured Login Broker credentials were removed."
} else {
    Write-Host "MoonWaker machine services were removed. This service-only command preserves profiles, Gateway configuration, and stored Login Broker credentials."
}

# Display names as the products register them in Apps & features (hidden parts excluded).
$products = [ordered]@{
    Vibepollo = @("*Vibepollo*")
    ViGEmBus = @("*ViGEmBus*", "*Virtual Gamepad Emulation Bus*")
    Python = @("Python 3.12.*")
    OpenSSL = @("OpenSSL*")
}
$uninstallKeys = @(
    "HKLM:\SOFTWARE\Microsoft\Windows\CurrentVersion\Uninstall\*",
    "HKLM:\SOFTWARE\WOW6432Node\Microsoft\Windows\CurrentVersion\Uninstall\*")
function Get-ProductEntries([string[]]$Patterns) {
    @(Get-ItemProperty $uninstallKeys -ErrorAction SilentlyContinue | Where-Object {
        $entry = $_
        $entry.SystemComponent -ne 1 -and $entry.UninstallString -and
            @($Patterns | Where-Object { [string]$entry.DisplayName -like $_ }).Count -gt 0
    })
}
foreach ($component in $products.Keys) {
    if ($RemoveComponents -notcontains $component) { continue }
    $patterns = $products[$component]
    foreach ($entry in Get-ProductEntries $patterns) {
        $command = if ($entry.QuietUninstallString) { [string]$entry.QuietUninstallString } else {
            [string]$entry.UninstallString }
        $msi = [regex]::Match($command, '(?i)msiexec(?:\.exe)?"?\s+/[IX]\s*(\{[0-9A-F-]+\})')
        if ($msi.Success) {
            $file = Join-Path ([Environment]::SystemDirectory) "msiexec.exe"
            $arguments = "/x $($msi.Groups[1].Value) /qn /norestart"
        } else {
            $parts = [regex]::Match($command.Trim(), '^(?:"([^"]+)"|(\S+))\s*(.*)$')
            $file = $parts.Groups[1].Value + $parts.Groups[2].Value
            $arguments = $parts.Groups[3].Value
        }
        Write-Host "Removing $($entry.DisplayName)..."
        $process = if ($arguments) { Start-Process -FilePath $file -ArgumentList $arguments -Wait -PassThru }
            else { Start-Process -FilePath $file -Wait -PassThru }
        if ($process.ExitCode -notin @(0, 3010, 1605)) {
            Write-Warning "$($entry.DisplayName) uninstaller ended with code $($process.ExitCode)."
        }
    }
    # Some uninstallers hand over to a copy of themselves and return at once.
    $deadline = [DateTime]::UtcNow.AddMinutes(10)
    while ((Get-ProductEntries $patterns).Count -gt 0 -and [DateTime]::UtcNow -lt $deadline) {
        Start-Sleep -Seconds 2
    }
    if ((Get-ProductEntries $patterns).Count -gt 0) {
        Write-Warning "$component is still installed; remove it in Windows Settings > Apps."
    } else {
        Write-Host "$component was removed."
    }
}
