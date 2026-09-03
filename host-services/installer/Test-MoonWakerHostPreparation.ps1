#requires -Version 5.1
[CmdletBinding()]
param()

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
$preparePath = Join-Path $root "install\Prepare-MoonWakerHost.ps1"
$wrapperPath = Join-Path $root "install\Invoke-MoonWakerMachineInstall.ps1"
$installerPath = Join-Path $PSScriptRoot "MoonWakerHostInstaller.cs"

foreach ($path in @($preparePath, $wrapperPath, $installerPath)) {
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) { throw "Missing installer source: $path" }
}

$prepare = Get-Content -LiteralPath $preparePath -Raw
$wrapper = Get-Content -LiteralPath $wrapperPath -Raw
$installer = Get-Content -LiteralPath $installerPath -Raw

foreach ($required in @(
    "https://api.github.com/repos/Nonary/Vibepollo/releases/latest",
    "/Nonary/Vibepollo/releases/download/",
    "Get-AuthenticodeSignature",
    'Status -ne "Valid"',
    'name -ieq "Vibepollo.msi"',
    "Enable-NetAdapterPowerManagement",
    "-WakeOnMagicPacket",
    "/deviceenableawake",
    "/devicequery wake_armed"
)) {
    if (-not $prepare.Contains($required)) { throw "Host preparation safety contract is missing: $required" }
}
if (-not $wrapper.Contains("Remove-Item -LiteralPath `$VibepolloCredentialPath")) {
    throw "The elevated wrapper no longer deletes the temporary credential file."
}
if (-not $installer.Contains("ProtectedData.Protect") -or
    -not $installer.Contains("DataProtectionScope.CurrentUser")) {
    throw "Vibepollo bootstrap credentials are no longer protected with CurrentUser DPAPI."
}
if ($installer.Contains('arguments += " -VibepolloPassword')) {
    throw "A Vibepollo password must never be passed on the elevated process command line."
}

Write-Output "MoonWaker host preparation safety test passed."
