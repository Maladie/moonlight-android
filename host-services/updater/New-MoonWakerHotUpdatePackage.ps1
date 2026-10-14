#requires -Version 5.1
[CmdletBinding()]
param(
    [Parameter(Mandatory)][ValidatePattern('^\d+\.\d+\.\d+$')][string]$Version,
    [Parameter(Mandatory)][ValidatePattern('^[A-Za-z0-9._-]{1,64}$')][string]$Build,
    [Parameter(Mandatory)][string[]]$Files,
    [Parameter(Mandatory)][string]$OutputPath,
    [string]$SourceRoot = (Split-Path -Parent $PSScriptRoot)
)
$ErrorActionPreference = "Stop"
function Test-Allowed([string]$Path) {
    return $Path -match '^(gateway/[A-Za-z0-9_.-]+\.py|bridges/(playnite/[A-Za-z0-9_.-]+\.py|discord/[A-Za-z0-9_.-]+\.(ps1|psm1)))$'
}
$temporary = Join-Path ([IO.Path]::GetTempPath()) ("moonwaker-hot-package-" + [guid]::NewGuid().ToString("N"))
try {
    New-Item -ItemType Directory -Path (Join-Path $temporary "payload") -Force | Out-Null
    $rows = @(); $seen = @{}
    foreach ($relative in $Files) {
        $relative = $relative.Replace('\', '/')
        if (-not (Test-Allowed $relative) -or $seen.ContainsKey($relative)) {
            throw "File is duplicate or outside the hot-update allowlist: $relative"
        }
        $seen[$relative] = $true
        $source = Join-Path $SourceRoot $relative.Replace('/', '\')
        if (-not (Test-Path -LiteralPath $source -PathType Leaf)) { throw "Source file not found: $relative" }
        $destination = Join-Path $temporary ("payload\" + $relative.Replace('/', '\'))
        New-Item -ItemType Directory -Path (Split-Path -Parent $destination) -Force | Out-Null
        Copy-Item -LiteralPath $source -Destination $destination
        $rows += @{ Path = $relative; Sha256 = (Get-FileHash -LiteralPath $source -Algorithm SHA256).Hash.ToLowerInvariant(); Size = (Get-Item -LiteralPath $source).Length }
    }
    if ($rows.Count -lt 1) { throw "At least one allowlisted source file is required." }
    $manifest = @("@{", "  Format = 1", "  Version = '$Version'", "  Build = '$Build'", "  Files = @(")
    foreach ($row in $rows) {
        $manifest += "    @{ Path = '$($row.Path)'; Sha256 = '$($row.Sha256)'; Size = $($row.Size) }"
    }
    $manifest += @("  )", "}")
    $manifestPath = Join-Path $temporary "manifest.psd1"
    [IO.File]::WriteAllLines($manifestPath, $manifest, [Text.UTF8Encoding]::new($false))
    Add-Type -AssemblyName System.IO.Compression
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $resolvedOutput = [IO.Path]::GetFullPath($OutputPath)
    New-Item -ItemType Directory -Path (Split-Path -Parent $resolvedOutput) -Force | Out-Null
    if (Test-Path -LiteralPath $resolvedOutput) { Remove-Item -LiteralPath $resolvedOutput -Force }
    $outputStream = [IO.File]::Open($resolvedOutput, [IO.FileMode]::CreateNew)
    try {
        $archive = [IO.Compression.ZipArchive]::new($outputStream, [IO.Compression.ZipArchiveMode]::Create, $false)
        try {
            foreach ($file in Get-ChildItem -LiteralPath $temporary -File -Recurse) {
                $entryName = $file.FullName.Substring($temporary.Length).TrimStart('\').Replace('\', '/')
                $entry = $archive.CreateEntry($entryName, [IO.Compression.CompressionLevel]::Optimal)
                $inputStream = $file.OpenRead()
                try { $entryStream = $entry.Open(); try { $inputStream.CopyTo($entryStream) } finally { $entryStream.Dispose() } }
                finally { $inputStream.Dispose() }
            }
        } finally { $archive.Dispose() }
    } finally { $outputStream.Dispose() }
    Get-Item -LiteralPath $resolvedOutput
} finally {
    if (Test-Path -LiteralPath $temporary) { Remove-Item -LiteralPath $temporary -Recurse -Force }
}
