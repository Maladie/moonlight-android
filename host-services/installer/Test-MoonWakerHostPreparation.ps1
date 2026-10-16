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
    Gateway = Join-Path $root "gateway\Install-WakePlayGateway.ps1"
    UpdaterInstaller = Join-Path $root "updater\Install-MoonWakerHotUpdater.ps1"
    GatewayRuntime = Join-Path $root "gateway\wakeplay_gateway.py"
    BrokerInstaller = Join-Path $root "windows-login\login-broker\Install-MoonWakerLoginBroker.ps1"
    DiscordBridge = Join-Path $root "bridges\discord\DiscordBridge.ps1"
    Configurator = Join-Path $root "control\MoonWakerHostConfigurator.cs"
    Installer = Join-Path $PSScriptRoot "MoonWakerHostInstaller.cs"
    Manifest = Join-Path $PSScriptRoot "MoonWakerHostInstaller.manifest"
    BuildHostServices = Join-Path (Split-Path -Parent $root) "Build-HostServices.ps1"
    InstallerBuild = Join-Path $PSScriptRoot "Build-MoonWakerHostInstaller.ps1"
    HostControlBuild = Join-Path $root "control\Build-MoonWakerHostControl.ps1"
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

Assert-Contains "Wrapper" @('& $HostInstallScript *>&1', "-ProtectedStagingDirectory", "-EnsureViGEmBus", "-EnsurePython", "-EnsureOpenSsl")
Assert-Contains "Wrapper" @(
    'Installer stage: $stage',
    '[string]::IsNullOrWhiteSpace($PrerequisiteScript)',
    '[string]::IsNullOrWhiteSpace($HostInstallScript)',
    '[Console]::Error.WriteLine($details)'
)
if ($sources.Wrapper.Contains("-SkipStart")) {
    throw "The elevated wrapper still leaves the machine services stopped after install."
}
Assert-Contains "Host" @(
    "Assert-MoonWakerProcessesStopped",
    '& $gatewayInstaller -Port $GatewayPort',
    "-SkipStart",
    'Join-Path $GatewayDirectory "gateway.json.lock"',
    "is nonempty and is not a MoonWaker installation.",
    "Install-MoonWakerLoginBroker.ps1",
    "Install-MoonWakerCredentialProvider.ps1",
    "The MoonWaker Login Broker package is incomplete.",
    "The MoonWaker Credential Provider package is incomplete.",
    "MoonWaker Host Control.lnk",
    '"HKLM:\SOFTWARE\Microsoft\Windows\CurrentVersion\Run"',
    '-Name "MoonWaker Host Control" -Value (''"{0}" --tray'' -f $controlExe)',
    '& $packagedProviderUninstall -Confirm:$false'
)
# Profiles hold only data: the installer no longer copies code or supervisors into them.
if ($sources.Host -match 'profile-agent|moonwaker-version|Start-ScheduledTask') {
    throw "The host installer still manages per-profile code or supervisor tasks."
}
foreach ($mutableInstallerScript in @(
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
    '-Name "MoonWaker Host Control" -ErrorAction SilentlyContinue',
    '& $providerUninstall -Confirm:$false',
    '& $brokerUninstall -InstallDirectory'
)
foreach ($unsafeLaunch in @("taskkill.exe", "Start-Process")) {
    if ($sources.Host.Contains($unsafeLaunch)) {
        throw "Elevated host install starts or kills mutable runtime code: $unsafeLaunch"
    }
}
# Processes are stopped only by the shared script, which proves membership by path.
if ($sources.Host.Contains("Stop-Process")) {
    throw "Elevated host install stops processes itself instead of Stop-MoonWakerInstalledProcesses.ps1."
}
Assert-Contains "Host" @('Stop-MoonWakerInstalledProcesses.ps1") -Root $InstallDirectory')
Assert-Contains "Uninstall" @('Stop-MoonWakerInstalledProcesses.ps1") -Root $root')

Assert-Contains "Gateway" @(
    '$lockPath = "$configPath.lock"',
    'if (-not (Test-Path -LiteralPath $configPath))',
    "-Profile Private",
    "-RemoteAddress LocalSubnet",
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
# The Gateway runs only as the MoonWakerGateway service: no PowerShell supervisor or
# start/stop scripts beside it.
foreach ($removed in @("gateway\MoonWakerGatewaySupervisor.ps1", "gateway\Start-MoonWakerGateway.ps1",
        "gateway\Start-WakePlayGateway.ps1", "gateway\Stop-MoonWakerGateway.ps1")) {
    if (Test-Path -LiteralPath (Join-Path $root $removed)) { throw "$removed is still shipped." }
}
Assert-Contains "Gateway" @(
    '& (Join-Path $sourceDirectory "Stop-MoonWakerGatewayWorkers.ps1") -GatewayDirectory $InstallDirectory',
    'MoonWaker Gateway did not open port',
    'No Python startup error was recorded.'
)
Assert-Contains "GatewayRuntime" @(
    'import platform',
    'Path("gateway-startup-error.txt").write_text(',
    'platform.python_version()'
)
Assert-Contains "BrokerInstaller" @(
    'Invoke-CimMethod -InputObject $serviceConfig -MethodName Change',
    'PathName = $quotedBinary',
    'StartMode = "Automatic"',
    'StartName = "LocalSystem"'
)
# The Gateway is an automatic service: nothing marks it as stopped by hand.
if ($sources.Gateway.Contains("gateway-manually-stopped")) {
    throw "Gateway still handles the manual Gateway stop marker."
}
# Host Control's Configurator provisions profiles in C#; no profile installer is shipped.
if ($sources.Host.Contains("Install-WakePlayProfile.ps1") -or
    (Test-Path -LiteralPath (Join-Path $root "install\Install-WakePlayProfile.ps1"))) {
    throw "The PowerShell profile installer is still installed."
}
Assert-Contains "DiscordBridge" @(
    '$mutexScope = $DataRoot.ToLowerInvariant()',
    'DiscordUnifiedRemoteRpcBridge_$mutexScope'
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
    'Environment.SpecialFolder.ProgramFiles), "MoonWaker");',
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

foreach ($name in @("Prepare", "Wrapper", "Host", "Uninstall", "Gateway", "UpdaterInstaller", "BrokerInstaller", "DiscordBridge")) {
    $tokens = $null
    $errors = $null
    $ast = [Management.Automation.Language.Parser]::ParseFile(
        $paths[$name], [ref]$tokens, [ref]$errors)
    if ($errors.Count -gt 0) {
        throw "$name has PowerShell syntax errors: $($errors[0].Message)"
    }
    # The installation lives only in %ProgramFiles%\MoonWaker: no script run by the
    # installer accepts another root, and no legacy location is consulted.
    if ($name -in @("Wrapper", "Host", "Uninstall", "Gateway", "UpdaterInstaller") -and
        $null -ne $ast.ParamBlock -and @($ast.ParamBlock.Parameters | Where-Object {
            $_.Name.VariablePath.UserPath -in @("InstallDirectory", "GatewayDirectory") }).Count) {
        throw "$name still accepts a caller-chosen installation directory."
    }
}
foreach ($entry in $sources.GetEnumerator()) {
    if ($entry.Value.IndexOf("C:\Tools", [StringComparison]::OrdinalIgnoreCase) -ge 0) {
        throw "$($entry.Key) still refers to a legacy C:\Tools installation."
    }
}
foreach ($chosenPath in @("MOONWAKER_INSTALL_DIRECTORY", "FolderBrowserDialog", '" -InstallDirectory "')) {
    if ($sources.Installer.Contains($chosenPath)) {
        throw "The GUI installer still lets the installation directory be chosen: $chosenPath"
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

# Discord is optional: only a -WithDiscord build puts its Bridge and audio worker into the
# payload, and the installers carry over exactly what the package contains (the Gateway
# installer's audio worker: test_gateway.py).
foreach ($name in @("BuildHostServices", "InstallerBuild")) {
    $buildTokens = $null
    $buildErrors = $null
    $buildAst = [Management.Automation.Language.Parser]::ParseFile(
        $paths[$name], [ref]$buildTokens, [ref]$buildErrors)
    if ($buildErrors.Count -gt 0) { throw "$name has PowerShell syntax errors." }
    $workerBuilds = @($buildAst.FindAll({
        param($node)
        $node -is [Management.Automation.Language.StringConstantExpressionAst] -and
            $node.Value.EndsWith("Build-MoonWakerDiscordAudioWorker.ps1")
    }, $true))
    if ($workerBuilds.Count -ne 1) { throw "$name must build the Discord audio worker exactly once." }
    $guard = $workerBuilds[0].Parent
    while ($null -ne $guard -and $guard -isnot [Management.Automation.Language.IfStatementAst]) {
        $guard = $guard.Parent
    }
    if ($null -eq $guard -or $guard.Clauses[0].Item1.Extent.Text -ne '$WithDiscord') {
        throw "$name builds the Discord audio worker without -WithDiscord."
    }
}
Assert-Contains "BuildHostServices" @(
    '[switch]$WithDiscord',
    'Remove-Item -LiteralPath (Join-Path $payloadRoot "bridges\discord") -Recurse -Force'
)
Assert-Contains "InstallerBuild" @(
    '[switch] $WithDiscord',
    '$_ -notlike "host-services/bridges/discord/*"'
)
Assert-Contains "Host" @(
    'if (Test-Path -LiteralPath (Join-Path $bridgeSource "discord\DiscordBridge.ps1") -PathType Leaf) {',
    'Remove-Item -LiteralPath $installedDiscord -Recurse -Force -ErrorAction Stop'
)

# Host Control is one WPF executable built by csc from its sources; the elevated helper shares
# its window style and change channel. Both package builds carry them.
Assert-Contains "HostControlBuild" @(
    "/reference:PresentationFramework.dll", "/reference:WindowsBase.dll", "/reference:System.Xaml.dll",
    "HostControlWindow.cs", "HostControlUi.cs", "HostControlBackend.cs", "MoonWakerHostControl.cs",
    "HostChanges.cs", "ConfiguratorChanges.cs"
)
Assert-Contains "BuildHostServices" @("HostControlWindow.cs", "HostControlUi.cs", "HostChanges.cs", "ConfiguratorChanges.cs")
Assert-Contains "InstallerBuild" @("host-services/control/HostControlWindow.cs", "host-services/control/HostControlUi.cs",
    "host-services/control/HostChanges.cs", "host-services/control/ConfiguratorChanges.cs")
foreach ($name in @("HostControlBuild", "BuildHostServices", "InstallerBuild")) {
    if ($sources[$name].Contains("ChildProfileForms.cs")) { throw "$name still carries the WinForms child forms." }
}

Write-Output "MoonWaker host preparation functional test passed."
