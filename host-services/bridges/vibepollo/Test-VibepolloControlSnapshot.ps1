#requires -Version 5.1
# Isolated production-function tests: no Bridge/service startup or network.
$ErrorActionPreference = 'Stop'
$parseTokens = $null
$parseErrors = $null
$ast = [Management.Automation.Language.Parser]::ParseFile(
    (Join-Path $PSScriptRoot 'VibepolloBridge.ps1'), [ref]$parseTokens, [ref]$parseErrors)
if ($parseErrors.Count) { throw ($parseErrors | Out-String) }
foreach ($name in @('ConvertTo-JsonSafe', 'ConvertTo-RemoteJsonSafe', 'Get-CachedApi',
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
$script:missingSessionCount = $false
$script:nullRtsp = $false
$script:unknownCurrentApp = $false
$script:activeFallback = $false
$script:slowSourceMs = 0
function Invoke-VibepolloApi {
    param($Path, [switch]$RawText, [int]$TimeoutMs)
    $script:calls++
    $script:callsByPath[$Path] = 1 + [int]$script:callsByPath[$Path]
    if ($Path -eq $script:failPath) { throw 'test source unavailable' }
    if ($script:slowSourceMs -gt 0) {
        if ($script:slowSourceMs -gt $TimeoutMs) {
            Start-Sleep -Milliseconds $TimeoutMs
            throw 'test source timed out'
        }
        Start-Sleep -Milliseconds $script:slowSourceMs
    }
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
function Get-WgcDiagnostics { return [pscustomobject]@{ available = $false } }
function Get-DerivedSessionMetrics { return $null }
function Get-QualityVerdict { return [pscustomobject]@{} }
function Get-RecentWarnings { return @() }
function Write-BridgeLog {}

$snapshot = Get-Snapshot -Force
if (-not $snapshot.control_fresh) { throw 'Fresh sources rejected' }
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
if ($script:calls -ne $controlPaths.Count) {
    throw "Forced control snapshot fetched $($script:calls) sources instead of $($controlPaths.Count)"
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
if ($script:calls -ne $uiCalls + $controlPaths.Count) {
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
if ($timer.ElapsedMilliseconds -gt 1900 -or $snapshot.control_fresh) {
    throw "Slow control sources exceeded the shared deadline or became fresh: $($timer.ElapsedMilliseconds) ms"
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
$snapshot = Get-Snapshot -Force
if ($snapshot.control_fresh -or $snapshot.control_unavailable_sources -notcontains 'apps') {
    throw 'Missing source became control evidence'
}
$script:failPath = ''
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
        $script:calls -ne $callsBeforeBackoff + $controlPaths.Count) {
    throw 'Forced recovery was suppressed by UI API backoff'
}

# Exercise the production snapshot and HTTP functions behind the same
# single-threaded loopback shape as the Bridge. A slow forced snapshot must
# release the listener before the supervisor's two-second health boundary.
$serverFunctions = @('ConvertTo-JsonSafe', 'ConvertTo-RemoteJsonSafe',
    'Get-CachedApi', 'Get-PropertyValue', 'Convert-Collection',
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
        param($Path, [switch]$RawText, [int]$TimeoutMs)
        $delay = 400
        if ($delay -gt $TimeoutMs) {
            Start-Sleep -Milliseconds $TimeoutMs
            throw 'loopback source timed out'
        }
        Start-Sleep -Milliseconds $delay
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
Write-Output 'PASS: bounded fresh control sources, UI cache isolation, singleton, missing source and backoff snapshots'
