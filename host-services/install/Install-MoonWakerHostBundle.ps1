#requires -Version 5.1
[CmdletBinding()]
param(
    [ValidatePattern('^[A-Za-z0-9._-]{1,64}$')]
    [string]$ProfileId = "default",
    [string]$ProfileName = "",
    [int]$GatewayPort = 8785,
    [int]$DiscordPort = 0,
    [int]$VibepolloPort = 0,
    [int]$PlaynitePort = 0,
    [string]$InstallDirectory = "C:\Tools\WakePlayHost",
    [string]$PlayniteDirectory = "",
    [switch]$SkipDiscord,
    [switch]$SkipVibepollo,
    [switch]$SkipPlaynite,
    [switch]$ProfileOnly,
    [switch]$SkipFirewall
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function Test-IsAdministrator {
    $identity = [Security.Principal.WindowsIdentity]::GetCurrent()
    $principal = [Security.Principal.WindowsPrincipal]::new($identity)
    return $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
}

function Assert-InteractiveAdministrator {
    $identity = [Security.Principal.WindowsIdentity]::GetCurrent()
    try {
        $interactiveUser = [string](Get-CimInstance Win32_ComputerSystem).UserName
        if (-not [string]::IsNullOrWhiteSpace($interactiveUser) -and
            -not $identity.Name.Equals($interactiveUser, [StringComparison]::OrdinalIgnoreCase)) {
            throw "The installer is elevated as '$($identity.Name)', but the active game profile is '$interactiveUser'. Sign in and elevate with the same Windows account."
        }
    } catch [Microsoft.Management.Infrastructure.CimException] {
        Write-Warning "The active Windows account could not be verified: $($_.Exception.Message)"
    }
}

function Get-PortFromEndpoint {
    param([object]$Entry, [string]$Property)
    if ($null -eq $Entry -or $null -eq $Entry.PSObject.Properties[$Property]) { return 0 }
    $uri = $null
    if ([uri]::TryCreate([string]$Entry.$Property, [UriKind]::Absolute, [ref]$uri)) {
        return $uri.Port
    }
    return 0
}

function Resolve-ProfilePorts {
    param([string]$ConfigPath)
    $defaults = @(8765, 8775, 8780)
    if ($ProfileId -eq "default") {
        return @(
            $(if ($DiscordPort) { $DiscordPort } else { $defaults[0] }),
            $(if ($VibepolloPort) { $VibepolloPort } else { $defaults[1] }),
            $(if ($PlaynitePort) { $PlaynitePort } else { $defaults[2] }))
    }
    $gateway = $null
    if (Test-Path -LiteralPath $ConfigPath) {
        $gateway = Get-Content -LiteralPath $ConfigPath -Raw | ConvertFrom-Json
    }
    $existing = if ($gateway -and $gateway.profiles) {
        $gateway.profiles.PSObject.Properties[$ProfileId]
    } else { $null }
    if ($existing) {
        $entry = $existing.Value
        return @(
            $(if ($DiscordPort) { $DiscordPort } else { Get-PortFromEndpoint $entry "discord_bridge" }),
            $(if ($VibepolloPort) { $VibepolloPort } else { Get-PortFromEndpoint $entry "vibepollo_bridge" }),
            $(if ($PlaynitePort) { $PlaynitePort } else { Get-PortFromEndpoint $entry "playnite_bridge" }))
    }
    $used = [Collections.Generic.HashSet[int]]::new()
    if ($gateway -and $gateway.profiles) {
        foreach ($property in $gateway.profiles.PSObject.Properties) {
            foreach ($name in @("discord_bridge", "vibepollo_bridge", "playnite_bridge")) {
                $port = Get-PortFromEndpoint $property.Value $name
                if ($port) { [void]$used.Add($port) }
            }
        }
    }
    for ($slot = 1; $slot -le 50; $slot++) {
        # Parenthesize each scalar calculation. Without this, PowerShell binds
        # the comma-separated array before multiplication and tries to invoke
        # op_Multiply on System.Object[].
        $candidate = @(
            ($defaults[0] + (100 * $slot)),
            ($defaults[1] + (100 * $slot)),
            ($defaults[2] + (100 * $slot))
        )
        if (-not $used.Contains($candidate[0]) -and -not $used.Contains($candidate[1]) -and
            -not $used.Contains($candidate[2])) {
            return @(
                $(if ($DiscordPort) { $DiscordPort } else { $candidate[0] }),
                $(if ($VibepolloPort) { $VibepolloPort } else { $candidate[1] }),
                $(if ($PlaynitePort) { $PlaynitePort } else { $candidate[2] }))
        }
    }
    throw "No free Bridge port set was found for the new profile."
}

function Resolve-PlayniteInstall {
    if (-not [string]::IsNullOrWhiteSpace($PlayniteDirectory)) {
        return $PlayniteDirectory.Trim()
    }
    $process = Get-Process -Name "Playnite.DesktopApp", "Playnite.FullscreenApp" `
        -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($process -and $process.Path) { return Split-Path -Parent $process.Path }
    $candidates = @(
        (Join-Path $env:LOCALAPPDATA "Playnite"),
        (Join-Path $env:ProgramFiles "Playnite"),
        $(if (${env:ProgramFiles(x86)}) { Join-Path ${env:ProgramFiles(x86)} "Playnite" } else { "" })
    )
    foreach ($candidate in $candidates) {
        if ($candidate -and (Test-Path -LiteralPath (Join-Path $candidate "Playnite.FullscreenApp.exe"))) {
            return $candidate
        }
    }
    throw "Playnite was not detected. Start Playnite or choose its installation directory."
}

function Protect-MachineText {
    param([Parameter(Mandatory)][string]$Value)
    Add-Type -AssemblyName System.Security
    $plain = [Text.Encoding]::UTF8.GetBytes($Value)
    $protected = [Security.Cryptography.ProtectedData]::Protect(
        $plain, $null, [Security.Cryptography.DataProtectionScope]::LocalMachine)
    return [Convert]::ToBase64String($protected)
}

function Unprotect-MachineText {
    param([Parameter(Mandatory)][string]$Value)
    Add-Type -AssemblyName System.Security
    $protected = [Convert]::FromBase64String($Value.Trim())
    $plain = [Security.Cryptography.ProtectedData]::Unprotect(
        $protected, $null, [Security.Cryptography.DataProtectionScope]::LocalMachine)
    return [Text.Encoding]::UTF8.GetString($plain)
}

function Initialize-MachineDiscordApplication {
    param([bool]$ProfileAlreadyConfigured)
    $machineRoot = Join-Path $script:HostRoot "machine-data"
    $applicationPath = Join-Path $machineRoot "discord-app.json"
    $secretPath = Join-Path $machineRoot "discord-app-secret.dpapi"
    $providedId = [string]$env:MOONWAKER_DISCORD_CLIENT_ID
    $providedSecret = [string]$env:MOONWAKER_DISCORD_CLIENT_SECRET
    if (-not [string]::IsNullOrWhiteSpace($providedId) -or
        -not [string]::IsNullOrWhiteSpace($providedSecret)) {
        if ($providedId -notmatch '^[0-9]{17,20}$' -or [string]::IsNullOrWhiteSpace($providedSecret)) {
            throw "Both Discord Client ID and Client Secret are required when updating the machine application."
        }
        New-Item -ItemType Directory -Path $machineRoot -Force | Out-Null
        [ordered]@{ client_id = $providedId } | ConvertTo-Json |
            Set-Content -LiteralPath $applicationPath -Encoding UTF8
        Protect-MachineText $providedSecret |
            Set-Content -LiteralPath $secretPath -Encoding ASCII
        $currentSid = [Security.Principal.WindowsIdentity]::GetCurrent().User.Value
        foreach ($path in @($applicationPath, $secretPath)) {
            & icacls.exe $path /inheritance:r /grant:r `
                "*$currentSid`:(F)" "*S-1-5-18:(F)" "*S-1-5-32-544:(F)" | Out-Null
            if ($LASTEXITCODE -ne 0) { throw "Unable to secure machine Discord application data." }
        }
        return
    }
    if ((Test-Path -LiteralPath $applicationPath) -and (Test-Path -LiteralPath $secretPath)) {
        $application = Get-Content -LiteralPath $applicationPath -Raw | ConvertFrom-Json
        $env:MOONWAKER_DISCORD_CLIENT_ID = [string]$application.client_id
        $env:MOONWAKER_DISCORD_CLIENT_SECRET = Unprotect-MachineText `
            (Get-Content -LiteralPath $secretPath -Raw)
        return
    }
    if (-not $ProfileAlreadyConfigured) {
        throw "Discord application data is required once for this computer."
    }
}

function New-MoonWakerVibepolloToken {
    $baseUrl = [string]$env:MOONWAKER_VIBEPOLLO_URL
    $username = [string]$env:MOONWAKER_VIBEPOLLO_ADMIN_USERNAME
    $password = [string]$env:MOONWAKER_VIBEPOLLO_ADMIN_PASSWORD
    if ([string]::IsNullOrWhiteSpace($baseUrl)) { $baseUrl = "https://127.0.0.1:47990" }
    $uri = $null
    if (-not [uri]::TryCreate($baseUrl, [UriKind]::Absolute, [ref]$uri) -or
        $uri.Scheme -ne "https" -or $uri.Host -notin @("127.0.0.1", "localhost")) {
        throw "Vibepollo automatic token creation is restricted to its local HTTPS API."
    }
    if ([string]::IsNullOrWhiteSpace($username) -or [string]::IsNullOrWhiteSpace($password)) {
        throw "Vibepollo administrator username and password are required to create a token."
    }
    $transport = Join-Path $packageRoot "bridges\vibepollo\VibepolloTransport.py"
    $scopePath = Join-Path $packageRoot "bridges\vibepollo\moonwaker-token-scopes.example.json"
    if (-not (Test-Path -LiteralPath $transport) -or -not (Test-Path -LiteralPath $scopePath)) {
        throw "The Vibepollo token helper payload is incomplete."
    }
    $scopeDocument = Get-Content -LiteralPath $scopePath -Raw | ConvertFrom-Json
    $appsScope = @($scopeDocument.scopes | Where-Object { $_.path -eq "/api/apps" }) |
        Select-Object -First 1
    if ($null -eq $appsScope -or "GET" -notin @($appsScope.methods) -or
        "POST" -notin @($appsScope.methods)) {
        throw "The Vibepollo token scope must allow GET and POST /api/apps."
    }
    $request = [ordered]@{
        base_url = $baseUrl.TrimEnd('/')
        path = "/api/token"
        method = "POST"
        username = $username
        password = $password
        body = @{ scopes = @($scopeDocument.scopes) }
    } | ConvertTo-Json -Depth 20 -Compress
    try {
        $raw = $request | & python.exe $transport
        $transportResult = $raw | ConvertFrom-Json
        if (-not $transportResult.ok) {
            throw "Vibepollo rejected token creation (HTTP $($transportResult.status)). Check administrator credentials."
        }
        $content = [string]$transportResult.content
        try { $response = $content | ConvertFrom-Json } catch { $response = $content.Trim() }
        $token = if ($response -is [string]) { [string]$response } `
            elseif ($response.PSObject.Properties["token"]) { [string]$response.token } `
            elseif ($response.PSObject.Properties["access_token"]) { [string]$response.access_token } `
            else { "" }
        if ([string]::IsNullOrWhiteSpace($token)) {
            throw "Vibepollo created no readable token in its response."
        }
        $env:MOONWAKER_VIBEPOLLO_TOKEN = $token
        Write-Host "Created a least-privilege Vibepollo token for the MoonWaker profile."
    } finally {
        $password = $null
        $request = $null
        $env:MOONWAKER_VIBEPOLLO_ADMIN_PASSWORD = $null
    }
}

