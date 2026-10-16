#requires -Version 5.1
[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$InstallerPath,
    [string]$PreviewDirectory = ""
)

$ErrorActionPreference = "Stop"
Add-Type -AssemblyName PresentationFramework, PresentationCore, WindowsBase
Add-Type -AssemblyName System.IO.Compression

function Test-PackagedControlModules([IO.Compression.ZipArchive]$payload) {
    $moduleDirectory = Join-Path ([IO.Path]::GetTempPath()) (
        "moonwaker-installer-modules-" + [Guid]::NewGuid().ToString("N"))
    try {
        New-Item -ItemType Directory -Path $moduleDirectory | Out-Null
        foreach ($modulePath in @("gateway/control_operation_policy.py", "gateway/child_terminal_operation.py",
                "gateway/provider_control_operation.py", "bridges/playnite/ControlEffectFence.py")) {
            $moduleName = Split-Path -Leaf $modulePath
            $entry = $payload.GetEntry("host-services/$modulePath")
            if ($null -eq $entry) { throw "Control module missing: host-services/$modulePath" }
            $input = $entry.Open()
            $output = [IO.File]::Create((Join-Path $moduleDirectory $moduleName))
            try { $input.CopyTo($output) }
            finally { $output.Dispose(); $input.Dispose() }
        }
        $python = (Get-Command python -ErrorAction Stop).Source
        & $python -I -c "import sys; sys.path.insert(0, sys.argv[1]); import control_operation_policy, child_terminal_operation, provider_control_operation, ControlEffectFence" $moduleDirectory
        if ($LASTEXITCODE -ne 0) {
            throw "Gateway control-operation modules are not importable from the installer payload."
        }
    } finally {
        if (Test-Path -LiteralPath $moduleDirectory) {
            $resolvedModuleDirectory = [IO.Path]::GetFullPath($moduleDirectory)
            if ($resolvedModuleDirectory.StartsWith([IO.Path]::GetTempPath(),
                    [StringComparison]::OrdinalIgnoreCase) -and
                    (Split-Path -Leaf $resolvedModuleDirectory) -like
                    "moonwaker-installer-modules-*") {
                Remove-Item -LiteralPath $resolvedModuleDirectory -Recurse -Force
            }
        }
    }
}

# Every element below root, through the logical tree.
function Get-Descendants($Parent) {
    foreach ($child in [Windows.LogicalTreeHelper]::GetChildren($Parent)) {
        if ($child -is [Windows.DependencyObject]) {
            $child
            Get-Descendants $child
        }
    }
}

$resolvedInstaller = (Resolve-Path -LiteralPath $InstallerPath).Path
$assembly = [Reflection.Assembly]::LoadFile($resolvedInstaller)
$payloadStream = $assembly.GetManifestResourceStream("MoonWaker.HostServices.zip")
if ($null -eq $payloadStream) { throw "Installer payload resource is missing." }
try {
    $payload = [IO.Compression.ZipArchive]::new(
        $payloadStream, [IO.Compression.ZipArchiveMode]::Read, $false)
    try {
        $payloadNames = @($payload.Entries | ForEach-Object { $_.FullName })
        # Host Control's backend and profile provisioning are compiled C#, not scripts.
        foreach ($removedScript in @("host-services/install/Install-WakePlayProfile.ps1",
                "host-services/control/Invoke-MoonWakerHostControl.ps1")) {
            if ($payloadNames -contains $removedScript) {
                throw "The installer payload still carries $removedScript."
            }
        }
        foreach ($controlBinary in @("host-services/control/MoonWakerHostControl.exe",
                "host-services/control/MoonWakerHostConfigurator.exe")) {
            if ($payloadNames -notcontains $controlBinary) {
                throw "Host Control binary is missing from the installer payload: $controlBinary"
            }
        }
        foreach ($requiredMachineEntry in @(
            "host-services/install/Install-WakePlayHost.ps1",
            "host-services/install/Invoke-MoonWakerMachineInstall.ps1",
            "host-services/install/Prepare-MoonWakerHost.ps1",
            "host-services/install/Uninstall-MoonWakerHostServices.ps1",
            "host-services/install/Stop-MoonWakerInstalledProcesses.ps1",
            "host-services/gateway/Stop-MoonWakerGatewayWorkers.ps1",
            "host-services/gateway/control_operation_policy.py",
            "host-services/gateway/child_terminal_operation.py",
            "host-services/gateway/provider_control_operation.py",
            "host-services/bridges/playnite/ControlEffectFence.py",
            "host-services/gateway/MoonWakerGatewayService.exe",
            "host-services/windows-login/login-broker/MoonWakerLoginBroker.exe",
            "host-services/windows-login/credential-provider/MoonWakerCredentialProvider.dll",
            "host-services/windows-login/login-broker/Remove-MoonWakerLoginCredentials.ps1"
        )) {
            if ($payloadNames -notcontains $requiredMachineEntry) {
                throw "Trusted machine installer payload is missing: $requiredMachineEntry"
            }
        }
        Test-PackagedControlModules $payload
    } finally { $payload.Dispose() }
} finally { $payloadStream.Dispose() }

