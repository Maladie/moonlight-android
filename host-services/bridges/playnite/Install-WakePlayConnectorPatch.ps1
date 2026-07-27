#requires -Version 5.1
[CmdletBinding(SupportsShouldProcess=$true)]
param(
    [string]$PlayniteDirectory = ""
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"
if ([string]::IsNullOrWhiteSpace($PlayniteDirectory)) {
    $process = Get-Process -Name "Playnite.DesktopApp", "Playnite.FullscreenApp" `
        -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($process -and $process.Path) {
        $PlayniteDirectory = Split-Path -Parent $process.Path
    }
}
if ([string]::IsNullOrWhiteSpace($PlayniteDirectory)) {
    throw "Playnite directory was not discovered. Pass -PlayniteDirectory explicitly."
}
$module = Join-Path $PlayniteDirectory "Extensions\SunshinePlaynite\SunshinePlaynite.psm1"
if (-not (Test-Path -LiteralPath $module)) {
    throw "Sunshine Playnite Connector was not found at $module"
}
$patcher = Join-Path $PSScriptRoot "PatchPlayniteConnector.py"
& python.exe $patcher $module
if ($LASTEXITCODE -ne 0) { throw "Installed connector is not compatible with the WakePlay patch." }
if ($PSCmdlet.ShouldProcess($module, "Add WakePlay launcher snapshot support")) {
    & python.exe $patcher $module --apply
    if ($LASTEXITCODE -ne 0) { throw "Unable to patch the Sunshine Playnite Connector." }
    Write-Warning "Restart Playnite to load WakePlay snapshot support."
}
