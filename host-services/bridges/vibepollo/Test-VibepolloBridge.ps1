#requires -Version 5.1
$config = Get-Content -LiteralPath (Join-Path $PSScriptRoot "config.json") -Raw | ConvertFrom-Json
$base = "http://127.0.0.1:$([int]$config.listen_port)"
Write-Host "Testing Vibepollo Bridge: $base" -ForegroundColor Cyan
$health = Invoke-RestMethod "$base/health" -TimeoutSec 10
$stream = Invoke-RestMethod "$base/apps/stream/ensure" -Method Post `
    -ContentType "application/json" -Body "{}" -TimeoutSec 10
$streamAgain = Invoke-RestMethod "$base/apps/stream/ensure" -Method Post `
    -ContentType "application/json" -Body "{}" -TimeoutSec 10
if ([string]$stream.uuid -ne [string]$streamAgain.uuid -or
    [long]$stream.app_id -ne [long]$streamAgain.app_id -or
    [bool]$streamAgain.created) {
    throw "MoonWaker Stream ensure is not idempotent"
}
$snapshot = Invoke-RestMethod "$base/snapshot?force=1" -TimeoutSec 30
$health | Format-List
$stream | Format-List
$snapshot | ConvertTo-Json -Depth 8
