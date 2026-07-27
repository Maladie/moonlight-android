$configPath = Join-Path $PSScriptRoot "discord_bridge_config.json"
$port = 8765

if (Test-Path -LiteralPath $configPath) {
    try {
        $config = Get-Content -LiteralPath $configPath -Raw | ConvertFrom-Json
        $port = [int]$config.port
    }
    catch {}
}

try {
    Invoke-WebRequest -UseBasicParsing -Uri "http://127.0.0.1:$port/shutdown" | Out-Null
    Write-Host "Wysłano polecenie zatrzymania bridge."
}
catch {
    Write-Host "Nie udało się połączyć z bridge: $($_.Exception.Message)"
}
