#requires -Version 5.1
[CmdletBinding()]
param()

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
$paths = [ordered]@{
    Prepare = Join-Path $root "install\Prepare-MoonWakerHost.ps1"
    Wrapper = Join-Path $root "install\Invoke-MoonWakerMachineInstall.ps1"
    Host = Join-Path $root "install\Install-WakePlayHost.ps1"
    Uninstall = Join-Path $root "install\Uninstall-MoonWakerHostServices.ps1"
    Profile = Join-Path $root "install\Install-WakePlayProfile.ps1"
    Gateway = Join-Path $root "gateway\Install-WakePlayGateway.ps1"
    BrokerInstaller = Join-Path $root "windows-login\login-broker\Install-MoonWakerLoginBroker.ps1"
    StartGateway = Join-Path $root "gateway\Start-WakePlayGateway.ps1"
    MachineStartGateway = Join-Path $root "gateway\Start-MoonWakerGateway.ps1"
    StopGateway = Join-Path $root "gateway\Stop-MoonWakerGateway.ps1"
    DiscordBridge = Join-Path $root "bridges\discord\DiscordBridge.ps1"
    Configurator = Join-Path $root "control\MoonWakerHostConfigurator.cs"
    Installer = Join-Path $PSScriptRoot "MoonWakerHostInstaller.cs"
    Manifest = Join-Path $PSScriptRoot "MoonWakerHostInstaller.manifest"
}

$sources = @{}
foreach ($entry in $paths.GetEnumerator()) {
    if (-not (Test-Path -LiteralPath $entry.Value -PathType Leaf)) {
        throw "Missing installer source: $($entry.Value)"
    }
    $sources[$entry.Key] = Get-Content -LiteralPath $entry.Value -Raw
}

function Assert-Contains([string]$Name, [string[]]$Needles) {
    foreach ($needle in $Needles) {
        if (-not $sources[$Name].Contains($needle)) {
            throw "$Name contract is missing: $needle"
        }
    }
}

Assert-Contains "Prepare" @(
    "https://api.github.com/repos/Nonary/Vibepollo/releases/latest",
    "/Nonary/Vibepollo/releases/download/",
    "Get-AuthenticodeSignature",
    'Status -ne "Valid"',
    'name -ieq "Vibepollo.msi"',
    "Enable-NetAdapterPowerManagement",
    "-WakeOnMagicPacket",
    "/deviceenableawake",
    "/devicequery wake_armed",
    "ProtectedStagingDirectory"
)
foreach ($name in @("Prepare", "Wrapper", "Installer")) {
    foreach ($secretTransport in @(
        "VibepolloCredentialPath",
        "ProtectedData.Protect",
        "vibepolloPassword",
        "--creds"
    )) {
        if ($sources[$name].Contains($secretTransport)) {
            throw "$name still transports a bootstrap secret: $secretTransport"
        }
    }
}
foreach ($profileInput in @('"-ProfileId ', '"-ProfileName ', '"-ProfileOnly"')) {
    if ($sources.Installer.Contains($profileInput)) {
        throw "The GUI installer still owns profile setup: $profileInput"
    }
}

Assert-Contains "Wrapper" @("-GatewayDirectory `$GatewayDirectory", "-ProtectedStagingDirectory")
if ($sources.Wrapper.Contains("-GatewayDirectory `$GatewayDirectory -SkipStart")) {
    throw "The elevated wrapper still leaves the machine services stopped after install."
}
Assert-Contains "Host" @(
    "Assert-MoonWakerProcessesStopped",
    '& $gatewayInstaller -InstallDirectory $GatewayDirectory',
    "-SkipStart",
    'Join-Path $GatewayDirectory "gateway.json.lock"',
    "The selected nonempty directory is not a MoonWaker installation.",
    "Install-MoonWakerLoginBroker.ps1",
    "Install-MoonWakerCredentialProvider.ps1",
    "MoonWaker Host Control.lnk",
    '$profilesToRestart',
    'Join-Path $_.FullName "moonwaker-version.json"',
    'Remove-Item -LiteralPath (Join-Path $profileRoot "profile-bridge-manually-stopped")',
    '@{ source = "discord"; destination = "discord" }'
)
Assert-Contains "Host" @('$partialInstallMarker')
Assert-Contains "Uninstall" @(
    "[switch]`$PurgeCredentials",
    "[switch]`$RemoveFiles",
    '$product -ne "MoonWaker Host"',
    'Remove-Item -LiteralPath $root -Recurse -Force',
    "Remove-MoonWakerLoginCredentials.ps1",
    '& $credentialCleanup -GatewayConfigPath',
    '"MoonWaker Profile Bridge ("',
    '"MoonWaker Host Control.lnk"',
    '& $providerUninstall -Confirm:$false',
    '& $brokerUninstall -InstallDirectory'
)
foreach ($unsafeLaunch in @("taskkill.exe", "Stop-Process", "Start-Process")) {
    if ($sources.Host.Contains($unsafeLaunch)) {
        throw "Elevated host install starts or kills mutable runtime code: $unsafeLaunch"
    }
}

