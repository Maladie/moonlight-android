#requires -Version 5.1
[CmdletBinding()]
param([string]$GatewayDirectory = $PSScriptRoot)

$GatewayDirectory = [IO.Path]::GetFullPath($GatewayDirectory)
$statePath = Join-Path $GatewayDirectory "gateway-supervisor-state.json"
$stopPath = Join-Path $GatewayDirectory "gateway-supervisor-stop"
Remove-Item -LiteralPath (Join-Path $GatewayDirectory "gateway-manually-stopped") -Force -ErrorAction SilentlyContinue
Remove-Item -LiteralPath $stopPath -Force -ErrorAction SilentlyContinue
$service = Get-Service -Name "MoonWakerGateway" -ErrorAction SilentlyContinue
if ($null -ne $service) {
    if ($service.Status -ne [ServiceProcess.ServiceControllerStatus]::Running) {
        Start-Service -Name $service.Name
        $service.WaitForStatus(
            [ServiceProcess.ServiceControllerStatus]::Running,
            [TimeSpan]::FromSeconds(15))
    }
    Write-Host "MoonWaker Gateway service is running."
    return
}
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
