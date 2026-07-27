#requires -Version 5.1
[CmdletBinding(SupportsShouldProcess=$true)]
param(
    [string]$BridgeDirectory = "C:\Tools\PlayniteBridge",
    [string]$GatewayDirectory = "C:\Tools\WakePlayGateway",
    [int]$PlaynitePort = 8780,
    [int]$VibepolloPort = 8775,
    [switch]$SkipScheduledTask,
    [switch]$SkipStart
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"
if ($PlaynitePort -lt 1024 -or $PlaynitePort -gt 65535 -or
    $VibepolloPort -lt 1024 -or $VibepolloPort -gt 65535 -or
    $PlaynitePort -eq $VibepolloPort) {
    throw "Bridge ports must be distinct values between 1024 and 65535."
}

$hostServicesRoot = Split-Path -Parent $PSScriptRoot
$source = Join-Path $hostServicesRoot "bridges\playnite"
$gatewaySource = Join-Path $hostServicesRoot "gateway\wakeplay_gateway.py"
$gatewayConfigPath = Join-Path $GatewayDirectory "gateway.json"
if (-not (Test-Path -LiteralPath $source) -or
    -not (Test-Path -LiteralPath $gatewaySource) -or
    -not (Test-Path -LiteralPath $gatewayConfigPath)) {
    throw "The Playnite source or existing flat Gateway installation was not found."
}

if ($PSCmdlet.ShouldProcess($BridgeDirectory, "Install profile-scoped Playnite Bridge")) {
    New-Item -ItemType Directory -Path $BridgeDirectory -Force | Out-Null
    foreach ($file in @(
        "PlayniteBridge.py", "config.example.json", "Start-PlayniteBridge.ps1",
        "Stop-PlayniteBridge.ps1", "PatchPlayniteConnector.py",
        "Install-WakePlayConnectorPatch.ps1", "README.md")) {
        Copy-Item -LiteralPath (Join-Path $source $file) -Destination $BridgeDirectory -Force
    }
    $configPath = Join-Path $BridgeDirectory "config.json"
    if (-not (Test-Path -LiteralPath $configPath)) {
        Copy-Item -LiteralPath (Join-Path $source "config.example.json") `
            -Destination $configPath
    }
    $config = Get-Content -LiteralPath $configPath -Raw | ConvertFrom-Json
    foreach ($entry in @{
        listen_host = "127.0.0.1"
        listen_port = $PlaynitePort
        vibepollo_bridge = "http://127.0.0.1:$VibepolloPort"
    }.GetEnumerator()) {
        if ($null -eq $config.PSObject.Properties[$entry.Key]) {
            $config | Add-Member -NotePropertyName $entry.Key -NotePropertyValue $entry.Value
        } else {
            $config.($entry.Key) = $entry.Value
        }
    }
    $config | ConvertTo-Json -Depth 20 | Set-Content -LiteralPath $configPath -Encoding UTF8

    Copy-Item -LiteralPath $gatewaySource -Destination $GatewayDirectory -Force
    $gateway = Get-Content -LiteralPath $gatewayConfigPath -Raw | ConvertFrom-Json
    $endpoint = "http://127.0.0.1:$PlaynitePort"
    if ($null -eq $gateway.PSObject.Properties["playnite_bridge"]) {
        $gateway | Add-Member -NotePropertyName playnite_bridge -NotePropertyValue $endpoint
    } else { $gateway.playnite_bridge = $endpoint }
    if ($null -eq $gateway.PSObject.Properties["profiles"]) {
        $gateway | Add-Member -NotePropertyName profiles -NotePropertyValue ([pscustomobject]@{})
    }
    if ($null -eq $gateway.profiles.PSObject.Properties["default"]) {
        $gateway.profiles | Add-Member -NotePropertyName default `
            -NotePropertyValue ([pscustomobject]@{ playnite_bridge = $endpoint })
    } elseif ($null -eq $gateway.profiles.default.PSObject.Properties["playnite_bridge"]) {
        $gateway.profiles.default | Add-Member -NotePropertyName playnite_bridge `
            -NotePropertyValue $endpoint
    } else { $gateway.profiles.default.playnite_bridge = $endpoint }
    $gateway | ConvertTo-Json -Depth 20 | Set-Content -LiteralPath $gatewayConfigPath -Encoding UTF8

    if (-not $SkipScheduledTask) {
        $identity = [Security.Principal.WindowsIdentity]::GetCurrent().Name
        $startScript = Join-Path $BridgeDirectory "Start-PlayniteBridge.ps1"
        $action = New-ScheduledTaskAction -Execute "powershell.exe" -Argument (
            "-NoProfile -WindowStyle Hidden -ExecutionPolicy Bypass -File `"$startScript`"")
        $trigger = New-ScheduledTaskTrigger -AtLogOn -User $identity
        $settings = New-ScheduledTaskSettingsSet -RestartCount 3 `
            -RestartInterval (New-TimeSpan -Minutes 1) -ExecutionTimeLimit (New-TimeSpan -Days 3650)
        $principal = New-ScheduledTaskPrincipal -UserId $identity `
            -LogonType Interactive -RunLevel Limited
        Register-ScheduledTask -TaskName "Wake & Play Playnite Bridge (default)" `
            -Action $action -Trigger $trigger -Settings $settings -Principal $principal -Force | Out-Null
    }
    if (-not $SkipStart) { & (Join-Path $BridgeDirectory "Start-PlayniteBridge.ps1") }
    Write-Warning "Restart Gateway and Playnite when ready to activate the new protocol."
}
