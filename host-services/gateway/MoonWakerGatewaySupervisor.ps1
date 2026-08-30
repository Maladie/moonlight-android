#requires -Version 5.1
[CmdletBinding()]
param([string]$GatewayDirectory = $PSScriptRoot)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"
$GatewayDirectory = [IO.Path]::GetFullPath($GatewayDirectory)
$stopPath = Join-Path $GatewayDirectory "gateway-supervisor-stop"
$manualStopPath = Join-Path $GatewayDirectory "gateway-manually-stopped"
$statePath = Join-Path $GatewayDirectory "gateway-supervisor-state.json"
$diagnosticPath = Join-Path $GatewayDirectory "gateway-supervisor.jsonl"
$diagnosticClock = [Diagnostics.Stopwatch]::StartNew()
$diagnosticRunId = [Guid]::NewGuid().ToString("N")
$diagnosticMaxBytes = 2MB
$mutex = [Threading.Mutex]::new($false, "Local\MoonWakerGatewaySupervisor")
$ownsMutex = $false

function Initialize-SupervisorDiagnostics {
    try {
        Get-ChildItem -LiteralPath $GatewayDirectory -Filter "gateway-supervisor.jsonl*" -File |
            Where-Object Name -Match '^gateway-supervisor\.jsonl(?:\.\d+)?$' |
            Where-Object LastWriteTimeUtc -lt ([DateTime]::UtcNow.AddDays(-7)) |
            Remove-Item -Force -ErrorAction SilentlyContinue
    } catch {}
}
function Write-SupervisorDiagnosticEvent([string]$Event, [hashtable]$Fields = @{}, [Exception]$Exception = $null) {
    try {
        if ($Event -notmatch '^[A-Za-z0-9._:$-]{1,256}$') { return }
        $record = [ordered]@{ v = 1; ts = [DateTime]::UtcNow.ToString("o"); mono_ms = [long]$diagnosticClock.ElapsedMilliseconds; level = if ($Exception) { "ERROR" } else { "INFO" }; component = "host.gateway-supervisor"; event = $Event; run_id = $diagnosticRunId }
        foreach ($key in @("status", "restart_count", "exit_code")) {
            if ($Fields.ContainsKey($key) -and ($Fields[$key] -is [int] -or $Fields[$key] -is [long] -or
                (($Fields[$key] -is [string]) -and $Fields[$key] -match '^[A-Za-z0-9._:$-]{1,256}$'))) { $record[$key] = $Fields[$key] }
        }
        if ($Exception -and $Exception.GetType().FullName -match '^[A-Za-z0-9._+$-]{1,256}$') { $record.error_type = $Exception.GetType().FullName }
        $line = $record | ConvertTo-Json -Compress
        $lineBytes = [Text.Encoding]::UTF8.GetByteCount($line + [Environment]::NewLine)
        if ((Test-Path -LiteralPath $diagnosticPath) -and
            ((Get-Item -LiteralPath $diagnosticPath).Length + $lineBytes -gt $diagnosticMaxBytes)) {
            Remove-Item -LiteralPath "$diagnosticPath.2" -Force -ErrorAction SilentlyContinue
            if (Test-Path -LiteralPath "$diagnosticPath.1") { Move-Item -LiteralPath "$diagnosticPath.1" -Destination "$diagnosticPath.2" -Force }
            Move-Item -LiteralPath $diagnosticPath -Destination "$diagnosticPath.1" -Force
        }
        Add-Content -LiteralPath $diagnosticPath -Encoding UTF8 -Value $line
    } catch {}
}
function Write-State([string]$Status, [int]$RestartCount) {
    [ordered]@{ pid = $PID; status = $Status; restart_count = $RestartCount; updated_at = [DateTimeOffset]::UtcNow.ToUnixTimeSeconds() } |
        ConvertTo-Json | Set-Content -LiteralPath $statePath -Encoding UTF8
}

Initialize-SupervisorDiagnostics
try {
    try { $ownsMutex = $mutex.WaitOne(0, $false) } catch [Threading.AbandonedMutexException] { $ownsMutex = $true }
    if (-not $ownsMutex) { exit 0 }
    if (Test-Path -LiteralPath $manualStopPath) {
        Write-SupervisorDiagnosticEvent "manual_stop_observed" @{ status = "manually_stopped" }
        return
    }
    Remove-Item -LiteralPath $stopPath -Force -ErrorAction SilentlyContinue
    $startScript = Join-Path $GatewayDirectory "Start-WakePlayGateway.ps1"
    if (-not (Test-Path -LiteralPath $startScript)) { throw "Gateway start script was not found." }
    $attempt = 0
    Write-SupervisorDiagnosticEvent "supervisor.started" @{ status = "running"; restart_count = $attempt }
    while (-not (Test-Path -LiteralPath $stopPath)) {
        Write-State "running" $attempt
        try {
            & $startScript -NoPairing
            if (-not (Test-Path -LiteralPath $stopPath)) { Write-SupervisorDiagnosticEvent "gateway.exited" @{ status = "restarting"; restart_count = $attempt } }
        } catch {
            Write-SupervisorDiagnosticEvent "gateway.start_failed" @{ status = "restarting"; restart_count = $attempt } $_.Exception
        }
        if (Test-Path -LiteralPath $stopPath) { break }
        $attempt = [Math]::Min($attempt + 1, 8)
        Write-State "recovering" $attempt
        Start-Sleep -Seconds ([Math]::Min(30, [Math]::Max(2, $attempt * 2)))
    }
} finally {
    $finalStatus = if (Test-Path -LiteralPath $manualStopPath) { "manually_stopped" } else { "stopped" }
    Write-State $finalStatus 0
    Write-SupervisorDiagnosticEvent "supervisor.stopped" @{ status = $finalStatus; restart_count = 0 }
    Remove-Item -LiteralPath $stopPath -Force -ErrorAction SilentlyContinue
    if ($ownsMutex) { try { $mutex.ReleaseMutex() } catch {} }
    $mutex.Dispose()
}
