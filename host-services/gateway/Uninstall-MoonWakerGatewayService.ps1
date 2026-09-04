#requires -Version 5.1
#requires -RunAsAdministrator
[CmdletBinding()]
param()

$ErrorActionPreference = "Stop"
$serviceName = "MoonWakerGateway"
$service = Get-Service -Name $serviceName -ErrorAction SilentlyContinue
if ($null -eq $service) {
    Write-Host "MoonWaker Gateway service is not installed."
    return
}
if ($service.Status -ne [ServiceProcess.ServiceControllerStatus]::Stopped) {
    Stop-Service -Name $serviceName -Force
}
$sc = Join-Path ([Environment]::SystemDirectory) "sc.exe"
& $sc delete $serviceName | Out-Null
if ($LASTEXITCODE -ne 0) { throw "Unable to remove the Gateway service." }
Write-Host "MoonWaker Gateway service was removed; Gateway configuration was preserved."
