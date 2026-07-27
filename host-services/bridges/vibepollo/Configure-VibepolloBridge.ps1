#requires -Version 5.1
Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$configPath = Join-Path $PSScriptRoot "config.json"
$tokenPath = Join-Path $PSScriptRoot "api_token.dpapi"

Write-Host "Wake & Play - Vibepollo Bridge configuration" -ForegroundColor Cyan
$baseUrlText = Read-Host "Vibepollo API URL [https://127.0.0.1:47990]"
$baseUrl = if ([string]::IsNullOrWhiteSpace($baseUrlText)) {
    "https://127.0.0.1:47990"
} else {
    $baseUrlText.Trim().TrimEnd('/')
}

$uri = $null
if (-not [uri]::TryCreate($baseUrl, [UriKind]::Absolute, [ref]$uri) -or
    $uri.Scheme -ne "https" -or
    $uri.Host -notin @("127.0.0.1", "localhost")) {
    throw "Vibepollo API must use HTTPS on 127.0.0.1 or localhost."
}

$portText = Read-Host "Local Bridge port [8775]"
$port = if ([string]::IsNullOrWhiteSpace($portText)) { 8775 } else { [int]$portText }
if ($port -lt 1024 -or $port -gt 65535) { throw "Bridge port must be between 1024 and 65535." }

$pythonPath = Read-Host "Optional full path to python.exe (leave empty for PATH discovery)"
$apiToken = Read-Host "Vibepollo API token (hidden)" -AsSecureString
$credential = [pscredential]::new("token", $apiToken)
if ([string]::IsNullOrWhiteSpace($credential.GetNetworkCredential().Password)) {
    throw "Vibepollo API token cannot be empty."
}

$config = [ordered]@{
    base_url = $baseUrl
    listen_port = $port
    python_path = $pythonPath.Trim()
}
$config | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath $configPath -Encoding UTF8
$apiToken | ConvertFrom-SecureString | Set-Content -LiteralPath $tokenPath -Encoding ASCII

Write-Host "Configuration saved for Windows user $env:USERNAME." -ForegroundColor Green
Write-Host "The API token is protected with DPAPI and cannot be reused by another profile."
