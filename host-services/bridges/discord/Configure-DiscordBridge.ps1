#requires -Version 5.1
Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$ScriptRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$ConfigPath = Join-Path $ScriptRoot "discord_bridge_config.json"
$SecretPath = Join-Path $ScriptRoot "client_secret.dpapi"
$TokenPath = Join-Path $ScriptRoot "oauth_token.dpapi"

Write-Host ""
Write-Host "Discord RPC Bridge - konfiguracja" -ForegroundColor Cyan
Write-Host "==================================" -ForegroundColor Cyan
Write-Host ""
Write-Host "Przed kontynuacją utwórz aplikację w Discord Developer Portal."
Write-Host "Dodaj swoje konto Discord jako App Tester."
Write-Host ""

$clientId = Read-Host "Application ID / Client ID"

if ([string]::IsNullOrWhiteSpace($clientId)) {
    throw "Client ID nie może być pusty."
}

$clientSecret = Read-Host "Client Secret (wartość nie będzie wyświetlana)" -AsSecureString
$credential = [System.Management.Automation.PSCredential]::new("x", $clientSecret)
$plainSecret = $credential.GetNetworkCredential().Password

if ([string]::IsNullOrWhiteSpace($plainSecret)) {
    throw "Client Secret nie może być pusty."
}

$portText = Read-Host "Port lokalnego bridge [8765]"
$port = 8765

if (-not [string]::IsNullOrWhiteSpace($portText)) {
    $port = [int]$portText
}

$redirectUri = Read-Host "Redirect URI (zostaw puste dla typowego RPC IPC)"

$config = [ordered]@{
    client_id = $clientId.Trim()
    port = $port
    redirect_uri = $redirectUri.Trim()
    scopes = @(
        "rpc",
        "identify",
        "guilds",
        "rpc.voice.read",
        "rpc.voice.write"
    )
}

$config |
    ConvertTo-Json -Depth 10 |
    Set-Content -LiteralPath $ConfigPath -Encoding UTF8

$clientSecret |
    ConvertFrom-SecureString |
    Set-Content -LiteralPath $SecretPath -Encoding ASCII

Remove-Item -LiteralPath $TokenPath -Force -ErrorAction SilentlyContinue

Write-Host ""
Write-Host "Zapisano:" -ForegroundColor Green
Write-Host "  $ConfigPath"
Write-Host "  $SecretPath (DPAPI, tylko dla tego konta Windows)"
Write-Host ""
Write-Host "Przy pierwszym wywołaniu /authorize lub /guilds Discord powinien pokazać modal autoryzacji." -ForegroundColor Yellow
Write-Host "Jeśli Discord odrzuci scope rpc/rpc.voice.*, sprawdź logi i status aplikacji/testera." -ForegroundColor Yellow
Write-Host ""
