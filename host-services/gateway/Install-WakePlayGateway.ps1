#requires -Version 5.1
#requires -RunAsAdministrator
[CmdletBinding()]
param(
    [ValidateRange(1, 65535)][int]$Port = 8785,
    [switch]$SkipFirewall,
    [switch]$SkipStart
)

$ErrorActionPreference = "Stop"
$sourceDirectory = $PSScriptRoot
$versionSource = Join-Path (Split-Path -Parent $sourceDirectory) "version.json"
$serviceName = "MoonWakerGateway"
$files = @(
    "wakeplay_gateway.py",
    "control_operation_policy.py",
    "child_terminal_operation.py",
    "provider_control_operation.py",
    "vibepollo_client.py",
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

$InstallDirectory = Join-Path ([Environment]::GetFolderPath(
    [Environment+SpecialFolder]::ProgramFiles)) "MoonWaker\gateway"

function Get-MachinePythonExecutable {
    $candidates = @()
    foreach ($programFiles in @(
        [Environment]::GetFolderPath([Environment+SpecialFolder]::ProgramFiles),
        [Environment]::GetFolderPath([Environment+SpecialFolder]::ProgramFilesX86))) {
        if ([string]::IsNullOrWhiteSpace($programFiles) -or
            -not (Test-Path -LiteralPath $programFiles -PathType Container)) { continue }
        $candidates += Get-ChildItem -LiteralPath $programFiles -Directory -Filter "Python*" `
            -ErrorAction SilentlyContinue | ForEach-Object { Join-Path $_.FullName "python.exe" }
    }
    foreach ($candidate in @($candidates | Where-Object {
        -not [string]::IsNullOrWhiteSpace($_)
    } | Sort-Object -Unique)) {
        if (-not (Test-Path -LiteralPath $candidate -PathType Leaf)) { continue }
        $versionText = & $candidate -c `
            "import sys; print('.'.join(map(str, sys.version_info[:3])))" 2>$null
        if ($LASTEXITCODE -ne 0) { continue }
        [version]$version = $null
        if ([version]::TryParse([string]$versionText, [ref]$version) -and
            $version -ge [version]"3.10" -and $version -lt [version]"4.0") {
            return [IO.Path]::GetFullPath($candidate)
        }
    }
    return $null
}

$python = Get-MachinePythonExecutable
if ($null -eq $python) {
    throw "A system-wide Python 3.10 or newer installation was not found."
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
try {
    $gatewayConfig = Get-Content -LiteralPath $configPath -Raw | ConvertFrom-Json
    $listenPort = [int]$gatewayConfig.listen_port
    if ($listenPort -lt 1 -or $listenPort -gt 65535) { throw "out of range" }
} catch {
    throw "Gateway configuration is invalid or has no valid listen_port: $configPath"
}

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
$certificateReady = (Test-Path -LiteralPath $certificate -PathType Leaf) -and
    (Get-Item -LiteralPath $certificate).Length -gt 0
$privateKeyReady = (Test-Path -LiteralPath $privateKey -PathType Leaf) -and
    (Get-Item -LiteralPath $privateKey).Length -gt 0
if (-not $certificateReady -or -not $privateKeyReady) {
    $openssl = $null
    foreach ($command in @(Get-Command openssl.exe -CommandType Application `
            -ErrorAction SilentlyContinue)) {
        $pathProperty = $command.PSObject.Properties["Path"]
        $candidate = if ($null -ne $pathProperty) { [string]$pathProperty.Value } else { "" }
        if (-not [string]::IsNullOrWhiteSpace($candidate) -and
            (Test-Path -LiteralPath $candidate -PathType Leaf)) {
            $openssl = $candidate
            break
        }
    }
    $candidates = @()
    foreach ($programFiles in @(
        [Environment]::GetFolderPath([Environment+SpecialFolder]::ProgramFiles),
        [Environment]::GetFolderPath([Environment+SpecialFolder]::ProgramFilesX86))) {
        if ([string]::IsNullOrWhiteSpace($programFiles)) { continue }
        $candidates += Join-Path $programFiles "OpenSSL-Win64\bin\openssl.exe"
        $candidates += Join-Path $programFiles "OpenSSL-Win32\bin\openssl.exe"
        $candidates += Join-Path $programFiles "OpenSSL\bin\openssl.exe"
    }
    if ([string]::IsNullOrWhiteSpace($openssl)) {
        $openssl = $candidates | Where-Object {
            -not [string]::IsNullOrWhiteSpace($_) -and
            (Test-Path -LiteralPath $_ -PathType Leaf)
        } | Select-Object -First 1
    }
    if ([string]::IsNullOrWhiteSpace($openssl)) {
        throw "OpenSSL executable was not found after prerequisite installation."
    }
    $openssl = [IO.Path]::GetFullPath($openssl)
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
    foreach ($generated in @($certificate, $privateKey)) {
        if (-not (Test-Path -LiteralPath $generated -PathType Leaf) -or
            (Get-Item -LiteralPath $generated).Length -eq 0) {
            throw "OpenSSL did not create the required Gateway certificate files."
        }
    }
}

$serviceHost = Join-Path $InstallDirectory "MoonWakerGatewayService.exe"
if (-not (Test-Path -LiteralPath $serviceHost -PathType Leaf)) {
    throw "The installed Gateway service executable is missing."
}
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
if ($null -eq $serviceConfig) {
    throw "Windows did not expose the MoonWaker Gateway service after installation."
}
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
# Host Control's "close session" request: any local account may ask the Gateway to end the
# running session. Both sides read and overwrite the file in place, so neither gets DELETE:
# an account that could remove the file would disable the channel until a reinstall. The
# Gateway writes the outcome beside it, where the directory ACL lets Host Control read it.
$hostCloseRequest = Join-Path $InstallDirectory "host-close-request.json"
[IO.File]::WriteAllText($hostCloseRequest, "{}", [Text.UTF8Encoding]::new($false))
& $icacls $hostCloseRequest /inheritance:r /grant:r `
    "*${serviceSid}:(R,W)" "*S-1-5-18:(F)" "*S-1-5-32-544:(F)" `
    "*S-1-5-32-545:(R,W)" | Out-Null
if ($LASTEXITCODE -ne 0) { throw "Unable to configure the host close request ACL." }
& $icacls $privateKey /inheritance:r /grant:r `
    "*${serviceSid}:(R)" "*S-1-5-18:(F)" "*S-1-5-32-544:(F)" | Out-Null
if ($LASTEXITCODE -ne 0) { throw "Unable to secure the Gateway private key." }
# The computer's one Vibepollo token. Only the elevated Host Configurator writes it, in place,
# so this ACL stays; the Gateway service only reads it. A reinstall keeps the token.
$vibepolloSettings = Join-Path $InstallDirectory "vibepollo-api.json"
if (-not (Test-Path -LiteralPath $vibepolloSettings -PathType Leaf)) {
    [IO.File]::WriteAllText($vibepolloSettings, "{}", [Text.UTF8Encoding]::new($false))
}
& $icacls $vibepolloSettings /inheritance:r /grant:r `
    "*${serviceSid}:(R)" "*S-1-5-18:(F)" "*S-1-5-32-544:(F)" | Out-Null
if ($LASTEXITCODE -ne 0) { throw "Unable to secure the Vibepollo token file." }
# The Gateway pins Vibepollo's certificate and proves paired Moonlight identities from its
# state file. The Host Configurator grants the service read access to exactly the files
# Vibepollo's sunshine.conf names, plus traverse (never listing: the credentials directory
# holds Vibepollo's private key) - the same grant every Vibepollo token save repeats.
$configurator = Join-Path (Split-Path -Parent $sourceDirectory) "control\MoonWakerHostConfigurator.exe"
$consoleEncoding = [Console]::OutputEncoding
[Console]::OutputEncoding = [Text.UTF8Encoding]::new($false)
try {
    # Capturing the output makes PowerShell wait for this GUI-subsystem executable.
    $vibepolloAccess = @(& $configurator --mode vibepollo-access)
} finally { [Console]::OutputEncoding = $consoleEncoding }
switch ($LASTEXITCODE) {
    0 {
        foreach ($path in $vibepolloAccess) {
            Write-Warning "Vibepollo file is missing, so the Gateway cannot read it: $path. Once Vibepollo has created it, save the Vibepollo token again in Host Control."
        }
    }
    2 {
        Write-Warning "The Gateway cannot reach Vibepollo yet: $($vibepolloAccess -join ' ') Install or repair Vibepollo, then save the Vibepollo token in Host Control."
    }
    default { throw "Unable to grant the Gateway read access to Vibepollo's files: $($vibepolloAccess -join ' ')" }
}

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
$startupErrorPath = Join-Path $InstallDirectory "gateway-startup-error.txt"
Remove-Item -LiteralPath (Join-Path $InstallDirectory "gateway-manually-stopped"),
    (Join-Path $InstallDirectory "gateway-supervisor-stop"), $startupErrorPath `
    -Force -ErrorAction SilentlyContinue
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
    $deadline = [DateTime]::UtcNow.AddSeconds(30)
    while ([DateTime]::UtcNow -lt $deadline) {
        $probe = [Net.Sockets.TcpClient]::new()
        try {
            $pending = $probe.ConnectAsync("127.0.0.1", $listenPort)
            if ($pending.Wait(500) -and $probe.Connected) { $ready = $true; break }
        } catch {} finally { $probe.Dispose() }
        Start-Sleep -Milliseconds 250
    }
    if (-not $ready) {
        $startupError = if (Test-Path -LiteralPath $startupErrorPath -PathType Leaf) {
            (Get-Content -LiteralPath $startupErrorPath -Raw).Trim()
        } else { "No Python startup error was recorded." }
        if ([string]::IsNullOrWhiteSpace($startupError)) {
            $startupError = "The Gateway process produced an empty startup diagnostic."
        }
        throw "MoonWaker Gateway did not open port $listenPort. $startupError"
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
