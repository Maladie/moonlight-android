#requires -Version 5.1
# Isolated production-function tests: no Bridge/service startup or network.
$ErrorActionPreference = 'Stop'
$parseTokens = $null
$parseErrors = $null
$ast = [Management.Automation.Language.Parser]::ParseFile(
    (Join-Path $PSScriptRoot 'VibepolloBridge.ps1'), [ref]$parseTokens, [ref]$parseErrors)
if ($parseErrors.Count) { throw ($parseErrors | Out-String) }
foreach ($name in @('ConvertTo-JsonSafe', 'ConvertTo-RemoteJsonSafe', 'Get-CachedApi',
        'Get-ForcedControlSources',
        'Get-PropertyValue', 'Convert-Collection', 'Normalize-Apps', 'Normalize-Clients',
        'Build-Snapshot', 'Get-Snapshot')) {
    $node = $ast.FindAll({ param($n)
        $n -is [Management.Automation.Language.FunctionDefinitionAst] -and $n.Name -eq $name
    }, $true) | Select-Object -First 1
    if ($null -eq $node) { throw "Missing production function $name" }
    . ([scriptblock]::Create($node.Extent.Text))
}
$script:Cache = @{}
$script:CacheTime = @{}
$script:DeniedKeys = @{}
$script:ApiUnavailableUntil = [datetime]::MinValue
$script:SnapshotCache = $null
$script:SnapshotCacheTime = [datetime]::MinValue
$script:StartedAt = Get-Date
$script:calls = 0
$script:callsByPath = @{}
$script:failPath = ''
$script:failMessage = 'test source unavailable'
$script:missingSessionCount = $false
$script:nullRtsp = $false
$script:unknownCurrentApp = $false
$script:activeFallback = $false
$script:slowSourceMs = 0
$script:ControlProbeLogPath = Join-Path ([IO.Path]::GetTempPath()) `
    ('mw-control-probe-' + [guid]::NewGuid().ToString('N') + '.jsonl')
function Get-TestControlValue {
    param($Path)
    switch ($Path) {
        '/api/session/status' {
            if ($script:missingSessionCount) { return [pscustomobject]@{ appRunning = $false } }
            return [pscustomobject]@{ appRunning = $false; activeSessions = 0 }
        }
        '/api/rtsp/sessions' {
            if ($script:nullRtsp) { return [pscustomobject]@{ sessions = $null } }
            [object[]]$sessions = @()
            if ($script:activeFallback) {
                $sessions += [pscustomobject]@{ uuid = 'client' }
            }
            return [pscustomobject]@{ sessions = $sessions }
        }
        '/api/webrtc/sessions' { return [pscustomobject]@{ sessions = @() } }
        '/api/apps' { return [pscustomobject]@{
            current_app = $(if ($script:unknownCurrentApp) { 'missing' }
                elseif ($script:activeFallback) { '7' } else { '' })
            apps = @([pscustomobject]@{ id = '7'; name = 'Stream' }) } }
        '/api/clients/list' { return [pscustomobject]@{
            named_certs = @([pscustomobject]@{ uuid = 'client'; name = 'TV' }) } }
        default { return [pscustomobject]@{} }
    }
}
function Invoke-VibepolloApi {
    param($Path, [switch]$RawText, [switch]$ControlBatch, [int]$TimeoutMs)
    $script:calls++
    if ($ControlBatch) {
        $paths = [ordered]@{
            session = '/api/session/status'; rtsp = '/api/rtsp/sessions'
            webrtc = '/api/webrtc/sessions'; apps = '/api/apps'
            clients = '/api/clients/list'
        }
        $duration = [Math]::Min($script:slowSourceMs, 600)
        if ($duration -gt 0) { Start-Sleep -Milliseconds $duration }
        $rows = [ordered]@{}
        foreach ($source in $paths.Keys) {
            $path = $paths[$source]
            $script:callsByPath[$path] = 1 + [int]$script:callsByPath[$path]
            $status = if ($path -eq $script:failPath -and
                    $script:failMessage -match 'HTTP ([0-9]{3})') {
                [int]$Matches[1] } else { 0 }
            $timedOut = $script:slowSourceMs -gt 600
            $rows[$source] = if ($status -gt 0 -or $path -eq $script:failPath -or $timedOut) {
                [pscustomobject]@{ ok = $false; status = $status
                    timeout = $timedOut; duration_ms = $duration }
            } else {
                [pscustomobject]@{ ok = $true; status = 200
                    content = (Get-TestControlValue $path | ConvertTo-Json -Depth 6 -Compress)
                    duration_ms = $duration }
            }
        }
        return [pscustomobject]$rows
    }
    $script:callsByPath[$Path] = 1 + [int]$script:callsByPath[$Path]
    if ($Path -eq $script:failPath) { throw $script:failMessage }
    return Get-TestControlValue $Path
}
function Get-WgcDiagnostics { return [pscustomobject]@{ available = $false } }
function Get-DerivedSessionMetrics { return $null }
function Get-QualityVerdict { return [pscustomobject]@{} }
function Get-RecentWarnings { return @() }
function Write-BridgeLog {}

$snapshot = Get-Snapshot -Force
if (-not $snapshot.control_fresh) { throw 'Fresh sources rejected' }
$probe = Get-Content -LiteralPath $script:ControlProbeLogPath -Tail 1 | ConvertFrom-Json
if (@($probe.sources).Count -ne 5 -or
        @($probe.sources | Where-Object { $_.outcome -ne 'ok' -or $_.duration_ms -lt 0 }).Count -ne 0) {
    throw 'Fresh forced source diagnostics are incomplete'
}
if ($snapshot.apps -isnot [array] -or $snapshot.clients -isnot [array]) {
    throw 'Singleton collections were unwrapped'
}
$wireSnapshot = (ConvertTo-JsonSafe (ConvertTo-RemoteJsonSafe $snapshot) 16) |
    ConvertFrom-Json
if ($wireSnapshot.control_unavailable_sources -isnot [array] -or
        $wireSnapshot.control_unavailable_sources.Count -ne 0 -or
        $wireSnapshot.apps -isnot [array] -or $wireSnapshot.clients -isnot [array]) {
    throw 'Wire JSON changed empty or singleton control collections'
}
$controlPaths = @('/api/session/status', '/api/rtsp/sessions',
    '/api/webrtc/sessions', '/api/apps', '/api/clients/list')
$optionalPaths = @('/api/metadata', '/api/host/stats', '/api/host/info',
    '/api/history/sessions/active', '/api/rtss/status',
    '/api/lossless_scaling/status', '/api/logs')
if ($script:calls -ne 1 -or @($controlPaths | Where-Object {
            [int]$script:callsByPath[$_] -ne 1 }).Count -ne 0) {
    throw 'Forced control snapshot did not fetch exactly the five authoritative sources in one process'
}
foreach ($path in $optionalPaths) {
    if ($script:callsByPath.ContainsKey($path)) {
        throw "Forced control snapshot fetched optional source $path"
    }
}
$bridgeSource = Get-Content -LiteralPath (Join-Path $PSScriptRoot 'VibepolloBridge.ps1') -Raw
if (-not $bridgeSource.Contains('$controlDeadlineMs = 1500') -or
        -not $bridgeSource.Contains('$wgc = if ($Force)')) {
    throw 'Forced control snapshot has no total deadline or still requires WGC'
}
$firstCalls = $script:calls
$null = Get-Snapshot
$uiSnapshotCache = $script:SnapshotCache
$uiCalls = $script:calls
$null = Get-Snapshot
if ($script:calls -ne $uiCalls) { throw 'UI snapshot did not use cache' }
$null = Get-Snapshot -Force
if ($script:calls -ne $uiCalls + 1) {
    throw 'Force did not fetch exactly the authoritative control sources'
}
if (-not [object]::ReferenceEquals($uiSnapshotCache, $script:SnapshotCache)) {
    throw 'Control snapshot replaced the UI snapshot cache'
}

$script:Cache = @{}
$script:CacheTime = @{}
$script:ApiUnavailableUntil = [datetime]::MinValue
$script:slowSourceMs = 400
$timer = [Diagnostics.Stopwatch]::StartNew()
$snapshot = Get-Snapshot -Force
$timer.Stop()
if ($timer.ElapsedMilliseconds -gt 1500 -or -not $snapshot.control_fresh) {
    throw "Parallel control sources did not remain fresh within the deadline: $($timer.ElapsedMilliseconds) ms"
}
$script:slowSourceMs = 900
$timer = [Diagnostics.Stopwatch]::StartNew()
$snapshot = Get-Snapshot -Force
$timer.Stop()
if ($timer.ElapsedMilliseconds -gt 1900 -or $snapshot.control_fresh) {
    throw "Timed-out control sources exceeded the health boundary or became fresh: $($timer.ElapsedMilliseconds) ms"
}
$probe = Get-Content -LiteralPath $script:ControlProbeLogPath -Tail 1 | ConvertFrom-Json
if (@($probe.sources).Count -ne 5 -or
        @($probe.sources | Where-Object { $_.outcome -eq 'timeout' }).Count -lt 1 -or
        ($probe | ConvertTo-Json -Depth 5) -match 'test source|api_token|base_url') {
    throw 'Timeout source diagnostics leaked data or lost status'
}
$script:slowSourceMs = 0
$script:ApiUnavailableUntil = [datetime]::MinValue

$script:failPath = '/api/metadata'
$optionalCalls = [int]$script:callsByPath['/api/metadata']
$snapshot = Get-Snapshot -Force
if (-not $snapshot.control_fresh -or
        [int]$script:callsByPath['/api/metadata'] -ne $optionalCalls) {
    throw 'Optional source poisoned forced control evidence'
}

$script:failPath = '/api/apps'
$script:failMessage = 'HTTP 503: private backend response'
$snapshot = Get-Snapshot -Force
if ($snapshot.control_fresh -or $snapshot.control_unavailable_sources -notcontains 'apps') {
    throw 'Missing source became control evidence'
}
$probe = Get-Content -LiteralPath $script:ControlProbeLogPath -Tail 1 | ConvertFrom-Json
$appProbe = @($probe.sources | Where-Object { $_.source -eq 'apps' })
if ($appProbe.Count -ne 1 -or $appProbe[0].outcome -ne 'http_503' -or
        $appProbe[0].http_status -ne 503 -or
        ($probe | ConvertTo-Json -Depth 5) -match 'private backend response') {
    throw 'HTTP source diagnostics leaked response or lost status'
}
$script:failPath = ''
$script:failMessage = 'test source unavailable'
$script:missingSessionCount = $true
$snapshot = Get-Snapshot -Force
if (-not $snapshot.control_fresh -or $snapshot.session.source -ne 'derived' -or
        $snapshot.session.count -ne 0 -or $snapshot.session.app_running) {
    throw 'Complete fallback sources did not replace unavailable session status'
}
$script:missingSessionCount = $false
$script:failPath = '/api/session/status'
$snapshot = Get-Snapshot -Force
if (-not $snapshot.control_fresh -or $snapshot.session.source -ne 'derived') {
    throw 'Unavailable optional session status poisoned complete fallback evidence'
}
$script:activeFallback = $true
$snapshot = Get-Snapshot -Force
if (-not $snapshot.control_fresh -or -not $snapshot.session.active -or
        $snapshot.session.count -ne 1 -or -not $snapshot.session.app_running -or
        @($snapshot.clients | Where-Object { $_.connected }).Count -ne 1 -or
        @($snapshot.apps | Where-Object { $_.running }).Count -ne 1) {
    throw ("Complete active fallback sources did not produce authoritative session evidence: " +
        ($snapshot | ConvertTo-Json -Depth 5 -Compress))
}
$script:activeFallback = $false
$script:failPath = ''
$script:unknownCurrentApp = $true
$snapshot = Get-Snapshot -Force
if ($snapshot.control_fresh -or $snapshot.control_unavailable_sources -notcontains 'apps') {
    throw 'Unknown current_app became authoritative control evidence'
}
$script:unknownCurrentApp = $false
$script:nullRtsp = $true
$snapshot = Get-Snapshot -Force
if ($snapshot.control_fresh -or $snapshot.control_unavailable_sources -notcontains 'rtsp') {
    throw 'Null RTSP sessions became an authoritative empty collection'
}
$script:nullRtsp = $false
$null = Get-Snapshot -Force
$callsBeforeBackoff = $script:calls
$script:ApiUnavailableUntil = (Get-Date).AddMinutes(1)
$snapshot = Get-Snapshot -Force
if (-not $snapshot.control_fresh -or
        $script:calls -ne $callsBeforeBackoff + 1) {
    throw 'Forced recovery was suppressed by UI API backoff'
}

# Exercise the production snapshot and HTTP functions behind the same
# single-threaded loopback shape as the Bridge. A slow forced snapshot must
# release the listener before the supervisor's two-second health boundary.
$serverFunctions = @('ConvertTo-JsonSafe', 'ConvertTo-RemoteJsonSafe',
    'Get-CachedApi', 'Get-ForcedControlSources', 'Get-PropertyValue', 'Convert-Collection',
    'Normalize-Apps', 'Normalize-Clients', 'Build-Snapshot', 'Get-Snapshot',
    'Read-HttpRequest', 'Send-JsonResponse') | ForEach-Object {
    $name = $_
    $node = $ast.FindAll({ param($n)
        $n -is [Management.Automation.Language.FunctionDefinitionAst] -and $n.Name -eq $name
    }, $true) | Select-Object -First 1
    if ($null -eq $node) { throw "Missing loopback production function $name" }
    $node.Extent.Text
}
$portProbe = [Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback, 0)
$portProbe.Start()
$loopbackPort = ([Net.IPEndPoint]$portProbe.LocalEndpoint).Port
$portProbe.Stop()
$server = Start-Job -ArgumentList $loopbackPort, ($serverFunctions -join "`n") -ScriptBlock {
    param($Port, $ProductionFunctions)
    Invoke-Expression $ProductionFunctions
    $script:Cache = @{}
    $script:CacheTime = @{}
    $script:DeniedKeys = @{}
    $script:ApiUnavailableUntil = [datetime]::MinValue
    $script:SnapshotCache = $null
    $script:SnapshotCacheTime = [datetime]::MinValue
    $script:StartedAt = Get-Date
    function Invoke-VibepolloApi {
        param($Path, [switch]$RawText, [switch]$ControlBatch, [int]$TimeoutMs)
        if ($ControlBatch) {
            Start-Sleep -Milliseconds 1400
            return [pscustomobject]@{
                session = [pscustomobject]@{ ok = $false; status = 0; timeout = $true; duration_ms = 600 }
                rtsp = [pscustomobject]@{ ok = $false; status = 0; timeout = $true; duration_ms = 600 }
                webrtc = [pscustomobject]@{ ok = $false; status = 0; timeout = $true; duration_ms = 600 }
                apps = [pscustomobject]@{ ok = $false; status = 0; timeout = $true; duration_ms = 600 }
                clients = [pscustomobject]@{ ok = $false; status = 0; timeout = $true; duration_ms = 600 }
            }
        }
        switch ($Path) {
            '/api/session/status' { return [pscustomobject]@{ appRunning = $false; activeSessions = 0 } }
            '/api/rtsp/sessions' { return [pscustomobject]@{ sessions = @() } }
            '/api/webrtc/sessions' { return [pscustomobject]@{ sessions = @() } }
            '/api/apps' { return [pscustomobject]@{
                current_app = ''; apps = @([pscustomobject]@{ id = '7'; name = 'Stream' }) } }
            '/api/clients/list' { return [pscustomobject]@{
                named_certs = @([pscustomobject]@{ uuid = 'client'; name = 'TV' }) } }
        }
    }
    function Get-WgcDiagnostics { return [pscustomobject]@{ available = $false } }
    function Get-DerivedSessionMetrics { return $null }
    function Get-QualityVerdict { return [pscustomobject]@{} }
    function Get-RecentWarnings { return @() }
    function Write-BridgeLog {}
    $listener = [Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback, $Port)
    $listener.Start()
    Write-Output 'READY'
    try {
        for ($requestIndex = 0; $requestIndex -lt 2; $requestIndex++) {
            $client = $listener.AcceptTcpClient()
            try {
                $request = Read-HttpRequest $client
                if ($request.Path -eq '/snapshot') {
                    Write-Output 'SNAPSHOT_STARTED'
                    Send-JsonResponse $request.Stream (Get-Snapshot -Force)
                } elseif ($request.Path -eq '/health') {
                    Send-JsonResponse $request.Stream ([pscustomobject]@{ ok = $true })
                } else {
                    Send-JsonResponse $request.Stream ([pscustomobject]@{ ok = $false }) 404
                }
            } finally { $client.Close() }
        }
    } finally { $listener.Stop() }
}
$snapshotHttp = $null
$healthHttp = $null
try {
    $ready = $false
    $readyTimer = [Diagnostics.Stopwatch]::StartNew()
    while (-not $ready -and $readyTimer.ElapsedMilliseconds -lt 5000) {
        $ready = @(Receive-Job $server) -contains 'READY'
        if (-not $ready) { Start-Sleep -Milliseconds 50 }
    }
    if (-not $ready) { throw 'Loopback listener did not start' }
    Add-Type -AssemblyName System.Net.Http
    $snapshotHandler = [Net.Http.HttpClientHandler]::new()
    $snapshotHandler.UseProxy = $false
    $healthHandler = [Net.Http.HttpClientHandler]::new()
    $healthHandler.UseProxy = $false
    $snapshotHttp = [Net.Http.HttpClient]::new($snapshotHandler)
    $healthHttp = [Net.Http.HttpClient]::new($healthHandler)
    $snapshotTask = $snapshotHttp.GetAsync("http://127.0.0.1:$loopbackPort/snapshot?force=1")
    $snapshotStarted = $false
    $startTimer = [Diagnostics.Stopwatch]::StartNew()
    while (-not $snapshotStarted -and $startTimer.ElapsedMilliseconds -lt 750) {
        $snapshotStarted = @(Receive-Job $server) -contains 'SNAPSHOT_STARTED'
        if (-not $snapshotStarted) { Start-Sleep -Milliseconds 20 }
    }
    if (-not $snapshotStarted) { throw 'Loopback listener did not begin the forced snapshot' }
    $healthTimer = [Diagnostics.Stopwatch]::StartNew()
    $healthTask = $healthHttp.GetAsync("http://127.0.0.1:$loopbackPort/health")
    if (-not $healthTask.Wait(2200)) { throw 'Loopback /health exceeded supervisor boundary' }
    $healthTimer.Stop()
    $health = $healthTask.Result.Content.ReadAsStringAsync().Result | ConvertFrom-Json
    if (-not $health.ok -or $healthTimer.ElapsedMilliseconds -gt 1900) {
        throw "Loopback /health was not responsive during forced snapshot: $($healthTimer.ElapsedMilliseconds) ms"
    }
    if (-not $snapshotTask.Wait(1000)) { throw 'Loopback forced snapshot did not finish' }
    $loopbackSnapshot = $snapshotTask.Result.Content.ReadAsStringAsync().Result | ConvertFrom-Json
    if ($loopbackSnapshot.control_fresh) {
        throw 'Deadline-exhausted loopback snapshot became fresh control evidence'
    }
    if (-not (Wait-Job $server -Timeout 2)) { throw 'Loopback listener did not stop' }
    if ($server.State -ne 'Completed') {
        throw "Loopback listener failed: $(@(Receive-Job $server) -join '; ')"
    }
} finally {
    if ($null -ne $snapshotHttp) { $snapshotHttp.Dispose() }
    if ($null -ne $healthHttp) { $healthHttp.Dispose() }
    if ($null -ne $server) {
        Stop-Job $server -ErrorAction SilentlyContinue
        Remove-Job $server -Force -ErrorAction SilentlyContinue
    }
}
Remove-Item -LiteralPath $script:ControlProbeLogPath -ErrorAction SilentlyContinue
Write-Output 'PASS: bounded fresh control sources, UI cache isolation, singleton, missing source and backoff snapshots'
