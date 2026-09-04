#requires -Version 5.1
[CmdletBinding()]
param(
    [string]$InstallDirectory = (Join-Path (
        [Environment]::GetFolderPath([Environment+SpecialFolder]::ProgramFiles)) "MoonWaker"),
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

$InstallDirectory = [IO.Path]::GetFullPath($InstallDirectory).TrimEnd('\')
if ([string]::IsNullOrWhiteSpace($GatewayDirectory)) {
    $GatewayDirectory = Join-Path $InstallDirectory "gateway"
}
$GatewayDirectory = [IO.Path]::GetFullPath($GatewayDirectory).TrimEnd('\')
if ($InstallDirectory -eq [IO.Path]::GetPathRoot($InstallDirectory) -or
    -not $GatewayDirectory.Equals((Join-Path $InstallDirectory "gateway"),
        [StringComparison]::OrdinalIgnoreCase)) {
    throw "Choose a dedicated MoonWaker install directory; Gateway must be its 'gateway' child."
}
if (Test-Path -LiteralPath $InstallDirectory -PathType Leaf) {
    throw "The MoonWaker install path is a file."
}
if (Test-Path -LiteralPath $InstallDirectory -PathType Container) {
    $children = @(Get-ChildItem -LiteralPath $InstallDirectory -Force)
    $partialInstallMarker = Join-Path $InstallDirectory `
        "windows-login\login-broker\Install-MoonWakerLoginBroker.ps1"
    if ($children.Count -gt 0 -and
        -not (Test-Path -LiteralPath (Join-Path $InstallDirectory "version.json") -PathType Leaf) -and
        -not (Test-Path -LiteralPath $partialInstallMarker -PathType Leaf)) {
        throw "The selected nonempty directory is not a MoonWaker installation."
    }
}

$hostServicesRoot = Split-Path -Parent $PSScriptRoot
$versionSource = Join-Path $hostServicesRoot "version.json"
$gatewaySource = Join-Path $hostServicesRoot "gateway"
$bridgeSource = Join-Path $hostServicesRoot "bridges"
$profileAgentSource = Join-Path $hostServicesRoot "profile-agent"
$controlSource = Join-Path $hostServicesRoot "control"
$toolsSource = Join-Path $hostServicesRoot "tools"
$windowsLoginSource = Join-Path $hostServicesRoot "windows-login"
foreach ($required in @($versionSource, $gatewaySource, $bridgeSource,
        $profileAgentSource, $controlSource, $toolsSource, $windowsLoginSource)) {
    if (-not (Test-Path -LiteralPath $required)) {
        throw "Run this installer from the complete versioned host-services package. Missing: $required"
    }
}

function Assert-MoonWakerProcessesStopped {
    $runningControl = @(Get-Process -Name "MoonWakerHostControl", "MoonWakerHostConfigurator" `
        -ErrorAction SilentlyContinue)
    if ($runningControl.Count -gt 0) {
        $runningControl | ForEach-Object { $_.Dispose() }
        throw "Close MoonWaker Host Control and Host Configurator, then run the installer again."
    }

    try {
        $gatewayProcesses = @(Get-CimInstance Win32_Process -Property Name, CommandLine |
            Where-Object {
                $commandLine = [string]$_.CommandLine
                $commandLine.IndexOf($GatewayDirectory, [StringComparison]::OrdinalIgnoreCase) -ge 0 -and
                ($commandLine.IndexOf("wakeplay_gateway.py", [StringComparison]::OrdinalIgnoreCase) -ge 0 -or
                 $commandLine.IndexOf("MoonWakerGatewaySupervisor.ps1", [StringComparison]::OrdinalIgnoreCase) -ge 0 -or
                 $commandLine.IndexOf("Start-MoonWakerGateway.ps1", [StringComparison]::OrdinalIgnoreCase) -ge 0)
            })
    } catch {
        throw "Cannot verify that MoonWaker Gateway is stopped. Stop it in Host Control and retry."
    }
    if ($gatewayProcesses.Count -gt 0) {
        throw "MoonWaker Gateway is running. Stop it in Host Control and retry the update."
    }
}

$profilesToRestart = @()
foreach ($profilesRoot in @(
        (Join-Path $InstallDirectory "profiles"),
        "C:\Tools\WakePlayHost\profiles",
        "C:\Tools\WakePlayGateway\profiles")) {
    if (-not (Test-Path -LiteralPath $profilesRoot -PathType Container)) { continue }
    Get-ChildItem -LiteralPath $profilesRoot -Directory | ForEach-Object {
        $stopProfile = Join-Path $_.FullName "Stop-MoonWakerProfileBridge.ps1"
        if (Test-Path -LiteralPath $stopProfile -PathType Leaf) {
            & $stopProfile -ProfileRoot $_.FullName
            if ($profilesRoot.Equals((Join-Path $InstallDirectory "profiles"),
                    [StringComparison]::OrdinalIgnoreCase)) {
                $profilesToRestart += $_.Name
            }
        }
    }
}

foreach ($serviceName in @("MoonWakerGateway", "MoonWakerLoginBroker")) {
    $service = Get-Service -Name $serviceName -ErrorAction SilentlyContinue
    if ($null -ne $service -and
        $service.Status -ne [ServiceProcess.ServiceControllerStatus]::Stopped) {
        Stop-Service -Name $serviceName -Force
    }
}

Assert-MoonWakerProcessesStopped

$sourceDirectory = Join-Path $InstallDirectory "bridge-source"
$profileAgentDirectory = Join-Path $InstallDirectory "profile-agent"
$controlDirectory = Join-Path $InstallDirectory "control"
$toolsDirectory = Join-Path $InstallDirectory "tools"
$installScripts = Join-Path $InstallDirectory "install"
$stateDirectory = Join-Path $InstallDirectory "state"
$windowsLoginDirectory = Join-Path $InstallDirectory "windows-login"
$loginBrokerDirectory = Join-Path $windowsLoginDirectory "login-broker"
$installedProviderUninstall = Join-Path $windowsLoginDirectory `
    "credential-provider\Uninstall-MoonWakerCredentialProvider.ps1"
if (Test-Path -LiteralPath $installedProviderUninstall -PathType Leaf) {
    & $installedProviderUninstall -Confirm:$false
}
New-Item -ItemType Directory -Path $InstallDirectory, $sourceDirectory,
    $profileAgentDirectory, $controlDirectory, $toolsDirectory, $installScripts,
    $stateDirectory, $windowsLoginDirectory -Force | Out-Null

Copy-Item -LiteralPath (Join-Path $bridgeSource "discord") -Destination $sourceDirectory -Recurse -Force
Copy-Item -LiteralPath (Join-Path $bridgeSource "vibepollo") -Destination $sourceDirectory -Recurse -Force
Copy-Item -LiteralPath (Join-Path $bridgeSource "playnite") -Destination $sourceDirectory -Recurse -Force
Remove-Item -LiteralPath (Join-Path $sourceDirectory "playnite\PlayniteBridge.py") `
    -Force -ErrorAction SilentlyContinue
Copy-Item -Path (Join-Path $profileAgentSource "*") -Destination $profileAgentDirectory -Recurse -Force
Copy-Item -Path (Join-Path $windowsLoginSource "*") -Destination $windowsLoginDirectory -Recurse -Force

# Existing profile roots are user data. Refresh only the three supervisor scripts
# and leave integrations/configuration untouched.
$profilesDirectory = Join-Path $InstallDirectory "profiles"
if (Test-Path -LiteralPath $profilesDirectory -PathType Container) {
    Get-ChildItem -LiteralPath $profilesDirectory -Directory | ForEach-Object {
        foreach ($name in @("MoonWakerProfileBridge.ps1", "Start-MoonWakerProfileBridge.ps1",
                "Stop-MoonWakerProfileBridge.ps1")) {
            Copy-Item -LiteralPath (Join-Path $profileAgentSource $name) `
                -Destination (Join-Path $_.FullName $name) -Force
        }
        Copy-Item -LiteralPath $versionSource `
            -Destination (Join-Path $_.FullName "moonwaker-version.json") -Force
        foreach ($component in @(
                @{ source = "discord"; destination = "discord" },
                @{ source = "vibepollo"; destination = "vibepollo" },
                @{ source = "playnite"; destination = "game-provider" })) {
            $destination = Join-Path $_.FullName $component.destination
            if (Test-Path -LiteralPath $destination -PathType Container) {
                Copy-Item -Path (Join-Path (Join-Path $bridgeSource $component.source) "*") `
                    -Destination $destination -Recurse -Force
            }
        }
    }
}

Assert-MoonWakerProcessesStopped
Copy-Item -Path (Join-Path $controlSource "*") -Destination $controlDirectory -Recurse -Force
if (-not (Test-Path -LiteralPath (Join-Path $toolsSource "legendary\legendary.exe"))) {
    throw "The MoonWaker package does not contain the required Legendary executable."
}
Copy-Item -LiteralPath (Join-Path $toolsSource "legendary") -Destination $toolsDirectory -Recurse -Force
Copy-Item -LiteralPath (Join-Path $PSScriptRoot "Install-WakePlayProfile.ps1") `
    -Destination $installScripts -Force
Copy-Item -LiteralPath (Join-Path $PSScriptRoot "Uninstall-MoonWakerHostServices.ps1") `
    -Destination $installScripts -Force

$brokerInstaller = Join-Path $loginBrokerDirectory "Install-MoonWakerLoginBroker.ps1"
$brokerBinary = Join-Path $windowsLoginSource "login-broker\MoonWakerLoginBroker.exe"
& $brokerInstaller -BinaryPath $brokerBinary -InstallDirectory $loginBrokerDirectory `
    -SkipStart:$SkipStart

$providerDirectory = Join-Path $windowsLoginDirectory "credential-provider"
$providerInstaller = Join-Path $providerDirectory "Install-MoonWakerCredentialProvider.ps1"
$providerBinary = Join-Path $providerDirectory "MoonWakerCredentialProvider.dll"
if (-not (Test-Path -LiteralPath $providerBinary -PathType Leaf)) {
    throw "The MoonWaker Credential Provider binary is missing from the package."
}
& $providerInstaller -DllPath $providerBinary -Confirm:$false

$registryLockPath = Join-Path $GatewayDirectory "gateway.json.lock"
$gatewayInstaller = Join-Path $gatewaySource "Install-WakePlayGateway.ps1"
& $gatewayInstaller -InstallDirectory $GatewayDirectory -Port $GatewayPort `
    -SkipFirewall:$SkipFirewall -SkipStart:$SkipStart
if (-not (Test-Path -LiteralPath $registryLockPath -PathType Leaf)) {
    [IO.File]::WriteAllBytes($registryLockPath, [byte[]]@(0))
}

# Remove obsolete per-user tasks from older installers. New profile tasks are
# created explicitly by Host Control for the selected account.
if (-not $SkipScheduledTask) {
    Get-ScheduledTask -ErrorAction SilentlyContinue | Where-Object {
        $_.TaskName -like "Wake & Play Host Gateway*" -or
        $_.TaskName -eq "MoonWaker Gateway Supervisor" -or
        $_.TaskName -like "Wake & Play * Bridge*"
    } | ForEach-Object {
        try { Unregister-ScheduledTask -TaskName $_.TaskName -Confirm:$false -ErrorAction Stop } catch {}
    }
}

$controlExe = Join-Path $controlDirectory "MoonWakerHostControl.exe"
if (Test-Path -LiteralPath $controlExe -PathType Leaf) {
    $startMenu = Join-Path $env:ProgramData "Microsoft\Windows\Start Menu\Programs\MoonWaker"
    New-Item -ItemType Directory -Path $startMenu -Force | Out-Null
    $shell = New-Object -ComObject WScript.Shell
    $shortcut = $shell.CreateShortcut((Join-Path $startMenu "MoonWaker Host Control.lnk"))
    $shortcut.TargetPath = $controlExe
    $shortcut.WorkingDirectory = $controlDirectory
    $shortcut.Description = "Sterowanie Gatewayem, profilami i integracjami MoonWaker"
    $shortcut.Save()
}

Copy-Item -LiteralPath $versionSource -Destination (Join-Path $InstallDirectory "version.json") -Force
foreach ($profileId in $profilesToRestart) {
    $profileRoot = Join-Path (Join-Path $InstallDirectory "profiles") $profileId
    Remove-Item -LiteralPath (Join-Path $profileRoot "profile-bridge-manually-stopped") `
        -Force -ErrorAction SilentlyContinue
    $task = Get-ScheduledTask -TaskName "MoonWaker Profile Bridge ($profileId)" `
        -ErrorAction SilentlyContinue
    if ($null -ne $task) {
        try { Start-ScheduledTask -InputObject $task -ErrorAction Stop } catch {}
    }
}
Write-Host "MoonWaker host components installed in $InstallDirectory" -ForegroundColor Green
if ($SkipStart) {
    Write-Host "Gateway and Login Broker are installed and will start automatically at the next boot."
} else {
    Write-Host "Gateway and Login Broker are running as machine services."
}
Write-Host "Add, edit, or remove Windows profiles in MoonWaker Host Control."
Write-Host "Gateway configuration: $(Join-Path $GatewayDirectory 'gateway.json')"
