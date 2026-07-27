#requires -Version 5.1
[CmdletBinding()]
param([string]$GatewayDirectory = $PSScriptRoot)

$GatewayDirectory = [IO.Path]::GetFullPath($GatewayDirectory)
$statePath = Join-Path $GatewayDirectory "gateway-supervisor-state.json"
$stopPath = Join-Path $GatewayDirectory "gateway-supervisor-stop"
Remove-Item -LiteralPath $stopPath -Force -ErrorAction SilentlyContinue
if (Test-Path -LiteralPath $statePath) {
    try {
        $state = Get-Content -LiteralPath $statePath -Raw | ConvertFrom-Json
        $existing = Get-Process -Id ([int]$state.pid) -ErrorAction SilentlyContinue
        if ($existing -and -not $existing.HasExited) { Write-Host "MoonWaker Gateway is already running."; return }
    } catch {}
}
$supervisor = Join-Path $GatewayDirectory "MoonWakerGatewaySupervisor.ps1"
$process = Start-Process -FilePath powershell.exe -WindowStyle Hidden -PassThru -ArgumentList @(
    "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", $supervisor, "-GatewayDirectory", $GatewayDirectory)
Write-Host "MoonWaker Gateway supervisor started (PID $($process.Id))."
