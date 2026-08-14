#requires -Version 5.1
[CmdletBinding()]
param(
    [string]$ConfigPath = ""
)

$ErrorActionPreference = "Stop"
$script:Root = $PSScriptRoot
if ([string]::IsNullOrWhiteSpace($ConfigPath)) { $ConfigPath = Join-Path $script:Root "config.json" }
$script:LogDirectory = Join-Path $script:Root "logs"
$script:ExportDirectory = Join-Path $script:Root "exports"
New-Item -ItemType Directory -Path $script:LogDirectory, $script:ExportDirectory -Force | Out-Null
$script:LogPath = Join-Path $script:LogDirectory ("bridge_{0}.log" -f (Get-Date -Format "yyyyMMdd"))

function Write-BridgeLog {
    param([string]$Message, [ValidateSet("DEBUG", "INFO", "WARN", "ERROR")][string]$Level = "INFO")
    $line = "{0:yyyy-MM-dd HH:mm:ss.fff} [{1}] {2}" -f (Get-Date), $Level, $Message
    Add-Content -LiteralPath $script:LogPath -Value $line -Encoding UTF8
    if ($Host.Name -notmatch "ServerRemoteHost") { Write-Verbose $line }
}

function ConvertFrom-ProtectedText {
    param([Parameter(Mandatory)][string]$Path)
    $encryptedText = (Get-Content -LiteralPath $Path -Raw).Trim()
    $encrypted = $encryptedText | ConvertTo-SecureString
    $credential = [pscredential]::new("token", $encrypted)
    return $credential.GetNetworkCredential().Password
}

function ConvertTo-JsonSafe {
    param($Value, [int]$Depth = 12)
    return ($Value | ConvertTo-Json -Depth $Depth -Compress)
}

function Get-PropertyValue {
    param($Object, [string[]]$Names, $Default = $null)
    if ($null -eq $Object) { return $Default }
    foreach ($name in $Names) {
        $property = $Object.PSObject.Properties[$name]
        if ($null -ne $property -and $null -ne $property.Value) { return $property.Value }
    }
    return $Default
}

if (-not (Test-Path -LiteralPath $ConfigPath)) {
    throw "Missing configuration: $ConfigPath. Run Configure-VibepolloBridge.ps1 first."
}
$script:Config = Get-Content -LiteralPath $ConfigPath -Raw | ConvertFrom-Json
$script:BaseUrl = ([string]$script:Config.base_url).TrimEnd('/')
$script:ListenPort = [int](Get-PropertyValue $script:Config @("listen_port") 8775)
$script:TokenPath = Join-Path $script:Root "api_token.dpapi"
if (-not (Test-Path -LiteralPath $script:TokenPath)) { throw "Missing encrypted API token: $script:TokenPath" }
$script:ApiToken = ConvertFrom-ProtectedText -Path $script:TokenPath

$script:TransportPath = Join-Path $script:Root "VibepolloTransport.py"
if (-not (Test-Path -LiteralPath $script:TransportPath)) { throw "Missing TLS transport: $script:TransportPath" }
$script:PythonExe = $null
$script:PythonPrefix = ""
if ($script:Config.PSObject.Properties["python_path"] -and $script:Config.python_path) {
    $script:PythonExe = [string]$script:Config.python_path
} else {
    $python = Get-Command python.exe -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($python) { $script:PythonExe = $python.Source }
    else {
        $launcher = Get-Command py.exe -ErrorAction SilentlyContinue | Select-Object -First 1
        if ($launcher) { $script:PythonExe = $launcher.Source; $script:PythonPrefix = "-3 " }
    }
}
if (-not $script:PythonExe -or -not (Test-Path -LiteralPath $script:PythonExe)) {
    throw "Python 3 was not found. Install Python 3 or set python_path in config.json."
}

$script:Cache = @{}
$script:CacheTime = @{}
$script:DeniedKeys = @{}
$script:LastApiError = ""
$script:LastApiSuccess = $null
$script:ApiUnavailableUntil = [datetime]::MinValue
$script:StartedAt = Get-Date
$script:SessionSamples = @{}
$script:SnapshotCache = $null
$script:SnapshotCacheTime = [datetime]::MinValue
$script:DiagnosticsCache = $null
$script:DiagnosticsCacheTime = [datetime]::MinValue
$script:MoonWakerClientPermissions = 0x07001F00 # list, view, launch and all input devices

