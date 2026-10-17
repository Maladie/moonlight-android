#Requires -RunAsAdministrator
[CmdletBinding(SupportsShouldProcess)]
param(
    [Parameter(Mandatory)]
    [ValidateNotNullOrEmpty()]
    [ValidateScript({ Test-Path -LiteralPath $_ -PathType Leaf })]
    [string]$DllPath
)

$ErrorActionPreference = 'Stop'
if (![Environment]::Is64BitProcess) { throw 'Run this script in 64-bit PowerShell.' }
$clsid = '{9F7D193F-8057-4D2D-88A0-5C1B8D03C441}'
$resolvedDll = (Resolve-Path -LiteralPath $DllPath).Path
$bytes = [IO.File]::ReadAllBytes($resolvedDll)
$peOffset = if ($bytes.Length -ge 0x40) { [BitConverter]::ToInt32($bytes, 0x3c) } else { -1 }
if ($peOffset -lt 0 -or $peOffset + 6 -gt $bytes.Length -or
    [BitConverter]::ToUInt32($bytes, $peOffset) -ne 0x00004550 -or
    [BitConverter]::ToUInt16($bytes, $peOffset + 4) -ne 0x8664) {
    throw 'Credential Provider DLL must be an x64 PE image.'
}

$providerKey = "Registry::HKEY_LOCAL_MACHINE\SOFTWARE\Microsoft\Windows\CurrentVersion\Authentication\Credential Providers\$clsid"
$comServerKey = "Registry::HKEY_LOCAL_MACHINE\SOFTWARE\Classes\CLSID\$clsid\InprocServer32"
$settingsKey = 'Registry::HKEY_LOCAL_MACHINE\SOFTWARE\MoonWaker\WindowsLogin\CredentialProvider'

if ($PSCmdlet.ShouldProcess($clsid, 'Register MoonWaker Credential Provider')) {
    New-Item -Path $comServerKey -Force | Out-Null
    Set-Item -Path $comServerKey -Value $resolvedDll
    New-ItemProperty -Path $comServerKey -Name 'ThreadingModel' -Value 'Apartment' `
        -PropertyType String -Force | Out-Null
    New-Item -Path $providerKey -Force | Out-Null
    Set-Item -Path $providerKey -Value 'MoonWaker Remote Sign-In'
    New-Item -Path $settingsKey -Force | Out-Null
    New-ItemProperty -Path $settingsKey -Name 'Enabled' -Value 1 `
        -PropertyType DWord -Force | Out-Null
    New-ItemProperty -Path $settingsKey -Name 'ProtocolVersion' -Value 1 `
        -PropertyType DWord -Force | Out-Null
}
