#requires -Version 5.1
[CmdletBinding()]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"
$config = Join-Path $PSScriptRoot "config.json"
if (-not (Test-Path -LiteralPath $config)) {
    Copy-Item -LiteralPath (Join-Path $PSScriptRoot "config.example.json") -Destination $config
}
$log = Join-Path $PSScriptRoot "playnite-bridge.log"
$errorLog = Join-Path $PSScriptRoot "playnite-bridge-error.log"
$process = Start-Process -FilePath "python.exe" -ArgumentList @(
    "`"$(Join-Path $PSScriptRoot 'PlayniteBridge.py')`"", "--config", "`"$config`"") `
    -WorkingDirectory $PSScriptRoot -WindowStyle Hidden -PassThru `
    -RedirectStandardOutput $log -RedirectStandardError $errorLog
Write-Host "Playnite Bridge started (PID $($process.Id))."
