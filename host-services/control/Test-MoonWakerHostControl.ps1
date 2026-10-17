#requires -Version 5.1
[CmdletBinding()]
param(
    # Where the UI smoke test writes its page renders (PNG); none when empty.
    [string]$UiRenderDirectory = ""
)

# Host Control checks: source contracts plus four headless C# test programs (singleton, the
# tray's process host, the backend, the WPF window on a hidden render) that use fixtures in
# %TEMP% only - no service, task, registry write or network beyond loopback, nothing on screen.
$ErrorActionPreference = "Stop"
function Read-Source([string]$Name) {
    Get-Content -LiteralPath (Join-Path $PSScriptRoot $Name) -Raw -Encoding UTF8
}
$traySource = Read-Source "MoonWakerHostControl.cs"
$windowSource = (Read-Source "HostControlWindow.cs") + (Read-Source "HostControlUi.cs")
$uiSource = $traySource + $windowSource
$backendSource = (Read-Source "HostControlBackend.cs") + (Read-Source "HostChanges.cs")
$configuratorSource = (Read-Source "MoonWakerHostConfigurator.cs") + (Read-Source "ConfiguratorChanges.cs")
$controlSource = $uiSource + $backendSource

# Vibepollo is a computer setting: its one token is written by the elevated helper, whose own
# window asks for the panel's administrator login; Windows passwords are typed only there too.
if (-not $windowSource.Contains('One(Change("vibepollo_token"))') -or
    -not $configuratorSource.Contains('VibepolloMachineApi.SaveToken') -or
    -not $configuratorSource.Contains('"Login administratora Vibepollo"') -or
    -not $configuratorSource.Contains('"Nowe hasło Windows"')) {
    throw "The Vibepollo token or a Windows password is not entered in the elevated helper only."
}
foreach ($secretInput in @("Login administratora Vibepollo", "Hasło administratora", "Nowe hasło Windows",
        "Powtórz hasło")) {
    if ($controlSource.Contains($secretInput)) { throw "Host Control asks for a secret the helper owns: $secretInput" }
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
# The window is WPF: no WinForms form or MessageBox chain in Host Control; the tray keeps
# NotifyIcon and the system folder picker only.
foreach ($winForms in @(": Form", "ControlForm", "SteamApiKeyDialog", "IntegrationsDialog",
        "MessageBox", "GamepadBadge")) {
    if ($uiSource.Contains($winForms)) { throw "Host Control still contains WinForms UI: $winForms" }
}
# The tray hosts the profile's processes: it cannot be exited, and no script starts,
# stops or adopts them.
if ($traySource.Contains('menu.Items.Add("Zako') -or
    -not $traySource.Contains('KillOnJobClose | SilentBreakawayOk') -or
    -not $traySource.Contains('@"SYSTEM\CurrentControlSet\Services\MoonWakerGateway"') -or
    -not $traySource.Contains('"bridge-source", "playnite", "GameProviderBridge.py"')) {
    throw "Host Control does not host the profile processes from the installation."
}
# A dispatcher exception must not end the tray (and with its job, the Agent).
if (-not $traySource.Contains('application.DispatcherUnhandledException +=') -or
    -not $traySource.Contains('e.Handled = true;')) {
    throw "Host Control lets a dispatcher exception end the tray."
}
if (-not $uiSource.Contains('Ctrl+Alt+Shift+End') -or
    -not $traySource.Contains('RegisterHotKey(handle, StreamHotkeyId') -or
    -not $windowSource.Contains('backend.CloseSession(25)') -or
    -not $traySource.Contains('new StreamHotkeyPipe(window.Dispatcher, window.CloseSession)') -or
    -not $traySource.Contains('MoonWakerHostControl.StreamHotkey.')) {
    throw "Host Control close-session hotkey or its secure-desktop pipe is missing."
}
if ($controlSource.Contains('/action/close-app') -or $uiSource.Contains('"CloseStream"') -or
    -not $backendSource.Contains('GatewayFile("host-close-request.json")') -or
    -not $backendSource.Contains('GatewayFile("host-close-result.json")')) {
    throw "Host Control must close a session through the Gateway request file only."
}
# Exactly one "Napraw" action; it restarts the Gateway service through the installer's
# service DACL.
if ([regex]::Matches($uiSource, 'Button\("Napraw"').Count -ne 1 -or
    [regex]::Matches($uiSource, 'backend\.Repair\(').Count -ne 1 -or
    -not $backendSource.Contains('new ServiceController(GatewayServiceName)')) {
    throw "Host Control must have one Napraw that repairs the Gateway service and the Agent."
}
# The theme follows Windows by reading HKCU only; DPI awareness is per monitor.
if ($uiSource -match 'Registry\.(LocalMachine|CurrentUser)\.(CreateSubKey|SetValue|DeleteValue)' -or
    $uiSource -match 'OpenSubKey\([^)]*true\)' -or
    -not $windowSource.Contains('"AppsUseLightTheme"') -or
    -not (Get-Content -LiteralPath (Join-Path $PSScriptRoot "MoonWakerHostControl.manifest") -Raw).Contains(
        'PerMonitorV2')) {
    throw "Host Control must only read the Windows theme and be per-monitor DPI aware."
}

function Test-HostControlHeadless([string]$TestSource, [string]$MainClass, [string[]]$Arguments = @()) {
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
            "/reference:System.IO.Compression.FileSystem.dll", "/reference:System.Management.dll",
            "/lib:$(Join-Path (Split-Path -Parent $compiler) 'WPF')", "/reference:PresentationFramework.dll",
            "/reference:PresentationCore.dll", "/reference:WindowsBase.dll", "/reference:System.Xaml.dll",
            (Join-Path $PSScriptRoot "MoonWakerHostControl.cs"),
            (Join-Path $PSScriptRoot "HostControlWindow.cs"),
            (Join-Path $PSScriptRoot "HostControlUi.cs"),
            (Join-Path $PSScriptRoot "HostControlBackend.cs"),
            (Join-Path $PSScriptRoot "HostChanges.cs"),
            (Join-Path $PSScriptRoot $TestSource))
        $compilerOutput = & $compiler @compilerArguments 2>&1
        if ($LASTEXITCODE -ne 0 -or -not (Test-Path -LiteralPath $testExecutable)) {
            throw ("Headless Host Control test compilation failed: " +
                ($compilerOutput -join [Environment]::NewLine))
        }
        & $testExecutable @Arguments
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
$uiArguments = if ([string]::IsNullOrWhiteSpace($UiRenderDirectory)) { @() } else { @($UiRenderDirectory) }
Test-HostControlHeadless "Test-MoonWakerHostControlUi.cs" "MoonWaker.HostControl.UiTests" $uiArguments
Write-Output "Host Control source contracts and headless tests passed."
