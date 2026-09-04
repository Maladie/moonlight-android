#Requires -RunAsAdministrator
[CmdletBinding(SupportsShouldProcess)]
param()

$ErrorActionPreference = 'Stop'
if (![Environment]::Is64BitProcess) { throw 'Run this script in 64-bit PowerShell.' }
$clsid = '{9F7D193F-8057-4D2D-88A0-5C1B8D03C441}'
$providerKey = "Registry::HKEY_LOCAL_MACHINE\SOFTWARE\Microsoft\Windows\CurrentVersion\Authentication\Credential Providers\$clsid"
$comKey = "Registry::HKEY_LOCAL_MACHINE\SOFTWARE\Classes\CLSID\$clsid"
$settingsKey = 'Registry::HKEY_LOCAL_MACHINE\SOFTWARE\MoonWaker\WindowsLogin\CredentialProvider'

if ($PSCmdlet.ShouldProcess($clsid, 'Unregister only MoonWaker Credential Provider')) {
    foreach ($key in @($providerKey, $comKey, $settingsKey)) {
        if (Test-Path -LiteralPath $key) {
            Remove-Item -LiteralPath $key -Recurse -Force
        }
    }
}
