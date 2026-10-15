#requires -Version 5.1
[CmdletBinding()]
param()

# Host Control checks: source contracts plus three headless C# test programs (singleton, the
# tray's process host, the backend) that use fixtures in %TEMP% only - no service, task,
# registry or network beyond loopback.
$ErrorActionPreference = "Stop"
$uiSource = Get-Content -LiteralPath (Join-Path $PSScriptRoot "MoonWakerHostControl.cs") -Raw
$backendSource = Get-Content -LiteralPath (Join-Path $PSScriptRoot "HostControlBackend.cs") -Raw
$configuratorSource = Get-Content -LiteralPath (Join-Path $PSScriptRoot "MoonWakerHostConfigurator.cs") -Raw
$controlSource = $uiSource + $backendSource

# Vibepollo is a computer setting: its one token is written by the elevated Configurator.
if (-not $uiSource.Contains('LaunchConfigurator("vibepollo", null)') -or
    -not $configuratorSource.Contains('else if (mode == "vibepollo") form = new VibepolloTokenForm(store);')) {
    throw "The Vibepollo token is not written through the elevated Configurator only."
}
# The backend runs in the tray's process: no PowerShell control script, no Gateway start/stop,
# no manual-stop marker, no Vibepollo page after adding a profile, no English labels.
if (Test-Path -LiteralPath (Join-Path $PSScriptRoot "Invoke-MoonWakerHostControl.ps1")) {
    throw "The PowerShell Host Control backend still exists."
}
foreach ($removed in @("Invoke-MoonWakerHostControl", "RunControlAsync",
        '"StartGateway"', '"StopGateway"', '"RestartGateway"', "gateway-manually-stopped",
        "Ostatnio używany profil", "47990", "ProfileCreatedExitCode", "ClearDiscordMachine",
        "Usuń dane Discorda", "machine-data", "Connect Steam", "Connect Epic", "Connect Playnite",
        "Disconnect Steam", "Disconnect Epic", "Disconnect Playnite", "Save key",
        "Steam Web API key help", "Install-WakePlayConnectorPatch")) {
    if ($controlSource.Contains($removed)) { throw "Host Control still contains: $removed" }
}
if ($configuratorSource.Contains("return 10;") -or $configuratorSource.Contains("panel Vibepollo")) {
    throw "Adding a profile still redirects to the Vibepollo panel."
}
# The tray hosts the profile's processes: it cannot be exited, and no script starts,
# stops or adopts them.
if ($uiSource.Contains('menu.Items.Add("Zako') -or
    -not $uiSource.Contains('KillOnJobClose | SilentBreakawayOk') -or
    -not $uiSource.Contains('@"SYSTEM\CurrentControlSet\Services\MoonWakerGateway"') -or
    -not $uiSource.Contains('"bridge-source", "playnite", "GameProviderBridge.py"')) {
    throw "Host Control does not host the profile processes from the installation."
}
if (-not $uiSource.Contains('Ctrl+Alt+Shift+End') -or
    -not $uiSource.Contains('RegisterHotKey(Handle, StreamHotkeyId') -or
    -not $uiSource.Contains('backend.CloseSession(25)') -or
    -not $uiSource.Contains('new StreamHotkeyPipe(this, CloseSession)') -or
    -not $uiSource.Contains('MoonWakerHostControl.StreamHotkey.')) {
    throw "Host Control close-session hotkey or its secure-desktop pipe is missing."
}
if ($controlSource.Contains('/action/close-app') -or $uiSource.Contains('"CloseStream"') -or
    -not $backendSource.Contains('GatewayFile("host-close-request.json")') -or
    -not $backendSource.Contains('GatewayFile("host-close-result.json")')) {
    throw "Host Control must close a session through the Gateway request file only."
}
# Exactly one "Napraw"; it restarts the Gateway service through the installer's service DACL.
if ([regex]::Matches($uiSource, 'AddActionButton\(\w+, "Napraw"').Count -ne 1 -or
    -not $backendSource.Contains('new ServiceController(GatewayServiceName)')) {
    throw "Host Control must have one Napraw that repairs the Gateway service and the Agent."
}