function Invoke-VibepolloApi {
    param(
        [Parameter(Mandatory)][string]$Path,
        [ValidateSet("GET", "POST", "PATCH", "DELETE")][string]$Method = "GET",
        $Body = $null,
        [switch]$Binary,
        [switch]$RawText
    )
    $transportRequest = [ordered]@{
        base_url = $script:BaseUrl; path = $Path; method = $Method
        token = $script:ApiToken; body = $Body
    }
    try {
        $startInfo = [Diagnostics.ProcessStartInfo]::new()
        $startInfo.FileName = $script:PythonExe
        $startInfo.Arguments = $script:PythonPrefix + "-u `"$script:TransportPath`""
        $startInfo.UseShellExecute = $false
        $startInfo.CreateNoWindow = $true
        $startInfo.RedirectStandardInput = $true
        $startInfo.RedirectStandardOutput = $true
        $startInfo.RedirectStandardError = $true
        $process = [Diagnostics.Process]::new()
        $process.StartInfo = $startInfo
        [void]$process.Start()
        $stdoutTask = $process.StandardOutput.ReadToEndAsync()
        $stderrTask = $process.StandardError.ReadToEndAsync()
        $process.StandardInput.Write((ConvertTo-JsonSafe $transportRequest 8))
        $process.StandardInput.Close()
        if (-not $process.WaitForExit(2000)) {
            try { $process.Kill() } catch {}
            throw "TLS transport timed out"
        }
        $stdout = $stdoutTask.GetAwaiter().GetResult()
        $stderr = $stderrTask.GetAwaiter().GetResult()
        if (-not $stdout) { throw "TLS transport returned no data. $stderr" }
        $response = $stdout | ConvertFrom-Json
        if (-not $response.ok) { throw "HTTP $($response.status): $($response.error)" }
        $script:LastApiError = ""
        $script:LastApiSuccess = Get-Date
        $script:ApiUnavailableUntil = [datetime]::MinValue
        if ([string]::IsNullOrWhiteSpace([string]$response.content)) { return [pscustomobject]@{ status = $true } }
        if ($RawText) { return [string]$response.content }
        return ([string]$response.content | ConvertFrom-Json)
    }
    catch {
        $script:LastApiError = "API $Method $Path failed: $($_.Exception.Message)"
        if ($_.Exception.Message -match 'HTTP 0|timed out|TimeoutError|ConnectionRefused') {
            $script:ApiUnavailableUntil = (Get-Date).AddSeconds(5)
        }
        Write-BridgeLog $script:LastApiError "WARN"
        throw $script:LastApiError
    }
}

function Get-CachedApi {
    param([string]$Key, [string]$Path, [double]$TtlSeconds, [switch]$Force, [switch]$RawText)
    $now = Get-Date
    if ($now -lt $script:ApiUnavailableUntil) {
        return $(if ($script:Cache.ContainsKey($Key)) { $script:Cache[$Key] } else { $null })
    }
    if ($script:DeniedKeys.ContainsKey($Key)) { return $null }
    $fresh = $script:Cache.ContainsKey($Key) -and $script:CacheTime.ContainsKey($Key) -and
        (($now - $script:CacheTime[$Key]).TotalSeconds -lt $TtlSeconds)
    if (-not $Force -and $fresh) { return $script:Cache[$Key] }
    try {
        $value = Invoke-VibepolloApi -Path $Path -RawText:$RawText
        $script:Cache[$Key] = $value
        $script:CacheTime[$Key] = $now
        return $value
    }
    catch {
        if ($_.Exception.Message -match 'HTTP 403') {
            $script:DeniedKeys[$Key] = $true
            Write-BridgeLog "Disabling unavailable optional source '$Key' until bridge restart" "INFO"
        }
        $script:Cache[$Key] = $null
        $script:CacheTime[$Key] = $now
        if ($script:Cache.ContainsKey($Key)) { return $script:Cache[$Key] }
        return $null
    }
}

function Convert-Collection {
    param($Value)
    if ($null -eq $Value) { return @() }
    if ($Value -is [System.Array]) { return @($Value) }
    return @($Value)
}

function Get-WgcDiagnostics {
    $result = [ordered]@{
        available = $false; capture_fps = $null; publish_fps = $null; target_fps = $null
        extra_latency_ms = $null; empty_drops = $null; delivery_replaced = $null
        scratch_dropped = $null; slow_context = $null; slow_mutex = $null; age_seconds = $null
    }
    try {
        $logDirectories = @(
            (Join-Path $env:APPDATA "Sunshine\logs"),
            (Join-Path $env:ProgramData "Sunshine\logs")
        ) | Where-Object { Test-Path -LiteralPath $_ }
        $file = $logDirectories | ForEach-Object {
            Get-ChildItem -LiteralPath $_ -Filter "sunshine_wgc_helper-*.log" -File -ErrorAction SilentlyContinue
        } | Sort-Object LastWriteTime -Descending | Select-Object -First 1
        if (-not $file) { return [pscustomobject]$result }
        $lines = Get-Content -LiteralPath $file.FullName -Tail 80 -ErrorAction Stop
        $configLine = $lines | Where-Object { $_ -match "Received config data:" } | Select-Object -Last 1
        $diagLine = $lines | Where-Object { $_ -match "WGC capture diagnostics:" } | Select-Object -Last 1
        if ($configLine -match "target_fps\s*[:=]\s*([0-9.]+)") { $result.target_fps = [double]$Matches[1] }
        if ($diagLine) {
            foreach ($pair in [regex]::Matches($diagLine, "([a-z_]+)=([0-9.]+)")) {
                $key = $pair.Groups[1].Value
                $value = [double]$pair.Groups[2].Value
                if ($result.Contains($key)) { $result[$key] = $value }
            }
            $result.available = $true
            $result.age_seconds = [math]::Round(((Get-Date) - $file.LastWriteTime).TotalSeconds, 1)
        }
    } catch { Write-BridgeLog "WGC diagnostics failed: $($_.Exception.Message)" "DEBUG" }
    return [pscustomobject]$result
}

function Get-RecentWarnings {
    param($LogsResponse)
    $text = ""
    if ($LogsResponse -is [string]) { $text = $LogsResponse }
    elseif ($null -ne $LogsResponse) {
        $text = [string](Get-PropertyValue $LogsResponse @("logs", "content", "log") "")
    }
    if (-not $text) { return @() }
    $rows = $text -split "`r?`n" |
        Where-Object { $_ -match "\]: (Warning|Error|Fatal):" } |
        ForEach-Object { ($_ -replace '^.*?\]:\s*', '') } |
        Select-Object -Unique |
        Select-Object -Last 8
    return @($rows)
}

function Normalize-Apps {
    param($Response)
    $current = [string](Get-PropertyValue $Response @("current_app") "")
    $apps = Convert-Collection (Get-PropertyValue $Response @("apps") @())
    return @($apps | ForEach-Object {
        $uuid = [string](Get-PropertyValue $_ @("uuid", "id") "")
        [pscustomobject]@{
            uuid = $uuid
            name = [string](Get-PropertyValue $_ @("name") "Unnamed application")
            running = ($uuid -ne "" -and $uuid -eq $current)
            playnite = [bool](Get-PropertyValue $_ @("playnite-id") $false)
        }
    } | Sort-Object @{ Expression = "running"; Descending = $true }, name)
}

function Normalize-Clients {
    param($Response, $RtspSessions)
    $sessions = Convert-Collection (Get-PropertyValue $RtspSessions @("sessions") @())
    $connectedByUuid = @{}
    foreach ($session in $sessions) {
        $uuid = [string](Get-PropertyValue $session @("uuid", "client_uuid") "")
        if ($uuid) { $connectedByUuid[$uuid] = $session }
    }
    $raw = Convert-Collection (Get-PropertyValue $Response @("named_certs", "clients") @())
    return @($raw | ForEach-Object {
        $uuid = [string](Get-PropertyValue $_ @("uuid", "id") "")
        $session = if ($connectedByUuid.ContainsKey($uuid)) { $connectedByUuid[$uuid] } else { $null }
        [pscustomobject]@{
            uuid = $uuid
            name = [string](Get-PropertyValue $_ @("name", "device_name") "Unnamed client")
            connected = ($null -ne $session)
            width = Get-PropertyValue $session @("width") $null
            height = Get-PropertyValue $session @("height") $null
            fps = Get-PropertyValue $session @("fps") $null
            codec = Get-PropertyValue $session @("codec") ""
            hdr = [bool](Get-PropertyValue $session @("hdr") $false)
        }
    } | Sort-Object @{ Expression = "connected"; Descending = $true }, name)
}

function Get-QualityVerdict {
    param($Session, $ActiveHistory, $Wgc)
    if ($null -eq $Session) { return [pscustomobject]@{ level = "idle"; label = "No active stream"; reasons = @() } }
    $limitedTelemetry = [bool](Get-PropertyValue $Session @("telemetry_limited") $false)
    $target = [double](Get-PropertyValue $ActiveHistory @("target_fps") (Get-PropertyValue $Session @("fps") 0))
    $actual = [double](Get-PropertyValue $ActiveHistory @("actual_fps") 0)
    $latency = [double](Get-PropertyValue $ActiveHistory @("encode_latency_ms") (Get-PropertyValue $Session @("encode_latency_ms") 0))
    $jitter = [double](Get-PropertyValue $ActiveHistory @("frame_interval_jitter_ms") 0)
    $losses = [double](Get-PropertyValue $ActiveHistory @("client_reported_losses") (Get-PropertyValue $Session @("client_reported_losses") 0))
    $reasons = @()
    $level = "good"
    if (($target -gt 0 -and $actual -gt 0 -and $actual -lt ($target * 0.85)) -or $latency -gt 20 -or $losses -gt 50) {
        $level = "bad"
    } elseif (($target -gt 0 -and $actual -gt 0 -and $actual -lt ($target * 0.95)) -or $latency -gt 10 -or $jitter -gt 3 -or $losses -gt 0) {
        $level = "warning"
    }
    if ($target -gt 0 -and $actual -gt 0 -and $actual -lt ($target * 0.95)) { $reasons += "Actual FPS below target" }
    if ($latency -gt 10) { $reasons += "High encode latency" }
    if ($jitter -gt 3) { $reasons += "Frame pacing jitter" }
    if ($losses -gt 0) { $reasons += "Client reported packet loss" }
    if ($Wgc.available -and $Wgc.target_fps -and $Wgc.publish_fps -lt ($Wgc.target_fps * 0.95)) { $reasons += "WGC publish FPS below target" }
    if ($limitedTelemetry) {
        $reasons += "Detailed RTSP telemetry unavailable; using session status and WGC"
        if ($level -eq "good") { $level = "warning" }
    }
    $label = if ($limitedTelemetry) { "Active - limited metrics" } else { @{ good = "Good"; warning = "Needs attention"; bad = "Poor" }[$level] }
    return [pscustomobject]@{ level = $level; label = $label; reasons = $reasons }
}

function Get-DerivedSessionMetrics {
    param($Session)
    if ($null -eq $Session) { return $null }
    $uuid = [string](Get-PropertyValue $Session @("uuid", "id") "")
    if (-not $uuid) { return $null }
    $now = Get-Date
    $frames = [double](Get-PropertyValue $Session @("frames_sent") 0)
    $bytes = [double](Get-PropertyValue $Session @("bytes_sent") 0)
    $derived = [pscustomobject]@{ actual_fps = $null; actual_bitrate_kbps = $null }
    if ($script:SessionSamples.ContainsKey($uuid)) {
        $previous = $script:SessionSamples[$uuid]
        $elapsed = ($now - $previous.time).TotalSeconds
        if ($elapsed -gt 0.25) {
            $frameDelta = $frames - $previous.frames
            $byteDelta = $bytes - $previous.bytes
            if ($frameDelta -ge 0) { $derived.actual_fps = [math]::Round($frameDelta / $elapsed, 1) }
            if ($byteDelta -ge 0) { $derived.actual_bitrate_kbps = [math]::Round(($byteDelta * 8) / $elapsed / 1000, 1) }
        }
    }
    $script:SessionSamples[$uuid] = [pscustomobject]@{ time = $now; frames = $frames; bytes = $bytes }
    return $derived
}

function Build-Snapshot {
    param([switch]$Force)
    $metadata = Get-CachedApi "metadata" "/api/metadata" 60 -Force:$Force
    $sessionStatus = Get-CachedApi "session" "/api/session/status" 2 -Force:$Force
    $hostStats = Get-CachedApi "hoststats" "/api/host/stats" 2 -Force:$Force
    $hostInfo = Get-CachedApi "hostinfo" "/api/host/info" 300 -Force:$Force
    $rtsp = Get-CachedApi "rtsp" "/api/rtsp/sessions" 2 -Force:$Force
    $webrtc = Get-CachedApi "webrtc" "/api/webrtc/sessions" 3 -Force:$Force
    $active = Get-CachedApi "activehistory" "/api/history/sessions/active" 2 -Force:$Force
    $appsRaw = Get-CachedApi "apps" "/api/apps" 12 -Force:$Force
    $clientsRaw = Get-CachedApi "clients" "/api/clients/list" 12 -Force:$Force
    $rtss = Get-CachedApi "rtss" "/api/rtss/status" 15 -Force:$Force
    $lossless = Get-CachedApi "lossless" "/api/lossless_scaling/status" 15 -Force:$Force
    # Vibepollo serves /api/logs as UTF-8 text, not as a JSON document.
    $logs = Get-CachedApi "logs" "/api/logs" 15 -Force:$Force -RawText

    $rtspSessions = Convert-Collection (Get-PropertyValue $rtsp @("sessions") @())
    $webSessions = Convert-Collection (Get-PropertyValue $webrtc @("sessions") @())
    $activeSessions = Convert-Collection (Get-PropertyValue $active @("sessions") @())
    $statusActiveCount = [int](Get-PropertyValue $sessionStatus @("activeSessions", "active_sessions", "sessionCount") 0)
    $stream = if ($rtspSessions.Count -gt 0) { $rtspSessions[0] } elseif ($webSessions.Count -gt 0) { $webSessions[0] } else { $null }
    if ($null -eq $stream -and $statusActiveCount -gt 0) {
        # Some Vibepollo builds report the active Moonlight connection through
        # /api/session/status before (or instead of) exposing detailed RTSP telemetry.
        # Preserve the correct active state and enrich it with WGC data below.
        $stream = [pscustomobject]@{
            uuid = "session-status-fallback"
            device_name = "Moonlight client"
            state = "running"
            telemetry_limited = $true
            width = $null; height = $null; fps = $null; codec = ""; hdr = $false
            frames_sent = 0; bytes_sent = 0; uptime_seconds = $null
        }
    }
    $history = if ($activeSessions.Count -gt 0) { $activeSessions[0] } else { $null }
    $derived = if ([bool](Get-PropertyValue $stream @("telemetry_limited") $false)) { $null } else { Get-DerivedSessionMetrics $stream }
    if ($null -eq $history -and $null -ne $stream) { $history = [pscustomobject]@{} }
    if ($null -ne $history -and $null -ne $derived) {
        $historyFps = [double](Get-PropertyValue $history @("actual_fps") 0)
        $historyBitrate = [double](Get-PropertyValue $history @("actual_bitrate_kbps") 0)
        if ($historyFps -le 0 -and $null -ne $derived.actual_fps) {
            $history | Add-Member -NotePropertyName actual_fps -NotePropertyValue $derived.actual_fps -Force
        }
        if ($historyBitrate -le 0 -and $null -ne $derived.actual_bitrate_kbps) {
            $history | Add-Member -NotePropertyName actual_bitrate_kbps -NotePropertyValue $derived.actual_bitrate_kbps -Force
        }
        if ($null -eq $history.PSObject.Properties["target_fps"]) {
            $history | Add-Member -NotePropertyName target_fps -NotePropertyValue (Get-PropertyValue $stream @("fps") $null)
        }
        foreach ($field in @("encode_latency_ms", "client_reported_losses", "idr_requests")) {
            if ($null -eq $history.PSObject.Properties[$field]) {
                $history | Add-Member -NotePropertyName $field -NotePropertyValue (Get-PropertyValue $stream @($field) $null)
            }
        }
    }
    $wgc = Get-WgcDiagnostics
    if ($null -ne $history -and [bool](Get-PropertyValue $stream @("telemetry_limited") $false) -and $wgc.available) {
        if ($null -eq $history.PSObject.Properties["target_fps"] -and $null -ne $wgc.target_fps) {
            $history | Add-Member -NotePropertyName target_fps -NotePropertyValue $wgc.target_fps
        }
    }
    $quality = Get-QualityVerdict $stream $history $wgc
    $apps = Normalize-Apps $appsRaw
    $clients = Normalize-Clients $clientsRaw $rtsp
    $appName = [string](Get-PropertyValue $sessionStatus @("appName") "")
    if (-not $appName -and $history) { $appName = [string](Get-PropertyValue $history @("app_name") "") }

    return [pscustomobject]@{
        generated_at = [DateTimeOffset]::Now.ToUnixTimeMilliseconds()
        bridge = [pscustomobject]@{
            online = $true; uptime_seconds = [math]::Round(((Get-Date) - $script:StartedAt).TotalSeconds)
            api_error = $script:LastApiError; base_url = $script:BaseUrl
        }
        host = [pscustomobject]@{
            online = ($null -ne $metadata -or $null -ne $sessionStatus -or $null -ne $hostStats -or $null -ne $appsRaw)
            version = [string](Get-PropertyValue $metadata @("version") "unknown")
            platform = [string](Get-PropertyValue $metadata @("platform") "")
            cpu_model = [string](Get-PropertyValue $hostInfo @("cpu_model") "")
            gpu_model = [string](Get-PropertyValue $hostInfo @("gpu_model") "")
            net_interface = [string](Get-PropertyValue $hostInfo @("net_interface") "")
            net_link_speed_mbps = Get-PropertyValue $hostInfo @("net_link_speed_mbps") $null
            stats = $hostStats
        }
        session = [pscustomobject]@{
            active = ($null -ne $stream)
            count = [math]::Max(($rtspSessions.Count + $webSessions.Count), $statusActiveCount)
            app_running = [bool](Get-PropertyValue $sessionStatus @("appRunning") $false)
            app_name = $appName
            paused = [bool](Get-PropertyValue $sessionStatus @("paused") $false)
            stream = $stream
            history = $history
        }
        quality = [pscustomobject]@{ verdict = $quality; wgc = $wgc; warnings = @(Get-RecentWarnings $logs) }
        apps = $apps
        clients = $clients
        integrations = [pscustomobject]@{ rtss = $rtss; lossless_scaling = $lossless }
    }
}

function Get-VibepolloClientRecords {
    $response = Invoke-VibepolloApi "/api/clients/list"
    return @(Convert-Collection (Get-PropertyValue $response @("named_certs", "clients") @()))
}

function New-ClientUpdatePayload {
    param([Parameter(Mandatory)]$Client, [Parameter(Mandatory)][string]$Name)
    return [ordered]@{
        uuid = [string](Get-PropertyValue $Client @("uuid") "")
        name = $Name
        perm = [uint32]$script:MoonWakerClientPermissions
        enable_legacy_ordering = [bool](Get-PropertyValue $Client @("enable_legacy_ordering") $true)
        allow_client_commands = [bool](Get-PropertyValue $Client @("allow_client_commands") $true)
        do = @(Convert-Collection (Get-PropertyValue $Client @("do") @()))
        undo = @(Convert-Collection (Get-PropertyValue $Client @("undo") @()))
        display_mode = [string](Get-PropertyValue $Client @("display_mode") "")
        output_name_override = [string](Get-PropertyValue $Client @("output_name_override") "")
        always_use_virtual_display = [bool](Get-PropertyValue $Client @("always_use_virtual_display") $false)
        virtual_display_mode = [string](Get-PropertyValue $Client @("virtual_display_mode") "")
        virtual_display_layout = [string](Get-PropertyValue $Client @("virtual_display_layout") "")
        prefer_10bit_sdr = [bool](Get-PropertyValue $Client @("prefer_10bit_sdr") $false)
        hdr_profile = [string](Get-PropertyValue $Client @("hdr_profile") "")
        config_overrides = Get-PropertyValue $Client @("config_overrides") ([pscustomobject]@{})
    }
}

function Pair-MoonWakerClient {
    param([Parameter(Mandatory)][string]$Pin, [Parameter(Mandatory)][string]$Name)
    if ($Pin -notmatch '^[0-9]{4}$') { throw "Pairing PIN must contain four digits" }
    $safeName = $Name.Trim()
    if (-not $safeName -or $safeName.Length -gt 80 -or $safeName -match '[\x00-\x1f\x7f]') {
        throw "Invalid pairing client name"
    }

    $before = @{}
    foreach ($client in @(Get-VibepolloClientRecords)) {
        $uuid = [string](Get-PropertyValue $client @("uuid") "")
        if ($uuid) { $before[$uuid] = $true }
    }
    $accepted = Invoke-VibepolloApi "/api/pin" POST @{ pin = $Pin; name = $safeName }
    if (-not [bool](Get-PropertyValue $accepted @("status") $false)) {
        throw "Vibepollo did not accept the pending Moonlight pairing PIN"
    }

    $deadline = (Get-Date).AddSeconds(10)
    $pairedClient = $null
    do {
        Start-Sleep -Milliseconds 150
        $candidates = @(Get-VibepolloClientRecords | Where-Object {
            $uuid = [string](Get-PropertyValue $_ @("uuid") "")
            -not $before.ContainsKey($uuid) -and
                [string](Get-PropertyValue $_ @("name") "") -eq $safeName
        })
        if ($candidates.Count -eq 1) { $pairedClient = $candidates[0]; break }
        if ($candidates.Count -gt 1) { throw "Vibepollo returned multiple newly paired clients" }
    } while ((Get-Date) -lt $deadline)
    if ($null -eq $pairedClient) {
        throw "The paired Moonlight client did not appear in Vibepollo before the timeout"
    }

    $payload = New-ClientUpdatePayload $pairedClient $safeName
    $updated = Invoke-VibepolloApi "/api/clients/update" POST $payload
    if (-not [bool](Get-PropertyValue $updated @("status") $false)) {
        throw "Vibepollo rejected the MoonWaker client permission update"
    }

    $verified = @(Get-VibepolloClientRecords | Where-Object {
        [string](Get-PropertyValue $_ @("uuid") "") -eq [string]$payload.uuid
    }) | Select-Object -First 1
    $actualPermissions = [uint32](Get-PropertyValue $verified @("perm") 0)
    if (($actualPermissions -band [uint32]$script:MoonWakerClientPermissions) -ne
        [uint32]$script:MoonWakerClientPermissions) {
        throw "Vibepollo did not persist the required MoonWaker client permissions"
    }
    return [pscustomobject]@{
        ok = $true
        client_uuid = [string]$payload.uuid
        permissions = $actualPermissions
    }
}

function Get-Snapshot {
    param([switch]$Force)
    $now = Get-Date
    # MoonWaker and Unified Remote may ask for snapshots at the same time.
    # Reusing the most recent complete result prevents a slow optional
    # Vibepollo endpoint from creating an unbounded single-threaded backlog.
    if ($null -ne $script:SnapshotCache -and
        (($now - $script:SnapshotCacheTime).TotalSeconds -lt 2)) {
        return $script:SnapshotCache
    }
    $script:SnapshotCache = Build-Snapshot -Force:$Force
    $script:SnapshotCacheTime = Get-Date
    return $script:SnapshotCache
}

function Get-ActiveDisplayDiagnostics {
    try {
        if (-not ([System.Management.Automation.PSTypeName]"MoonWakerDisplayProbe").Type) {
            Add-Type -TypeDefinition @"
using System;
using System.Collections.Generic;
using System.Runtime.InteropServices;

public static class MoonWakerDisplayProbe
{
    [StructLayout(LayoutKind.Sequential)]
    private struct RECT
    {
        public int Left;
        public int Top;
        public int Right;
        public int Bottom;
    }

    [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Unicode)]
    private struct MONITORINFOEX
    {
        public int Size;
        public RECT Monitor;
        public RECT Work;
        public uint Flags;
        [MarshalAs(UnmanagedType.ByValTStr, SizeConst = 32)]
        public string DeviceName;
    }

    public sealed class Display
    {
        public string Name { get; set; }
        public bool Primary { get; set; }
        public int X { get; set; }
        public int Y { get; set; }
        public int Width { get; set; }
        public int Height { get; set; }
    }

    private delegate bool MonitorCallback(
        IntPtr monitor, IntPtr deviceContext, ref RECT bounds, IntPtr data);

    [DllImport("user32.dll")]
    private static extern bool EnumDisplayMonitors(
        IntPtr deviceContext, IntPtr clip, MonitorCallback callback, IntPtr data);

    [DllImport("user32.dll", CharSet = CharSet.Unicode)]
    private static extern bool GetMonitorInfo(IntPtr monitor, ref MONITORINFOEX info);

    public static Display[] Enumerate()
    {
        var result = new List<Display>();
        MonitorCallback callback = delegate(
            IntPtr monitor, IntPtr deviceContext, ref RECT bounds, IntPtr data)
        {
            var info = new MONITORINFOEX();
            info.Size = Marshal.SizeOf(typeof(MONITORINFOEX));
            if (GetMonitorInfo(monitor, ref info) && !String.IsNullOrWhiteSpace(info.DeviceName))
            {
                result.Add(new Display {
                    Name = info.DeviceName,
                    Primary = (info.Flags & 1U) != 0,
                    X = info.Monitor.Left,
                    Y = info.Monitor.Top,
                    Width = info.Monitor.Right - info.Monitor.Left,
                    Height = info.Monitor.Bottom - info.Monitor.Top
                });
            }
            return true;
        };
        EnumDisplayMonitors(IntPtr.Zero, IntPtr.Zero, callback, IntPtr.Zero);
        return result.ToArray();
    }
}
"@ -ErrorAction Stop
        }
        $displays = @([MoonWakerDisplayProbe]::Enumerate() | ForEach-Object {
            [pscustomobject]@{
                display_name = [string]$_.Name
                primary = [bool]$_.Primary
                bounds = [pscustomobject]@{
                    x = [int]$_.X
                    y = [int]$_.Y
                    width = [int]$_.Width
                    height = [int]$_.Height
                }
            }
        })
        return [pscustomobject]@{
            ok = $true
            source = "Win32.EnumDisplayMonitors"
            value = [pscustomobject]@{ displays = $displays }
        }
    }
    catch {
        return [pscustomobject]@{
            ok = $false
            source = "Win32.EnumDisplayMonitors"
            error = "Active display enumeration is unavailable."
        }
    }
}

function Build-StreamSourceDiagnostics {
    $sources = @(
        @{ name = "session"; path = "/api/session/status" },
        @{ name = "rtsp"; path = "/api/rtsp/sessions" },
        @{ name = "webrtc"; path = "/api/webrtc/sessions" },
        @{ name = "active_history"; path = "/api/history/sessions/active" },
        @{ name = "recent_history"; path = "/api/history/sessions" },
        @{ name = "clients"; path = "/api/clients/list" }
    )
    $results = [ordered]@{}
    foreach ($source in $sources) {
        try {
            $value = Invoke-VibepolloApi -Path $source.path
            $results[$source.name] = [pscustomobject]@{
                ok = $true
                path = $source.path
                value = $value
            }
        }
        catch {
            $results[$source.name] = [pscustomobject]@{
                ok = $false
                path = $source.path
                error = $_.Exception.Message
            }
        }
    }
    $results["active_displays"] = Get-ActiveDisplayDiagnostics
    return [pscustomobject]@{
        generated_at = [DateTimeOffset]::Now.ToUnixTimeMilliseconds()
        sources = [pscustomobject]$results
        denied_cache_keys = @($script:DeniedKeys.Keys)
    }
}

function Get-StreamSourceDiagnostics {
    $now = Get-Date
    # Unified Remote polls this endpoint frequently. The source set requires
    # several authenticated API calls, so recomputing it for every poll can
    # permanently starve /health on this deliberately small loopback server.
    if ($null -ne $script:DiagnosticsCache -and
        (($now - $script:DiagnosticsCacheTime).TotalSeconds -lt 60)) {
        $script:DiagnosticsCache.sources.active_displays = Get-ActiveDisplayDiagnostics
        return $script:DiagnosticsCache
    }
    $script:DiagnosticsCache = Build-StreamSourceDiagnostics
    $script:DiagnosticsCacheTime = Get-Date
    return $script:DiagnosticsCache
}

function Send-JsonResponse {
    param([IO.Stream]$Stream, $Value, [int]$StatusCode = 200)
    $safeValue = ConvertTo-RemoteJsonSafe $Value
    $json = ConvertTo-JsonSafe $safeValue 16
    $body = [Text.Encoding]::UTF8.GetBytes($json)
    $statusText = switch ($StatusCode) {
        200 { "OK" }
        201 { "Created" }
        400 { "Bad Request" }
        404 { "Not Found" }
        405 { "Method Not Allowed" }
        409 { "Conflict" }
        default { "Internal Server Error" }
    }
    $headers = [Text.Encoding]::ASCII.GetBytes(
        "HTTP/1.1 $StatusCode $statusText`r`nContent-Type: application/json; charset=utf-8`r`nContent-Length: $($body.Length)`r`nCache-Control: no-store`r`nConnection: close`r`n`r`n")
    $Stream.Write($headers, 0, $headers.Length)
    if ($body.Length -gt 0) { $Stream.Write($body, 0, $body.Length) }
    $Stream.Flush()
}

function ConvertTo-RemoteJsonSafe {
    param($Value)
    if ($null -eq $Value) { return $null }
    if ($Value -is [string] -or $Value -is [bool]) { return $Value }
    if ($Value -is [int] -or $Value -is [int16] -or $Value -is [uint16] -or $Value -is [byte] -or $Value -is [sbyte]) {
        return $Value
    }
    if ($Value -is [long] -or $Value -is [uint64] -or $Value -is [uint32] -or $Value -is [decimal]) {
        if ([math]::Abs([double]$Value) -gt 2147483647) {
            return $Value.ToString([Globalization.CultureInfo]::InvariantCulture)
        }
        return $Value
    }
    if ($Value -is [double] -or $Value -is [single]) {
        if ([double]::IsNaN([double]$Value) -or [double]::IsInfinity([double]$Value)) { return $null }
        if ([math]::Abs([double]$Value) -gt 2147483647) {
            return ([double]$Value).ToString('R', [Globalization.CultureInfo]::InvariantCulture)
        }
        return $Value
    }
    if ($Value -is [Collections.IDictionary]) {
        $result = [ordered]@{}
        foreach ($key in $Value.Keys) { $result[[string]$key] = ConvertTo-RemoteJsonSafe $Value[$key] }
        return $result
    }
    if ($Value -is [Collections.IEnumerable] -and -not ($Value -is [string])) {
        $items = @()
        foreach ($entry in $Value) { $items += ,(ConvertTo-RemoteJsonSafe $entry) }
        return $items
    }
    if ($Value -is [psobject]) {
        $result = [ordered]@{}
        foreach ($property in $Value.PSObject.Properties) {
            if ($property.MemberType -in @('NoteProperty', 'Property', 'AliasProperty')) {
                $result[$property.Name] = ConvertTo-RemoteJsonSafe $property.Value
            }
        }
        return $result
    }
    return [string]$Value
}

function Read-HttpRequest {
    param([Net.Sockets.TcpClient]$Client)
    $stream = $Client.GetStream()
    $stream.ReadTimeout = 2000
    $stream.WriteTimeout = 2000
    $headerBytes = [IO.MemoryStream]::new()
    $tail = 0
    while ($headerBytes.Length -lt 16384) {
        $value = $stream.ReadByte()
        if ($value -lt 0) { break }
        $headerBytes.WriteByte([byte]$value)
        $tail = (($tail -shl 8) -bor $value) -band 0xffffffff
        if ($tail -eq 0x0d0a0d0a) { break }
    }
    if ($headerBytes.Length -eq 0) { return $null }
    if ($tail -ne 0x0d0a0d0a) { throw "Invalid or oversized HTTP headers" }
    $headerText = [Text.Encoding]::ASCII.GetString($headerBytes.ToArray())
    $lines = $headerText -split "`r`n"
    $requestLine = $lines[0]
    $headers = @{}
    foreach ($line in $lines | Select-Object -Skip 1) {
        if ([string]::IsNullOrWhiteSpace($line)) { continue }
        $separator = $line.IndexOf(':')
        if ($separator -gt 0) {
            $headers[$line.Substring(0, $separator).Trim().ToLowerInvariant()] =
                $line.Substring($separator + 1).Trim()
        }
    }
    $parts = $requestLine.Split(' ')
    if ($parts.Length -lt 2) { throw "Invalid HTTP request line: $requestLine" }
    $contentLength = 0
    if ($headers.ContainsKey("content-length")) {
        if (-not [int]::TryParse([string]$headers["content-length"], [ref]$contentLength) -or
            $contentLength -lt 0 -or $contentLength -gt 16384) {
            throw "Invalid or oversized HTTP request body"
        }
    }
    $bodyBytes = [byte[]]::new($contentLength)
    $offset = 0
    while ($offset -lt $contentLength) {
        $read = $stream.Read($bodyBytes, $offset, $contentLength - $offset)
        if ($read -le 0) { throw "Incomplete HTTP request body" }
        $offset += $read
    }
    $uri = [uri]("http://127.0.0.1" + $parts[1])
    Add-Type -AssemblyName System.Web
    return [pscustomobject]@{
        Method = $parts[0].ToUpperInvariant(); Path = $uri.AbsolutePath.TrimEnd('/')
        Query = [Web.HttpUtility]::ParseQueryString($uri.Query); Stream = $stream
        Body = [Text.Encoding]::UTF8.GetString($bodyBytes)
    }
}

function Get-VibepolloApps {
    $value = Invoke-VibepolloApi "/api/apps"
    $apps = Get-PropertyValue $value @("apps") $value
    return @(Convert-Collection $apps)
}

function Get-NormalizedAppName {
    param($Value)
    return (([string]$Value).Trim() -replace '\s+', ' ').ToLowerInvariant()
}

function Find-AppsByPlayniteId {
    param([object[]]$Apps, [string]$GameId)
    $matches = @()
    for ($index = 0; $index -lt $Apps.Count; $index++) {
        $app = $Apps[$index]
        $id = [string](Get-PropertyValue $app @("playnite-id", "playnite_id") "")
        if ($id.Equals($GameId, [StringComparison]::OrdinalIgnoreCase)) {
            $remoteIndex = [int](Get-PropertyValue $app @("index") $index)
            $matches += [pscustomobject]@{ app = $app; index = $remoteIndex }
        }
    }
    return @($matches)
}

function Get-PlayniteAppScore {
    param($App)
    $score = @($App.PSObject.Properties).Count
    foreach ($field in @("image-path", "playnite-icon-path", "cmd", "prep-cmd", "state-cmd")) {
        $value = Get-PropertyValue $App @($field) $null
        if ($null -ne $value -and -not [string]::IsNullOrWhiteSpace([string]$value)) {
            $score += 100
        }
    }
    return $score
}

function Select-CanonicalPlayniteApp {
    param([object[]]$Matches)
    return @($Matches | Sort-Object `
        @{ Expression = { Get-PlayniteAppScore $_.app }; Descending = $true }, `
        @{ Expression = { $_.index }; Descending = $false })[0]
}

function Remove-DuplicatePlayniteApps {
    param([object[]]$Matches, $Canonical)
    if ($Matches.Count -le 1) { return 0 }
    $canonicalUuid = [string](Get-PropertyValue $Canonical.app @("uuid") "")
    $canonicalId = [string](Get-PropertyValue $Canonical.app @("id") "")
    $duplicates = @($Matches | Where-Object {
        $uuid = [string](Get-PropertyValue $_.app @("uuid") "")
        $id = [string](Get-PropertyValue $_.app @("id") "")
        -not (($canonicalUuid -and $uuid -eq $canonicalUuid) -or
            ($canonicalId -and $id -eq $canonicalId))
    } | Sort-Object index -Descending)
    $removed = 0
    foreach ($duplicate in $duplicates) {
        try {
            [void](Invoke-VibepolloApi ("/api/apps/{0}" -f [int]$duplicate.index) DELETE)
            $removed++
        }
        catch {
            # Older/scoped tokens may not allow DELETE. Keep the canonical app and
            # report the skipped cleanup instead of making launch preparation fail.
            Write-BridgeLog "Skipped duplicate Playnite app at index $($duplicate.index): $($_.Exception.Message)" "WARN"
        }
    }
    return $removed
}

function Get-PlayniteAppStatus {
    param([string]$GameId)
    $normalizedId = $GameId.Trim().ToLowerInvariant()
    if ($normalizedId -notmatch '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$') {
        throw "Invalid Playnite game ID"
    }
    $matches = @(Find-AppsByPlayniteId @(Get-VibepolloApps) $normalizedId)
    if ($matches.Count -eq 0) {
        return [pscustomobject]@{ ok = $true; state = "missing"; playnite_game_id = $normalizedId }
    }
    $canonical = Select-CanonicalPlayniteApp $matches
    $result = New-EnsuredPlayniteAppResult $canonical $false $false 0
    $result | Add-Member -NotePropertyName state -NotePropertyValue "ready"
    $result | Add-Member -NotePropertyName duplicates `
        -NotePropertyValue ([Math]::Max(0, $matches.Count - 1))
    return $result
}

function New-EnsuredPlayniteAppResult {
    param($Match, [bool]$Created, [bool]$Updated, [int]$Deduplicated)
    $app = $Match.app
    $numericId = 0L
    [void][long]::TryParse([string](Get-PropertyValue $app @("id") "0"), [ref]$numericId)
    return [pscustomobject]@{
        ok = $true; created = $Created; updated = $Updated
        uuid = [string](Get-PropertyValue $app @("uuid") "")
        app_id = $numericId
        index = [int]$Match.index
        name = [string](Get-PropertyValue $app @("name") "")
        playnite_game_id = [string](Get-PropertyValue $app @("playnite-id", "playnite_id") "")
        deduplicated = $Deduplicated
    }
}

function Ensure-PlayniteApp {
    param([string]$GameId, [string]$Name)
    $normalizedId = $GameId.Trim().ToLowerInvariant()
    $normalizedName = $Name.Trim()
    if ($normalizedId -notmatch '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$') {
        throw "Invalid Playnite game ID"
    }
    if ([string]::IsNullOrWhiteSpace($normalizedName) -or $normalizedName.Length -gt 200 -or
        $normalizedName -match '[\x00-\x1f\x7f]') {
        throw "Invalid application name"
    }

    $apps = @(Get-VibepolloApps)
    $existing = @(Find-AppsByPlayniteId $apps $normalizedId)
    if ($existing.Count -gt 0) {
        $canonical = Select-CanonicalPlayniteApp $existing
        $managed = [string](Get-PropertyValue $canonical.app @("playnite-managed") "")
        if ($managed -in @("manual", "auto")) {
            # The native Playnite integration launches a short-lived helper process.
            # Monitoring that helper as the streamed application makes Vibepollo end a
            # healthy session a few seconds after the real game has started. Keep the
            # stream detached from the helper; game lifetime remains observable through
            # the Playnite integration itself.
            if ([bool](Get-PropertyValue $canonical.app @("auto-detach") $false)) {
                $deduplicated = Remove-DuplicatePlayniteApps $existing $canonical
                $result = New-EnsuredPlayniteAppResult $canonical $false $false $deduplicated
                $result | Add-Member -NotePropertyName state -NotePropertyValue "ready"
                return $result
            }
            $payload = ConvertTo-RemoteJsonSafe $canonical.app
            $payload["index"] = [int]$canonical.index
            $created = $false
        }
        else {
            # Older MoonWaker versions used a private marker. Vibepollo's native
            # manual marker is required so its Playnite integration enriches the
            # same record instead of publishing a second transient application.
            $payload = ConvertTo-RemoteJsonSafe $canonical.app
            $payload["index"] = [int]$canonical.index
            $payload["playnite-managed"] = "manual"
            [void]$payload.Remove("playnite-source")
            $created = $false
        }
    }
    else {
        $expectedName = Get-NormalizedAppName $normalizedName
        $sameName = @($apps | Where-Object {
            (Get-NormalizedAppName (Get-PropertyValue $_ @("name") "")) -eq $expectedName
        })
        if ($sameName.Count -gt 1) {
            throw "Multiple Vibepollo applications already use this name"
        }

        $created = $sameName.Count -eq 0
        if ($created) {
            $payload = [ordered]@{
                index = -1
                name = $normalizedName
                "playnite-id" = $normalizedId
                "playnite-managed" = "manual"
                "exit-timeout" = 10
            }
        } else {
            # Vibepollo replaces an app record on POST. Copy every legacy field first
            # so assigning the Playnite ID never resets command, image or hook settings.
            $payload = ConvertTo-RemoteJsonSafe $sameName[0]
            $payload["index"] = [array]::IndexOf($apps, $sameName[0])
            $payload["playnite-id"] = $normalizedId
            $payload["playnite-managed"] = "manual"
            [void]$payload.Remove("playnite-source")
        }
    }
    # A Playnite game is launched through a helper which exits after handing off
    # to the game. Its exit must not be treated as the end of the stream.
    $payload["auto-detach"] = $true
    [void](Invoke-VibepolloApi "/api/apps" POST $payload)
    $script:CacheTime.Clear()
    $script:SnapshotCache = $null
    $deadline = (Get-Date).AddSeconds(6)
    $canonical = $null
    $lastSignature = ""
    $stableReads = 0
    do {
        Start-Sleep -Milliseconds 350
        $updatedApps = @(Get-VibepolloApps)
        $matches = @(Find-AppsByPlayniteId $updatedApps $normalizedId)
        if ($matches.Count -gt 0) {
            $candidate = Select-CanonicalPlayniteApp $matches
            $signature = @(
                [string](Get-PropertyValue $candidate.app @("uuid") ""),
                [string](Get-PropertyValue $candidate.app @("id") ""),
                [string](Get-PropertyValue $candidate.app @("image-path") ""),
                [string](Get-PropertyValue $candidate.app @("playnite-managed") "")
            ) -join "|"
            if ($signature -eq $lastSignature) { $stableReads++ }
            else { $lastSignature = $signature; $stableReads = 1 }
            $canonical = $candidate
            if ($stableReads -ge 3) { break }
        }
    } while ((Get-Date) -lt $deadline)
    if ($null -eq $canonical) { throw "Vibepollo did not return the ensured application" }
    $matches = @(Find-AppsByPlayniteId @(Get-VibepolloApps) $normalizedId)
    $canonical = Select-CanonicalPlayniteApp $matches
    $deduplicated = Remove-DuplicatePlayniteApps $matches $canonical
    if ($deduplicated -gt 0) {
        $script:CacheTime.Clear(); $script:SnapshotCache = $null
        $canonical = Select-CanonicalPlayniteApp @(
            Find-AppsByPlayniteId @(Get-VibepolloApps) $normalizedId)
    }
    $result = New-EnsuredPlayniteAppResult $canonical $created (-not $created) $deduplicated
    $result | Add-Member -NotePropertyName state -NotePropertyValue "ready"
    return $result
}

function Invoke-Action {
    param([string]$Name, $Query)
    switch ($Name) {
        "launch" {
            $uuid = [uri]::UnescapeDataString([string]$Query["uuid"])
            if (-not $uuid) { throw "Missing application UUID" }
            # Vibepollo's web launch handler creates a GameStream launch_session.
            # make_launch_session() currently reads rikeyid unconditionally, even
            # for the trusted web-UI certificate, so an explicit neutral value is
            # required in the query string.
            return Invoke-VibepolloApi "/api/apps/launch?rikeyid=0" POST @{ uuid = $uuid }
        }
        "close-app" { return Invoke-VibepolloApi "/api/apps/close" POST @{} }
        "disconnect" {
            $uuid = [uri]::UnescapeDataString([string]$Query["uuid"])
            if (-not $uuid) { throw "Missing client UUID" }
            return Invoke-VibepolloApi "/api/clients/disconnect" POST @{ uuid = $uuid }
        }
        "restart" { return Invoke-VibepolloApi "/api/restart" POST @{} }
        "reset-display" { return Invoke-VibepolloApi "/api/reset-display-device-persistence" POST @{} }
        "export-logs" {
            $path = Join-Path $script:ExportDirectory ("Vibepollo_Logs_{0}.zip" -f (Get-Date -Format "yyyyMMdd_HHmmss"))
            $transportRequest = [ordered]@{
                base_url = $script:BaseUrl; path = "/api/logs/export"; method = "GET"
                token = $script:ApiToken; body = $null; output_path = $path
            }
            $startInfo = [Diagnostics.ProcessStartInfo]::new()
            $startInfo.FileName = $script:PythonExe
            $startInfo.Arguments = $script:PythonPrefix + "-u `"$script:TransportPath`""
            $startInfo.UseShellExecute = $false; $startInfo.CreateNoWindow = $true
            $startInfo.RedirectStandardInput = $true; $startInfo.RedirectStandardOutput = $true; $startInfo.RedirectStandardError = $true
            $process = [Diagnostics.Process]::new(); $process.StartInfo = $startInfo; [void]$process.Start()
            $stdoutTask = $process.StandardOutput.ReadToEndAsync(); $stderrTask = $process.StandardError.ReadToEndAsync()
            $process.StandardInput.Write((ConvertTo-JsonSafe $transportRequest 8)); $process.StandardInput.Close()
            if (-not $process.WaitForExit(20000)) {
                try { $process.Kill() } catch {}
                throw "Log export transport timed out"
            }
            $stdout = $stdoutTask.GetAwaiter().GetResult(); $stderr = $stderrTask.GetAwaiter().GetResult()
            if (-not $stdout) { throw "Log export transport failed: $stderr" }
            $transportResponse = $stdout | ConvertFrom-Json
            if (-not $transportResponse.ok) { throw "Log export failed: $($transportResponse.error)" }
            return [pscustomobject]@{ status = $true; path = $path }
        }
        default { throw "Unknown action: $Name" }
    }
}

$mutexName = "Local\VibepolloUnifiedRemoteBridge_$script:ListenPort"
$createdNew = $false
$mutex = [Threading.Mutex]::new($true, $mutexName, [ref]$createdNew)
if (-not $createdNew) { Write-BridgeLog "Another bridge instance is already running" "WARN"; exit 0 }
$listener = [Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback, $script:ListenPort)
$running = $true
try {
    $listener.Start()
    Write-BridgeLog "Vibepollo Bridge started on http://127.0.0.1:$script:ListenPort; API=$script:BaseUrl"
    while ($running) {
        $client = $listener.AcceptTcpClient()
        try {
            $request = Read-HttpRequest $client
            if ($null -eq $request) { continue }
            $path = $request.Path
            if (-not $path) { $path = "/" }
            switch -Regex ($path) {
                '^/health$' {
                    $apiOnline = $null -ne $script:LastApiSuccess -and ((Get-Date) - $script:LastApiSuccess).TotalSeconds -lt 60
                    Send-JsonResponse $request.Stream ([pscustomobject]@{ ok = $true; api_online = $apiOnline; port = $script:ListenPort })
                }
                '^/snapshot$' {
                    $force = [string]$request.Query["force"] -in @("1", "true", "yes")
                    Send-JsonResponse $request.Stream (Get-Snapshot -Force:$force)
                }
                '^/diagnostics/stream-sources$' {
                    Send-JsonResponse $request.Stream (Get-StreamSourceDiagnostics)
                }
                '^/apps$' { Send-JsonResponse $request.Stream ([pscustomobject]@{ apps = (Get-Snapshot).apps }) }
                '^/apps/ensure$' {
                    if ($request.Method -ne "POST") {
                        Send-JsonResponse $request.Stream ([pscustomobject]@{ ok = $false; error = "POST required" }) 405
                        continue
                    }
                    $body = if ([string]::IsNullOrWhiteSpace($request.Body)) {
                        [pscustomobject]@{}
                    } else { $request.Body | ConvertFrom-Json }
                    $result = Ensure-PlayniteApp ([string]$body.playnite_game_id) ([string]$body.name)
                    Send-JsonResponse $request.Stream $result $(if ($result.created) { 201 } else { 200 })
                }
                '^/apps/status$' {
                    $gameId = [uri]::UnescapeDataString([string]$request.Query["playnite_game_id"])
                    Send-JsonResponse $request.Stream (Get-PlayniteAppStatus $gameId)
                }
                '^/clients$' { Send-JsonResponse $request.Stream ([pscustomobject]@{ clients = (Get-Snapshot).clients }) }
                '^/pair$' {
                    if ($request.Method -ne "POST") {
                        Send-JsonResponse $request.Stream ([pscustomobject]@{ ok = $false; error = "POST required" }) 405
                        continue
                    }
                    $body = if ([string]::IsNullOrWhiteSpace($request.Body)) {
                        [pscustomobject]@{}
                    } else { $request.Body | ConvertFrom-Json }
                    Send-JsonResponse $request.Stream (Pair-MoonWakerClient ([string]$body.pin) ([string]$body.name))
                }
                '^/action/([^/]+)$' {
                    $actionName = $Matches[1]
                    $result = Invoke-Action $actionName $request.Query
                    $script:CacheTime.Clear()
                    $script:SnapshotCache = $null
                    $script:DiagnosticsCache = $null
                    Send-JsonResponse $request.Stream ([pscustomobject]@{ ok = $true; action = $actionName; result = $result })
                }
                '^/shutdown$' {
                    Send-JsonResponse $request.Stream ([pscustomobject]@{ ok = $true; shutting_down = $true })
                    $running = $false
                }
                default { Send-JsonResponse $request.Stream ([pscustomobject]@{ ok = $false; error = "Unknown endpoint: $path" }) 404 }
            }
        }
        catch {
            $level = if ($_.Exception.Message -match 'connection.*(aborted|closed)|transport') {
                "DEBUG"
            } else { "ERROR" }
            Write-BridgeLog "Request $path failed: $($_.Exception.Message)" $level
            try { Send-JsonResponse $request.Stream ([pscustomobject]@{ ok = $false; error = $_.Exception.Message }) 500 } catch {}
        }
        finally { try { $client.Close() } catch {} }
    }
}
finally {
    try { $listener.Stop() } catch {}
    try { $mutex.ReleaseMutex() } catch {}
    $mutex.Dispose()
    Write-BridgeLog "Vibepollo Bridge stopped"
}
