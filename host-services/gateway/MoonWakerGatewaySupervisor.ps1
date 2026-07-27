#requires -Version 5.1
[CmdletBinding()]
param([string]$GatewayDirectory = $PSScriptRoot)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"
$GatewayDirectory = [IO.Path]::GetFullPath($GatewayDirectory)
$stopPath = Join-Path $GatewayDirectory "gateway-supervisor-stop"
$manualStopPath = Join-Path $GatewayDirectory "gateway-manually-stopped"
$statePath = Join-Path $GatewayDirectory "gateway-supervisor-state.json"
$logPath = Join-Path $GatewayDirectory "gateway-supervisor.log"
$mutex = [Threading.Mutex]::new($false, "Local\MoonWakerGatewaySupervisor")
$ownsMutex = $false

function Write-SupervisorLog([string]$Message) {
    Add-Content -LiteralPath $logPath -Encoding UTF8 -Value ("{0:o} {1}" -f [DateTimeOffset]::Now, $Message)
}
function Write-State([string]$Status, [int]$RestartCount) {
    [ordered]@{ pid = $PID; status = $Status; restart_count = $RestartCount; updated_at = [DateTimeOffset]::UtcNow.ToUnixTimeSeconds() } |
        ConvertTo-Json | Set-Content -LiteralPath $statePath -Encoding UTF8
}

try {
    try { $ownsMutex = $mutex.WaitOne(0, $false) } catch [Threading.AbandonedMutexException] { $ownsMutex = $true }
    if (-not $ownsMutex) { exit 0 }
    if (Test-Path -LiteralPath $manualStopPath) {
        Write-SupervisorLog "Gateway remains stopped because the user stopped it manually."
        return
    }
    Remove-Item -LiteralPath $stopPath -Force -ErrorAction SilentlyContinue
    $startScript = Join-Path $GatewayDirectory "Start-WakePlayGateway.ps1"
    if (-not (Test-Path -LiteralPath $startScript)) { throw "Gateway start script was not found." }
    $attempt = 0
    while (-not (Test-Path -LiteralPath $stopPath)) {
        Write-State "running" $attempt
        try {
            & $startScript -NoPairing
            if (-not (Test-Path -LiteralPath $stopPath)) { Write-SupervisorLog "Gateway process exited; restarting." }
        } catch {
            Write-SupervisorLog "Gateway start failed: $($_.Exception.Message)"
        }
        if (Test-Path -LiteralPath $stopPath) { break }
        $attempt = [Math]::Min($attempt + 1, 8)
        Write-State "recovering" $attempt
        Start-Sleep -Seconds ([Math]::Min(30, [Math]::Max(2, $attempt * 2)))
    }
} finally {
    Write-State $(if (Test-Path -LiteralPath $manualStopPath) { "manually_stopped" } else { "stopped" }) 0
    Remove-Item -LiteralPath $stopPath -Force -ErrorAction SilentlyContinue
    if ($ownsMutex) { try { $mutex.ReleaseMutex() } catch {} }
    $mutex.Dispose()
}
