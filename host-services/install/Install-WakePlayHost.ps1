#requires -Version 5.1
[CmdletBinding()]
param(
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

# Fixed, never chosen: the machine-wide Run entry starts code from here in every account,
# so its parent must be one that ordinary users cannot rename or create in.
$InstallDirectory = Join-Path ([Environment]::GetFolderPath(
    [Environment+SpecialFolder]::ProgramFiles)) "MoonWaker"
$GatewayDirectory = Join-Path $InstallDirectory "gateway"
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
        throw "$InstallDirectory is nonempty and is not a MoonWaker installation."
    }
}

$hostServicesRoot = Split-Path -Parent $PSScriptRoot
$versionSource = Join-Path $hostServicesRoot "version.json"
$gatewaySource = Join-Path $hostServicesRoot "gateway"
$bridgeSource = Join-Path $hostServicesRoot "bridges"
$controlSource = Join-Path $hostServicesRoot "control"
$toolsSource = Join-Path $hostServicesRoot "tools"
$windowsLoginSource = Join-Path $hostServicesRoot "windows-login"
$updaterSource = Join-Path $hostServicesRoot "updater"
$packagedProviderUninstall = Join-Path $windowsLoginSource `
    "credential-provider\Uninstall-MoonWakerCredentialProvider.ps1"
if (-not (Test-Path -LiteralPath $versionSource -PathType Leaf)) {
    throw "Run this installer from the complete versioned host-services package. Missing: $versionSource"
}
foreach ($required in @($gatewaySource, $bridgeSource,
        $controlSource, $toolsSource, $windowsLoginSource, $updaterSource)) {
    if (-not (Test-Path -LiteralPath $required -PathType Container)) {
        throw "Run this installer from the complete versioned host-services package. Missing: $required"
    }
}
foreach ($required in @($packagedProviderUninstall)) {
    if (-not (Test-Path -LiteralPath $required -PathType Leaf)) {
        throw "Run this installer from the complete versioned host-services package. Missing: $required"
    }
}

function Assert-MoonWakerProcessesStopped {
    # The trays of every session have no exit: close them here (their Job Objects end the
    # hosted Agents and Discord Bridges). They come back at each account's next sign-in.
    $controlPrefix = (Join-Path $InstallDirectory "control") + "\"
    $trays = @(Get-CimInstance Win32_Process -Filter "Name='MoonWakerHostControl.exe'" -ErrorAction Stop |
        Where-Object { ([string]$_.ExecutablePath).StartsWith($controlPrefix,
            [StringComparison]::OrdinalIgnoreCase) })
    foreach ($tray in $trays) {
        Stop-Process -Id ([int]$tray.ProcessId) -Force -ErrorAction SilentlyContinue
    }
    if ($trays.Count -gt 0) {
        Wait-Process -Id @($trays | ForEach-Object { [int]$_.ProcessId }) -Timeout 10 `
            -ErrorAction SilentlyContinue
    }
    $runningControl = @(Get-Process -Name "MoonWakerHostControl", "MoonWakerHostConfigurator" `
        -ErrorAction SilentlyContinue)
    if ($runningControl.Count -gt 0) {
        $runningControl | ForEach-Object { $_.Dispose() }
        throw "Close MoonWaker Host Configurator (and any Host Control outside this installation), then run the installer again."
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

function Stop-HotUpdaterQuiescent {
    $service = Get-Service -Name "MoonWakerHotUpdater" -ErrorAction SilentlyContinue
    if ($service -and $service.Status -ne [ServiceProcess.ServiceControllerStatus]::Stopped) {
        Stop-Service -Name "MoonWakerHotUpdater" -Force
        $service.WaitForStatus([ServiceProcess.ServiceControllerStatus]::Stopped,
            [TimeSpan]::FromSeconds(30))
    }
    $deadline = [DateTime]::UtcNow.AddSeconds(20)
    do {
        $remaining = @(Get-CimInstance Win32_Process -Property Name,CommandLine -ErrorAction Stop |
            Where-Object {
                $_.Name -eq "MoonWakerHotUpdaterService.exe" -or
                ([string]$_.CommandLine -like "*Apply-MoonWakerHotUpdate.ps1*" -and
                 [string]$_.CommandLine -like "*$InstallDirectory*")
            })
        if ($remaining.Count -eq 0) { return }
        Start-Sleep -Milliseconds 250
    } while ([DateTime]::UtcNow -lt $deadline)
    throw "MoonWaker Hot Updater did not become quiescent; runtime files were not changed."
}

# Code that the tray, the Agent, the installer and the updater execute must not be writable
# by other accounts, whatever ACL Program Files passes down: protected, owned by
# Administrators, SYSTEM/Administrators full, Users read. Children only inherit it.
function Protect-InstalledCode([string]$Path) {
    $icacls = Join-Path ([Environment]::SystemDirectory) "icacls.exe"
    $directory = Test-Path -LiteralPath $Path -PathType Container
    $inherit = if ($directory) { "(OI)(CI)" } else { "" }
    & $icacls $Path /reset /T /C /Q | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "Unable to reset the ACL of $Path." }
    & $icacls $Path /setowner "*S-1-5-32-544" /T /C /Q | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "Unable to set the owner of $Path." }
    & $icacls $Path /inheritance:r /grant:r "*S-1-5-18:${inherit}(F)" `
        "*S-1-5-32-544:${inherit}(F)" "*S-1-5-32-545:${inherit}(RX)" /Q | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "Unable to protect $Path." }
}

