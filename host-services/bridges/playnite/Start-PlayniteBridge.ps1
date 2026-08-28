#requires -Version 5.1
[CmdletBinding()]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"
$config = Join-Path $PSScriptRoot "config.json"
if (-not (Test-Path -LiteralPath $config)) {
    Copy-Item -LiteralPath (Join-Path $PSScriptRoot "config.example.json") -Destination $config
}
$log = Join-Path $PSScriptRoot "game-provider-bridge.log"
$errorLog = Join-Path $PSScriptRoot "game-provider-bridge-error.log"
$process = Start-Process -FilePath "python.exe" -ArgumentList @(
    "`"$(Join-Path $PSScriptRoot 'GameProviderBridge.py')`"", "--config", "`"$config`"") `
    -WorkingDirectory $PSScriptRoot -WindowStyle Hidden -PassThru `
    -RedirectStandardOutput $log -RedirectStandardError $errorLog
Write-Host "Game Provider Bridge started (PID $($process.Id))."
