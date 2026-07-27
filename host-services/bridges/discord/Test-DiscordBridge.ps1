$configPath = Join-Path $PSScriptRoot "discord_bridge_config.json"
$port = 8765

if (Test-Path -LiteralPath $configPath) {
    $config = Get-Content -LiteralPath $configPath -Raw | ConvertFrom-Json
    $port = [int]$config.port
}

$base = "http://127.0.0.1:$port"

Write-Host "HEALTH:" -ForegroundColor Cyan
try {
    (Invoke-WebRequest -UseBasicParsing -Uri "$base/health").Content
}
catch {
    Write-Host $_.Exception.Message -ForegroundColor Red
}

Write-Host ""
Write-Host "AUTHORIZE / CONNECT:" -ForegroundColor Cyan
try {
    (Invoke-WebRequest -UseBasicParsing -Uri "$base/authorize" -TimeoutSec 120).Content
}
catch {
    Write-Host $_.Exception.Message -ForegroundColor Red
}

Write-Host ""
Write-Host "GUILDS:" -ForegroundColor Cyan
try {
    (Invoke-WebRequest -UseBasicParsing -Uri "$base/guilds" -TimeoutSec 120).Content
}
catch {
    Write-Host $_.Exception.Message -ForegroundColor Red
}
