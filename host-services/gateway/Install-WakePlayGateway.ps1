#requires -Version 5.1
#requires -RunAsAdministrator
[CmdletBinding()]
param(
    [string]$InstallDirectory = "C:\Tools\WakePlayGateway",
    [int]$Port = 8785,
    [switch]$SkipFirewall,
    [switch]$SkipStart
)

$ErrorActionPreference = "Stop"
$sourceDirectory = $PSScriptRoot
$versionSource = Join-Path (Split-Path -Parent $sourceDirectory) "version.json"
$serviceName = "MoonWakerGateway"
$files = @(
    "wakeplay_gateway.py",
    "MoonWakerGatewayService.exe",
    "MoonWakerMicrophoneWorker.exe",
    "MoonWakerDiscordAudioWorker.exe",
    "Start-WakePlayGateway.ps1",
    "MoonWakerGatewaySupervisor.ps1",
    "Start-MoonWakerGateway.ps1",
    "Stop-MoonWakerGateway.ps1",
    "Stop-MoonWakerGatewayWorkers.ps1",
    "Uninstall-MoonWakerGatewayService.ps1",
    "gateway.example.json",
    "README.md"
)

$python = [IO.Path]::GetFullPath((Get-Command python.exe -ErrorAction Stop).Source)
if (-not (Test-Path -LiteralPath $python -PathType Leaf)) {
    throw "Python executable was not found."
}

$existingService = Get-Service -Name $serviceName -ErrorAction SilentlyContinue
if ($null -ne $existingService -and
    $existingService.Status -ne [ServiceProcess.ServiceControllerStatus]::Stopped) {
    Stop-Service -Name $serviceName -Force
}

New-Item -ItemType Directory -Path $InstallDirectory -Force | Out-Null
foreach ($name in $files) {
    $source = Join-Path $sourceDirectory $name
    if (-not (Test-Path -LiteralPath $source -PathType Leaf)) {
        throw "The Gateway package is incomplete. Missing: $name"
    }
    Copy-Item -LiteralPath $source -Destination (Join-Path $InstallDirectory $name) -Force
}

$configPath = Join-Path $InstallDirectory "gateway.json"
if (-not (Test-Path -LiteralPath $configPath)) {
    $config = Get-Content -LiteralPath (Join-Path $InstallDirectory "gateway.example.json") -Raw |
        ConvertFrom-Json
    $config.listen_port = $Port
    # Profiles belong to Host Control. A new machine starts with none.
    $config.profiles = [pscustomobject]@{}
    $config | ConvertTo-Json -Depth 20 | Set-Content -LiteralPath $configPath -Encoding UTF8
}
$listenPort = [int](Get-Content -LiteralPath $configPath -Raw | ConvertFrom-Json).listen_port

$lockPath = "$configPath.lock"
if (-not (Test-Path -LiteralPath $lockPath -PathType Leaf)) {
    [IO.File]::WriteAllBytes($lockPath, [byte[]]@(0))
}

# A pre-service Gateway from a legacy installation can otherwise keep the LAN
# port while Host Control writes pairing state to this installation.
& (Join-Path $InstallDirectory "Stop-MoonWakerGateway.ps1") `
    -GatewayDirectory $InstallDirectory

$certificate = Join-Path $InstallDirectory "gateway-cert.pem"
$privateKey = Join-Path $InstallDirectory "gateway-key.pem"
if (-not (Test-Path -LiteralPath $certificate) -or -not (Test-Path -LiteralPath $privateKey)) {
    $openssl = [IO.Path]::GetFullPath((Get-Command openssl.exe -ErrorAction Stop).Source)
    if (-not (Test-Path -LiteralPath $openssl -PathType Leaf)) {
        throw "OpenSSL executable was not found."
    }
    $previousErrorActionPreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = "Continue"
        & $openssl req -x509 -newkey rsa:3072 -sha256 -nodes `
            -keyout $privateKey -out $certificate -days 825 `
            -subj "/CN=Wake and Play Host Gateway" `
            -addext "subjectAltName=DNS:localhost,IP:127.0.0.1" 2>$null
        $opensslExitCode = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $previousErrorActionPreference
    }
    if ($opensslExitCode -ne 0) { throw "OpenSSL certificate generation failed." }
}

