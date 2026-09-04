#Requires -RunAsAdministrator
[CmdletBinding(SupportsShouldProcess)]
param()

$ErrorActionPreference = 'Stop'
if (![Environment]::Is64BitProcess) { throw 'Run this script in 64-bit PowerShell.' }
$clsid = '{9F7D193F-8057-4D2D-88A0-5C1B8D03C441}'
$comServerKey = "Registry::HKEY_LOCAL_MACHINE\SOFTWARE\Classes\CLSID\$clsid\InprocServer32"
if (!(Test-Path -LiteralPath $comServerKey)) {
    throw 'MoonWaker Credential Provider is not registered. Run the install script first.'
}
$dll = (Get-Item -LiteralPath $comServerKey).GetValue('')
if ([string]::IsNullOrWhiteSpace($dll) -or !(Test-Path -LiteralPath $dll -PathType Leaf)) {
    throw 'The registered MoonWaker Credential Provider DLL is missing.'
}

$settingsKey = 'Registry::HKEY_LOCAL_MACHINE\SOFTWARE\MoonWaker\WindowsLogin\CredentialProvider'
if ($PSCmdlet.ShouldProcess('MoonWaker Credential Provider', 'Enable enumeration')) {
    New-Item -Path $settingsKey -Force | Out-Null
    New-ItemProperty -Path $settingsKey -Name 'Enabled' -Value 1 `
        -PropertyType DWord -Force | Out-Null
    try {
        $event = [Threading.EventWaitHandle]::new(
            $false, [Threading.EventResetMode]::AutoReset,
            'Global\MoonWaker.LoginAttempt.v1')
        $event.Set() | Out-Null
        $event.Dispose()
    } catch { }
}
