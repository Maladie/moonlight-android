#requires -Version 5.1
[CmdletBinding()]
param(
    [Parameter(Mandatory)]
    [ValidateSet("Status", "StartGateway", "StopGateway", "RestartGateway", "PairGateway",
        "StartProfile", "StopProfile", "RestartProfile", "RecoverAll",
        "ConfigureSteamWebApi", "DisconnectSteam", "ConnectEpic", "DisconnectEpic",
        "ConnectPlaynite", "DisconnectPlaynite",
        "ClearDiscord", "ClearDiscordMachine", "RemoveProfile", "ExportDiagnostics")]
    [string]$Action,
    [ValidatePattern('^[A-Za-z0-9._-]{0,64}$')][string]$ProfileId = "",
    [switch]$RemoveMachineDiscordApplication,
    [switch]$SteamWebApiKeyFromStdin,
    [switch]$SteamWebApiKeyProtectedFromEnvironment,
    [string]$SteamWebApiDiagnosticPath = "",
    [string]$PlayniteDirectory = "",
    [string]$DiagnosticsOutputDirectory = "",
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

function Get-GameProviderDirectory([string]$Root) {
    if ([string]::IsNullOrWhiteSpace($Root)) { return "" }
    $canonical = Join-Path $Root "game-provider"
    if (Test-Path -LiteralPath $canonical -PathType Container) { return $canonical }
    $legacy = Join-Path $Root "playnite"
    if (Test-Path -LiteralPath $legacy -PathType Container) { return $legacy }
    return $canonical
}

function Get-SteamWebApiDiagnosticPath {
    if (-not [string]::IsNullOrWhiteSpace($SteamWebApiDiagnosticPath)) {
        return [IO.Path]::GetFullPath($SteamWebApiDiagnosticPath)
    }
    return Join-Path $env:LOCALAPPDATA "MoonWaker\logs\steam-web-api-configure.log"
}

function Write-SteamWebApiDiagnostic([string]$Profile, [string]$Phase) {
    try {
        $path = Get-SteamWebApiDiagnosticPath
        $directory = Split-Path -Parent $path
        New-Item -ItemType Directory -Path $directory -Force | Out-Null
        if ((Test-Path -LiteralPath $path) -and (Get-Item -LiteralPath $path).Length -gt 65536) {
            Clear-Content -LiteralPath $path
        }
        Add-Content -LiteralPath $path -Encoding UTF8 -Value (
            "{0:o} pid={1} profile={2} component=script phase={3}" -f `
                [DateTimeOffset]::Now, $PID, $Profile, $Phase)
    } catch {}
}

function Get-MoonWakerVersion {
    $path = Join-Path (Get-InstallRoot) "version.json"
    try {
        $value = Get-Content -LiteralPath $path -Raw | ConvertFrom-Json
        return [ordered]@{
            version = [string]$value.version
            build = [string]$value.build
            protocol_version = [int]$value.protocol_version
        }
    } catch {
        return [ordered]@{ version = "unknown"; build = "unknown"; protocol_version = 0 }
    }
}

function Copy-DiagnosticSeries([string]$SourceDirectory, [string]$BaseName,
        [string]$DestinationDirectory) {
    $pattern = '^' + [regex]::Escape($BaseName) + '(?:\.[0-9]+)?$'
    try {
        if (-not (Test-Path -LiteralPath $SourceDirectory -PathType Container -ErrorAction Stop)) {
            return 0
        }
        $files = @(Get-ChildItem -LiteralPath $SourceDirectory -File -ErrorAction Stop |
            Where-Object { $_.Name -match $pattern } | Sort-Object Name)
    } catch { return 0 }
    $copied = 0
    foreach ($file in $files) {
        if (($file.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) { continue }
        $destination = Join-Path $DestinationDirectory $file.Name
        $input = $null
        $output = $null
        try {
            New-Item -ItemType Directory -Path $DestinationDirectory -Force | Out-Null
            $sharing = [IO.FileShare]::ReadWrite -bor [IO.FileShare]::Delete
            $input = [IO.File]::Open($file.FullName, [IO.FileMode]::Open,
                [IO.FileAccess]::Read, $sharing)
            $output = [IO.File]::Open($destination, [IO.FileMode]::Create,
                [IO.FileAccess]::Write, [IO.FileShare]::None)
            $input.CopyTo($output)
            $copied++
        } catch {
            Remove-Item -LiteralPath $destination -Force -ErrorAction SilentlyContinue
        } finally {
            if ($output) { $output.Dispose() }
            if ($input) { $input.Dispose() }
        }
    }
    return $copied
}

function Export-Diagnostics {
    $gatewayDirectory = Get-GatewayDirectory
    $config = $null
    try {
        $config = Get-Content -LiteralPath (Join-Path $gatewayDirectory "gateway.json") -Raw |
            ConvertFrom-Json
    } catch {}
    $outputDirectory = $DiagnosticsOutputDirectory
    if ([string]::IsNullOrWhiteSpace($outputDirectory)) {
        foreach ($candidate in @([Environment]::GetFolderPath("DesktopDirectory"),
                [Environment]::GetFolderPath("MyDocuments"), [IO.Path]::GetTempPath())) {
            if (-not [string]::IsNullOrWhiteSpace($candidate) -and
                (Test-Path -LiteralPath $candidate -PathType Container)) {
                $outputDirectory = $candidate
                break
            }
        }
    }
    $outputDirectory = [IO.Path]::GetFullPath($outputDirectory)
    New-Item -ItemType Directory -Path $outputDirectory -Force | Out-Null

    $tempRoot = [IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd('\')
    $staging = Join-Path $tempRoot ("MoonWaker-Diagnostics-" + [guid]::NewGuid().ToString("N"))
    $staging = [IO.Path]::GetFullPath($staging)
    $stagingValid = $staging.StartsWith($tempRoot + [IO.Path]::DirectorySeparatorChar,
        [StringComparison]::OrdinalIgnoreCase) -and
        (Split-Path -Leaf $staging) -match '^MoonWaker-Diagnostics-[A-Fa-f0-9]{32}$'
    if (-not $stagingValid) { throw "Unable to create a safe diagnostics staging directory." }

    try {
        New-Item -ItemType Directory -Path $staging -Force | Out-Null
        $count = 0
        $count += Copy-DiagnosticSeries $gatewayDirectory "gateway-supervisor.jsonl" `
            (Join-Path $staging "gateway\supervisor")
        $count += Copy-DiagnosticSeries (Join-Path $gatewayDirectory "logs") `
            "gateway-diagnostics.jsonl" (Join-Path $staging "gateway\service")
        if ($config -and $config.profiles) {
            foreach ($property in $config.profiles.PSObject.Properties) {
                $id = [regex]::Replace([string]$property.Name, '[^A-Za-z0-9._-]', '_')
                if ([string]::IsNullOrWhiteSpace($id) -or $id -in @(".", "..")) {
                    $id = "unknown"
                }
                $root = Get-ProfileRoot $property.Value ([string]$property.Name)
                if ([string]::IsNullOrWhiteSpace($root)) { continue }
                $profileDestination = Join-Path $staging ("profiles\" + $id)
                $count += Copy-DiagnosticSeries $root "profile-bridge.jsonl" `
                    (Join-Path $profileDestination "supervisor")
                $count += Copy-DiagnosticSeries (Join-Path (Get-GameProviderDirectory $root) "logs") `
                    "provider-diagnostics.jsonl" (Join-Path $profileDestination "provider")
            }
        }

        $version = Get-MoonWakerVersion
        $safeVersion = if ([string]$version.version -match '^[A-Za-z0-9._+-]{1,128}$') {
            [string]$version.version
        } else { "unknown" }
        $safeBuild = if ([string]$version.build -match '^[A-Za-z0-9._+-]{1,128}$') {
            [string]$version.build
        } else { "unknown" }
        $manifest = [ordered]@{
            schema_version = 1
            created_utc = [DateTime]::UtcNow.ToString("o")
            version = $safeVersion
            build = $safeBuild
            protocol_version = [int]$version.protocol_version
            file_count = $count
        } | ConvertTo-Json -Compress
        [IO.File]::WriteAllText((Join-Path $staging "manifest.json"), $manifest,
            [Text.UTF8Encoding]::new($false))
        $name = "MoonWaker-Diagnostics-{0}-{1}.zip" -f `
            [DateTime]::UtcNow.ToString("yyyyMMdd-HHmmss-fff"),
            [guid]::NewGuid().ToString("N").Substring(0, 8)
        $archive = Join-Path $outputDirectory $name
        Add-Type -AssemblyName System.IO.Compression.FileSystem
        [IO.Compression.ZipFile]::CreateFromDirectory($staging, $archive,
            [IO.Compression.CompressionLevel]::Optimal, $false)
        return [ordered]@{ ok = $true; path = $archive; files = $count }
    } finally {
        if ($stagingValid -and (Test-Path -LiteralPath $staging)) {
            Remove-Item -LiteralPath $staging -Recurse -Force -ErrorAction SilentlyContinue
        }
    }
}

function Test-TcpPort([string]$HostName, [int]$Port) {
    $client = [Net.Sockets.TcpClient]::new()
    try {
        $pending = $client.ConnectAsync($HostName, $Port)
        return $pending.Wait(500) -and $client.Connected
    } catch { return $false } finally { $client.Dispose() }
}

function Get-ListeningProcessIds([int]$Port) {
    $values = @(Get-NetTCPConnection -State Listen -LocalPort $Port `
        -ErrorAction SilentlyContinue | Select-Object -ExpandProperty OwningProcess -Unique)
    if ($values.Count -gt 0) { return $values }
    $pattern = "^\s*TCP\s+\S+:$Port\s+\S+\s+LISTENING\s+(\d+)\s*$"
    return @(& netstat.exe -ano -p TCP | ForEach-Object {
        if ($_ -match $pattern) { [int]$matches[1] }
    } | Sort-Object -Unique)
}

function Wait-GatewayState([bool]$Running, [int]$TimeoutSeconds = 20,
        [string]$Directory = "") {
    $directory = if ([string]::IsNullOrWhiteSpace($Directory)) {
        Get-GatewayDirectory
    } else { [IO.Path]::GetFullPath($Directory) }
    $port = 8785
    try { $port = [int](Get-Content -LiteralPath (Join-Path $directory "gateway.json") -Raw |
        ConvertFrom-Json).listen_port } catch {}
    $expected = Get-MoonWakerVersion
    $deadline = [DateTime]::UtcNow.AddSeconds($TimeoutSeconds)
    while ([DateTime]::UtcNow -lt $deadline) {
        $listening = Test-TcpPort "127.0.0.1" $port
        if (-not $Running -and -not $listening) { return $true }
        if ($Running -and $listening) {
            try {
                $runtime = Get-Content -LiteralPath (Join-Path $directory "gateway-runtime.json") -Raw |
                    ConvertFrom-Json
                $process = Get-Process -Id ([int]$runtime.pid) -ErrorAction SilentlyContinue
                if ($process -and [string]$runtime.version -eq [string]$expected.version -and
                    [string]$runtime.build -eq [string]$expected.build) { return $true }
            } catch {}
        }
        Start-Sleep -Milliseconds 200
    }
    return $false
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

function Get-MicrophoneReadiness([object]$Gateway, [string]$Directory) {
    $name = if ($Gateway.PSObject.Properties["microphone_worker"]) {
        [string]$Gateway.microphone_worker
    } else { "MoonWakerMicrophoneWorker.exe" }
    $worker = if ([IO.Path]::IsPathRooted($name)) { $name } else { Join-Path $Directory $name }
    if (-not (Test-Path -LiteralPath $worker -PathType Leaf)) {
        return [ordered]@{ ready = $false; reason = "worker_missing" }
    }
    $process = $null
    try {
        $start = [Diagnostics.ProcessStartInfo]::new($worker, "--probe")
        $start.UseShellExecute = $false
        $start.CreateNoWindow = $true
        $start.RedirectStandardOutput = $true
        $start.RedirectStandardError = $true
        $process = [Diagnostics.Process]::Start($start)
        if ($process.WaitForExit(3000) -and $process.ExitCode -eq 0) {
            return [ordered]@{ ready = $true; reason = "ready" }
        }
        if (-not $process.HasExited) { try { $process.Kill() } catch {} }
    } catch {} finally { if ($process) { $process.Dispose() } }
    return [ordered]@{
        ready = $false
        reason = "steam_endpoint_missing_or_ambiguous_or_unsupported"
    }
}

function Test-CurrentProfileOwner([object]$Entry) {
    if (-not $Entry -or -not $Entry.PSObject.Properties["owner"]) { return $true }
    $owner = [string]$Entry.owner
    if ([string]::IsNullOrWhiteSpace($owner)) { return $true }
    return $owner.Equals([Security.Principal.WindowsIdentity]::GetCurrent().Name,
        [StringComparison]::OrdinalIgnoreCase)
}

function Protect-ForCurrentUser([string]$Value) {
    Add-Type -AssemblyName System.Security
    $plainBytes = [Text.Encoding]::Unicode.GetBytes($Value)
    $protectedBytes = $null
    try {
        $protectedBytes = [Security.Cryptography.ProtectedData]::Protect(
            $plainBytes, $null, [Security.Cryptography.DataProtectionScope]::CurrentUser)
        return ($protectedBytes | ForEach-Object { $_.ToString("x2") }) -join ""
    } finally {
        [Array]::Clear($plainBytes, 0, $plainBytes.Length)
        if ($null -ne $protectedBytes) {
            [Array]::Clear($protectedBytes, 0, $protectedBytes.Length)
        }
    }
}

function Unprotect-ForCurrentUser([string]$Value) {
    if ($Value -notmatch '^(?:[A-Fa-f0-9]{2})+$') {
        throw "The DPAPI value is not valid hexadecimal data."
    }
    Add-Type -AssemblyName System.Security
    $protectedBytes = New-Object byte[] ($Value.Length / 2)
    for ($index = 0; $index -lt $protectedBytes.Length; $index++) {
        $protectedBytes[$index] = [Convert]::ToByte($Value.Substring($index * 2, 2), 16)
    }
    $plainBytes = $null
    try {
        $plainBytes = [Security.Cryptography.ProtectedData]::Unprotect(
            $protectedBytes, $null, [Security.Cryptography.DataProtectionScope]::CurrentUser)
        return [Text.Encoding]::Unicode.GetString($plainBytes)
    } finally {
        [Array]::Clear($protectedBytes, 0, $protectedBytes.Length)
        if ($null -ne $plainBytes) {
            [Array]::Clear($plainBytes, 0, $plainBytes.Length)
        }
    }
}

function Get-LegendaryExecutable {
    $path = Join-Path (Get-InstallRoot) "tools\legendary\legendary.exe"
    if (Test-Path -LiteralPath $path -PathType Leaf) { return $path }
    return ""
}

function Test-SteamConnection([string]$Root) {
    if ([string]::IsNullOrWhiteSpace($Root)) { return $false }
    $path = Join-Path (Get-GameProviderDirectory $Root) "steam-web-api-key.dpapi"
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) { return $false }
    try {
        $protected = (Get-Content -LiteralPath $path -Raw).Trim()
        return (Unprotect-ForCurrentUser $protected) -match '^[A-Fa-f0-9]{32}$'
    } catch { return $false }
}

function Test-PlayniteConnection([string]$Root) {
    try {
        $directory = Get-GameProviderDirectory $Root
        $config = Get-Content -LiteralPath (Join-Path $directory "config.json") -Raw |
            ConvertFrom-Json
        return [bool]$config.playnite_enabled -and
            (Test-Path -LiteralPath ([string]$config.playnite_desktop_executable) -PathType Leaf) -and
            (Test-Path -LiteralPath ([string]$config.playnite_fullscreen_executable) -PathType Leaf)
    } catch { return $false }
}

function Test-LegendaryConnection([string]$Root) {
    $executable = Get-LegendaryExecutable
    if ([string]::IsNullOrWhiteSpace($Root) -or
        [string]::IsNullOrWhiteSpace($executable)) { return $false }
    $state = Join-Path $Root "state\legendary"
    if (-not (Test-Path -LiteralPath $state -PathType Container)) { return $false }
    $previous = $env:LEGENDARY_CONFIG_PATH
    try {
        $env:LEGENDARY_CONFIG_PATH = $state
        $raw = (& $executable status --offline --json 2>$null | Out-String)
        if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($raw)) { return $false }
        $status = $raw | ConvertFrom-Json
        return $null -ne $status.account
    } catch { return $false } finally { $env:LEGENDARY_CONFIG_PATH = $previous }
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
        # A profile contains user-DPAPI-protected credentials. Starting it from
        # another Windows account makes it look alive while every protected
        # integration fails immediately.
        if (-not (Test-CurrentProfileOwner $property.Value)) { continue }
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
    $gatewayRuntime = $null
    try { $gatewayRuntime = Get-Content -LiteralPath (Join-Path $gatewayDirectory "gateway-runtime.json") -Raw | ConvertFrom-Json } catch {}
    $version = Get-MoonWakerVersion
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
            $steamConnected = Test-SteamConnection $root
            $profiles += [ordered]@{
                id = $id
                name = if ($entry.PSObject.Properties["name"]) { [string]$entry.name } else { $id }
                owner = if ($entry.PSObject.Properties["owner"]) { [string]$entry.owner } else { "" }
                profile_root = $root
                current_user = Test-CurrentProfileOwner $entry
                supervisor = Get-SupervisorStatus $root
                supervisor_pid = Get-ProfileProcessId $root "supervisor"
                discord = if ($manuallyStopped) { "manually_stopped" } else { Test-HttpHealth ([string]$entry.discord_bridge) }
                discord_pid = Get-ProfileProcessId $root "discord"
                vibepollo = if ($manuallyStopped) { "manually_stopped" } else { Test-HttpHealth ([string]$entry.vibepollo_bridge) }
                vibepollo_pid = Get-ProfileProcessId $root "vibepollo"
                game_provider = if ($manuallyStopped) { "manually_stopped" } else {
                    $endpoint = if ($entry.PSObject.Properties["game_provider_bridge"]) {
                        [string]$entry.game_provider_bridge
                    } else { [string]$entry.playnite_bridge }
                    Test-HttpHealth $endpoint
                }
                game_provider_pid = Get-ProfileProcessId $root "game-provider"
                playnite = if ($manuallyStopped) { "manually_stopped" } else {
                    $endpoint = if ($entry.PSObject.Properties["game_provider_bridge"]) {
                        [string]$entry.game_provider_bridge
                    } else { [string]$entry.playnite_bridge }
                    Test-HttpHealth $endpoint
                }
                playnite_pid = Get-ProfileProcessId $root "game-provider"
                steam_web_api_configured = $steamConnected
                steam_connected = $steamConnected
                epic_connected = Test-LegendaryConnection $root
                playnite_connected = Test-PlayniteConnection $root
                platform_controls_available = Test-CurrentProfileOwner $entry
                last_used = $runtime -and [string]$runtime.profile_id -eq $id
                last_used_at = if ($runtime -and [string]$runtime.profile_id -eq $id) { [int64]$runtime.updated_at } else { 0 }
            }
        }
    }
    return [ordered]@{
        ok = $true
        version = $version
        legendary = [ordered]@{
            installed = -not [string]::IsNullOrWhiteSpace((Get-LegendaryExecutable))
        }
        microphone = Get-MicrophoneReadiness $gateway $gatewayDirectory
        gateway = [ordered]@{
            installed = Test-Path -LiteralPath $configPath
            running = Test-TcpPort "127.0.0.1" $port
            directory = $gatewayDirectory
            port = $port
            pairing = $pairing
            pairing_seconds = $pairingSeconds
            paired_clients = @($gateway.clients).Count
            installed_version = [string]$version.version
            installed_build = [string]$version.build
            runtime_version = if ($gatewayRuntime) { [string]$gatewayRuntime.version } else { "" }
            runtime_build = if ($gatewayRuntime) { [string]$gatewayRuntime.build } else { "" }
            runtime_pid = if ($gatewayRuntime) { [int]$gatewayRuntime.pid } else { 0 }
            runtime_started_at = if ($gatewayRuntime) { [int64]$gatewayRuntime.started_at } else { 0 }
            version_mismatch = $gatewayRuntime -and (
                [string]$gatewayRuntime.version -ne [string]$version.version -or
                [string]$gatewayRuntime.build -ne [string]$version.build)
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
    if (-not (Wait-GatewayState -Running $true -Directory $directory)) {
        throw "Gateway did not start with the installed MoonWaker version."
    }
}

function Stop-Gateway {
    $directory = Get-GatewayDirectory
    $stopScript = Join-Path $directory "Stop-MoonWakerGateway.ps1"
    if (Test-Path -LiteralPath $stopScript) {
        & $stopScript -GatewayDirectory $directory
        if (-not (Wait-GatewayState -Running $false -Directory $directory)) {
            throw "Gateway did not stop."
        }
        return
    }
    $configPath = Join-Path $directory "gateway.json"
    $port = 8785
    try { $port = [int](Get-Content -LiteralPath $configPath -Raw | ConvertFrom-Json).listen_port } catch {}
    $owners = @(Get-ListeningProcessIds $port)
    foreach ($ownerPid in $owners) {
        $process = Get-CimInstance Win32_Process -Filter "ProcessId=$ownerPid" -ErrorAction SilentlyContinue
        if ($process -and [string]$process.CommandLine -like "*wakeplay_gateway.py*" -and
            [string]$process.CommandLine -like "*$configPath*") {
            Stop-Process -Id $ownerPid -Force -ErrorAction Stop
        }
    }
    if (-not (Wait-GatewayState -Running $false -Directory $directory)) {
        throw "Gateway did not stop."
    }
}

function Wait-ProfileState([string]$Id, [bool]$Running, [int]$TimeoutSeconds = 20) {
    $profile = Resolve-Profile $Id
    $deadline = [DateTime]::UtcNow.AddSeconds($TimeoutSeconds)
    while ([DateTime]::UtcNow -lt $deadline) {
        $state = Get-SupervisorStatus $profile.root
        if ($Running -and $state -eq "running") { return $true }
        if (-not $Running -and $state -in @("stopped", "manually_stopped")) { return $true }
        Start-Sleep -Milliseconds 200
    }
    return $false
}

function Restart-Profile([string]$Id) {
    Invoke-ProfileControl $Id "stop"
    if (-not (Wait-ProfileState $Id $false)) { throw "Profile '$Id' did not stop." }
    Invoke-ProfileControl $Id "start"
    if (-not (Wait-ProfileState $Id $true)) { throw "Profile '$Id' did not start." }
}

function Recover-All {
    Stop-Gateway
    Start-Gateway
    $status = Get-Status
    $failures = @()
    foreach ($profile in @($status.profiles)) {
        if (-not $profile.current_user -or $profile.supervisor -eq "unavailable") { continue }
        try { Restart-Profile ([string]$profile.id) } catch { $failures += $_.Exception.Message }
    }
    if ($failures.Count) {
        throw "Gateway was repaired, but profile repair failed: $($failures -join '; ')"
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
    if (-not (Test-CurrentProfileOwner $profile.entry)) {
        # A legacy Host Control may already have started this profile under the
        # wrong account. Let that same account stop its own accidental process,
        # but never let it start or restart another user's profile.
        $stateOwner = ""
        try {
            $stateOwner = [string](Get-Content -LiteralPath (Join-Path $profile.root "profile-bridge-state.json") `
                -Raw | ConvertFrom-Json).owner
        } catch {}
        $currentUser = [Security.Principal.WindowsIdentity]::GetCurrent().Name
        if ($Mode -ne "stop" -or -not $stateOwner.Equals($currentUser, [StringComparison]::OrdinalIgnoreCase)) {
            throw "Profile '$Id' belongs to $([string]$profile.entry.owner). Sign in to that Windows account to control its Bridge."
        }
    }
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

function Set-SteamWebApiKey([string]$Id, [switch]$FromStdin,
        [switch]$ProtectedFromEnvironment) {
    $phase = "start"
    Write-SteamWebApiDiagnostic $Id $phase
    try {
        $profile = Resolve-Profile $Id
        $phase = "profile_resolved"
        Write-SteamWebApiDiagnostic $Id $phase
        if (-not (Test-CurrentProfileOwner $profile.entry) -or
            [string]::IsNullOrWhiteSpace($profile.root)) {
            throw "Sign in to the Windows account that owns profile '$Id' to configure Steam."
        }
        $phase = "owner_verified"
        Write-SteamWebApiDiagnostic $Id $phase
        $protected = ""
        if ($ProtectedFromEnvironment) {
            $protected = [Environment]::GetEnvironmentVariable(
                "MOONWAKER_STEAM_WEB_API_PROTECTED", "Process")
            Remove-Item Env:MOONWAKER_STEAM_WEB_API_PROTECTED -ErrorAction SilentlyContinue
            if ([string]::IsNullOrWhiteSpace($protected)) {
                throw "Host Control did not provide a protected Steam Web API key."
            }
            $phase = "protected_value_received"
            Write-SteamWebApiDiagnostic $Id $phase
            $protected = $protected.Trim()
            $plain = Unprotect-ForCurrentUser $protected
            $phase = "dpapi_decrypted"
            Write-SteamWebApiDiagnostic $Id $phase
        } elseif ($FromStdin) {
            $plain = [Console]::In.ReadLine()
        } else {
            $secret = Read-Host "Steam Web API key (hidden)" -AsSecureString
            $credential = [pscredential]::new("steam", $secret)
            $plain = $credential.GetNetworkCredential().Password
        }
        if ($plain -notmatch '^[A-Fa-f0-9]{32}$') {
            throw "Steam Web API key must contain exactly 32 hexadecimal characters."
        }
        $phase = "key_validated"
        Write-SteamWebApiDiagnostic $Id $phase
        $path = Join-Path (Get-GameProviderDirectory $profile.root) "steam-web-api-key.dpapi"
        New-Item -ItemType Directory -Path (Split-Path -Parent $path) -Force | Out-Null
        $phase = "directory_ready"
        Write-SteamWebApiDiagnostic $Id $phase
        if ([string]::IsNullOrWhiteSpace($protected)) {
            $protected = Protect-ForCurrentUser $plain
        }
        Set-Content -LiteralPath $path -Value $protected -Encoding ASCII
        $phase = "secret_written"
        Write-SteamWebApiDiagnostic $Id $phase
        if (-not (Test-SteamConnection $profile.root)) {
            Remove-Item -LiteralPath $path -Force -ErrorAction SilentlyContinue
            throw "Windows DPAPI could not verify the saved Steam Web API key."
        }
        $phase = "secret_verified"
        Write-SteamWebApiDiagnostic $Id $phase
        $plain = $null
        $phase = "completed"
        Write-SteamWebApiDiagnostic $Id $phase
        return [ordered]@{ ok = $true; profile_id = $Id; configured = $true; connected = $true }
    } catch {
        Write-SteamWebApiDiagnostic $Id ("failed_at_" + $phase + "_" + $_.Exception.GetType().Name)
        throw
    } finally {
        $plain = $null
    }
}

function Disconnect-Steam([string]$Id) {
    $profile = Resolve-Profile $Id
    if (-not (Test-CurrentProfileOwner $profile.entry) -or
        [string]::IsNullOrWhiteSpace($profile.root)) {
        throw "Sign in to the Windows account that owns profile '$Id' to disconnect Steam."
    }
    Remove-Item -LiteralPath (Join-Path (Get-GameProviderDirectory $profile.root) `
        "steam-web-api-key.dpapi") `
        -Force -ErrorAction SilentlyContinue
    return [ordered]@{ ok = $true; profile_id = $Id; connected = $false }
}

function Connect-Epic([string]$Id) {
    $profile = Resolve-Profile $Id
    if (-not (Test-CurrentProfileOwner $profile.entry) -or
        [string]::IsNullOrWhiteSpace($profile.root)) {
        throw "Sign in to the Windows account that owns profile '$Id' to connect Epic."
    }
    $executable = Get-LegendaryExecutable
    if ([string]::IsNullOrWhiteSpace($executable)) {
        throw "Legendary is missing. Update MoonWaker Host with the current installer."
    }
    $state = Join-Path $profile.root "state\legendary"
    New-Item -ItemType Directory -Path $state -Force | Out-Null
    $previous = $env:LEGENDARY_CONFIG_PATH
    try {
        $env:LEGENDARY_CONFIG_PATH = $state
        $process = Start-Process -FilePath $executable -ArgumentList @("auth") `
            -WorkingDirectory (Split-Path -Parent $executable) -PassThru -Wait
        if ($process.ExitCode -ne 0) {
            throw "Legendary authentication ended with code $($process.ExitCode)."
        }
    } finally { $env:LEGENDARY_CONFIG_PATH = $previous }
    if (-not (Test-LegendaryConnection $profile.root)) {
        throw "Legendary did not confirm an Epic connection."
    }
    return [ordered]@{ ok = $true; profile_id = $Id; connected = $true }
}

function Disconnect-Epic([string]$Id) {
    $profile = Resolve-Profile $Id
    if (-not (Test-CurrentProfileOwner $profile.entry) -or
        [string]::IsNullOrWhiteSpace($profile.root)) {
        throw "Sign in to the Windows account that owns profile '$Id' to disconnect Epic."
    }
    $executable = Get-LegendaryExecutable
    if ([string]::IsNullOrWhiteSpace($executable)) {
        throw "Legendary is missing. Update MoonWaker Host with the current installer."
    }
    $state = Join-Path $profile.root "state\legendary"
    $previous = $env:LEGENDARY_CONFIG_PATH
    try {
        $env:LEGENDARY_CONFIG_PATH = $state
        $null = & $executable auth --delete 2>&1
        if ($LASTEXITCODE -ne 0) {
            throw "Legendary could not remove the Epic authentication."
        }
    } finally { $env:LEGENDARY_CONFIG_PATH = $previous }
    if (Test-LegendaryConnection $profile.root) {
        throw "Legendary still reports an active Epic connection."
    }
    return [ordered]@{ ok = $true; profile_id = $Id; connected = $false }
}

function Set-PlayniteConnection([string]$Id, [bool]$Enabled, [string]$Directory = "") {
    $profile = Resolve-Profile $Id
    if (-not (Test-CurrentProfileOwner $profile.entry) -or
        [string]::IsNullOrWhiteSpace($profile.root)) {
        throw "Sign in to the Windows account that owns profile '$Id' to change Playnite."
    }
    $providerDirectory = Get-GameProviderDirectory $profile.root
    $configPath = Join-Path $providerDirectory "config.json"
    if (-not (Test-Path -LiteralPath $configPath -PathType Leaf)) {
        throw "Game Provider Bridge is missing. Update MoonWaker Host first."
    }
    $config = Get-Content -LiteralPath $configPath -Raw | ConvertFrom-Json
    if ($Enabled) {
        if ([string]::IsNullOrWhiteSpace($Directory)) {
            throw "Select the Playnite installation directory."
        }
        $resolved = [IO.Path]::GetFullPath($Directory)
        $desktop = Join-Path $resolved "Playnite.DesktopApp.exe"
        $fullscreen = Join-Path $resolved "Playnite.FullscreenApp.exe"
        $connector = Join-Path $resolved "Extensions\SunshinePlaynite\SunshinePlaynite.psm1"
        foreach ($path in @($desktop, $fullscreen, $connector)) {
            if (-not (Test-Path -LiteralPath $path -PathType Leaf)) {
                throw "The selected directory does not contain Playnite and SunshinePlaynite Connector."
            }
        }
        foreach ($setting in @{
            playnite_enabled = $true
            playnite_desktop_executable = $desktop
            playnite_fullscreen_executable = $fullscreen
        }.GetEnumerator()) {
            if ($null -eq $config.PSObject.Properties[$setting.Key]) {
                $config | Add-Member -NotePropertyName $setting.Key -NotePropertyValue $setting.Value
            } else { $config.($setting.Key) = $setting.Value }
        }
        $patch = Join-Path $providerDirectory "Install-WakePlayConnectorPatch.ps1"
        if (-not (Test-Path -LiteralPath $patch -PathType Leaf)) {
            throw "The installed Playnite connector patch is missing."
        }
        & $patch -PlayniteDirectory $resolved
        $config | ConvertTo-Json -Depth 20 |
            Set-Content -LiteralPath $configPath -Encoding UTF8
    } else {
        foreach ($setting in @{
            playnite_enabled = $false
            playnite_desktop_executable = ""
            playnite_fullscreen_executable = ""
        }.GetEnumerator()) {
            if ($null -eq $config.PSObject.Properties[$setting.Key]) {
                $config | Add-Member -NotePropertyName $setting.Key -NotePropertyValue $setting.Value
            } else { $config.($setting.Key) = $setting.Value }
        }
        $config | ConvertTo-Json -Depth 20 |
            Set-Content -LiteralPath $configPath -Encoding UTF8
    }
    Restart-Profile $Id
    return [ordered]@{ ok = $true; profile_id = $Id; connected = $Enabled }
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
        "Wake & Play Game Provider Bridge ($Id)",
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
        "RestartGateway" { Stop-Gateway; Start-Gateway; [ordered]@{ ok = $true } }
        "PairGateway" { [ordered]@{ ok = $true; pairing_code = Set-PairingCode; expires_minutes = 10 } }
        "StartProfile" {
            Invoke-ProfileControl $ProfileId "start"
            if (-not (Wait-ProfileState $ProfileId $true)) { throw "Profile '$ProfileId' did not start." }
            [ordered]@{ ok = $true }
        }
        "StopProfile" {
            Invoke-ProfileControl $ProfileId "stop"
            if (-not (Wait-ProfileState $ProfileId $false)) { throw "Profile '$ProfileId' did not stop." }
            [ordered]@{ ok = $true }
        }
        "RestartProfile" { Restart-Profile $ProfileId; [ordered]@{ ok = $true } }
        "RecoverAll" { Recover-All; [ordered]@{ ok = $true } }
        "ConfigureSteamWebApi" { Set-SteamWebApiKey -Id $ProfileId `
            -FromStdin:$SteamWebApiKeyFromStdin `
            -ProtectedFromEnvironment:$SteamWebApiKeyProtectedFromEnvironment }
        "DisconnectSteam" { Disconnect-Steam $ProfileId }
        "ConnectEpic" { Connect-Epic $ProfileId }
        "DisconnectEpic" { Disconnect-Epic $ProfileId }
        "ConnectPlaynite" { Set-PlayniteConnection $ProfileId $true $PlayniteDirectory }
        "DisconnectPlaynite" { Set-PlayniteConnection $ProfileId $false }
        "ClearDiscord" { Clear-DiscordData $ProfileId; [ordered]@{ ok = $true } }
        "ClearDiscordMachine" { $RemoveMachineDiscordApplication = $true; Clear-DiscordData $ProfileId; [ordered]@{ ok = $true } }
        "RemoveProfile" { Remove-Profile $ProfileId; [ordered]@{ ok = $true } }
        "ExportDiagnostics" { Export-Diagnostics }
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
