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
    ProfileStop = Join-Path $root "profile-agent\Stop-MoonWakerProfileBridge.ps1"
    Gateway = Join-Path $root "gateway\Install-WakePlayGateway.ps1"
    GatewayRuntime = Join-Path $root "gateway\wakeplay_gateway.py"
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
    'VibepolloSetup-.*\.exe',
    'PSObject.Properties["DisplayName"]',
    "https://github.com/nefarius/ViGEmBus/releases/download/v1.22.0/",
    "ViGEmBus_1.22.0_x64_x86_arm64.exe",
    "89220A7865076B342892F98865F3499FB7C4CFD673159E89D352C360FD014C6A",
    "CN=Nefarius Software Solutions e.U.,*",
    'ArgumentList @("/qn", "/norestart")',
    'installedVersion -ge [version]"1.17"',
    "https://www.python.org/ftp/python/3.12.10/python-3.12.10-amd64.exe",
    "67B5635E80EA51072B87941312D00EC8927C4DB9BA18938F7AD2D27B328B95FB",
    "CN=Python Software Foundation,*",
    '"InstallAllUsers=1"',
    '$version -ge [version]"3.10"',
    "https://raw.githubusercontent.com/slproweb/opensslhashes/master/win32_openssl_hashes.json",
    "OpenSSL_Light-",
    "published SHA-256 hash",
    "invalid SHA-256 hash",
    "valid Authenticode signature",
    "-CommandType Application",
    '[string]::IsNullOrWhiteSpace($path)',
    'PSObject.Properties["InstallLocation"]',
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

Assert-Contains "Wrapper" @("-GatewayDirectory `$GatewayDirectory", "-ProtectedStagingDirectory", "-EnsureViGEmBus", "-EnsurePython", "-EnsureOpenSsl")
Assert-Contains "Wrapper" @(
    'Installer stage: $stage',
    '[string]::IsNullOrWhiteSpace($PrerequisiteScript)',
    '[string]::IsNullOrWhiteSpace($HostInstallScript)',
    '[Console]::Error.WriteLine($details)'
)
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
    "The MoonWaker Login Broker package is incomplete.",
    "The MoonWaker Credential Provider package is incomplete.",
    "MoonWaker Host Control.lnk",
    '$profilesToRestart',
    'Join-Path $_.FullName "moonwaker-version.json"',
    'Remove-Item -LiteralPath (Join-Path $profileRoot "profile-bridge-manually-stopped")',
    '@{ source = "discord"; destination = "discord" }',
    '& $packagedProfileStop -ProfileRoot $_.FullName',
    '& $packagedProviderUninstall -Confirm:$false'
)
foreach ($mutableInstallerScript in @(
    '& $stopProfile -ProfileRoot $_.FullName',
    '& $installedProviderUninstall -Confirm:$false')) {
    if ($sources.Host.Contains($mutableInstallerScript)) {
        throw "The elevated installer still invokes mutable installed code: $mutableInstallerScript"
    }
}
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
    'A system-wide Python 3.10 or newer installation was not found.',
    'gateway-startup-error.txt',
    'Gateway configuration is invalid or has no valid listen_port',
    'Windows did not expose the MoonWaker Gateway service after installation.',
    '[DateTime]::UtcNow.AddSeconds(30)',
    '-CommandType Application',
    '"*${serviceSid}:(OI)(CI)(RX)" /T /C',
    'Test-Path -LiteralPath $candidate -PathType Leaf',
    'Test-Path -LiteralPath $openssl -PathType Leaf'
)
Assert-Contains "Gateway" @(
    '& (Join-Path $InstallDirectory "Stop-MoonWakerGateway.ps1")',
    'MoonWaker Gateway did not open port',
    'No Python startup error was recorded.'
)
Assert-Contains "GatewayRuntime" @(
    'import platform',
    'Path("gateway-startup-error.txt").write_text(',
    'platform.python_version()'
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
Assert-Contains "ProfileStop" @(
    '[ValidateNotNullOrEmpty()][string]$ProfileRoot',
    '$ProfileRoot = [IO.Path]::GetFullPath($ProfileRoot)'
)
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
    "Choose the Vibepollo folder",
    'arguments += " -EnsureViGEmBus"',
    'arguments += " -EnsurePython"',
    'arguments += " -EnsureOpenSsl"',
    'ensureVibepollo, ensureViGEmBus,',
    'true, true));',
    'Path.Combine("gateway", "MoonWakerGatewayService.exe")',
    'Path.Combine("windows-login", "login-broker", "MoonWakerLoginBroker.exe")',
    'Path.Combine("windows-login", "credential-provider", "MoonWakerCredentialProvider.dll")',
    "Profiles in Host Control",
    "Gateway and Login Broker are running",
    "Gateway i Login Broker działają",
    "PrepareHostControlUpdate(installMachine.Checked, true)",
    "RestartHostControlIfNeeded()",
    "RedirectStandardError = true",
    'if (installError == null) throw;',
    'if (uninstallError == null) throw;',
    'The installation path is invalid.',
    '" -PurgeCredentials -RemoveFiles"',
    '"Uninstall", "Odinstaluj"'
)
foreach ($name in @("Prepare", "Gateway")) {
    if ($sources[$name].Contains('Test-Path -LiteralPath $command.Source')) {
        throw "$name can still pass an empty command Source to LiteralPath."
    }
}
foreach ($unsafeCom in @("launchHostControl", "Shell.Application", "WScript.Shell")) {
    if ($sources.Installer.Contains($unsafeCom)) {
        throw "Elevated installer still launches user-owned UI through COM: $unsafeCom"
    }
}

foreach ($name in @("Prepare", "Wrapper", "Host", "Uninstall", "Profile", "ProfileStop", "Gateway", "BrokerInstaller", "StartGateway", "MachineStartGateway", "StopGateway", "DiscordBridge")) {
    $tokens = $null
    $errors = $null
    [void][Management.Automation.Language.Parser]::ParseFile(
        $paths[$name], [ref]$tokens, [ref]$errors)
    if ($errors.Count -gt 0) {
        throw "$name has PowerShell syntax errors: $($errors[0].Message)"
    }
}

$prepareTokens = $null
$prepareErrors = $null
$prepareAst = [Management.Automation.Language.Parser]::ParseFile(
    $paths.Prepare, [ref]$prepareTokens, [ref]$prepareErrors)
$openSslResolver = $prepareAst.Find({
    param($node)
    $node -is [Management.Automation.Language.FunctionDefinitionAst] -and
        $node.Name -eq "Get-OpenSslExecutable"
}, $true)
if ($null -eq $openSslResolver) { throw "OpenSSL resolver function is missing." }
& {
    param([string]$Definition)
    . ([scriptblock]::Create($Definition))
    function Get-Command {
        param($Name, $CommandType, $ErrorAction)
        [pscustomobject]@{ Source = ""; Path = "" }
    }
    function Test-Path {
        param([string]$LiteralPath, $PathType)
        if ([string]::IsNullOrWhiteSpace($LiteralPath)) {
            throw "The OpenSSL resolver passed an empty LiteralPath."
        }
        return $false
    }
    if ($null -ne (Get-OpenSslExecutable)) {
        throw "The isolated OpenSSL resolver unexpectedly found an executable."
    }
} $openSslResolver.Extent.Text

Write-Output "MoonWaker host preparation functional test passed."
