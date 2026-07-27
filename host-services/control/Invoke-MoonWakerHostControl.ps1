#requires -Version 5.1
[CmdletBinding()]
param(
    [Parameter(Mandatory)]
    [ValidateSet("Status", "StartGateway", "StopGateway", "RestartGateway", "PairGateway",
        "StartProfile", "StopProfile", "RestartProfile", "RecoverAll", "ClearDiscord", "ClearDiscordMachine", "RemoveProfile")]
    [string]$Action,
    [ValidatePattern('^[A-Za-z0-9._-]{0,64}$')][string]$ProfileId = "",
    [switch]$RemoveMachineDiscordApplication,
    [string]$ResultPath = ""
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function Get-InstallRoot {
    return [IO.Path]::GetFullPath((Split-Path -Parent $PSScriptRoot)).TrimEnd('\')
}

function Get-GatewayDirectory {
    foreach ($candidate in @((Join-Path (Get-InstallRoot) "gateway"),
        "C:\Tools\WakePlayHost\gateway", "C:\Tools\WakePlayGateway")) {
        if (Test-Path -LiteralPath (Join-Path $candidate "gateway.json")) { return $candidate }
    }
    return (Join-Path (Get-InstallRoot) "gateway")
}

function Test-TcpPort([string]$HostName, [int]$Port) {
    $client = [Net.Sockets.TcpClient]::new()
    try {
        $pending = $client.ConnectAsync($HostName, $Port)
        return $pending.Wait(500) -and $client.Connected
    } catch { return $false } finally { $client.Dispose() }
}

function Test-HttpHealth([string]$Endpoint) {
    if ([string]::IsNullOrWhiteSpace($Endpoint)) { return "disabled" }
    try {
        $response = Invoke-RestMethod -Uri ($Endpoint.TrimEnd('/') + "/health") -TimeoutSec 1
        if ($response -is [string]) {
            return $(if ($response.TrimStart().StartsWith("ok", [StringComparison]::OrdinalIgnoreCase)) {
                "online"
            } else { "error" })
        }
        if ($response.PSObject.Properties["ok"] -and $response.ok -eq $false) { return "error" }
        return "online"
    } catch { return "offline" }
}

function Get-ProfileRoot([object]$Entry, [string]$Id) {
    if ($entry -and $entry.PSObject.Properties["profile_root"]) {
        $candidate = [string]$entry.profile_root
        if (-not [string]::IsNullOrWhiteSpace($candidate)) { return $candidate }
    }
    $local = Join-Path (Get-InstallRoot) "profiles\$Id"
    if (Test-Path -LiteralPath $local) { return $local }
    return ""
}

function Get-SupervisorStatus([string]$Root) {
    if ([string]::IsNullOrWhiteSpace($Root)) { return "unavailable" }
    if (Test-Path -LiteralPath (Join-Path $Root "profile-bridge-manually-stopped")) {
        return "manually_stopped"
    }
    $statePath = Join-Path $Root "profile-bridge-state.json"
    try {
        $state = Get-Content -LiteralPath $statePath -Raw | ConvertFrom-Json
        $process = Get-Process -Id ([int]$state.supervisor_pid) -ErrorAction SilentlyContinue
        if ($process -and -not $process.HasExited) { return "running" }
    } catch {}
    return "stopped"
}

function Get-ProfileProcessId([string]$Root, [string]$Name) {
    if ([string]::IsNullOrWhiteSpace($Root)) { return 0 }
    try {
        $state = Get-Content -LiteralPath (Join-Path $Root "profile-bridge-state.json") -Raw | ConvertFrom-Json
        if ($Name -eq "supervisor") { return [int]$state.supervisor_pid }
        if ($state.components -and $state.components.PSObject.Properties[$Name]) {
            return [int]$state.components.$Name.pid
        }
    } catch {}
    return 0
}

function Test-GatewayManualStop([string]$Directory) {
    return Test-Path -LiteralPath (Join-Path $Directory "gateway-manually-stopped")
}

function Ensure-BackgroundServices([object]$Gateway, [string]$GatewayDirectory, [int]$GatewayPort) {
    if (-not (Test-TcpPort "127.0.0.1" $GatewayPort) -and -not (Test-GatewayManualStop $GatewayDirectory)) {
        try { Start-Gateway } catch {}
    }
    if (-not $Gateway.profiles) { return }
    $currentProfilesRoot = [IO.Path]::GetFullPath((Join-Path (Get-InstallRoot) "profiles"))
    foreach ($property in $Gateway.profiles.PSObject.Properties) {
        $id = [string]$property.Name
        $root = Get-ProfileRoot $property.Value $id
        if ([string]::IsNullOrWhiteSpace($root) -or
            (Test-Path -LiteralPath (Join-Path $root "profile-bridge-manually-stopped"))) { continue }
        try {
            $resolvedRoot = [IO.Path]::GetFullPath($root)
            if (-not $resolvedRoot.StartsWith($currentProfilesRoot + [IO.Path]::DirectorySeparatorChar,
                [StringComparison]::OrdinalIgnoreCase)) { continue }
            if ((Get-SupervisorStatus $root) -ne "running") {
                Invoke-ProfileControl $id "start"
            }
        } catch {}
    }
}

function Get-Status {
    $gatewayDirectory = Get-GatewayDirectory
    $configPath = Join-Path $gatewayDirectory "gateway.json"
    $gateway = if (Test-Path -LiteralPath $configPath) {
        Get-Content -LiteralPath $configPath -Raw | ConvertFrom-Json
    } else { [pscustomobject]@{ listen_port = 8785; profiles = [pscustomobject]@{}; clients = @() } }
    $port = if ($gateway.PSObject.Properties["listen_port"]) { [int]$gateway.listen_port } else { 8785 }
    Ensure-BackgroundServices $gateway $gatewayDirectory $port
    $runtime = $null
    try { $runtime = Get-Content -LiteralPath (Join-Path $gatewayDirectory "runtime-status.json") -Raw | ConvertFrom-Json } catch {}
    $pairing = $false
    $pairingSeconds = 0
    try {
        $pairingControl = Get-Content -LiteralPath (Join-Path $gatewayDirectory "pairing-code.json") -Raw | ConvertFrom-Json
        $pairingSeconds = [Math]::Max(0, [int64]$pairingControl.expires_at - [DateTimeOffset]::UtcNow.ToUnixTimeSeconds())
        $pairing = $pairingSeconds -gt 0
    } catch {}
    $profiles = @()
    if ($gateway.profiles) {
        foreach ($property in $gateway.profiles.PSObject.Properties) {
            $id = [string]$property.Name
            $entry = $property.Value
            $root = Get-ProfileRoot $entry $id
            $manuallyStopped = -not [string]::IsNullOrWhiteSpace($root) -and
                (Test-Path -LiteralPath (Join-Path $root "profile-bridge-manually-stopped"))
            $profiles += [ordered]@{
                id = $id
                name = if ($entry.PSObject.Properties["name"]) { [string]$entry.name } else { $id }
                owner = if ($entry.PSObject.Properties["owner"]) { [string]$entry.owner } else { "" }
                profile_root = $root
                current_user = -not [string]::IsNullOrWhiteSpace($root) -and
                    $root.StartsWith((Join-Path (Get-InstallRoot) "profiles"), [StringComparison]::OrdinalIgnoreCase)
                supervisor = Get-SupervisorStatus $root
                supervisor_pid = Get-ProfileProcessId $root "supervisor"
                discord = if ($manuallyStopped) { "manually_stopped" } else { Test-HttpHealth ([string]$entry.discord_bridge) }
                discord_pid = Get-ProfileProcessId $root "discord"
                vibepollo = if ($manuallyStopped) { "manually_stopped" } else { Test-HttpHealth ([string]$entry.vibepollo_bridge) }
                vibepollo_pid = Get-ProfileProcessId $root "vibepollo"
                playnite = if ($manuallyStopped) { "manually_stopped" } else { Test-HttpHealth ([string]$entry.playnite_bridge) }
                playnite_pid = Get-ProfileProcessId $root "playnite"
                last_used = $runtime -and [string]$runtime.profile_id -eq $id
                last_used_at = if ($runtime -and [string]$runtime.profile_id -eq $id) { [int64]$runtime.updated_at } else { 0 }
            }
        }
    }
    return [ordered]@{
        ok = $true
        gateway = [ordered]@{
            installed = Test-Path -LiteralPath $configPath
            running = Test-TcpPort "127.0.0.1" $port
            directory = $gatewayDirectory
            port = $port
            pairing = $pairing
            pairing_seconds = $pairingSeconds
            paired_clients = @($gateway.clients).Count
        }
        active_profile = if ($runtime) { [string]$runtime.profile_id } else { "" }
        profiles = $profiles
    }
}

function Start-Gateway {
    $directory = Get-GatewayDirectory
    $script = Join-Path $directory "Start-MoonWakerGateway.ps1"
    if (-not (Test-Path -LiteralPath $script)) { throw "Gateway is not installed." }
    & $script -GatewayDirectory $directory
}

function Stop-Gateway {
    $directory = Get-GatewayDirectory
    $stopScript = Join-Path $directory "Stop-MoonWakerGateway.ps1"
    if (Test-Path -LiteralPath $stopScript) { & $stopScript -GatewayDirectory $directory; return }
    $configPath = Join-Path $directory "gateway.json"
    $port = 8785
    try { $port = [int](Get-Content -LiteralPath $configPath -Raw | ConvertFrom-Json).listen_port } catch {}
    $owners = Get-NetTCPConnection -State Listen -LocalPort $port -ErrorAction SilentlyContinue |
        Select-Object -ExpandProperty OwningProcess -Unique
    foreach ($ownerPid in $owners) {
        $process = Get-CimInstance Win32_Process -Filter "ProcessId=$ownerPid" -ErrorAction SilentlyContinue
        if ($process -and [string]$process.CommandLine -like "*wakeplay_gateway.py*" -and
            [string]$process.CommandLine -like "*$configPath*") {
            Stop-Process -Id $ownerPid -Force -ErrorAction Stop
        }
    }
}

function Recover-All {
    Stop-Gateway
    Start-Sleep -Milliseconds 500
    Start-Gateway
    $status = Get-Status
    foreach ($profile in @($status.profiles)) {
        if ($profile.supervisor -ne "unavailable") {
            try {
                Invoke-ProfileControl ([string]$profile.id) "stop"
                Start-Sleep -Milliseconds 250
                Invoke-ProfileControl ([string]$profile.id) "start"
            } catch {}
        }
    }
}

function Set-PairingCode {
    $directory = Get-GatewayDirectory
    if (-not (Test-Path -LiteralPath (Join-Path $directory "gateway.json"))) { throw "Gateway is not installed." }
    $code = [string](Get-Random -Minimum 100000 -Maximum 999999)
    $sha = [Security.Cryptography.SHA256]::Create()
    try {
        $digest = ($sha.ComputeHash([Text.Encoding]::UTF8.GetBytes($code)) |
            ForEach-Object { $_.ToString("x2") }) -join ""
    } finally { $sha.Dispose() }
    [ordered]@{
        code_sha256 = $digest
        expires_at = [DateTimeOffset]::UtcNow.AddMinutes(10).ToUnixTimeSeconds()
    } | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $directory "pairing-code.json") -Encoding UTF8
    return $code
}

function Resolve-Profile([string]$Id) {
    if ([string]::IsNullOrWhiteSpace($Id)) { throw "Select a profile." }
    $directory = Get-GatewayDirectory
    $config = Get-Content -LiteralPath (Join-Path $directory "gateway.json") -Raw | ConvertFrom-Json
    $property = $config.profiles.PSObject.Properties[$Id]
    if (-not $property) { throw "Unknown profile '$Id'." }
    $root = Get-ProfileRoot $property.Value $Id
    return [pscustomobject]@{ gateway_directory = $directory; config = $config; entry = $property.Value; root = $root }
}

function Invoke-ProfileControl([string]$Id, [string]$Mode) {
    $profile = Resolve-Profile $Id
    if ([string]::IsNullOrWhiteSpace($profile.root)) { throw "This profile must be controlled from its Windows account." }
    $currentProfileRoot = [IO.Path]::GetFullPath((Join-Path (Get-InstallRoot) "profiles"))
    $resolvedProfileRoot = [IO.Path]::GetFullPath($profile.root)
    if (-not $resolvedProfileRoot.StartsWith($currentProfileRoot + [IO.Path]::DirectorySeparatorChar,
        [StringComparison]::OrdinalIgnoreCase)) {
        throw "Sign in to the Windows account that owns profile '$Id' to control its Bridge."
    }
    $scriptName = if ($Mode -eq "start") {
        "Start-MoonWakerProfileBridge.ps1"
    } else {
        "Stop-MoonWakerProfileBridge.ps1"
    }
    $script = Join-Path $profile.root $scriptName
    if (-not (Test-Path -LiteralPath $script)) { throw "Profile Bridge controller is not installed for '$Id'." }
    & $script -ProfileRoot $profile.root
}

function Clear-DiscordData([string]$Id) {
    $profile = Resolve-Profile $Id
    if ([string]::IsNullOrWhiteSpace($profile.root)) { throw "This profile's files are not available in the current Windows session." }
    foreach ($name in @("oauth_token.dpapi", "client_secret.dpapi")) {
        Remove-Item -LiteralPath (Join-Path (Join-Path $profile.root "discord") $name) -Force -ErrorAction SilentlyContinue
    }
    if ($RemoveMachineDiscordApplication) {
        Remove-Item -LiteralPath (Join-Path (Get-InstallRoot) "machine-data\discord-app.json") -Force -ErrorAction Stop
        Remove-Item -LiteralPath (Join-Path (Get-InstallRoot) "machine-data\discord-app-secret.dpapi") -Force -ErrorAction Stop
    }
}

function Remove-Profile([string]$Id) {
    $profile = Resolve-Profile $Id
    if ([string]::IsNullOrWhiteSpace($profile.root)) { throw "This profile's files are not available in the current Windows session." }
    $expectedRoot = [IO.Path]::GetFullPath((Join-Path (Get-InstallRoot) "profiles"))
    $resolvedRoot = [IO.Path]::GetFullPath($profile.root)
    if (-not $resolvedRoot.StartsWith($expectedRoot + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) {
        throw "Refusing to remove a profile outside the current user's MoonWaker profile directory."
    }
    try { Invoke-ProfileControl $Id "stop" } catch {}
    foreach ($taskName in @("Wake & Play Discord Bridge ($Id)", "Wake & Play Vibepollo Bridge ($Id)",
        "Wake & Play Playnite Bridge ($Id)", "MoonWaker Profile Bridge ($Id)")) {
        Unregister-ScheduledTask -TaskName $taskName -Confirm:$false -ErrorAction SilentlyContinue
    }
    $runKey = "HKCU:\Software\Microsoft\Windows\CurrentVersion\Run"
    Get-ItemProperty -Path $runKey -ErrorAction SilentlyContinue | ForEach-Object {
        $_.PSObject.Properties | Where-Object { $_.Name -like "MoonWaker*$Id*" } |
            ForEach-Object { Remove-ItemProperty -Path $runKey -Name $_.Name -ErrorAction SilentlyContinue }
    }
    Remove-Item -LiteralPath $resolvedRoot -Recurse -Force
    $profile.config.profiles.PSObject.Properties.Remove($Id)
    $profile.config | ConvertTo-Json -Depth 20 | Set-Content -LiteralPath `
        (Join-Path $profile.gateway_directory "gateway.json") -Encoding UTF8
}

try {
    $result = switch ($Action) {
        "Status" { Get-Status }
        "StartGateway" { Start-Gateway; [ordered]@{ ok = $true } }
        "StopGateway" { Stop-Gateway; [ordered]@{ ok = $true } }
        "RestartGateway" { Stop-Gateway; Start-Sleep -Milliseconds 500; Start-Gateway; [ordered]@{ ok = $true } }
        "PairGateway" { [ordered]@{ ok = $true; pairing_code = Set-PairingCode; expires_minutes = 10 } }
        "StartProfile" { Invoke-ProfileControl $ProfileId "start"; [ordered]@{ ok = $true } }
        "StopProfile" { Invoke-ProfileControl $ProfileId "stop"; [ordered]@{ ok = $true } }
        "RestartProfile" { Invoke-ProfileControl $ProfileId "stop"; Start-Sleep -Milliseconds 500; Invoke-ProfileControl $ProfileId "start"; [ordered]@{ ok = $true } }
        "RecoverAll" { Recover-All; [ordered]@{ ok = $true } }
        "ClearDiscord" { Clear-DiscordData $ProfileId; [ordered]@{ ok = $true } }
        "ClearDiscordMachine" { $RemoveMachineDiscordApplication = $true; Clear-DiscordData $ProfileId; [ordered]@{ ok = $true } }
        "RemoveProfile" { Remove-Profile $ProfileId; [ordered]@{ ok = $true } }
    }
    $json = $result | ConvertTo-Json -Depth 12 -Compress
    if ($ResultPath) { Set-Content -LiteralPath $ResultPath -Value $json -Encoding UTF8 }
    $json
} catch {
    $json = [ordered]@{ ok = $false; error = $_.Exception.Message } | ConvertTo-Json -Compress
    if ($ResultPath) { Set-Content -LiteralPath $ResultPath -Value $json -Encoding UTF8 }
    $json
    exit 1
}
