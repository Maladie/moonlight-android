#requires -Version 5.1
#requires -RunAsAdministrator
[CmdletBinding()]
param([string]$InstallDirectory = (Join-Path $env:ProgramFiles "MoonWaker\windows-login\login-broker"))

$ErrorActionPreference = "Stop"
$serviceName = "MoonWakerLoginBroker"
$service = Get-Service -Name $serviceName -ErrorAction SilentlyContinue
if ($null -ne $service) {
    if ($service.Status -ne [ServiceProcess.ServiceControllerStatus]::Stopped) {
        Stop-Service -Name $serviceName -Force
    }
    $sc = Join-Path $env:SystemRoot "System32\sc.exe"
    & $sc delete $serviceName | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "Could not remove the Login Broker service." }
}

$binary = Join-Path $InstallDirectory "MoonWakerLoginBroker.exe"
Remove-Item -LiteralPath $binary -Force -ErrorAction SilentlyContinue
if ((Test-Path -LiteralPath $InstallDirectory -PathType Container) -and
    -not (Get-ChildItem -LiteralPath $InstallDirectory -Force | Select-Object -First 1)) {
    Remove-Item -LiteralPath $InstallDirectory -Force
}
Write-Host "MoonWaker Login Broker service was removed. Stored credentials are intentionally preserved; a full MoonWaker uninstall must run Remove-MoonWakerLoginCredentials.ps1 before this script."
