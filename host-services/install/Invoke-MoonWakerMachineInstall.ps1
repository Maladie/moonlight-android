#requires -Version 5.1
[CmdletBinding()]
param(
    [string]$HostInstallScript = "",
    [Parameter(Mandatory)][string]$InstallDirectory,
    [Parameter(Mandatory)][string]$GatewayDirectory,
    [Parameter(Mandatory)][string]$ResultPath,
    [Parameter(Mandatory)][string]$ProtectedStagingDirectory,
    [string]$PrerequisiteScript = "",
    [switch]$EnableWakeOnLan,
    [switch]$EnsureVibepollo,
    [switch]$SkipMoonWakerHost
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"
$trustedModuleRoot = [IO.Path]::GetFullPath((Join-Path $PSHOME "Modules"))
$env:PSModulePath = $trustedModuleRoot
foreach ($module in @("CimCmdlets", "ScheduledTasks", "NetAdapter", "NetSecurity")) {
    $manifest = Join-Path $trustedModuleRoot "$module\$module.psd1"
    if (-not (Test-Path -LiteralPath $manifest -PathType Leaf)) {
        throw "Required trusted Windows PowerShell module is missing: $module"
    }
    Import-Module -Name $manifest -Force -ErrorAction Stop
}
try {
    $lines = @()
    if ($EnableWakeOnLan -or $EnsureVibepollo) {
        if (-not (Test-Path -LiteralPath $PrerequisiteScript -PathType Leaf)) {
            throw "MoonWaker prerequisite script is missing."
        }
        $lines += & $PrerequisiteScript -EnableWakeOnLan:$EnableWakeOnLan `
            -EnsureVibepollo:$EnsureVibepollo `
            -ProtectedStagingDirectory $ProtectedStagingDirectory *>&1
    }
    if (-not $SkipMoonWakerHost) {
        if (-not (Test-Path -LiteralPath $HostInstallScript -PathType Leaf)) {
            throw "MoonWaker host install script is missing."
        }
        $lines += & $HostInstallScript -InstallDirectory $InstallDirectory `
            -GatewayDirectory $GatewayDirectory *>&1
    }
    $output = $lines | Out-String
    Set-Content -LiteralPath $ResultPath -Value $output -Encoding UTF8
    exit 0
} catch {
    $details = $_ | Out-String
    Set-Content -LiteralPath $ResultPath -Value $details -Encoding UTF8
    exit 1
}
