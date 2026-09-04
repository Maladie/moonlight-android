[CmdletBinding()]
param(
    [string]$NativeTestPath = ''
)

$ErrorActionPreference = 'Stop'

function Assert-Contains([string]$Text, [string]$Needle, [string]$Message) {
    if (!$Text.Contains($Needle)) { throw $Message }
}

$sourcePath = Join-Path $PSScriptRoot 'MoonWakerCredentialProvider.cpp'
$protocolPath = Join-Path $PSScriptRoot 'BrokerProtocol.cpp'
$source = Get-Content -Raw -LiteralPath $sourcePath
$protocol = Get-Content -Raw -LiteralPath $protocolPath
$providerProject = Get-Content -Raw -LiteralPath (
    Join-Path $PSScriptRoot 'MoonWakerCredentialProvider.vcxproj')
$testProject = Get-Content -Raw -LiteralPath (
    Join-Path $PSScriptRoot 'CredentialProviderProtocolTests.vcxproj')
$definition = Get-Content -Raw -LiteralPath (
    Join-Path $PSScriptRoot 'MoonWakerCredentialProvider.def')
$build = Get-Content -Raw -LiteralPath (
    Join-Path $PSScriptRoot 'Build-MoonWakerCredentialProvider.ps1')

Assert-Contains $source 'public ICredentialProvider' 'ICredentialProvider is missing.'
Assert-Contains $source 'public ICredentialProviderCredential2' 'Credential implementation is missing.'
Assert-Contains $source 'public ICredentialProviderSetUserArray' `
    'V2 provider user-array binding is missing.'
Assert-Contains $source 'UserArrayContainsSid(userArray_, attempt.sid)' `
    'V2 credential SID is not correlated with the LogonUI user array.'
Assert-Contains $source 'CPUS_LOGON' 'Logon usage scenario is missing.'
Assert-Contains $source 'CPUS_UNLOCK_WORKSTATION' 'Unlock usage scenario is missing.'
Assert-Contains $source 'CPUS_CREDUI' 'CredUI must be rejected explicitly.'
Assert-Contains $source 'CPUS_CHANGE_PASSWORD' 'Password-change usage must be rejected explicitly.'
Assert-Contains $source 'CPUS_PLAP' 'PLAP usage must be rejected explicitly.'
Assert-Contains $source 'MoonWakerLoginBroker.Provider.v1' 'Provider Broker pipe is missing.'
Assert-Contains $source 'Global\\MoonWaker.LoginAttempt.v1' 'Broker notification event is missing.'
Assert-Contains $source 'CredentialsChanged' 'LogonUI event refresh is missing.'
Assert-Contains $source 'RegisterHotKey(nullptr, kStreamHotkeyId' `
    'Secure-desktop stream hotkey registration is missing.'
Assert-Contains $source 'MoonWakerHostControl.StreamHotkey.' `
    'Secure-desktop hotkey does not signal the per-session Host Control pipe.'
