#requires -Version 5.1
[CmdletBinding()]
param(
    [string] $OutputDirectory = (Join-Path (Split-Path -Parent $PSScriptRoot) "dist"),
    [string] $CredentialProviderBinary = ""
)

$ErrorActionPreference = "Stop"
$hostServicesRoot = Split-Path -Parent $PSScriptRoot
$repoRoot = Split-Path -Parent $hostServicesRoot
$compiler = "C:\Windows\Microsoft.NET\Framework64\v4.0.30319\csc.exe"
if (-not (Test-Path -LiteralPath $compiler)) {
    $compiler = "C:\Windows\Microsoft.NET\Framework\v4.0.30319\csc.exe"
}
if (-not (Test-Path -LiteralPath $compiler)) {
    throw ".NET Framework C# compiler was not found."
}

$resolvedOutput = [IO.Path]::GetFullPath($OutputDirectory)
New-Item -ItemType Directory -Path $resolvedOutput -Force | Out-Null
$temporaryRoot = Join-Path ([IO.Path]::GetTempPath()) (
    "moonwaker-installer-build-" + [Guid]::NewGuid().ToString("N"))
$payloadRoot = Join-Path $temporaryRoot "payload"
$payloadHostServices = Join-Path $payloadRoot "host-services"
$archive = Join-Path $temporaryRoot "MoonWaker.HostServices.zip"

