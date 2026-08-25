#requires -Version 5.1
[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$HostInstallScript,
    [Parameter(Mandatory)][string]$InstallDirectory,
    [Parameter(Mandatory)][string]$GatewayDirectory,
    [switch]$SkipEpicLegendary,
    [Parameter(Mandatory)][string]$ResultPath
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"
try {
    $output = & $HostInstallScript -InstallDirectory $InstallDirectory `
        -GatewayDirectory $GatewayDirectory -SkipEpicLegendary:$SkipEpicLegendary `
        -SkipStart *>&1 | Out-String
    Set-Content -LiteralPath $ResultPath -Value $output -Encoding UTF8
    exit 0
} catch {
    $details = "{0}`r`n{1}" -f $_.Exception.Message, ($_ | Out-String)
    Set-Content -LiteralPath $ResultPath -Value $details -Encoding UTF8
    exit 1
}
