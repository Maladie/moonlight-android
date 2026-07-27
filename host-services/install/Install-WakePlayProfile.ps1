#requires -Version 5.1
[CmdletBinding()]
param(
    [ValidatePattern('^[A-Za-z0-9._-]{1,64}$')]
    [string]$ProfileId = "default",
    [string]$ProfileName = "",
    [int]$DiscordPort = 8765,
    [int]$VibepolloPort = 8775,
    [int]$PlaynitePort = 8780,
    [string]$InstallRoot = (Join-Path $env:LOCALAPPDATA "WakePlayHost\profiles"),
    [string]$GatewayConfigPath = "",
    [string]$GatewayDirectory = "",
    [string]$HostControlExecutable = "",
    [switch]$SkipDiscord,
    [switch]$SkipVibepollo,
    [switch]$SkipPlaynite,
    [switch]$SkipGatewayRegistration,
    [switch]$NonInteractiveConfiguration,
    [switch]$SkipStart
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"
$profileDisplayName = if ([string]::IsNullOrWhiteSpace($ProfileName)) {
    $ProfileId
} else {
    $ProfileName.Trim()
}
if ($profileDisplayName.Length -gt 80 -or $profileDisplayName -match '[\x00-\x1f\x7f]') {
    throw "Profile name must contain at most 80 printable characters."
}

if ($DiscordPort -lt 1024 -or $DiscordPort -gt 65535 -or
    $VibepolloPort -lt 1024 -or $VibepolloPort -gt 65535 -or
    $PlaynitePort -lt 1024 -or $PlaynitePort -gt 65535) {
    throw "Bridge ports must be between 1024 and 65535."
}
$activePorts = @()
if (-not $SkipDiscord) { $activePorts += $DiscordPort }
if (-not $SkipVibepollo) { $activePorts += $VibepolloPort }
if (-not $SkipPlaynite) { $activePorts += $PlaynitePort }
if (($activePorts | Select-Object -Unique).Count -ne $activePorts.Count) {
    throw "Discord, Vibepollo and Playnite Bridges must use different ports."
}
if ($ProfileId -ne "default" -and (
    (-not $SkipDiscord -and -not $PSBoundParameters.ContainsKey("DiscordPort")) -or
    (-not $SkipVibepollo -and -not $PSBoundParameters.ContainsKey("VibepolloPort")) -or
    (-not $SkipPlaynite -and -not $PSBoundParameters.ContainsKey("PlaynitePort")))) {
    throw "Additional profiles require explicit, unique -DiscordPort, -VibepolloPort and -PlaynitePort values."
}

$hostServicesRoot = Split-Path -Parent $PSScriptRoot
$sourceRoot = Join-Path $hostServicesRoot "bridges"
if (-not (Test-Path -LiteralPath $sourceRoot)) {
    $sourceRoot = Join-Path (Split-Path -Parent $PSScriptRoot) "bridge-source"
}
if (-not (Test-Path -LiteralPath $sourceRoot)) {
    throw "Bridge source package was not found next to the installer."
}

$profileRoot = Join-Path $InstallRoot $ProfileId
New-Item -ItemType Directory -Path $profileRoot -Force | Out-Null
$agentSource = Join-Path $hostServicesRoot "profile-agent"
if (-not (Test-Path -LiteralPath $agentSource)) {
    $agentSource = Join-Path (Split-Path -Parent $PSScriptRoot) "profile-agent-source"
}
if ([string]::IsNullOrWhiteSpace($GatewayConfigPath)) {
    $GatewayConfigPath = Join-Path (Split-Path -Parent $InstallRoot) "gateway\gateway.json"
}
if (-not (Test-Path -LiteralPath $agentSource)) {
    throw "Profile Bridge supervisor package was not found next to the installer."
}

function Install-BridgeFiles {
    param([string]$Name, [string[]]$Files)
    $source = Join-Path $sourceRoot $Name
    $destination = Join-Path $profileRoot $Name
    New-Item -ItemType Directory -Path $destination -Force | Out-Null
    foreach ($file in $Files) {
        Copy-Item -LiteralPath (Join-Path $source $file) -Destination $destination -Force
    }
    return $destination
}

function Stop-InstalledBridge {
    param([string]$BridgeName, [string]$StopScriptName)
    $directory = Join-Path $profileRoot $BridgeName
    $stopScript = Join-Path $directory $StopScriptName
    if (-not (Test-Path -LiteralPath $stopScript)) { return }
    try {
        & $stopScript
    } catch {
        Write-Warning "Unable to stop the existing $BridgeName Bridge cleanly: $($_.Exception.Message)"
    }
}

function Set-ConfigPort {
    param([string]$Path, [string]$Property, [int]$Port)
    $config = Get-Content -LiteralPath $Path -Raw | ConvertFrom-Json
    if ($null -eq $config.PSObject.Properties[$Property]) {
        $config | Add-Member -NotePropertyName $Property -NotePropertyValue $Port
    } else {
        $config.$Property = $Port
    }
    $config | ConvertTo-Json -Depth 20 | Set-Content -LiteralPath $Path -Encoding UTF8
}

function Set-ConfigValue {
    param([string]$Path, [string]$Property, [object]$Value)
    $config = Get-Content -LiteralPath $Path -Raw | ConvertFrom-Json
    if ($null -eq $config.PSObject.Properties[$Property]) {
        $config | Add-Member -NotePropertyName $Property -NotePropertyValue $Value
    } else {
        $config.$Property = $Value
    }
    $config | ConvertTo-Json -Depth 20 | Set-Content -LiteralPath $Path -Encoding UTF8
}

function Initialize-DiscordConfig {
    param([string]$Directory, [string]$ConfigPath, [int]$Port)
    if (-not $NonInteractiveConfiguration) {
        & (Join-Path $Directory "Configure-DiscordBridge.ps1")
        return
    }
    $clientId = [string]$env:MOONWAKER_DISCORD_CLIENT_ID
    $clientSecret = [string]$env:MOONWAKER_DISCORD_CLIENT_SECRET
    if ($clientId -notmatch '^[0-9]{17,20}$' -or [string]::IsNullOrWhiteSpace($clientSecret)) {
        throw "Discord configuration is missing. Provide it in the MoonWaker installer."
    }
    $previousClientId = ""
    if (Test-Path -LiteralPath $ConfigPath) {
        try { $previousClientId = [string](Get-Content -LiteralPath $ConfigPath -Raw | ConvertFrom-Json).client_id } catch {}
    }
    [ordered]@{
        client_id = $clientId
        port = $Port
        redirect_uri = ""
        scopes = @("rpc", "identify", "guilds", "rpc.voice.read", "rpc.voice.write")
    } | ConvertTo-Json -Depth 10 | Set-Content -LiteralPath $ConfigPath -Encoding UTF8
    ConvertTo-SecureString $clientSecret -AsPlainText -Force |
        ConvertFrom-SecureString |
        Set-Content -LiteralPath (Join-Path $Directory "client_secret.dpapi") -Encoding ASCII
    if ($previousClientId -and $previousClientId -ne $clientId) {
        Remove-Item -LiteralPath (Join-Path $Directory "oauth_token.dpapi") `
            -Force -ErrorAction SilentlyContinue
    }
}

function Initialize-VibepolloConfig {
    param([string]$Directory, [string]$ConfigPath, [int]$Port)
    if (-not $NonInteractiveConfiguration) {
        & (Join-Path $Directory "Configure-VibepolloBridge.ps1")
        return
    }
    $existing = $null
    if (Test-Path -LiteralPath $ConfigPath) {
        try { $existing = Get-Content -LiteralPath $ConfigPath -Raw | ConvertFrom-Json } catch {}
    }
    $baseUrl = [string]$env:MOONWAKER_VIBEPOLLO_URL
    $apiToken = [string]$env:MOONWAKER_VIBEPOLLO_TOKEN
    if ([string]::IsNullOrWhiteSpace($baseUrl) -and $null -ne $existing) {
        $baseUrl = [string]$existing.base_url
    }
    if ([string]::IsNullOrWhiteSpace($baseUrl)) { $baseUrl = "https://127.0.0.1:47990" }
    $uri = $null
    if (-not [uri]::TryCreate($baseUrl, [UriKind]::Absolute, [ref]$uri) -or
        $uri.Scheme -ne "https" -or $uri.Host -notin @("127.0.0.1", "localhost") -or
        [string]::IsNullOrWhiteSpace($apiToken)) {
        throw "Vibepollo configuration is missing or invalid. Provide it in the MoonWaker installer."
    }
    [ordered]@{
        base_url = $baseUrl.TrimEnd('/')
        listen_port = $Port
        python_path = if ($null -ne $existing -and
            $null -ne $existing.PSObject.Properties["python_path"]) {
            [string]$existing.python_path
        } else { "" }
    } | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath $ConfigPath -Encoding UTF8
    ConvertTo-SecureString $apiToken -AsPlainText -Force |
        ConvertFrom-SecureString |
        Set-Content -LiteralPath (Join-Path $Directory "api_token.dpapi") -Encoding ASCII
}

function Remove-LegacyBridgeStartup {
    foreach ($name in @("Wake & Play Discord Bridge ($ProfileId)",
        "Wake & Play Vibepollo Bridge ($ProfileId)", "Wake & Play Playnite Bridge ($ProfileId)")) {
        try { Unregister-ScheduledTask -TaskName $name -Confirm:$false -ErrorAction SilentlyContinue } catch {}
    }
    $runKey = "HKCU:\Software\Microsoft\Windows\CurrentVersion\Run"
    try {
        $item = Get-ItemProperty -Path $runKey -ErrorAction SilentlyContinue
        foreach ($property in $item.PSObject.Properties) {
            if ($property.Name -like "MoonWaker*Bridge*$ProfileId*") {
                Remove-ItemProperty -Path $runKey -Name $property.Name -ErrorAction SilentlyContinue
            }
        }
    } catch {}
    $startupShortcut = Join-Path $env:APPDATA (
        "Microsoft\Windows\Start Menu\Programs\Startup\MoonWaker Profile Bridge ($ProfileId).lnk")
    Remove-Item -LiteralPath $startupShortcut -Force -ErrorAction SilentlyContinue
}

function Register-ProfileAgent {
    param([string]$StartScript)
    $Name = "MoonWaker Profile Bridge ($ProfileId)"
    # A Startup shortcut belongs to this Windows account.  Unlike a task made
    # by an elevated installer, it can be managed and started by the same user.
    $startup = Join-Path $env:APPDATA "Microsoft\Windows\Start Menu\Programs\Startup"
    New-Item -ItemType Directory -Path $startup -Force | Out-Null
    $shell = New-Object -ComObject WScript.Shell
    $shortcut = $shell.CreateShortcut((Join-Path $startup "MoonWaker Profile Bridge ($ProfileId).lnk"))
    $shortcut.TargetPath = "powershell.exe"
    $shortcut.Arguments = "-NoProfile -WindowStyle Hidden -ExecutionPolicy Bypass -File `"$StartScript`""
    $shortcut.WorkingDirectory = Split-Path -Parent $StartScript
    $shortcut.Description = "MoonWaker Profile Bridge supervisor ($ProfileId)"
    $shortcut.Save()
    Write-Host "Registered per-user Profile Bridge startup shortcut."
}

function Register-UserStartup {
    param([string]$Name, [string]$TargetPath, [string]$Arguments, [string]$Description)
    if ([string]::IsNullOrWhiteSpace($TargetPath)) { return }
    if (-not [IO.Path]::IsPathRooted($TargetPath)) {
        $command = Get-Command $TargetPath -ErrorAction SilentlyContinue
        if ($null -eq $command) { return }
        $TargetPath = $command.Source
    }
    if (-not (Test-Path -LiteralPath $TargetPath)) { return }
    $startup = Join-Path $env:APPDATA "Microsoft\Windows\Start Menu\Programs\Startup"
    New-Item -ItemType Directory -Path $startup -Force | Out-Null
    $shell = New-Object -ComObject WScript.Shell
    $shortcut = $shell.CreateShortcut((Join-Path $startup "$Name.lnk"))
    $shortcut.TargetPath = $TargetPath
    $shortcut.Arguments = $Arguments
    $shortcut.WorkingDirectory = Split-Path -Parent $TargetPath
    $shortcut.Description = $Description
    $shortcut.Save()
}

Remove-LegacyBridgeStartup
foreach ($file in @("MoonWakerProfileBridge.ps1", "Start-MoonWakerProfileBridge.ps1",
    "Stop-MoonWakerProfileBridge.ps1")) {
    Copy-Item -LiteralPath (Join-Path $agentSource $file) -Destination (Join-Path $profileRoot $file) -Force
}
Register-ProfileAgent (Join-Path $profileRoot "Start-MoonWakerProfileBridge.ps1")
if ([string]::IsNullOrWhiteSpace($GatewayDirectory)) { $GatewayDirectory = Split-Path -Parent $GatewayConfigPath }
$gatewayStart = Join-Path $GatewayDirectory "Start-MoonWakerGateway.ps1"
Register-UserStartup "MoonWaker Gateway" "powershell.exe" (
    "-NoProfile -WindowStyle Hidden -ExecutionPolicy Bypass -File `"$gatewayStart`"") "MoonWaker Gateway supervisor"
Register-UserStartup "MoonWaker Host Control" $HostControlExecutable "--tray" "MoonWaker Host Control"

$discordDirectory = $null
if (-not $SkipDiscord) {
    Stop-InstalledBridge "discord" "Stop-DiscordBridge.ps1"
    $discordDirectory = Install-BridgeFiles "discord" @(
        "DiscordBridge.ps1", "Configure-DiscordBridge.ps1",
        "discord_bridge_config.example.json", "Start-DiscordBridge.ps1",
        "Stop-DiscordBridge.ps1", "Test-DiscordBridge.ps1", "WindowsAudio.cs", "README.md")
    $discordConfig = Join-Path $discordDirectory "discord_bridge_config.json"
    if (-not (Test-Path -LiteralPath $discordConfig) -or
        ($NonInteractiveConfiguration -and -not [string]::IsNullOrWhiteSpace($env:MOONWAKER_DISCORD_CLIENT_ID))) {
        Initialize-DiscordConfig $discordDirectory $discordConfig $DiscordPort
    }
    Set-ConfigPort $discordConfig "port" $DiscordPort
}

$vibepolloDirectory = $null
if (-not $SkipVibepollo) {
    Stop-InstalledBridge "vibepollo" "Stop-VibepolloBridge.ps1"
    $vibepolloDirectory = Install-BridgeFiles "vibepollo" @(
        "VibepolloBridge.ps1", "VibepolloTransport.py",
        "Configure-VibepolloBridge.ps1", "config.example.json",
        "moonwaker-token-scopes.example.json",
        "Start-VibepolloBridge.ps1", "Stop-VibepolloBridge.ps1",
        "Test-VibepolloBridge.ps1", "README.md")
    $vibepolloConfig = Join-Path $vibepolloDirectory "config.json"
    if (-not (Test-Path -LiteralPath $vibepolloConfig) -or
        ($NonInteractiveConfiguration -and -not [string]::IsNullOrWhiteSpace($env:MOONWAKER_VIBEPOLLO_TOKEN))) {
        Initialize-VibepolloConfig $vibepolloDirectory $vibepolloConfig $VibepolloPort
    }
    Set-ConfigPort $vibepolloConfig "listen_port" $VibepolloPort
}

$playniteDirectory = $null
if (-not $SkipPlaynite) {
    Stop-InstalledBridge "playnite" "Stop-PlayniteBridge.ps1"
    $playniteDirectory = Install-BridgeFiles "playnite" @(
        "PlayniteBridge.py", "config.example.json",
        "Start-PlayniteBridge.ps1", "Stop-PlayniteBridge.ps1",
        "PatchPlayniteConnector.py", "Install-WakePlayConnectorPatch.ps1", "README.md")
    $playniteConfig = Join-Path $playniteDirectory "config.json"
    if (-not (Test-Path -LiteralPath $playniteConfig)) {
        Copy-Item -LiteralPath (Join-Path $playniteDirectory "config.example.json") `
            -Destination $playniteConfig
    }
    Set-ConfigPort $playniteConfig "listen_port" $PlaynitePort
    Set-ConfigValue $playniteConfig "vibepollo_bridge" `
        $(if ($SkipVibepollo) { "" } else { "http://127.0.0.1:$VibepolloPort" })
}

if (-not $SkipGatewayRegistration) {
    try {
        $gateway = Get-Content -LiteralPath $GatewayConfigPath -Raw | ConvertFrom-Json
        if ($null -eq $gateway.PSObject.Properties["profiles"]) {
            $gateway | Add-Member -NotePropertyName profiles -NotePropertyValue ([pscustomobject]@{})
        }
        $entry = [pscustomobject]@{
            name = $profileDisplayName
            owner = [Security.Principal.WindowsIdentity]::GetCurrent().Name
            profile_root = $profileRoot
            discord_bridge = if ($SkipDiscord) { "" } else { "http://127.0.0.1:$DiscordPort" }
            vibepollo_bridge = if ($SkipVibepollo) { "" } else { "http://127.0.0.1:$VibepolloPort" }
            playnite_bridge = if ($SkipPlaynite) { "" } else { "http://127.0.0.1:$PlaynitePort" }
        }
        if ($null -eq $gateway.profiles.PSObject.Properties[$ProfileId]) {
            $gateway.profiles | Add-Member -NotePropertyName $ProfileId -NotePropertyValue $entry
        } else {
            $gateway.profiles.$ProfileId = $entry
        }
        $gateway | ConvertTo-Json -Depth 20 | Set-Content -LiteralPath $GatewayConfigPath -Encoding UTF8
        Write-Host "Registered profile '$ProfileId' in the Gateway configuration." -ForegroundColor Green
        Write-Warning "Restart the Gateway to load the updated profile registry."
    } catch {
        $registrationPath = Join-Path $profileRoot "gateway-profile-registration.json"
        [ordered]@{
            profile_id = $ProfileId
            name = $profileDisplayName
            owner = [Security.Principal.WindowsIdentity]::GetCurrent().Name
            profile_root = $profileRoot
            discord_bridge = if ($SkipDiscord) { "" } else { "http://127.0.0.1:$DiscordPort" }
            vibepollo_bridge = if ($SkipVibepollo) { "" } else { "http://127.0.0.1:$VibepolloPort" }
            playnite_bridge = if ($SkipPlaynite) { "" } else { "http://127.0.0.1:$PlaynitePort" }
        } | ConvertTo-Json | Set-Content -LiteralPath $registrationPath -Encoding UTF8
        Write-Warning "Gateway configuration could not be updated: $($_.Exception.Message)"
        Write-Warning "Registration data was written to $registrationPath for an administrator."
    }
}

if (-not $SkipStart) {
    if (Test-Path -LiteralPath $gatewayStart) {
        & $gatewayStart -GatewayDirectory $GatewayDirectory
    }
    & (Join-Path $profileRoot "Start-MoonWakerProfileBridge.ps1") -ProfileRoot $profileRoot -ProfileId $ProfileId
}

# Never leak installer-provided secrets into Bridge child processes.
$env:MOONWAKER_DISCORD_CLIENT_SECRET = $null
$env:MOONWAKER_VIBEPOLLO_TOKEN = $null

Write-Host "Wake & Play integration profile '$ProfileId' installed for $env:USERNAME." -ForegroundColor Green
