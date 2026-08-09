#requires -Version 5.1
[CmdletBinding()]
param(
    [string] $OutputDirectory = (Join-Path (Split-Path -Parent $PSScriptRoot) "dist")
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
        "host-services/version.json"
    if ($LASTEXITCODE -ne 0 -or -not $tracked) {
        throw "Unable to enumerate the host payload."
    }
    foreach ($relative in $tracked) {
        if ($relative -match "(^|/)(dist|__pycache__)(/|$)" -or
                $relative -match "\.pyc$") {
            continue
        }
        $source = Join-Path $repoRoot ($relative -replace "/", "\")
        $destination = Join-Path $payloadRoot ($relative -replace "/", "\")
        $destinationDirectory = Split-Path -Parent $destination
        New-Item -ItemType Directory -Path $destinationDirectory -Force | Out-Null
        Copy-Item -LiteralPath $source -Destination $destination -Force
    }

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
        "/reference:System.IO.Compression.dll",
        "/reference:System.IO.Compression.FileSystem.dll",
        (Join-Path $PSScriptRoot "MoonWakerHostInstaller.cs")
    )
    $icon = Join-Path $hostServicesRoot "control\moonwaker-host.ico"
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
