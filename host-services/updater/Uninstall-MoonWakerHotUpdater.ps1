#requires -Version 5.1
#requires -RunAsAdministrator
[CmdletBinding()] param([switch]$PurgeState)
$ErrorActionPreference = "Stop"
$service = Get-Service -Name "MoonWakerHotUpdater" -ErrorAction SilentlyContinue
if ($service) {
    if ($service.Status -ne [ServiceProcess.ServiceControllerStatus]::Stopped) {
        Stop-Service -Name "MoonWakerHotUpdater" -Force
        $service.WaitForStatus([ServiceProcess.ServiceControllerStatus]::Stopped,
            [TimeSpan]::FromSeconds(10))
    }
    & (Join-Path ([Environment]::SystemDirectory) "sc.exe") delete MoonWakerHotUpdater | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "Unable to delete MoonWaker Hot Updater service." }
}
if ($PurgeState) {
    $stateRoot = Join-Path $env:ProgramData "MoonWaker\HotUpdater"
    if ((Split-Path -Leaf $stateRoot) -ne "HotUpdater" -or
        (Split-Path -Leaf (Split-Path -Parent $stateRoot)) -ne "MoonWaker") {
        throw "Refusing to remove an unexpected updater state path."
    }
    if (Test-Path -LiteralPath $stateRoot) {
        Remove-Item -LiteralPath $stateRoot -Recurse -Force
    }
}