$serviceHost = Join-Path $InstallDirectory "MoonWakerGatewayService.exe"
$serviceImage = '"{0}" --gateway-dir "{1}" --python "{2}"' -f `
    $serviceHost, $InstallDirectory, $python
$sc = Join-Path ([Environment]::SystemDirectory) "sc.exe"
if ($null -eq $existingService) {
    New-Service -Name $serviceName -BinaryPathName $serviceImage `
        -DisplayName "MoonWaker Gateway" `
        -Description "Runs the machine Gateway before sign-in; interactive Bridges remain per-user." `
        -StartupType Automatic | Out-Null
}
$serviceConfig = Get-CimInstance Win32_Service -Filter "Name='$serviceName'"
$change = Invoke-CimMethod -InputObject $serviceConfig -MethodName Change -Arguments @{
    DisplayName = "MoonWaker Gateway"
    PathName = $serviceImage
    StartMode = "Automatic"
    StartName = "NT SERVICE\$serviceName"
}
if ($change.ReturnValue -ne 0) {
    throw "Unable to configure the Gateway service (Win32_Service.Change $($change.ReturnValue))."
}
& $sc description $serviceName `
    "Runs the machine Gateway before sign-in; interactive Bridges remain per-user." | Out-Null
if ($LASTEXITCODE -ne 0) { throw "Unable to describe the Gateway service." }
& $sc sidtype $serviceName restricted | Out-Null
if ($LASTEXITCODE -ne 0) { throw "Unable to restrict the Gateway service identity." }

# The virtual service account owns runtime writes. Host Control can read status,
# while the elevated Configurator retains full access to profile configuration.
$serviceSid = ([Security.Principal.NTAccount]::new(
    "NT SERVICE", $serviceName)).Translate([Security.Principal.SecurityIdentifier]).Value
$icacls = Join-Path ([Environment]::SystemDirectory) "icacls.exe"
$pythonDirectory = Split-Path -Parent $python
& $icacls $pythonDirectory /grant:r "*${serviceSid}:(OI)(CI)(RX)" /T /C | Out-Null
if ($LASTEXITCODE -ne 0) { throw "Unable to grant the Gateway service read access to Python." }
& $icacls $InstallDirectory /remove:g "*S-1-5-11" /T /C | Out-Null
if ($LASTEXITCODE -ne 0) { throw "Unable to remove the legacy Gateway write ACL." }
& $icacls $InstallDirectory /inheritance:r /grant:r `
    "*${serviceSid}:(OI)(CI)(M)" "*S-1-5-18:(OI)(CI)(F)" `
    "*S-1-5-32-544:(OI)(CI)(F)" "*S-1-5-32-545:(OI)(CI)(RX)" | Out-Null
if ($LASTEXITCODE -ne 0) { throw "Unable to configure the Gateway directory ACL." }
$pairingControl = Join-Path $InstallDirectory "pairing-code.json"
if (-not (Test-Path -LiteralPath $pairingControl -PathType Leaf)) {
    [IO.File]::WriteAllText($pairingControl, "{}", [Text.UTF8Encoding]::new($false))
}
& $icacls $pairingControl /inheritance:r /grant:r `
    "*${serviceSid}:(M)" "*S-1-5-18:(F)" "*S-1-5-32-544:(F)" `
    "*S-1-5-32-545:(M)" | Out-Null
if ($LASTEXITCODE -ne 0) { throw "Unable to configure the pairing control ACL." }
& $icacls $privateKey /inheritance:r /grant:r `
    "*${serviceSid}:(R)" "*S-1-5-18:(F)" "*S-1-5-32-544:(F)" | Out-Null
if ($LASTEXITCODE -ne 0) { throw "Unable to secure the Gateway private key." }

# Host Control's existing Start/Stop/Restart buttons control only this service.
# Interactive-user rights do not grant writes to Gateway configuration or keys.
& $sc sdset $serviceName `
    "D:(A;;CCDCLCSWRPWPDTLOCRSDRCWDWO;;;SY)(A;;CCDCLCSWRPWPDTLOCRSDRCWDWO;;;BA)(A;;CCLCSWRPWPLOCRRC;;;IU)" | Out-Null
if ($LASTEXITCODE -ne 0) { throw "Unable to configure Gateway service controls." }

if (Test-Path -LiteralPath $versionSource) {
    Copy-Item -LiteralPath $versionSource -Destination (Join-Path $InstallDirectory "version.json") -Force
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
Remove-Item -LiteralPath (Join-Path $InstallDirectory "gateway-manually-stopped"),
    (Join-Path $InstallDirectory "gateway-supervisor-stop") -Force -ErrorAction SilentlyContinue
if (-not $SkipStart) {
    $pairingCode = [string](Get-Random -Minimum 100000 -Maximum 999999)
    $sha = [Security.Cryptography.SHA256]::Create()
    try {
        $digest = ($sha.ComputeHash([Text.Encoding]::UTF8.GetBytes($pairingCode)) |
            ForEach-Object { $_.ToString("x2") }) -join ""
    } finally { $sha.Dispose() }
    [ordered]@{
        code_sha256 = $digest
        expires_at = [DateTimeOffset]::UtcNow.AddMinutes(10).ToUnixTimeSeconds()
    } | ConvertTo-Json | Set-Content -LiteralPath $pairingControl -Encoding UTF8
    Start-Service -Name $serviceName
    (Get-Service -Name $serviceName).WaitForStatus(
        [ServiceProcess.ServiceControllerStatus]::Running, [TimeSpan]::FromSeconds(15))
    $processId = [int](Get-CimInstance Win32_Service -Filter "Name='$serviceName'").ProcessId
    $ready = $false
    $deadline = [DateTime]::UtcNow.AddSeconds(15)
    while ([DateTime]::UtcNow -lt $deadline) {
        $probe = [Net.Sockets.TcpClient]::new()
        try {
            $pending = $probe.ConnectAsync("127.0.0.1", $listenPort)
            if ($pending.Wait(500) -and $probe.Connected) { $ready = $true; break }
        } catch {} finally { $probe.Dispose() }
        Start-Sleep -Milliseconds 250
    }
    if (-not $ready) {
        throw "MoonWaker Gateway service started, but its Gateway process did not open port $listenPort."
    }
}

[pscustomobject]@{
    installed = $true
    directory = $InstallDirectory
    port = $Port
    process_id = $processId
    pairing_code = $pairingCode
    pairing_expires_minutes = $(if ($SkipStart) { 0 } else { 10 })
    service = $serviceName
    firewall_rule = (-not $SkipFirewall)
}