function Test-HostControlHeadless([string]$TestSource, [string]$MainClass) {
    $compiler = "C:\Windows\Microsoft.NET\Framework64\v4.0.30319\csc.exe"
    if (-not (Test-Path -LiteralPath $compiler)) {
        $compiler = "C:\Windows\Microsoft.NET\Framework\v4.0.30319\csc.exe"
    }
    if (-not (Test-Path -LiteralPath $compiler)) {
        throw ".NET Framework C# compiler was not found for the headless Host Control tests."
    }

    $separatorCharacters = [char[]]@([IO.Path]::DirectorySeparatorChar,
        [IO.Path]::AltDirectorySeparatorChar)
    $tempParent = [IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd($separatorCharacters)
    $testRoot = [IO.Path]::GetFullPath((Join-Path $tempParent `
        ("MoonWakerHostControlSingletonTest-" + [guid]::NewGuid().ToString("N"))))
    if ([IO.Directory]::GetParent($testRoot).FullName.TrimEnd($separatorCharacters) -ine $tempParent) {
        throw "Headless Host Control test directory escaped the temporary parent."
    }
    New-Item -ItemType Directory -Path $testRoot -Force | Out-Null
    try {
        $testExecutable = Join-Path $testRoot "HostControlTests.exe"
        $compilerArguments = @(
            "/nologo", "/target:exe", "/main:$MainClass",
            "/out:$testExecutable", "/reference:System.dll", "/reference:System.Core.dll",
            "/reference:System.Drawing.dll", "/reference:System.Windows.Forms.dll",
            "/reference:System.Web.Extensions.dll", "/reference:System.Security.dll",
            "/reference:System.ServiceProcess.dll", "/reference:System.IO.Compression.dll",
            "/reference:System.IO.Compression.FileSystem.dll",
            (Join-Path $PSScriptRoot "MoonWakerHostControl.cs"),
            (Join-Path $PSScriptRoot "HostControlBackend.cs"),
            (Join-Path $PSScriptRoot $TestSource))
        $compilerOutput = & $compiler @compilerArguments 2>&1
        if ($LASTEXITCODE -ne 0 -or -not (Test-Path -LiteralPath $testExecutable)) {
            throw ("Headless Host Control test compilation failed: " +
                ($compilerOutput -join [Environment]::NewLine))
        }
        & $testExecutable
        if ($LASTEXITCODE -ne 0) {
            throw "Headless Host Control test $MainClass failed with exit code $LASTEXITCODE."
        }
    }
    finally {
        $resolvedTestRoot = [IO.Path]::GetFullPath($testRoot)
        $resolvedTestParent = ([IO.Directory]::GetParent($resolvedTestRoot).FullName).TrimEnd(
            $separatorCharacters)
        if ($resolvedTestParent -ine $tempParent -or
            [IO.Path]::GetFileName($resolvedTestRoot) -notmatch `
                '^MoonWakerHostControlSingletonTest-[0-9a-f]{32}$') {
            throw "Refusing to recursively remove an unexpected headless test directory."
        }
        Remove-Item -LiteralPath $resolvedTestRoot -Recurse -Force -ErrorAction SilentlyContinue
    }
}

Test-HostControlHeadless "Test-MoonWakerHostControlSingleton.cs" "MoonWaker.HostControl.SingletonTests"
Test-HostControlHeadless "Test-MoonWakerHostControlProcessHost.cs" "MoonWaker.HostControl.ProcessHostTests"
Test-HostControlHeadless "Test-MoonWakerHostControlBackend.cs" "MoonWaker.HostControl.BackendTests"
Write-Output "Host Control source contracts and headless tests passed."
