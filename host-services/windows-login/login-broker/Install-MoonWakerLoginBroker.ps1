#requires -Version 5.1
#requires -RunAsAdministrator
[CmdletBinding()]
param(
    [string]$BinaryPath = (Join-Path $PSScriptRoot "dist\MoonWakerLoginBroker.exe"),
    [string]$InstallDirectory = (Join-Path $env:ProgramFiles "MoonWaker\windows-login\login-broker"),
    [switch]$SkipStart
)

$ErrorActionPreference = "Stop"
$serviceName = "MoonWakerLoginBroker"
$displayName = "MoonWaker Login Broker"
$source = (Resolve-Path -LiteralPath $BinaryPath).Path
if (-not (Test-Path -LiteralPath $source -PathType Leaf)) {
    throw "Login Broker binary was not found: $BinaryPath"
}

New-Item -ItemType Directory -Path $InstallDirectory -Force | Out-Null
$destination = Join-Path $InstallDirectory "MoonWakerLoginBroker.exe"
$service = Get-Service -Name $serviceName -ErrorAction SilentlyContinue
if ($null -ne $service -and $service.Status -ne [ServiceProcess.ServiceControllerStatus]::Stopped) {
    Stop-Service -Name $serviceName -Force
}
if (-not $source.Equals([IO.Path]::GetFullPath($destination),
        [StringComparison]::OrdinalIgnoreCase)) {
    Copy-Item -LiteralPath $source -Destination $destination -Force
}

$quotedBinary = '"{0}"' -f $destination
if ($null -eq $service) {
    New-Service -Name $serviceName -BinaryPathName $quotedBinary -DisplayName $displayName `
        -Description "Stores MoonWaker Windows sign-in credentials and serves local login requests." `
        -StartupType Automatic | Out-Null
}
$serviceConfig = Get-CimInstance Win32_Service -Filter "Name='$serviceName'"
$change = Invoke-CimMethod -InputObject $serviceConfig -MethodName Change -Arguments @{
    PathName = $quotedBinary
    StartMode = "Automatic"
    StartName = "LocalSystem"
}
if ($change.ReturnValue -ne 0) {
    throw "Could not configure the Login Broker as LocalSystem (Win32_Service.Change $($change.ReturnValue))."
}
if (-not $SkipStart) { Start-Service -Name $serviceName }
Get-Service -Name $serviceName
