param(
    [string]$InstallDirectory = "C:\Tools\WakePlayGateway",
    [int]$Port = 8785,
    [switch]$SkipFirewall,
    [switch]$SkipStart
)

$ErrorActionPreference = "Stop"
$sourceDirectory = $PSScriptRoot
$files = @(
    "wakeplay_gateway.py",
    "Start-WakePlayGateway.ps1",
    "MoonWakerGatewaySupervisor.ps1",
    "Start-MoonWakerGateway.ps1",
    "Stop-MoonWakerGateway.ps1",
    "gateway.example.json",
    "README.md"
)

New-Item -ItemType Directory -Path $InstallDirectory -Force | Out-Null
foreach ($name in $files) {
    Copy-Item -LiteralPath (Join-Path $sourceDirectory $name) `
        -Destination (Join-Path $InstallDirectory $name) -Force
}

$configPath = Join-Path $InstallDirectory "gateway.json"
if (-not (Test-Path -LiteralPath $configPath)) {
    $config = Get-Content -LiteralPath (Join-Path $InstallDirectory "gateway.example.json") -Raw |
        ConvertFrom-Json
    $config.listen_port = $Port
    $config | ConvertTo-Json -Depth 10 | Set-Content -LiteralPath $configPath -Encoding UTF8
}

$certificate = Join-Path $InstallDirectory "gateway-cert.pem"
$privateKey = Join-Path $InstallDirectory "gateway-key.pem"
if (-not (Test-Path -LiteralPath $certificate) -or -not (Test-Path -LiteralPath $privateKey)) {
    $openssl = (Get-Command openssl.exe -ErrorAction Stop).Source
    & $openssl req -x509 -newkey rsa:3072 -sha256 -nodes `
        -keyout $privateKey -out $certificate -days 825 `
        -subj "/CN=Wake and Play Host Gateway" `
        -addext "subjectAltName=DNS:localhost,IP:127.0.0.1"
    if ($LASTEXITCODE -ne 0) { throw "OpenSSL certificate generation failed." }
}

# The Gateway may be used by any local Windows profile that has an integration
# profile.  Task Scheduler binds a task to the account that happened to run an
# elevated install, which made the Gateway unavailable after switching users.
# Keep the configuration writable by local authenticated users instead.  It
# contains only public certificate material and hashes of pairing tokens.
foreach ($path in @($privateKey, $configPath)) {
    & icacls.exe $path /inheritance:r /grant:r `
        "*S-1-5-11:(M)" "*S-1-5-18:(F)" "*S-1-5-32-544:(F)" | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "Unable to secure $path" }
}

if (-not $SkipFirewall) {
    $ruleName = "Wake & Play Host Gateway (Private LAN)"
    Get-NetFirewallRule -DisplayName $ruleName -ErrorAction SilentlyContinue |
        Remove-NetFirewallRule -ErrorAction SilentlyContinue
    New-NetFirewallRule -DisplayName $ruleName -Direction Inbound -Action Allow `
        -Profile Private -Protocol TCP -LocalPort $Port -RemoteAddress LocalSubnet | Out-Null
}

$pairingCode = $null
$processId = $null
if (-not $SkipStart) {
    $pairingCode = [string](Get-Random -Minimum 100000 -Maximum 999999)
    $startScript = Join-Path $InstallDirectory "Start-WakePlayGateway.ps1"
    $logPath = Join-Path $InstallDirectory "gateway.log"
    $errorPath = Join-Path $InstallDirectory "gateway-error.log"
    $process = Start-Process -FilePath "powershell.exe" -WindowStyle Hidden -PassThru `
        -ArgumentList @("-NoProfile", "-ExecutionPolicy", "Bypass", "-File", $startScript,
            "-PairingCode", $pairingCode) `
        -RedirectStandardOutput $logPath -RedirectStandardError $errorPath

    Start-Sleep -Milliseconds 900
    if ($process.HasExited) {
        $details = if (Test-Path -LiteralPath $errorPath) { Get-Content -LiteralPath $errorPath -Raw } else { "" }
        throw "Gateway exited during startup. $details"
    }
    $processId = $process.Id
}

[pscustomobject]@{
    installed = $true
    directory = $InstallDirectory
    port = $Port
    process_id = $processId
    pairing_code = $pairingCode
    pairing_expires_minutes = $(if ($SkipStart) { 0 } else { 10 })
    scheduled_task = $false
    firewall_rule = (-not $SkipFirewall)
}
