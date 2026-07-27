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
$logPath = Join-Path $ProfileRoot "profile-bridge.log"
$sid = [Security.Principal.WindowsIdentity]::GetCurrent().User.Value -replace '[^A-Za-z0-9]', '_'
$mutex = [Threading.Mutex]::new($false, "Local\MoonWakerProfileBridge_${sid}_$ProfileId")
$ownsMutex = $false
$children = @{}

function Write-AgentLog([string]$Message) {
    $line = "{0:o} {1}" -f [DateTimeOffset]::Now, $Message
    Add-Content -LiteralPath $logPath -Value $line -Encoding UTF8
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
            Write-AgentLog "$Name already owns its configured port; adopting PID $owner."
            return Get-Process -Id $owner -ErrorAction SilentlyContinue
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
    $script = Join-Path $directory "PlayniteBridge.py"
    $config = Join-Path $directory "config.json"
    if (-not (Test-Path -LiteralPath $script) -or -not (Test-Path -LiteralPath $config)) { return $null }
    return Start-HiddenProcess "python.exe" ('"{0}" --config "{1}"' -f `
        $script.Replace('"', '\"'), $config.Replace('"', '\"')) $directory
}

function Test-ComponentHealth([string]$Name) {
    $directory = Join-Path $ProfileRoot $Name
    $configName = if ($Name -eq "discord") { "discord_bridge_config.json" } else { "config.json" }
    $portProperty = if ($Name -eq "discord") { "port" } else { "listen_port" }
    try {
        $config = Get-Content -LiteralPath (Join-Path $directory $configName) -Raw | ConvertFrom-Json
        $port = [int]$config.$portProperty
        $client = [Net.Sockets.TcpClient]::new()
        try {
            $pending = $client.ConnectAsync("127.0.0.1", $port)
            if (-not $pending.Wait(750) -or -not $client.Connected) { return $false }
        } finally { $client.Dispose() }
        $response = Invoke-WebRequest -UseBasicParsing -Uri "http://127.0.0.1:$port/health" -TimeoutSec 2
        return $response.StatusCode -ge 200 -and $response.StatusCode -lt 300
    } catch { return $false }
}

function Restart-Component([string]$Name, [Diagnostics.Process]$Process) {
    Write-AgentLog "$Name failed its health check; restarting."
    try { if ($null -ne $Process -and -not $Process.HasExited) { Stop-Process -Id $Process.Id -Force } } catch {}
    Start-Sleep -Milliseconds 500
    try { $children[$Name] = Start-Component $Name } catch { Write-AgentLog "Restart failed for \${Name}: $($_.Exception.Message)" }
}

function Stop-Components {
    foreach ($entry in @(
        @{ name = "discord"; script = "Stop-DiscordBridge.ps1" },
        @{ name = "vibepollo"; script = "Stop-VibepolloBridge.ps1" },
        @{ name = "playnite"; script = "Stop-PlayniteBridge.ps1" })) {
        $stopScript = Join-Path (Join-Path $ProfileRoot $entry.name) $entry.script
        if (Test-Path -LiteralPath $stopScript) {
            try { & $stopScript | Out-Null } catch { Write-AgentLog "Graceful stop failed for $($entry.name): $($_.Exception.Message)" }
        }
    }
    Start-Sleep -Milliseconds 500
    foreach ($process in @($children.Values)) {
        try { if ($null -ne $process -and -not $process.HasExited) { Stop-Process -Id $process.Id -Force } } catch {}
    }
}

try {
    try { $ownsMutex = $mutex.WaitOne(0, $false) } catch [Threading.AbandonedMutexException] { $ownsMutex = $true }
    if (-not $ownsMutex) { exit 0 }
    if (Test-Path -LiteralPath $manualStopPath) {
        Write-AgentLog "Profile Bridge remains stopped because the user stopped it manually."
        Write-State "manually_stopped"
        exit 0
    }
    Remove-Item -LiteralPath $stopPath -Force -ErrorAction SilentlyContinue
    Write-AgentLog "Profile Bridge supervisor started for $ProfileId."
    foreach ($name in @("discord", "vibepollo", "playnite")) {
        try { $children[$name] = Start-Component $name } catch { Write-AgentLog "Start failed for ${name}: $($_.Exception.Message)" }
    }
    Write-State "running"
    $healthTick = 0
    while (-not (Test-Path -LiteralPath $stopPath)) {
        foreach ($name in @("discord", "vibepollo", "playnite")) {
            $process = $children[$name]
            if ($null -ne $process -and $process.HasExited) {
                Write-AgentLog "$name exited with code $($process.ExitCode); restarting."
                Start-Sleep -Milliseconds 750
                try { $children[$name] = Start-Component $name } catch { Write-AgentLog "Restart failed for ${name}: $($_.Exception.Message)" }
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
                if ($null -ne $process -and -not $process.HasExited -and -not (Test-ComponentHealth $name)) {
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
    Write-State $(if (Test-Path -LiteralPath $manualStopPath) { "manually_stopped" } else { "stopped" })
    Write-AgentLog "Profile Bridge supervisor stopped."
    if ($ownsMutex) { try { $mutex.ReleaseMutex() } catch {} }
    $mutex.Dispose()
}
