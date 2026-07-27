#requires -Version 5.1
$config = Get-Content -LiteralPath (Join-Path $PSScriptRoot "config.json") -Raw | ConvertFrom-Json
$base = "http://127.0.0.1:$([int]$config.listen_port)"
Write-Host "Testing Vibepollo Bridge: $base" -ForegroundColor Cyan
$health = Invoke-RestMethod "$base/health" -TimeoutSec 10
$snapshot = Invoke-RestMethod "$base/snapshot?force=1" -TimeoutSec 30
$health | Format-List
$snapshot | ConvertTo-Json -Depth 8
