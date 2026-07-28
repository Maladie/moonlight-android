#requires -Version 5.1
[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$InstallDirectory,
    [Parameter(Mandatory)][ValidatePattern('^S-1-[0-9-]+$')][string]$UserSid
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"
$principal = [Security.Principal.WindowsPrincipal]::new(
    [Security.Principal.WindowsIdentity]::GetCurrent())
if (-not $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
    throw "Grant-MoonWakerMachineDiscordAccess.ps1 must run as administrator."
}

$machineRoot = Join-Path ([IO.Path]::GetFullPath($InstallDirectory)) "machine-data"
$paths = @(
    (Join-Path $machineRoot "discord-app.json"),
    (Join-Path $machineRoot "discord-app-secret.dpapi")
)
foreach ($path in $paths) {
    if (-not (Test-Path -LiteralPath $path)) {
        throw "Shared Discord application data is missing: $path"
    }
}

# The secret is LocalMachine-DPAPI protected.  Access is granted only to the
# account that is currently adding its profile, so it can create its own
# CurrentUser-DPAPI copy without exposing the secret to every local account.
& icacls.exe $machineRoot /grant "*$UserSid`:(RX)" | Out-Null
if ($LASTEXITCODE -ne 0) { throw "Unable to grant access to the shared Discord data directory." }
foreach ($path in $paths) {
    & icacls.exe $path /grant:r "*$UserSid`:(R)" | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "Unable to grant access to shared Discord application data." }
}
Write-Host "Granted the current Windows profile read access to shared Discord application data."
