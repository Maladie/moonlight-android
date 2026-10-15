#requires -Version 5.1
[CmdletBinding()]
param(
    # Where the change tests write the elevated helper's window renders (PNG); none when empty.
    [string]$UiRenderDirectory = ""
)

$ErrorActionPreference = "Stop"
$compiler = "C:\Windows\Microsoft.NET\Framework64\v4.0.30319\csc.exe"
if (-not (Test-Path -LiteralPath $compiler)) {
    $compiler = "C:\Windows\Microsoft.NET\Framework\v4.0.30319\csc.exe"
}
if (-not (Test-Path -LiteralPath $compiler)) {
    throw ".NET Framework C# compiler was not found."
}
# The Configurator's sources: the store and its operations, the change sets of the elevated
# helper, and the change channel and window style it shares with Host Control.
$configuratorSources = @("MoonWakerHostConfigurator.cs", "ConfiguratorChanges.cs", "HostChanges.cs", "HostControlUi.cs") |
    ForEach-Object { Join-Path $PSScriptRoot $_ }
$references = @(
    "/reference:System.dll", "/reference:System.Core.dll", "/reference:System.Web.Extensions.dll",
    "/reference:System.Management.dll", "/reference:System.Security.dll",
    "/lib:$(Join-Path (Split-Path -Parent $compiler) 'WPF')", "/reference:PresentationFramework.dll",
    "/reference:PresentationCore.dll", "/reference:WindowsBase.dll", "/reference:System.Xaml.dll")

$temporary = Join-Path ([IO.Path]::GetTempPath()) (
    "MoonWakerConfiguratorTest-" + [guid]::NewGuid().ToString("N"))
