#requires -Version 5.1
[CmdletBinding()]
param(
    [string]$OutputDirectory = "",
    [switch]$SkipTests
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"
$hostServices = Join-Path $PSScriptRoot "host-services"
if ([string]::IsNullOrWhiteSpace($OutputDirectory)) {
    $OutputDirectory = Join-Path $PSScriptRoot "host-services\dist"
}
if (-not (Test-Path -LiteralPath $hostServices)) {
    throw "host-services was not found next to this build script."
}
$scopeDocument = Get-Content -LiteralPath (Join-Path $hostServices `
    "bridges\vibepollo\moonwaker-token-scopes.example.json") -Raw | ConvertFrom-Json
$appsScope = @($scopeDocument.scopes | Where-Object { $_.path -eq "/api/apps" }) |
    Select-Object -First 1
if ($null -eq $appsScope -or "GET" -notin @($appsScope.methods) -or
    "POST" -notin @($appsScope.methods)) {
    throw "Vibepollo token scope must allow GET and POST /api/apps."
}
if (-not $SkipTests) {
    & python.exe -m unittest discover -s (Join-Path $hostServices "gateway") -p "test*.py"
    if ($LASTEXITCODE -ne 0) { throw "Gateway tests failed." }
    & python.exe -m unittest discover -s (Join-Path $hostServices "bridges\playnite") -p "test*.py"
    if ($LASTEXITCODE -ne 0) { throw "Game Provider Bridge tests failed." }
}

$temporary = Join-Path ([IO.Path]::GetTempPath()) ("moonwaker-host-build-" + [guid]::NewGuid().ToString("N"))
$payloadRoot = Join-Path $temporary "payload\host-services"
$zipPath = Join-Path $temporary "host-services.zip"
New-Item -ItemType Directory -Path $payloadRoot, $OutputDirectory -Force | Out-Null
try {
    foreach ($directory in @("bridges", "gateway", "install", "profile-agent")) {
        Copy-Item -LiteralPath (Join-Path $hostServices $directory) `
            -Destination $payloadRoot -Recurse -Force
    }
    & (Join-Path $hostServices "installer\Install-LegendaryPayload.ps1") `
        -TargetDirectory (Join-Path $payloadRoot "tools\legendary")
    Copy-Item -LiteralPath (Join-Path $hostServices "version.json") `
        -Destination $payloadRoot -Force
    $controlTarget = Join-Path $payloadRoot "control"
    New-Item -ItemType Directory -Path $controlTarget -Force | Out-Null
    Copy-Item -LiteralPath (Join-Path $hostServices "installer\Install-LegendaryPayload.ps1") `
        -Destination $controlTarget -Force
    foreach ($file in @("Build-MoonWakerHostControl.ps1", "Invoke-MoonWakerHostControl.ps1",
        "MoonWakerHostControl.cs", "MoonWakerHostControl.manifest")) {
        Copy-Item -LiteralPath (Join-Path $hostServices "control\$file") `
            -Destination $controlTarget -Force
    }
    & (Join-Path $hostServices "control\Build-MoonWakerHostControl.ps1") `
        -OutputDirectory $controlTarget | Out-Host

    Get-ChildItem -LiteralPath (Join-Path $temporary "payload") -Recurse -Force |
        Where-Object {
            $_.Name -eq "__pycache__" -or $_.Extension -in @(".pyc", ".log") -or
            $_.FullName -match '[\\/]exports[\\/]'
        } | Sort-Object FullName -Descending | Remove-Item -Recurse -Force

    Add-Type -AssemblyName System.IO.Compression
    $zipStream = [IO.File]::Open($zipPath, [IO.FileMode]::CreateNew)
    try {
        $archive = [IO.Compression.ZipArchive]::new(
            $zipStream, [IO.Compression.ZipArchiveMode]::Create, $false)
        try {
            $payloadDirectory = Join-Path $temporary "payload"
            Get-ChildItem -LiteralPath $payloadDirectory -File -Recurse |
                Sort-Object FullName | ForEach-Object {
                    $relative = $_.FullName.Substring($payloadDirectory.Length + 1).Replace('\', '/')
                    $entry = $archive.CreateEntry($relative, [IO.Compression.CompressionLevel]::Optimal)
                    $input = [IO.File]::OpenRead($_.FullName)
                    $output = $entry.Open()
                    try { $input.CopyTo($output) } finally { $output.Dispose(); $input.Dispose() }
                }
        } finally { $archive.Dispose() }
    } finally { $zipStream.Dispose() }

    $installer = Join-Path $OutputDirectory "MoonWakerHostInstaller.exe"
    $frameworkRoot = Join-Path $env:WINDIR "Microsoft.NET\Framework64"
    $csc = Get-ChildItem -LiteralPath $frameworkRoot -Directory |
        Where-Object { Test-Path -LiteralPath (Join-Path $_.FullName "csc.exe") } |
        Sort-Object { [version]($_.Name.TrimStart("v")) } -Descending |
        Select-Object -First 1 -ExpandProperty FullName
    if ([string]::IsNullOrWhiteSpace($csc)) {
        throw "The .NET Framework C# compiler was not found."
    }
    $csc = Join-Path $csc "csc.exe"
    $installerSource = Join-Path $hostServices "installer\MoonWakerHostInstaller.cs"
    $installerManifest = Join-Path $hostServices "installer\MoonWakerHostInstaller.manifest"
    $installerIcon = Join-Path $hostServices "installer\moonwaker-host.ico"
    if (-not (Test-Path -LiteralPath $installerIcon)) {
        throw "MoonWaker installer icon was not found: $installerIcon"
    }
    $compilerArguments = @(
        "/nologo", "/target:winexe", "/optimize+", "/platform:anycpu",
        "/out:$installer", "/win32icon:$installerIcon", "/win32manifest:$installerManifest",
        "/resource:$zipPath,MoonWaker.HostServices.zip",
        "/resource:$(Join-Path $hostServices 'version.json'),MoonWaker.Version.json",
        "/reference:System.dll", "/reference:System.Core.dll",
        "/reference:System.Drawing.dll", "/reference:System.Windows.Forms.dll",
        "/reference:System.IO.Compression.dll", "/reference:System.IO.Compression.FileSystem.dll",
        $installerSource
    )
    & $csc $compilerArguments
    if ($LASTEXITCODE -ne 0 -or -not (Test-Path -LiteralPath $installer)) {
        throw "MoonWaker Host Installer compilation failed."
    }

    $hash = (Get-FileHash -LiteralPath $installer -Algorithm SHA256).Hash.ToLowerInvariant()
    [ordered]@{
        installer = $installer
        sha256 = $hash
        payload_files = @(Get-ChildItem -LiteralPath $payloadRoot -File -Recurse).Count
    } | ConvertTo-Json -Compress
} finally {
    if (Test-Path -LiteralPath $temporary) {
        Remove-Item -LiteralPath $temporary -Recurse -Force
    }
}