$script:IsAdministrator = Test-IsAdministrator
if ($script:IsAdministrator -and -not $ProfileOnly) { Assert-InteractiveAdministrator }
if (-not (Get-Command "python.exe" -ErrorAction SilentlyContinue)) {
    throw "python.exe is required but was not found in PATH."
}

$packageRoot = Split-Path -Parent $PSScriptRoot
$hostInstaller = Join-Path $PSScriptRoot "Install-WakePlayHost.ps1"
$profileInstaller = Join-Path $PSScriptRoot "Install-WakePlayProfile.ps1"
if (-not (Test-Path -LiteralPath $hostInstaller) -or -not (Test-Path -LiteralPath $profileInstaller)) {
    throw "The embedded MoonWaker host package is incomplete."
}

if ([string]::IsNullOrWhiteSpace($InstallDirectory) -or
    -not [IO.Path]::IsPathRooted($InstallDirectory)) {
    throw "Choose an absolute MoonWaker installation directory."
}
$script:HostRoot = [IO.Path]::GetFullPath($InstallDirectory.Trim()).TrimEnd('\')
if ($script:HostRoot -notmatch '^[A-Za-z]:\\') {
    throw "MoonWaker must be installed on a local Windows drive."
}
$driveRoot = [IO.Path]::GetPathRoot($script:HostRoot)
if (-not (Test-Path -LiteralPath $driveRoot)) {
    throw "The selected installation drive is unavailable: $driveRoot"
}
New-Item -ItemType Directory -Path $script:HostRoot -Force | Out-Null

$hostRoot = $script:HostRoot
$gatewayDirectory = Join-Path $hostRoot "gateway"
New-Item -ItemType Directory -Path $gatewayDirectory -Force | Out-Null
if (-not (Test-Path -LiteralPath (Join-Path $gatewayDirectory "gateway.json"))) {
    foreach ($legacyGateway in @("C:\Tools\WakePlayHost\gateway", "C:\Tools\WakePlayGateway")) {
        if ($legacyGateway.Equals($gatewayDirectory, [StringComparison]::OrdinalIgnoreCase) -or
            -not (Test-Path -LiteralPath (Join-Path $legacyGateway "gateway.json"))) { continue }
        Write-Host "Migrating existing Gateway settings from $legacyGateway..."
        foreach ($name in @("gateway.json", "gateway-cert.pem", "gateway-key.pem",
            "runtime-status.json", "pairing-code.json")) {
            $source = Join-Path $legacyGateway $name
            if (Test-Path -LiteralPath $source) {
                Copy-Item -LiteralPath $source -Destination (Join-Path $gatewayDirectory $name) -Force
            }
        }
        break
    }
}
$gatewayConfig = Join-Path $gatewayDirectory "gateway.json"
$gatewayCertificate = Join-Path $gatewayDirectory "gateway-cert.pem"
$gatewayPrivateKey = Join-Path $gatewayDirectory "gateway-key.pem"
if (($ProfileOnly -or -not $script:IsAdministrator) -and -not (Test-Path -LiteralPath $gatewayConfig)) {
    throw "The machine Gateway must be installed once by an administrator before adding a standard-user profile."
}
if ((-not (Test-Path -LiteralPath $gatewayCertificate) -or
    -not (Test-Path -LiteralPath $gatewayPrivateKey)) -and
    -not (Get-Command "openssl.exe" -ErrorAction SilentlyContinue)) {
    throw "openssl.exe is required only to create the first Gateway certificate, but was not found in PATH."
}
$ports = Resolve-ProfilePorts $gatewayConfig
$profilesRoot = Join-Path $hostRoot "profiles"
$profileRoot = Join-Path $profilesRoot $ProfileId
$legacyProfileRoot = Join-Path $env:LOCALAPPDATA "WakePlayHost\profiles\$ProfileId"
if (-not (Test-Path -LiteralPath $profileRoot) -and
    (Test-Path -LiteralPath $legacyProfileRoot)) {
    Write-Host "Migrating profile '$ProfileId' from $legacyProfileRoot..."
    $legacyStop = Join-Path $legacyProfileRoot "Stop-MoonWakerProfileBridge.ps1"
    if (Test-Path -LiteralPath $legacyStop) {
        Write-Host "Stopping the legacy profile supervisor before migration..."
        & $legacyStop -ProfileRoot $legacyProfileRoot
    }
    New-Item -ItemType Directory -Path $profilesRoot -Force | Out-Null
    Copy-Item -LiteralPath $legacyProfileRoot -Destination $profilesRoot -Recurse -Force
}
$legacyMachineRoot = Join-Path $env:ProgramData "MoonWakerHost"
$machineRoot = Join-Path $hostRoot "machine-data"
if (-not (Test-Path -LiteralPath $machineRoot) -and (Test-Path -LiteralPath $legacyMachineRoot)) {
    Copy-Item -LiteralPath $legacyMachineRoot -Destination $machineRoot -Recurse -Force
}
if (-not $SkipDiscord) {
    $profileDiscordConfigured = Test-Path -LiteralPath `
        (Join-Path $profileRoot "discord\discord_bridge_config.json")
    if ($ProfileOnly) {
        if (-not $profileDiscordConfigured -and (
            [string]::IsNullOrWhiteSpace($env:MOONWAKER_DISCORD_CLIENT_ID) -or
            [string]::IsNullOrWhiteSpace($env:MOONWAKER_DISCORD_CLIENT_SECRET))) {
            throw "Discord credentials are required when adding a new Windows profile."
        }
    } else {
        try {
            Initialize-MachineDiscordApplication $profileDiscordConfigured
        } catch {
            if (-not $profileDiscordConfigured) { throw }
            Write-Host "Using this profile's existing Discord application credentials."
        }
    }
}
if (-not $SkipVibepollo -and $env:MOONWAKER_VIBEPOLLO_CREATE_TOKEN -eq "1") {
    New-MoonWakerVibepolloToken
}
$resolvedPlaynite = if ($SkipPlaynite) { "" } else { Resolve-PlayniteInstall }
if (-not $SkipPlaynite) {
    $connector = Join-Path $resolvedPlaynite "Extensions\SunshinePlaynite\SunshinePlaynite.psm1"
    if (-not (Test-Path -LiteralPath $connector)) {
        throw "Sunshine Playnite Connector was not found in $resolvedPlaynite."
    }
}

if ([string]::IsNullOrWhiteSpace($ProfileName)) { $ProfileName = $env:USERNAME }
try {
    if ($script:IsAdministrator -and -not $ProfileOnly) {
        Write-Host "Installing machine Gateway and Bridge package..."
        & $hostInstaller -InstallDirectory $hostRoot -GatewayDirectory $gatewayDirectory `
            -GatewayPort $GatewayPort -SkipFirewall:$SkipFirewall -SkipStart
    } else {
        Write-Host "Existing machine Gateway detected; updating only this Windows profile."
    }

    Write-Host "Installing integration profile '$ProfileId'..."
    & $profileInstaller -ProfileId $ProfileId -ProfileName $ProfileName `
        -InstallRoot $profilesRoot `
        -DiscordPort $ports[0] -VibepolloPort $ports[1] -PlaynitePort $ports[2] `
        -GatewayConfigPath $gatewayConfig -SkipDiscord:$SkipDiscord `
        -GatewayDirectory $gatewayDirectory -HostControlExecutable (Join-Path $hostRoot "control\MoonWakerHostControl.exe") `
        -SkipVibepollo:$SkipVibepollo -SkipPlaynite:$SkipPlaynite `
        -NonInteractiveConfiguration

    if (-not $SkipPlaynite) {
        $installedPatch = Join-Path $profileRoot "playnite\Install-WakePlayConnectorPatch.ps1"
        $installedConfig = Join-Path $profileRoot "playnite\config.json"
        $playniteConfig = Get-Content -LiteralPath $installedConfig -Raw | ConvertFrom-Json
        foreach ($setting in @{
            playnite_desktop_executable = (Join-Path $resolvedPlaynite "Playnite.DesktopApp.exe")
            playnite_fullscreen_executable = (Join-Path $resolvedPlaynite "Playnite.FullscreenApp.exe")
        }.GetEnumerator()) {
            if ($null -eq $playniteConfig.PSObject.Properties[$setting.Key]) {
                $playniteConfig | Add-Member -NotePropertyName $setting.Key -NotePropertyValue $setting.Value
            } else {
                $playniteConfig.($setting.Key) = $setting.Value
            }
        }
        $playniteConfig | ConvertTo-Json -Depth 20 |
            Set-Content -LiteralPath $installedConfig -Encoding UTF8
        Write-Host "Updating the installed Playnite Connector..."
        & $installedPatch -PlayniteDirectory $resolvedPlaynite
    }

    [ordered]@{
        ok = $true
        profile_id = $ProfileId
        profile_name = $ProfileName
        gateway_directory = $gatewayDirectory
        install_directory = $hostRoot
        discord_port = if ($SkipDiscord) { 0 } else { $ports[0] }
        vibepollo_port = if ($SkipVibepollo) { 0 } else { $ports[1] }
        playnite_port = if ($SkipPlaynite) { 0 } else { $ports[2] }
        restart_required = $true
    } | ConvertTo-Json -Compress | ForEach-Object { "MOONWAKER_INSTALL_RESULT=$_" }
} finally {
    $env:MOONWAKER_DISCORD_CLIENT_SECRET = $null
    $env:MOONWAKER_VIBEPOLLO_TOKEN = $null
    $env:MOONWAKER_VIBEPOLLO_ADMIN_PASSWORD = $null
}