try {
    New-Item -ItemType Directory -Path $payloadHostServices -Force | Out-Null
    $tracked = & git -C $repoRoot ls-files -- `
        "host-services/bridges" `
        "host-services/control" `
        "host-services/gateway" `
        "host-services/install" `
        "host-services/profile-agent" `
        "host-services/windows-login" `
        "host-services/version.json"
    if ($LASTEXITCODE -ne 0 -or -not $tracked) {
        throw "Unable to enumerate the host payload."
    }
    # Newly added mandatory payload files must also be available before the
    # first commit that contains them.
    $tracked = @($tracked) + @(
        "host-services/bridges/playnite/GameProviderBridge.py",
        "host-services/gateway/Stop-MoonWakerGatewayWorkers.ps1",
        "host-services/install/Prepare-MoonWakerHost.ps1",
        "host-services/control/MoonWakerHostConfigurator.cs",
        "host-services/control/ChildProfileForms.cs",
        "host-services/control/MoonWakerHostConfigurator.manifest",
        "host-services/control/Test-MoonWakerHostConfigurator.ps1",
        "host-services/gateway/Build-MoonWakerGatewayService.ps1",
        "host-services/gateway/MoonWakerGatewayService.cs",
        "host-services/gateway/MoonWakerGatewayService.manifest",
        "host-services/gateway/MoonWakerGatewayServiceTests.cs",
        "host-services/gateway/Test-MoonWakerGatewayService.ps1",
        "host-services/gateway/Uninstall-MoonWakerGatewayService.ps1",
        "host-services/install/Uninstall-MoonWakerHostServices.ps1"
    ) | Sort-Object -Unique
    $windowsLogin = Get-ChildItem -LiteralPath (Join-Path $hostServicesRoot "windows-login") `
        -File -Recurse | Where-Object {
            $_.FullName -notmatch '[\\/](dist|out|obj|verify-dist)[\\/]' -and
            $_.Extension -notin @(".exe", ".dll")
        } | ForEach-Object {
            "host-services/" + $_.FullName.Substring($hostServicesRoot.Length + 1).Replace("\", "/")
        }
    $tracked = @($tracked) + @($windowsLogin) | Sort-Object -Unique
    foreach ($relative in $tracked) {
        if ($relative -eq "host-services/install/Install-MoonWakerHostBundle.ps1") {
            continue
        }
        if ($relative -match "(^|/)(dist|__pycache__)(/|$)" -or
                $relative -match "\.pyc$") {
            continue
        }
        $source = Join-Path $repoRoot ($relative -replace "/", "\")
        if (-not (Test-Path -LiteralPath $source -PathType Leaf)) {
            if ($relative -eq "host-services/bridges/playnite/PlayniteBridge.py") { continue }
            throw "Required host payload file is missing: $relative"
        }
        $destination = Join-Path $payloadRoot ($relative -replace "/", "\")
        $destinationDirectory = Split-Path -Parent $destination
        New-Item -ItemType Directory -Path $destinationDirectory -Force | Out-Null
        Copy-Item -LiteralPath $source -Destination $destination -Force
    }
    & (Join-Path $PSScriptRoot "Install-LegendaryPayload.ps1") `
        -TargetDirectory (Join-Path $payloadHostServices "tools\legendary")

    $payloadGateway = Join-Path $payloadHostServices "gateway"
    & (Join-Path $payloadHostServices `
        "bridges\microphone\Build-MoonWakerMicrophoneWorker.ps1") `
        -OutputDirectory $payloadGateway | Out-Null
    & (Join-Path $payloadHostServices `
        "bridges\discord\Build-MoonWakerDiscordAudioWorker.ps1") `
        -OutputDirectory $payloadGateway | Out-Null
    & (Join-Path $payloadHostServices "gateway\Build-MoonWakerGatewayService.ps1") `
        -OutputDirectory $payloadGateway | Out-Null

    $payloadWindowsLogin = Join-Path $payloadHostServices "windows-login"
    & (Join-Path $payloadWindowsLogin "login-broker\Build-MoonWakerLoginBroker.ps1") `
        -OutputDirectory (Join-Path $payloadWindowsLogin "login-broker") | Out-Null
    $providerBinary = if ([string]::IsNullOrWhiteSpace($CredentialProviderBinary)) {
        $providerOutput = Join-Path $temporaryRoot "credential-provider-build"
        & (Join-Path $payloadWindowsLogin `
            "credential-provider\Build-MoonWakerCredentialProvider.ps1") `
            -Configuration Release -OutputDirectory $providerOutput | Out-Host
        Join-Path $providerOutput "MoonWakerCredentialProvider.dll"
    } else {
        & (Join-Path $payloadWindowsLogin `
            "credential-provider\Test-MoonWakerCredentialProvider.ps1")
        (Resolve-Path -LiteralPath $CredentialProviderBinary).Path
    }
    Copy-Item -LiteralPath $providerBinary `
        -Destination (Join-Path $payloadWindowsLogin `
            "credential-provider\MoonWakerCredentialProvider.dll") -Force

    & (Join-Path $payloadHostServices "control\Build-MoonWakerHostControl.ps1") `
        -OutputDirectory (Join-Path $payloadHostServices "control")
    if ($LASTEXITCODE -ne 0) {
        throw "MoonWaker Host Control build failed."
    }

    Add-Type -AssemblyName System.IO.Compression
    $archiveStream = [IO.File]::Create($archive)
    try {
        $zip = [IO.Compression.ZipArchive]::new(
            $archiveStream, [IO.Compression.ZipArchiveMode]::Create, $true)
        try {
            foreach ($file in Get-ChildItem -LiteralPath $payloadHostServices `
                    -Recurse -File) {
                $relative = $file.FullName.Substring(
                    $payloadRoot.Length + 1).Replace("\", "/")
                $entry = $zip.CreateEntry(
                    $relative, [IO.Compression.CompressionLevel]::Optimal)
                $input = $file.OpenRead()
                $output = $entry.Open()
                try { $input.CopyTo($output) }
                finally { $output.Dispose(); $input.Dispose() }
            }
        }
        finally { $zip.Dispose() }
    }
    finally { $archiveStream.Dispose() }

    $output = Join-Path $resolvedOutput "MoonWakerHostInstaller.exe"
    $arguments = @(
        "/nologo", "/target:winexe", "/optimize+", "/out:$output",
        "/win32manifest:$(Join-Path $PSScriptRoot 'MoonWakerHostInstaller.manifest')",
        "/resource:$archive,MoonWaker.HostServices.zip",
        "/resource:$(Join-Path $hostServicesRoot 'version.json'),MoonWaker.Version.json",
        "/reference:System.dll", "/reference:System.Core.dll",
        "/reference:System.Drawing.dll", "/reference:System.Windows.Forms.dll",
        "/reference:System.Security.dll",
        "/reference:System.IO.Compression.dll",
        "/reference:System.IO.Compression.FileSystem.dll",
        (Join-Path $PSScriptRoot "MoonWakerHostInstaller.cs")
    )
    $icon = Join-Path $PSScriptRoot "moonwaker-host.ico"
    if (Test-Path -LiteralPath $icon) {
        $arguments = @("/win32icon:$icon") + $arguments
    }
    & $compiler @arguments
    if ($LASTEXITCODE -ne 0 -or -not (Test-Path -LiteralPath $output)) {
        throw "MoonWaker Host Installer compilation failed."
    }
    Get-Item -LiteralPath $output
}
finally {
    if (Test-Path -LiteralPath $temporaryRoot) {
        $resolvedTemporary = [IO.Path]::GetFullPath($temporaryRoot)
        if ($resolvedTemporary.StartsWith([IO.Path]::GetTempPath(),
                [StringComparison]::OrdinalIgnoreCase) -and
                (Split-Path -Leaf $resolvedTemporary) -like
                "moonwaker-installer-build-*") {
            Remove-Item -LiteralPath $resolvedTemporary -Recurse -Force
        }
    }
}
