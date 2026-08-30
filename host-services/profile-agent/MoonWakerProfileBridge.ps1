#requires -Version 5.1
[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$ProfileRoot,
    [Parameter(Mandatory)][ValidatePattern('^[A-Za-z0-9._-]{1,64}$')][string]$ProfileId
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"
$ProfileRoot = [IO.Path]::GetFullPath($ProfileRoot)
$statePath = Join-Path $ProfileRoot "profile-bridge-state.json"
$stopPath = Join-Path $ProfileRoot "profile-bridge-stop"
$manualStopPath = Join-Path $ProfileRoot "profile-bridge-manually-stopped"
$diagnosticPath = Join-Path $ProfileRoot "profile-bridge.jsonl"
$diagnosticClock = [Diagnostics.Stopwatch]::StartNew()
$diagnosticRunId = [Guid]::NewGuid().ToString("N")
$diagnosticMaxBytes = 2MB
$sid = [Security.Principal.WindowsIdentity]::GetCurrent().User.Value -replace '[^A-Za-z0-9]', '_'
$mutex = [Threading.Mutex]::new($false, "Local\MoonWakerProfileBridge_${sid}_$ProfileId")
$ownsMutex = $false
$children = @{}

function Get-ExpectedProfileOwner {
    try {
        $installRoot = Split-Path -Parent (Split-Path -Parent $ProfileRoot)
        $gateway = Get-Content -LiteralPath (Join-Path $installRoot "gateway\gateway.json") -Raw | ConvertFrom-Json
        return [string]$gateway.profiles.$ProfileId.owner
    } catch { return "" }
}

function Initialize-AgentDiagnostics {
    try {
        Get-ChildItem -LiteralPath $ProfileRoot -Filter "profile-bridge.jsonl*" -File |
            Where-Object Name -Match '^profile-bridge\.jsonl(?:\.\d+)?$' |
            Where-Object LastWriteTimeUtc -lt ([DateTime]::UtcNow.AddDays(-7)) |
            Remove-Item -Force -ErrorAction SilentlyContinue
    } catch {}
}
function Write-AgentDiagnosticEvent([string]$Event, [hashtable]$Fields = @{}, [Exception]$Exception = $null) {
    try {
        if ($Event -notmatch '^[A-Za-z0-9._:$-]{1,256}$') { return }
        $record = [ordered]@{ v = 1; ts = [DateTime]::UtcNow.ToString("o"); mono_ms = [long]$diagnosticClock.ElapsedMilliseconds; level = if ($Exception) { "ERROR" } else { "INFO" }; component = "host.profile-supervisor"; event = $Event; run_id = $diagnosticRunId }
        foreach ($key in @("profile_id", "child_component", "pid", "exit_code", "restart_count", "status")) {
            $value = $Fields[$key]
            if ($key -eq "child_component" -and $value -in @("discord", "vibepollo", "playnite")) { $record[$key] = $value }
            elseif ($key -ne "child_component" -and (($value -is [int] -or $value -is [long]) -or
                (($value -is [string]) -and $value -match '^[A-Za-z0-9._:$-]{1,256}$'))) { $record[$key] = $value }
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

function Start-HiddenProcess([string]$FileName, [string]$Arguments, [string]$WorkingDirectory = "") {
    $info = [Diagnostics.ProcessStartInfo]::new()
    $info.FileName = $FileName
    $info.Arguments = $Arguments
    $info.UseShellExecute = $false
    $info.CreateNoWindow = $true
    $info.WindowStyle = [Diagnostics.ProcessWindowStyle]::Hidden
    if ($WorkingDirectory) { $info.WorkingDirectory = $WorkingDirectory }
    return [Diagnostics.Process]::Start($info)
}

function Write-State([string]$Status) {
    $components = [ordered]@{}
    foreach ($name in @("discord", "vibepollo", "playnite")) {
        $process = $children[$name]
        $components[$name] = [ordered]@{
            enabled = Test-Path -LiteralPath (Join-Path $ProfileRoot $name)
            running = $null -ne $process -and -not $process.HasExited
            pid = if ($null -ne $process -and -not $process.HasExited) { $process.Id } else { 0 }
        }
    }
    [ordered]@{
        profile_id = $ProfileId
        owner = [Security.Principal.WindowsIdentity]::GetCurrent().Name
        supervisor_pid = $PID
        status = $Status
        updated_at = [DateTimeOffset]::UtcNow.ToUnixTimeSeconds()
        components = $components
    } | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $statePath -Encoding UTF8
}

function Start-Component([string]$Name) {
    $directory = Join-Path $ProfileRoot $Name
    if (-not (Test-Path -LiteralPath $directory)) { return $null }
    $configName = if ($Name -eq "discord") { "discord_bridge_config.json" } else { "config.json" }
    $portProperty = if ($Name -eq "discord") { "port" } else { "listen_port" }
    try {
        $config = Get-Content -LiteralPath (Join-Path $directory $configName) -Raw | ConvertFrom-Json
        $port = [int]$config.$portProperty
        $owner = Get-NetTCPConnection -State Listen -LocalAddress "127.0.0.1" -LocalPort $port `
            -ErrorAction SilentlyContinue | Select-Object -ExpandProperty OwningProcess -First 1
        if ($owner) {
            if (Test-ComponentHealth $Name $true) {
                Write-AgentDiagnosticEvent "component.adopted" @{ profile_id = $ProfileId; child_component = $Name; pid = [int]$owner; status = "running" }
                return Get-Process -Id $owner -ErrorAction SilentlyContinue
            }
            if ($Name -eq "playnite") {
                $health = Get-ComponentHealth $Name
                $legacyBridgeIdentity = $health -and [int]$health.pid -eq [int]$owner -and
                    $null -ne $health.connector_connected -and
                    -not [string]::IsNullOrWhiteSpace([string]$health.version)
                if ($health -and ([string]$health.component -in @("game-provider", "playnite") -or
                    $legacyBridgeIdentity)) {
                    Write-AgentDiagnosticEvent "stale_component.stopped" @{ profile_id = $ProfileId; child_component = $Name; pid = [int]$owner; status = "stale" }
                    Stop-Process -Id $owner -Force -ErrorAction Stop
                    Start-Sleep -Milliseconds 250
                } else {
                    Write-AgentDiagnosticEvent "adoption_refused" @{ profile_id = $ProfileId; child_component = $Name; pid = [int]$owner; status = "unknown_identity" }
                    return $null
                }
            } else {
                Write-AgentDiagnosticEvent "adoption_refused" @{ profile_id = $ProfileId; child_component = $Name; pid = [int]$owner; status = "unhealthy" }
                return Get-Process -Id $owner -ErrorAction SilentlyContinue
            }
        }
    } catch {}
    if ($Name -eq "discord") {
        $script = Join-Path $directory "DiscordBridge.ps1"
        if (-not (Test-Path -LiteralPath $script)) { return $null }
        return Start-HiddenProcess "powershell.exe" ('-NoProfile -ExecutionPolicy Bypass -File "{0}"' -f $script.Replace('"', '\"'))
    }
    if ($Name -eq "vibepollo") {
        $script = Join-Path $directory "VibepolloBridge.ps1"
        if (-not (Test-Path -LiteralPath $script)) { return $null }
        return Start-HiddenProcess "powershell.exe" ('-NoProfile -ExecutionPolicy Bypass -File "{0}"' -f $script.Replace('"', '\"'))
    }
    $script = Join-Path $directory "GameProviderBridge.py"
    $config = Join-Path $directory "config.json"
    if (-not (Test-Path -LiteralPath $script) -or -not (Test-Path -LiteralPath $config)) { return $null }
    return Start-HiddenProcess "python.exe" ('"{0}" --config "{1}"' -f `
        $script.Replace('"', '\"'), $config.Replace('"', '\"')) $directory
}

function Get-ComponentHealth([string]$Name) {
    $directory = Join-Path $ProfileRoot $Name
    $configName = if ($Name -eq "discord") { "discord_bridge_config.json" } else { "config.json" }
    $portProperty = if ($Name -eq "discord") { "port" } else { "listen_port" }
    try {
        $config = Get-Content -LiteralPath (Join-Path $directory $configName) -Raw | ConvertFrom-Json
        $port = [int]$config.$portProperty
        $client = [Net.Sockets.TcpClient]::new()
        try {
            $pending = $client.ConnectAsync("127.0.0.1", $port)
            if (-not $pending.Wait(750) -or -not $client.Connected) { return $null }
        } finally { $client.Dispose() }
        $response = Invoke-WebRequest -UseBasicParsing -Uri "http://127.0.0.1:$port/health" -TimeoutSec 2
        if ($response.StatusCode -lt 200 -or $response.StatusCode -ge 300) { return $null }
        return $response.Content | ConvertFrom-Json
    } catch { return $null }
}

function Test-ComponentHealth([string]$Name, [bool]$RequireIdentity = $false) {
    $health = Get-ComponentHealth $Name
    if (-not $health) { return $false }
    if ($Name -ne "playnite" -or -not $RequireIdentity) { return $true }
    try {
        $expected = Get-Content -LiteralPath (Join-Path $ProfileRoot "moonwaker-version.json") -Raw |
            ConvertFrom-Json
        return [string]$health.component -eq "game-provider" -and
            [string]$health.profile_id -eq $ProfileId -and
            [string]$health.version -eq [string]$expected.version
    } catch { return $false }
}

function Restart-Component([string]$Name, [Diagnostics.Process]$Process) {
    Write-AgentDiagnosticEvent "health_check.failed" @{ profile_id = $ProfileId; child_component = $Name; status = "restarting" }
    try { if ($null -ne $Process -and -not $Process.HasExited) { Stop-Process -Id $Process.Id -Force } } catch {}
    Start-Sleep -Milliseconds 500
    try { $children[$Name] = Start-Component $Name } catch { Write-AgentDiagnosticEvent "component.restart_failed" @{ profile_id = $ProfileId; child_component = $Name; status = "failed" } $_.Exception }
}

function Stop-Components {
    foreach ($entry in @(
        @{ name = "discord"; script = "Stop-DiscordBridge.ps1" },
        @{ name = "vibepollo"; script = "Stop-VibepolloBridge.ps1" },
        @{ name = "playnite"; script = "Stop-PlayniteBridge.ps1" })) {
        $stopScript = Join-Path (Join-Path $ProfileRoot $entry.name) $entry.script
        if (Test-Path -LiteralPath $stopScript) {
            try { & $stopScript | Out-Null } catch { Write-AgentDiagnosticEvent "component.graceful_stop_failed" @{ profile_id = $ProfileId; child_component = $entry.name; status = "failed" } $_.Exception }
        }
    }
    Start-Sleep -Milliseconds 500
    foreach ($process in @($children.Values)) {
        try { if ($null -ne $process -and -not $process.HasExited) { Stop-Process -Id $process.Id -Force } } catch {}
    }
}

Initialize-AgentDiagnostics
$expectedOwner = Get-ExpectedProfileOwner
$currentUser = [Security.Principal.WindowsIdentity]::GetCurrent().Name
if (-not [string]::IsNullOrWhiteSpace($expectedOwner) -and
    -not $expectedOwner.Equals($currentUser, [StringComparison]::OrdinalIgnoreCase)) {
    Write-AgentDiagnosticEvent "wrong_user" @{ profile_id = $ProfileId; status = "refused" }
    Write-State "wrong_user"
    exit 1
}

try {
    try { $ownsMutex = $mutex.WaitOne(0, $false) } catch [Threading.AbandonedMutexException] { $ownsMutex = $true }
    if (-not $ownsMutex) { exit 0 }
    if (Test-Path -LiteralPath $manualStopPath) {
        Write-AgentDiagnosticEvent "manual_stop_observed" @{ profile_id = $ProfileId; status = "manually_stopped" }
        Write-State "manually_stopped"
        exit 0
    }
    Remove-Item -LiteralPath $stopPath -Force -ErrorAction SilentlyContinue
    Write-AgentDiagnosticEvent "supervisor.started" @{ profile_id = $ProfileId; status = "running" }
    foreach ($name in @("discord", "vibepollo", "playnite")) {
        try { $children[$name] = Start-Component $name } catch { Write-AgentDiagnosticEvent "component.start_failed" @{ profile_id = $ProfileId; child_component = $name; status = "failed" } $_.Exception }
    }
    Write-State "running"
    $healthTick = 0
    while (-not (Test-Path -LiteralPath $stopPath)) {
        foreach ($name in @("discord", "vibepollo", "playnite")) {
            $process = $children[$name]
            if ($null -ne $process -and $process.HasExited) {
                Write-AgentDiagnosticEvent "component.exited" @{ profile_id = $ProfileId; child_component = $name; exit_code = [int]$process.ExitCode; status = "restarting" }
                Start-Sleep -Milliseconds 750
                try { $children[$name] = Start-Component $name } catch { Write-AgentDiagnosticEvent "component.restart_failed" @{ profile_id = $ProfileId; child_component = $name; status = "failed" } $_.Exception }
            } elseif ($null -eq $process) {
                try { $children[$name] = Start-Component $name } catch {}
            }
        }
        $healthTick++
        if ($healthTick -ge 8) {
            $healthTick = 0
            foreach ($name in @("discord", "vibepollo", "playnite")) {
                $process = $children[$name]
                # DiscordBridge deliberately handles AUTHORIZE synchronously while
                # Discord displays its consent modal. It cannot answer /health in
                # that interval, so restarting it would cancel OAuth. Its bounded
                # RPC read timeout and the normal exited-process check above still
                # recover a genuinely failed Bridge.
                if ($name -eq "discord") { continue }
                if ($null -ne $process -and -not $process.HasExited -and
                    -not (Test-ComponentHealth $name ($name -eq "playnite"))) {
                    Restart-Component $name $process
                }
            }
        }
        Write-State "running"
        Start-Sleep -Seconds 2
    }
} finally {
    Write-State "stopping"
    Stop-Components
    Remove-Item -LiteralPath $stopPath -Force -ErrorAction SilentlyContinue
    $finalStatus = if (Test-Path -LiteralPath $manualStopPath) { "manually_stopped" } else { "stopped" }
    Write-State $finalStatus
    Write-AgentDiagnosticEvent "supervisor.stopped" @{ profile_id = $ProfileId; status = $finalStatus }
    if ($ownsMutex) { try { $mutex.ReleaseMutex() } catch {} }
    $mutex.Dispose()
}