Assert-Contains $source 'ProcessIdToSessionId' `
    'Secure-desktop hotkey pipe is not isolated to its Windows session.'
Assert-Contains $source 'BrokerCall(kObserve' 'Pending-attempt observation is missing.'
Assert-Contains $source 'broker.Acquire(attempt_' 'Single-use acquisition is missing.'
Assert-Contains $source 'CredProtectW' 'Password protection before serialization is missing.'
Assert-Contains $source 'KERB_INTERACTIVE_UNLOCK_LOGON' 'Windows logon/unlock serialization is missing.'
Assert-Contains $source 'KerbInteractiveUnlockLogonPack' 'Packed Kerberos credential helper is missing.'
Assert-Contains $source 'KerbInteractiveLogon' 'CPUS_LOGON message type is missing.'
Assert-Contains $source 'KerbWorkstationUnlockLogon' 'CPUS_UNLOCK message type is missing.'
if ($source -match 'CredPackAuthenticationBufferW') {
    throw 'CredPackAuthenticationBuffer must not be used for local-user unlock.'
}
Assert-Contains $source 'RetrieveNegotiatePackage' 'Negotiate authentication package lookup is missing.'
Assert-Contains $source 'BrokerClient().Report' 'ReportResult does not report to Broker.'
Assert-Contains $source 'windows_logon_failed' 'Failure result is not deterministic.'
Assert-Contains $source 'SecureZeroMemory' 'Sensitive buffers are not explicitly cleared.'
Assert-Contains $source 'value == 1' 'Fail-closed recovery switch is missing.'
Assert-Contains $source '9f7d193f' 'Fixed provider CLSID is missing.'
Assert-Contains $source 'IsSystemPipeServer' 'Broker server identity validation is missing.'
Assert-Contains $protocol 'kMaximumMessageBytes' 'Bounded protocol parser is missing.'
Assert-Contains $protocol 'reply->Find(field.key)' 'Protocol duplicate-field rejection is missing.'
Assert-Contains $definition 'DllGetClassObject' 'COM activation export is missing.'
Assert-Contains $definition 'DllCanUnloadNow' 'COM unload export is missing.'

if ($source -match '(?i)ICredentialProviderFilter|WinHttp|WinInet|WSAStartup|HttpSendRequest|InternetOpen|LookupAccountName') {
    throw 'Credential Provider must not filter providers or perform network I/O.'
}
if ($providerProject -notmatch '<ConfigurationType>DynamicLibrary</ConfigurationType>' -or
    $providerProject -match 'Include="[^\"]*\|Win32"' -or
    $providerProject -notmatch 'Release\|x64') {
    throw 'Provider project must be an x64 native DLL project.'
}
if ($testProject -notmatch '<ConfigurationType>Application</ConfigurationType>' -or
    $testProject -match 'Include="[^\"]*\|Win32"' -or
    $testProject -notmatch 'Release\|x64') {
    throw 'Protocol tests must be an x64 native executable.'
}
if ($build -match '(?i)Install-MoonWakerCredentialProvider|regsvr32|Credential Providers\\') {
    throw 'Build must never install or register the Credential Provider.'
}

$expectedClsid = '{9F7D193F-8057-4D2D-88A0-5C1B8D03C441}'
foreach ($scriptName in @(
        'Install-MoonWakerCredentialProvider.ps1',
        'Uninstall-MoonWakerCredentialProvider.ps1',
        'Enable-MoonWakerCredentialProvider.ps1')) {
    $script = Get-Content -Raw -LiteralPath (Join-Path $PSScriptRoot $scriptName)
    Assert-Contains $script $expectedClsid "$scriptName does not use the fixed CLSID."
    if ($script -match '(?i)regsvr32|Credential Provider Filters|ExcludeCredentialProviders') {
        throw "$scriptName may affect providers outside MoonWaker."
    }
}
$uninstall = Get-Content -Raw -LiteralPath (
    Join-Path $PSScriptRoot 'Uninstall-MoonWakerCredentialProvider.ps1')
$install = Get-Content -Raw -LiteralPath (
    Join-Path $PSScriptRoot 'Install-MoonWakerCredentialProvider.ps1')
Assert-Contains $install "-Name 'ProtocolVersion' -Value 1" `
    'Install does not publish the Broker protocol version.'
Assert-Contains $uninstall 'Unregister only MoonWaker Credential Provider' `
    'Uninstall scope is not explicit.'
$disable = Get-Content -Raw -LiteralPath (
    Join-Path $PSScriptRoot 'Disable-MoonWakerCredentialProvider.ps1')
Assert-Contains $disable "-Value 0" 'Disable command does not set the recovery switch.'

if (![string]::IsNullOrWhiteSpace($NativeTestPath)) {
    $resolvedTest = (Resolve-Path -LiteralPath $NativeTestPath).Path
    & $resolvedTest
    if ($LASTEXITCODE -ne 0) { throw 'Native protocol/helper tests failed.' }
}

Write-Host 'MoonWaker Credential Provider static validation passed.'