Assert-Contains "Gateway" @(
    '$config.profiles = [pscustomobject]@{}',
    '$lockPath = "$configPath.lock"',
    'if (-not (Test-Path -LiteralPath $configPath))',
    "-Profile Private",
    "-RemoteAddress LocalSubnet",
    '"*S-1-5-11" /T /C',
    '"*S-1-5-32-545:(OI)(CI)(RX)"',
    'Invoke-CimMethod -InputObject $serviceConfig -MethodName Change',
    'StartName = "NT SERVICE\$serviceName"',
    '$pythonDirectory = Split-Path -Parent $python',
    '"*${serviceSid}:(OI)(CI)(RX)" /T /C',
    'Test-Path -LiteralPath $python -PathType Leaf',
    'Test-Path -LiteralPath $openssl -PathType Leaf'
)
Assert-Contains "Gateway" @(
    '& (Join-Path $InstallDirectory "Stop-MoonWakerGateway.ps1")',
    'MoonWaker Gateway service started, but its Gateway process did not open port'
)
Assert-Contains "StopGateway" @(
    '"C:\Tools\WakePlayHost\gateway"',
    '"C:\Tools\WakePlayGateway"',
    '$runtimes | Where-Object { $_.pid -eq $ownerPid }'
)
Assert-Contains "BrokerInstaller" @(
    'Invoke-CimMethod -InputObject $serviceConfig -MethodName Change',
    'PathName = $quotedBinary',
    'StartMode = "Automatic"',
    'StartName = "LocalSystem"'
)
Assert-Contains "StartGateway" @(
    '$registryLockPath = "$ConfigPath.lock"',
    '"--registry-lock", $registryLockPath'
)
Assert-Contains "MachineStartGateway" @(
    'Remove-Item -LiteralPath (Join-Path $GatewayDirectory "gateway-manually-stopped")'
)
Assert-Contains "DiscordBridge" @(
    '$mutexScope = $ScriptRoot.ToLowerInvariant()',
    'DiscordUnifiedRemoteRpcBridge_$mutexScope'
)
Assert-Contains "Profile" @('$GatewayRegistryLockPath = "$GatewayConfigPath.lock"')
Assert-Contains "Configurator" @(
    'RegistryLockPath = ConfigPath + ".lock";',
    "FileMode.OpenOrCreate",
    "File.Replace("
)
foreach ($removedHardening in @(
    "InstalledCodeTrust",
    "CredentialLifecycleStore",
    "FilteredTokenImpersonation",
    'state\gateway.json.lock'
)) {
    if ($sources.Configurator.Contains($removedHardening)) {
        throw "Configurator retains obsolete local-adversary hardening: $removedHardening"
    }
}

if (-not $sources.Manifest.Contains('requestedExecutionLevel level="requireAdministrator"') -or
    $sources.Manifest.Contains('requestedExecutionLevel level="asInvoker"')) {
    throw "The self-extracting installer must request administrator rights."
}
Assert-Contains "Installer" @(
    "never receives or logs a Vibepollo password",
    "Profiles in Host Control",
    "Gateway and Login Broker are running",
    "Gateway i Login Broker działają",
    "PrepareHostControlUpdate(installMachine.Checked, true)",
    "RestartHostControlIfNeeded()",
    '" -PurgeCredentials -RemoveFiles"',
    '"Uninstall", "Odinstaluj"'
)
foreach ($unsafeCom in @("launchHostControl", "Shell.Application", "WScript.Shell")) {
    if ($sources.Installer.Contains($unsafeCom)) {
        throw "Elevated installer still launches user-owned UI through COM: $unsafeCom"
    }
}

foreach ($name in @("Prepare", "Wrapper", "Host", "Uninstall", "Profile", "Gateway", "BrokerInstaller", "StartGateway", "MachineStartGateway", "StopGateway", "DiscordBridge")) {
    $tokens = $null
    $errors = $null
    [void][Management.Automation.Language.Parser]::ParseFile(
        $paths[$name], [ref]$tokens, [ref]$errors)
    if ($errors.Count -gt 0) {
        throw "$name has PowerShell syntax errors: $($errors[0].Message)"
    }
}

Write-Output "MoonWaker host preparation functional test passed."
