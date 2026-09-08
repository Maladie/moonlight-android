#requires -Version 5.1
[CmdletBinding()]
param(
    [string]$HostInstallScript = "",
    [Parameter(Mandatory)][ValidateNotNullOrEmpty()][string]$InstallDirectory,
    [Parameter(Mandatory)][ValidateNotNullOrEmpty()][string]$GatewayDirectory,
    [Parameter(Mandatory)][ValidateNotNullOrEmpty()][string]$ResultPath,
    [Parameter(Mandatory)][ValidateNotNullOrEmpty()][string]$ProtectedStagingDirectory,
    [string]$PrerequisiteScript = "",
    [switch]$EnableWakeOnLan,
    [switch]$EnsureVibepollo,
    [switch]$EnsureViGEmBus,
    [switch]$EnsurePython,
    [switch]$EnsureOpenSsl,
    [switch]$SkipMoonWakerHost
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"
$stage = "initializing the elevated installer"
try {
    $trustedModuleRoot = [IO.Path]::GetFullPath((Join-Path $PSHOME "Modules"))
    $env:PSModulePath = $trustedModuleRoot
    foreach ($module in @("CimCmdlets", "ScheduledTasks", "NetAdapter", "NetSecurity")) {
        $manifest = Join-Path $trustedModuleRoot "$module\$module.psd1"
        if (-not (Test-Path -LiteralPath $manifest -PathType Leaf)) {
            throw "Required trusted Windows PowerShell module is missing: $module"
        }
        Import-Module -Name $manifest -Force -ErrorAction Stop
    }
    $lines = @()
    if ($EnableWakeOnLan -or $EnsureVibepollo -or $EnsureViGEmBus -or
        $EnsurePython -or $EnsureOpenSsl) {
        $stage = "checking host prerequisites"
        if ([string]::IsNullOrWhiteSpace($PrerequisiteScript) -or
            -not (Test-Path -LiteralPath $PrerequisiteScript -PathType Leaf)) {
            throw "MoonWaker prerequisite script is missing."
        }
        $lines += & $PrerequisiteScript -EnableWakeOnLan:$EnableWakeOnLan `
            -EnsureVibepollo:$EnsureVibepollo `
            -EnsureViGEmBus:$EnsureViGEmBus `
            -EnsurePython:$EnsurePython `
            -EnsureOpenSsl:$EnsureOpenSsl `
            -ProtectedStagingDirectory $ProtectedStagingDirectory *>&1
    }
    if (-not $SkipMoonWakerHost) {
        $stage = "installing MoonWaker host services"
        if ([string]::IsNullOrWhiteSpace($HostInstallScript) -or
            -not (Test-Path -LiteralPath $HostInstallScript -PathType Leaf)) {
            throw "MoonWaker host install script is missing."
        }
        $lines += & $HostInstallScript -InstallDirectory $InstallDirectory `
            -GatewayDirectory $GatewayDirectory *>&1
    }
    $output = $lines | Out-String
    Set-Content -LiteralPath $ResultPath -Value $output -Encoding UTF8
    exit 0
} catch {
    $details = "Installer stage: $stage`r`n`r`n" + ($_ | Out-String)
    try { Set-Content -LiteralPath $ResultPath -Value $details -Encoding UTF8 } catch {}
    [Console]::Error.WriteLine($details)
    exit 1
}