function Test-HotUpdaterStartEligible([string]$ReadyMarker, [object]$Service,
        [string]$StartMode) {
    return $null -ne $Service -and
        (Test-Path -LiteralPath $ReadyMarker -PathType Leaf) -and
        $StartMode -ne "Disabled"
}

Stop-HotUpdaterQuiescent
$pendingHotUpdate = Join-Path $env:ProgramData "MoonWaker\HotUpdater\transaction.json"
if (Test-Path -LiteralPath $pendingHotUpdate -PathType Leaf) {
    & (Join-Path $updaterSource "Apply-MoonWakerHotUpdate.ps1") `
        -HostRoot $InstallDirectory -RecoverOnly -LeaveStopped
    Stop-HotUpdaterQuiescent
}

foreach ($serviceName in @("MoonWakerGateway", "MoonWakerLoginBroker", "MoonWakerHotUpdater")) {
    $service = Get-Service -Name $serviceName -ErrorAction SilentlyContinue
    if ($null -ne $service -and
        $service.Status -ne [ServiceProcess.ServiceControllerStatus]::Stopped) {
        Stop-Service -Name $serviceName -Force
    }
}

Assert-MoonWakerProcessesStopped

$sourceDirectory = Join-Path $InstallDirectory "bridge-source"
$controlDirectory = Join-Path $InstallDirectory "control"
$toolsDirectory = Join-Path $InstallDirectory "tools"
$installScripts = Join-Path $InstallDirectory "install"
$stateDirectory = Join-Path $InstallDirectory "state"
$windowsLoginDirectory = Join-Path $InstallDirectory "windows-login"
$loginBrokerDirectory = Join-Path $windowsLoginDirectory "login-broker"
$installedProviderUninstall = Join-Path $windowsLoginDirectory `
    "credential-provider\Uninstall-MoonWakerCredentialProvider.ps1"
if (Test-Path -LiteralPath $installedProviderUninstall -PathType Leaf) {
    # Unregister with the verified package script, not mutable installed code.
    & $packagedProviderUninstall -Confirm:$false
}
New-Item -ItemType Directory -Path $InstallDirectory, $sourceDirectory,
    $controlDirectory, $toolsDirectory, $installScripts,
    $stateDirectory, $windowsLoginDirectory -Force | Out-Null
# Before anything is copied or executed from them. The updater directory gets the same
# exact ACL from Install-MoonWakerHotUpdater.ps1; profiles and gateway keep their own.
foreach ($codeDirectory in @($sourceDirectory, $controlDirectory, $toolsDirectory,
        $installScripts, $windowsLoginDirectory)) {
    Protect-InstalledCode $codeDirectory
}

Copy-Item -LiteralPath (Join-Path $bridgeSource "discord") -Destination $sourceDirectory -Recurse -Force
Copy-Item -LiteralPath (Join-Path $bridgeSource "playnite") -Destination $sourceDirectory -Recurse -Force
Remove-Item -LiteralPath (Join-Path $sourceDirectory "playnite\PlayniteBridge.py") `
    -Force -ErrorAction SilentlyContinue
Copy-Item -Path (Join-Path $windowsLoginSource "*") -Destination $windowsLoginDirectory -Recurse -Force

# Profile roots hold only data and configuration; Bridge code runs from bridge-source.
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
foreach ($required in @($brokerInstaller, $brokerBinary)) {
    if (-not (Test-Path -LiteralPath $required -PathType Leaf)) {
        throw "The MoonWaker Login Broker package is incomplete. Missing: $required"
    }
}
& $brokerInstaller -BinaryPath $brokerBinary -InstallDirectory $loginBrokerDirectory `
    -SkipStart:$SkipStart

$providerDirectory = Join-Path $windowsLoginDirectory "credential-provider"
$providerInstaller = Join-Path $providerDirectory "Install-MoonWakerCredentialProvider.ps1"
$providerBinary = Join-Path $providerDirectory "MoonWakerCredentialProvider.dll"
foreach ($required in @($providerInstaller, $providerBinary)) {
    if (-not (Test-Path -LiteralPath $required -PathType Leaf)) {
        throw "The MoonWaker Credential Provider package is incomplete. Missing: $required"
    }
}
& $providerInstaller -DllPath $providerBinary -Confirm:$false

$registryLockPath = Join-Path $GatewayDirectory "gateway.json.lock"
$gatewayInstaller = Join-Path $gatewaySource "Install-WakePlayGateway.ps1"
& $gatewayInstaller -Port $GatewayPort `
    -SkipFirewall:$SkipFirewall -SkipStart:$SkipStart
if (-not (Test-Path -LiteralPath $registryLockPath -PathType Leaf)) {
    [IO.File]::WriteAllBytes($registryLockPath, [byte[]]@(0))
}

& (Join-Path $updaterSource "Install-MoonWakerHotUpdater.ps1") -SkipStart

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
    # One machine-wide sign-in entry: the tray of every account with a profile hosts its
    # Agent (an account without a profile exits at once), including profiles that the
    # elevated Configurator creates for other accounts.
    New-ItemProperty -Path "HKLM:\SOFTWARE\Microsoft\Windows\CurrentVersion\Run" `
        -Name "MoonWaker Host Control" -Value ('"{0}" --tray' -f $controlExe) `
        -PropertyType String -Force | Out-Null
}

Copy-Item -LiteralPath $versionSource -Destination (Join-Path $InstallDirectory "version.json") -Force
Protect-InstalledCode (Join-Path $InstallDirectory "version.json")
& (Join-Path $updaterSource "Apply-MoonWakerHotUpdate.ps1") `
    -HostRoot $InstallDirectory -RecoverOnly -LeaveStopped -FullInstallCommit
if (-not $SkipStart) {
    $hotUpdater = Get-Service -Name "MoonWakerHotUpdater" -ErrorAction SilentlyContinue
    $hotUpdaterConfig = if ($hotUpdater) {
        Get-CimInstance Win32_Service -Filter "Name='MoonWakerHotUpdater'"
    } else { $null }
    $hotUpdaterStartMode = if ($hotUpdaterConfig) {
        [string]$hotUpdaterConfig.StartMode
    } else { "" }
    $hotUpdaterReady = Join-Path $env:ProgramData "MoonWaker\HotUpdater\installer-ready"
    if (Test-HotUpdaterStartEligible $hotUpdaterReady $hotUpdater $hotUpdaterStartMode) {
        Start-Service -Name "MoonWakerHotUpdater"
        $hotUpdater.WaitForStatus([ServiceProcess.ServiceControllerStatus]::Running,
            [TimeSpan]::FromSeconds(10))
    } elseif ($hotUpdater) {
        Write-Warning "MoonWaker Hot Updater remains stopped because bootstrap trust validation did not succeed."
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
