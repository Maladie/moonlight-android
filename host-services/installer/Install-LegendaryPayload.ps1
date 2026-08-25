#requires -Version 5.1
[CmdletBinding()]
param([Parameter(Mandatory)][string]$TargetDirectory)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"
$url = "https://github.com/legendary-gl/legendary/releases/download/0.20.34/legendary.exe"
$expectedSize = 8463909
$expectedHash = "01ea22ea51749f46a0019657f64fc0d34429fb7cbf9b590c0848c0e0bd9c1f07"
New-Item -ItemType Directory -Path $TargetDirectory -Force | Out-Null
$target = Join-Path $TargetDirectory "legendary.exe"
if (Test-Path -LiteralPath $target) {
    $hash = (Get-FileHash -LiteralPath $target -Algorithm SHA256).Hash.ToLowerInvariant()
    if ((Get-Item -LiteralPath $target).Length -eq $expectedSize -and $hash -eq $expectedHash) { return }
    Remove-Item -LiteralPath $target -Force
}
Invoke-WebRequest -Uri $url -OutFile $target -UseBasicParsing
$hash = (Get-FileHash -LiteralPath $target -Algorithm SHA256).Hash.ToLowerInvariant()
if ((Get-Item -LiteralPath $target).Length -ne $expectedSize -or $hash -ne $expectedHash) {
    Remove-Item -LiteralPath $target -Force -ErrorAction SilentlyContinue
    throw "Legendary payload hash or size verification failed."
}
