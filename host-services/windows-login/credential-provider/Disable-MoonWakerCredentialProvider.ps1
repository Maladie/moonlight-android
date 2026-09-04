#Requires -RunAsAdministrator
[CmdletBinding(SupportsShouldProcess)]
param()

$ErrorActionPreference = 'Stop'
if (![Environment]::Is64BitProcess) { throw 'Run this script in 64-bit PowerShell.' }
$settingsKey = 'Registry::HKEY_LOCAL_MACHINE\SOFTWARE\MoonWaker\WindowsLogin\CredentialProvider'
if ($PSCmdlet.ShouldProcess('MoonWaker Credential Provider', 'Disable enumeration')) {
    New-Item -Path $settingsKey -Force | Out-Null
    New-ItemProperty -Path $settingsKey -Name 'Enabled' -Value 0 `
        -PropertyType DWord -Force | Out-Null
    try {
        $event = [Threading.EventWaitHandle]::new(
            $false, [Threading.EventResetMode]::AutoReset,
            'Global\MoonWaker.LoginAttempt.v1')
        $event.Set() | Out-Null
        $event.Dispose()
    } catch { }
}
