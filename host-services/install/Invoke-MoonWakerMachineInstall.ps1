#requires -Version 5.1
[CmdletBinding()]
param(
    [string]$HostInstallScript = "",
    [Parameter(Mandatory)][string]$InstallDirectory,
    [Parameter(Mandatory)][string]$GatewayDirectory,
    [Parameter(Mandatory)][string]$ResultPath,
    [string]$PrerequisiteScript = "",
    [string]$VibepolloCredentialPath = "",
    [switch]$EnableWakeOnLan,
    [switch]$EnsureVibepollo,
    [switch]$SkipMoonWakerHost
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"
try {
    $lines = @()
    if ($EnableWakeOnLan -or $EnsureVibepollo) {
        if (-not (Test-Path -LiteralPath $PrerequisiteScript -PathType Leaf)) {
            throw "MoonWaker prerequisite script is missing."
        }
        $lines += & $PrerequisiteScript -EnableWakeOnLan:$EnableWakeOnLan `
            -EnsureVibepollo:$EnsureVibepollo `
            -VibepolloCredentialPath $VibepolloCredentialPath *>&1
    }
    if (-not $SkipMoonWakerHost) {
        if (-not (Test-Path -LiteralPath $HostInstallScript -PathType Leaf)) {
            throw "MoonWaker host install script is missing."
        }
        $lines += & $HostInstallScript -InstallDirectory $InstallDirectory `
            -GatewayDirectory $GatewayDirectory -SkipStart *>&1
    }
    $output = $lines | Out-String
    Set-Content -LiteralPath $ResultPath -Value $output -Encoding UTF8
    exit 0
} catch {
    $details = "{0}`r`n{1}" -f $_.Exception.Message, ($_ | Out-String)
    Set-Content -LiteralPath $ResultPath -Value $details -Encoding UTF8
    exit 1
} finally {
    if (-not [string]::IsNullOrWhiteSpace($VibepolloCredentialPath)) {
        Remove-Item -LiteralPath $VibepolloCredentialPath -Force -ErrorAction SilentlyContinue
    }
}