# The window is never shown: Loaded (the system check) does not run, and each state is set
# directly, rendered at the default size and inspected.
$windowType = $assembly.GetType("MoonWaker.HostInstaller.InstallerWindow", $true)
$flags = [Reflection.BindingFlags]::Instance -bor [Reflection.BindingFlags]::NonPublic
$window = $windowType.GetConstructor($flags, $null, @([bool]), $null).Invoke(@($false))
function Set-State([string]$Name, $Value) { $windowType.GetField($Name, $flags).SetValue($window, $Value) }
function Get-State([string]$Name) { $windowType.GetField($Name, $flags).GetValue($window) }
$pageType = $windowType.GetNestedType("Page", [Reflection.BindingFlags]::NonPublic)
$render = $windowType.GetMethod("Render", $flags, $null, [Type]::EmptyTypes, $null)

$probe = Get-State "wakeOnLan"
$probe.GetType().GetField("Supported", $flags).SetValue($probe, $false)
Set-State "checking" $false
foreach ($part in (Get-State "parts")) { $part.GetType().GetField("Present", $flags).SetValue($part, $true) }
$log = Get-State "log"
[void]$log.AppendLine("Przygotowywanie instalacji…").AppendLine("Rozpakowywanie zweryfikowanych komponentów hosta…")

$resolvedPreview = if ([string]::IsNullOrWhiteSpace($PreviewDirectory)) { "" } else {
    [IO.Path]::GetFullPath($PreviewDirectory) }
if ($resolvedPreview) { New-Item -ItemType Directory -Path $resolvedPreview -Force | Out-Null }

$states = @(
    @{ Name = "01-check"; Page = "Check" },
    @{ Name = "02-ready"; Page = "Ready" },
    @{ Name = "03-progress"; Page = "Progress"; Running = $true },
    @{ Name = "04-installed"; Page = "Progress"; Completed = $true },
    @{ Name = "05-remove"; Page = "Remove"; Removing = $true },
    @{ Name = "06-removed"; Page = "Progress"; Removing = $true; Completed = $true }
)
$allText = @()
foreach ($language in @(1, 0)) {
    Set-State "language" $language
    foreach ($state in $states) {
        Set-State "page" ([Enum]::Parse($pageType, $state.Page))
        Set-State "running" ([bool]$state.Running)
        Set-State "completed" ([bool]$state.Completed)
        Set-State "removing" ([bool]$state.Removing)
        Set-State "progressTitle" "Instalowanie MoonWaker Host"
        $render.Invoke($window, @()) | Out-Null
        $root = $window.Content
        $window.Content = $null
        $root.Measure([Windows.Size]::new(980, 668))
        $root.Arrange([Windows.Rect]::new(0, 0, 980, 668))
        $root.UpdateLayout()
        $elements = @(Get-Descendants $root)
        if (@($elements | Where-Object { $_ -is [Windows.Controls.PasswordBox] }).Count) {
            throw "The installer must not collect a password ($($state.Name))."
        }
        $allText += @($elements | Where-Object { $_ -is [Windows.Controls.TextBlock] } | ForEach-Object { $_.Text })
        if ($resolvedPreview -and $language -eq 1) {
            $bitmap = [Windows.Media.Imaging.RenderTargetBitmap]::new(980, 668, 96, 96,
                [Windows.Media.PixelFormats]::Pbgra32)
            $bitmap.Render($root)
            $encoder = [Windows.Media.Imaging.PngBitmapEncoder]::new()
            $encoder.Frames.Add([Windows.Media.Imaging.BitmapFrame]::Create($bitmap))
            $stream = [IO.File]::Create((Join-Path $resolvedPreview ($state.Name + ".png")))
            try { $encoder.Save($stream) } finally { $stream.Dispose() }
        }
    }
    $expected = if ($language -eq 1) { "Instalator MoonWaker Host" } else { "MoonWaker Host Installer" }
    if (-not $window.Title.StartsWith($expected)) { throw "Language $language was not applied to the title." }
}
foreach ($needle in @("Profile w Host Control", "Profiles in Host Control", "Legendary (Epic Games)",
        "Vibepollo", "Odinstaluj MoonWaker", "Uninstall MoonWaker")) {
    if (-not ($allText -contains $needle)) { throw "The installer does not show: $needle" }
}
if ($null -ne $windowType.GetField("launchHostControl", $flags)) {
    throw "The elevated installer must not automatically activate Host Control through per-user COM."
}
Write-Output "MoonWaker Host Installer UI test passed."
