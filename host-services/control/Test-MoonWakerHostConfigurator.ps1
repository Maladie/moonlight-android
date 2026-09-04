#requires -Version 5.1
[CmdletBinding()]
param()

$ErrorActionPreference = "Stop"
$compiler = "C:\Windows\Microsoft.NET\Framework64\v4.0.30319\csc.exe"
if (-not (Test-Path -LiteralPath $compiler)) {
    $compiler = "C:\Windows\Microsoft.NET\Framework\v4.0.30319\csc.exe"
}
if (-not (Test-Path -LiteralPath $compiler)) {
    throw ".NET Framework C# compiler was not found."
}

$temporary = Join-Path ([IO.Path]::GetTempPath()) (
    "MoonWakerConfiguratorTest-" + [guid]::NewGuid().ToString("N"))
try {
    New-Item -ItemType Directory -Path $temporary -Force | Out-Null
    $testExecutable = Join-Path $temporary "MoonWakerHostConfiguratorTests.exe"
    $arguments = @(
        "/nologo", "/target:exe", "/platform:x64", "/optimize+",
        "/main:MoonWaker.HostConfigurator.SelfTestProgram", "/out:$testExecutable",
        "/reference:System.dll", "/reference:System.Core.dll", "/reference:System.Drawing.dll",
        "/reference:System.Windows.Forms.dll", "/reference:System.Web.Extensions.dll",
        "/reference:System.Management.dll", "/reference:System.Security.dll",
        (Join-Path $PSScriptRoot "MoonWakerHostConfigurator.cs"))
    & $compiler @arguments
    if ($LASTEXITCODE -ne 0 -or -not (Test-Path -LiteralPath $testExecutable)) {
        throw "MoonWaker Host Configurator test compilation failed."
    }
    & $testExecutable
    if ($LASTEXITCODE -ne 0) { throw "MoonWaker Host Configurator tests failed." }

    $hostControlSource = Get-Content -LiteralPath (
        Join-Path $PSScriptRoot "MoonWakerHostControl.cs") -Raw
    $launcher = [regex]::Match($hostControlSource,
        '(?s)private async void LaunchConfigurator\(.+?\n        \}').Value
    if ([string]::IsNullOrWhiteSpace($launcher) -or
        $launcher -notmatch 'Verb\s*=\s*"runas"' -or
        $launcher -notmatch 'UseShellExecute\s*=\s*true' -or
        $launcher -match 'powershell|password|EnvironmentVariables') {
        throw "Host Control does not use the fixed secret-free Configurator elevation boundary."
    }
    if ([regex]::Matches($hostControlSource, 'Verb\s*=\s*"runas"').Count -ne 1) {
        throw "A generic elevated action exists outside MoonWakerHostConfigurator.exe."
    }

    $source = Get-Content -LiteralPath (
        Join-Path $PSScriptRoot "MoonWakerHostConfigurator.cs") -Raw
    foreach ($required in @(
        'NamedPipeClientStream', 'IsLocalSystemServer', 'password.Clear()',
        'Array.Clear(first', 'TimeoutMilliseconds = 500',
        'Login Broker niedostępny', 'ConfigPath + ".lock"',
        'FileAccess.ReadWrite', 'File.Replace(temporary, ConfigPath',
        'ProvisionAndFinalize', 'reservation_nonce', 'EnsureProvisioningReservation',
        'deletion_tombstone', 'if (profile.RemoteSignInEnabled)',
        'RequestMayHaveReachedBroker')) {
        if (-not $source.Contains($required)) {
            throw "Configurator contract is missing: $required"
        }
    }
    if ($source -match '(?i)ReadProfileCredential|GetProfilePassword|ReadPassword' -or
        $source -match 'Run\("powershell\.exe"' -or
        $source.Contains('CredentialLifecycleStore') -or
        $source.Contains('InstalledCodeTrust')) {
        throw "Configurator exposes a password-read API or uses a mutable PowerShell executable path."
    }

    [xml]$controlManifest = Get-Content -LiteralPath (
        Join-Path $PSScriptRoot "MoonWakerHostControl.manifest") -Raw
    [xml]$configuratorManifest = Get-Content -LiteralPath (
        Join-Path $PSScriptRoot "MoonWakerHostConfigurator.manifest") -Raw
    if ($controlManifest.assembly.trustInfo.security.requestedPrivileges.requestedExecutionLevel.level -ne
            "asInvoker" -or
        $configuratorManifest.assembly.trustInfo.security.requestedPrivileges.requestedExecutionLevel.level -ne
            "requireAdministrator") {
        throw "Host Control and Configurator privilege manifests are not separated."
    }

    $profileInstaller = Get-Content -LiteralPath (
        Join-Path (Split-Path -Parent $PSScriptRoot) "install\Install-WakePlayProfile.ps1") -Raw
    foreach ($required in @(
        'LogonType Interactive', '$provisioningOwner.sid', '$MachineProvisioning',
        'Machine profile root must be exactly', 'Protect-ProfileTree $true',
        '$GatewayConfigPath.lock')) {
        if (-not $profileInstaller.Contains($required)) {
            throw "Profile provisioning contract is missing: $required"
        }
    }
    if ($profileInstaller -match '(?i)OwnerPassword|TaskPassword' -or
        $profileInstaller -match 'New-ScheduledTaskAction -Execute "powershell\.exe"') {
        throw "Profile startup would store a password or use a mutable executable lookup."
    }

    $profileAgent = Get-Content -LiteralPath (Join-Path (
        Split-Path -Parent $PSScriptRoot) "profile-agent\MoonWakerProfileBridge.ps1") -Raw
    if ($profileAgent -notmatch 'Test-AuthoritativeProfileRuntime' -or
        $profileAgent -notmatch 'profile_authority_revoked' -or
        $profileAgent -notmatch 'windows_account_sid') {
        throw "Profile supervisor does not stop after its profile authority is revoked."
    }

    Write-Output "MoonWaker Host Control runas/password tests passed."
    Write-Output "MoonWaker profile startup and broker-unavailable UI tests passed."
} finally {
    Remove-Item -LiteralPath $temporary -Recurse -Force -ErrorAction SilentlyContinue
}