try {
    New-Item -ItemType Directory -Path $temporary -Force | Out-Null
    $testExecutable = Join-Path $temporary "MoonWakerHostConfiguratorTests.exe"
    $arguments = @(
        "/nologo", "/target:exe", "/platform:x64", "/optimize+",
        "/main:MoonWaker.HostConfigurator.SelfTestProgram", "/out:$testExecutable") + $references + $configuratorSources
    & $compiler @arguments
    if ($LASTEXITCODE -ne 0 -or -not (Test-Path -LiteralPath $testExecutable)) {
        throw "MoonWaker Host Configurator test compilation failed."
    }
    & $testExecutable
    if ($LASTEXITCODE -ne 0) { throw "MoonWaker Host Configurator tests failed." }

    # The change channel on real pipes and the helper's change sets on a %TEMP% fixture; the
    # Broker, provisioning, account list and Vibepollo are stand-ins.
    $changeExecutable = Join-Path $temporary "MoonWakerHostConfiguratorChangeTests.exe"
    $arguments = @(
        "/nologo", "/target:exe", "/platform:x64", "/optimize+",
        "/main:MoonWaker.HostConfigurator.ChangeTests", "/out:$changeExecutable") + $references + $configuratorSources +
        @(Join-Path $PSScriptRoot "Test-MoonWakerHostConfiguratorChanges.cs")
    & $compiler @arguments
    if ($LASTEXITCODE -ne 0 -or -not (Test-Path -LiteralPath $changeExecutable)) {
        throw "MoonWaker Host Configurator change test compilation failed."
    }
    $changeArguments = @()
    if (-not [string]::IsNullOrWhiteSpace($UiRenderDirectory)) { $changeArguments = @($UiRenderDirectory) }
    & $changeExecutable @changeArguments
    if ($LASTEXITCODE -ne 0) { throw "MoonWaker Host Configurator change tests failed." }

    $identity = [Security.Principal.WindowsIdentity]::GetCurrent()
    $principal = New-Object Security.Principal.WindowsPrincipal($identity)
    if ($principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
        Write-Output "MoonWaker Host Configurator redirected child-api test skipped for an elevated token."
    } else {
        $childApiExecutable = Join-Path $temporary "MoonWakerHostConfiguratorChildApi.exe"
        $childApiArguments = @(
            "/nologo", "/target:exe", "/platform:x64", "/optimize+",
            "/main:MoonWaker.HostConfigurator.Program", "/out:$childApiExecutable") + $references + $configuratorSources
        & $compiler @childApiArguments
        if ($LASTEXITCODE -ne 0 -or -not (Test-Path -LiteralPath $childApiExecutable)) {
            throw "MoonWaker Host Configurator child-api test compilation failed."
        }
        $startInfo = New-Object System.Diagnostics.ProcessStartInfo
        $startInfo.FileName = $childApiExecutable
        $startInfo.Arguments = "--mode child-api"
        $startInfo.UseShellExecute = $false
        $startInfo.CreateNoWindow = $true
        $startInfo.RedirectStandardInput = $true
        $startInfo.RedirectStandardOutput = $true
        $startInfo.RedirectStandardError = $true
        $process = New-Object System.Diagnostics.Process
        $process.StartInfo = $startInfo
        [void]$process.Start()
        $childApiRequest = @'
{"operation":"sharing_set","parent_profile_id":"parent","game_key":"steam:demo","selected_child_ids":[],"parent_catalog_game_keys":["parent/steam:demo"],"expected_revision":0,"request_id":"redirected-unicode","label":"Żółć"}
'@
        $childApiBytes = [Text.UTF8Encoding]::new($false).GetBytes($childApiRequest)
        $process.StandardInput.BaseStream.Write(
            $childApiBytes, 0, $childApiBytes.Length)
        $process.StandardInput.Close()
        $childApiOutput = $process.StandardOutput.ReadToEnd()
        $childApiError = $process.StandardError.ReadToEnd()
        $process.WaitForExit()
        if ($process.ExitCode -ne 0 -or -not [string]::IsNullOrWhiteSpace($childApiError)) {
            throw "MoonWaker Host Configurator child-api redirected stream test failed."
        }
        try { $childApiResult = $childApiOutput | ConvertFrom-Json }
        catch { throw "MoonWaker Host Configurator child-api returned invalid JSON." }
        if (-not $childApiResult.ok -and
            $childApiResult.reason -eq "administrator_required") {
            Write-Output "MoonWaker Host Configurator redirected child-api test passed."
        } else {
            throw "MoonWaker Host Configurator child-api did not fail closed before registry access."
        }
        # The forms' modes are gone: anything but --apply --pipe and the two --mode commands
        # ends without a window.
        $removedMode = Start-Process -FilePath $childApiExecutable -ArgumentList "--mode", "edit", "--profile", "x" `
            -NoNewWindow -PassThru -Wait
        if ($removedMode.ExitCode -ne 2) { throw "The Configurator still accepts a removed --mode." }
    }

    # The only elevation in Host Control: the helper with a fresh pipe name, through UAC; no
    # secret, environment or script on its command line.
    $hostControlSource = @("MoonWakerHostControl.cs", "HostControlWindow.cs", "HostControlUi.cs",
        "HostControlBackend.cs", "HostChanges.cs") |
        ForEach-Object { Get-Content -LiteralPath (Join-Path $PSScriptRoot $_) -Raw } | Out-String
    $launcher = [regex]::Match($hostControlSource,
        '(?s)internal ChangeAnswer ApplyChanges\(.+?\n        \}').Value
    if ([string]::IsNullOrWhiteSpace($launcher) -or
        $launcher -notmatch 'Verb\s*=\s*"runas"' -or
        $launcher -notmatch 'UseShellExecute\s*=\s*true' -or
        $launcher -notmatch '"--apply --pipe " \+ channel\.ToString\("D"\) \+ " --client " \+ sid' -or
        $launcher -notmatch 'ChangeChannel\.Exchange\(ChangeChannel\.PipeName\(channel\), helper\.Id' -or
        $launcher -match 'powershell|password|EnvironmentVariables|--mode') {
        throw "Host Control does not use the fixed secret-free elevation boundary of the change channel."
    }
    if ([regex]::Matches($hostControlSource, 'Verb\s*=\s*"runas"').Count -ne 1) {
        throw "A generic elevated action exists outside MoonWakerHostConfigurator.exe."
    }
    $channel = Get-Content -LiteralPath (Join-Path $PSScriptRoot "HostChanges.cs") -Raw
    foreach ($required in @('FileFlagFirstPipeInstance', 'PipeRejectRemoteClients', 'WellKnownSidType.NetworkSid',
            'GetNamedPipeServerProcessId(pipe.SafePipeHandle, out server) || server != serverProcessId',
            'GetNamedPipeClientProcessId(pipe.SafePipeHandle, out connected)', 'GetNamedPipeClientSessionId',
            'pipe.RunAsClient(', 'user.Equals(client)', 'MaxRequestBytes', 'MaxAnswerBytes',
            'TransferMilliseconds', 'PeekNamedPipe', 'throw new AnswerLostException(error)')) {
        if (-not $channel.Contains($required)) { throw "Change channel contract is missing: $required" }
    }

    $source = (Get-Content -LiteralPath (Join-Path $PSScriptRoot "MoonWakerHostConfigurator.cs") -Raw) +
        (Get-Content -LiteralPath (Join-Path $PSScriptRoot "ConfiguratorChanges.cs") -Raw)
    foreach ($required in @(
        'NamedPipeClientStream', 'IsLocalSystemServer', 'first.Clear()', 'plan.Clear()',
        'Array.Clear(one, 0, one.Length)', 'TimeoutMilliseconds = 500',
        'Login Broker niedostępny', 'ConfigPath + ".lock"',
        'FileAccess.ReadWrite', 'ReplaceRegistryFile(temporary, ConfigPath)',
        'File.Replace(source, destination, null)', 'RegistryRevision.Of(content) != expectedRevision',
        'store.Transaction(set.RegistryRevision', 'ParentProcess.Id()', 'client.IsAccountSid()',
        'ProvisionAndFinalize', 'reservation_nonce', 'EnsureProvisioningReservation',
        'deletion_tombstone', 'if (profile.RemoteSignInEnabled && !effects.Broker.Delete(profile).Success)',
        'RequestMayHaveReachedBroker', 'SchemaVersion = 3', 'CreateChildProfile',
        'parent_profile_id', 'allowed_game_keys', 'ManageChildren', 'if (!connected()) return Answer("cancelled"')) {
        if (-not $source.Contains($required)) {
            throw "Configurator contract is missing: $required"
        }
    }
    if ($source -match '(?i)ReadProfileCredential|GetProfilePassword|ReadPassword' -or
        $source -match 'Run\("powershell\.exe"' -or
        $source.Contains('CredentialLifecycleStore') -or
        $source.Contains('InstalledCodeTrust') -or
        $source.Contains('System.Windows.Forms') -or
        (Test-Path -LiteralPath (Join-Path $PSScriptRoot "ChildProfileForms.cs"))) {
        throw "Configurator exposes a password-read API, uses PowerShell or keeps its WinForms forms."
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

    # Profile provisioning runs in the elevated Configurator itself (the self-test above covers
    # its layout and ACL): no PowerShell profile installer, no startup task, no Bridge code.
    if (Test-Path -LiteralPath (Join-Path (Split-Path -Parent $PSScriptRoot) "install\Install-WakePlayProfile.ps1")) {
        throw "The PowerShell profile installer still exists."
    }
    $provisioner = [regex]::Match($source,
        '(?s)internal static class ProfileProvisioner.+?internal enum BrokerOperation').Value
    foreach ($required in @('RequireSupportedAccount(profile.Sid, profile.AccountName)',
            'TrustedInstalledDirectory(store, "bridge-source")', 'ProtectTree(CreateLayout())',
            'EnsureOrdinaryPath(root)', 'Directory.SetAccessControl(root, (DirectorySecurity)Security(true))')) {
        if (-not $provisioner.Contains($required)) {
            throw "Profile provisioning contract is missing: $required"
        }
    }
    if ($provisioner -match '(?i)powershell|ScheduledTask|GameProviderBridge\.py|Startup|ProcessStartInfo') {
        throw "Profile provisioning starts processes, registers startup or copies Bridge code."
    }
    $agentGuard = Get-Content -LiteralPath (Join-Path (Split-Path -Parent $PSScriptRoot) `
        "bridges\playnite\AgentGuard.py") -Raw
    if ($agentGuard -notmatch 'AUTHORITY_LOST_EXIT_CODE = 78' -or
        $agentGuard -notmatch 'windows_account_sid' -or
        $agentGuard -notmatch 'deletion_tombstone') {
        throw "The Agent does not stop after its profile authority is revoked."
    }

    Write-Output "MoonWaker Host Control change channel and password boundary tests passed."
    Write-Output "MoonWaker profile startup and broker-unavailable tests passed."
} finally {
    Remove-Item -LiteralPath $temporary -Recurse -Force -ErrorAction SilentlyContinue
}
