# MoonWaker Credential Provider

Native x64 Windows 10/11 Credential Provider for one pending MoonWaker remote
sign-in attempt. It supports only `CPUS_LOGON` and
`CPUS_UNLOCK_WORKSTATION`; it does not implement a filter or network client.

The provider observes `MoonWakerLoginBroker.Provider.v1`, waits for
`Global\MoonWaker.LoginAttempt.v1`, and acquires the password only inside
`GetSerialization`. The Broker's acquire operation is the single-use boundary.
`ReportResult` reports success or failure; failure is never retried by the
provider. Password response and UTF-16 working buffers are explicitly cleared.

Build from a Visual Studio Developer PowerShell with the Desktop development
with C++ workload:

```powershell
.\Build-MoonWakerCredentialProvider.ps1
```

The build runs static checks plus the native protocol/helper tests. It never
registers the DLL. After the host installer has copied the DLL to its final,
administrator-protected path, registration is an explicit administrator action:

```powershell
.\Install-MoonWakerCredentialProvider.ps1 -DllPath 'C:\Program Files\MoonWaker\WindowsLogin\MoonWakerCredentialProvider.dll'
```

Recovery commands affect only MoonWaker's provider setting and registration:

```powershell
.\Disable-MoonWakerCredentialProvider.ps1
.\Enable-MoonWakerCredentialProvider.ps1
.\Uninstall-MoonWakerCredentialProvider.ps1
```

Disabling or unregistering MoonWaker never changes Microsoft or third-party
Credential Provider registrations.

Actual logon, unlock, one-submission behavior, cold boot, failure recovery, and
two-profile isolation must be checked on a disposable VM using
[`../MANUAL_VM_TEST_PLAN.md`](../MANUAL_VM_TEST_PLAN.md). Do not infer LogonUI
compatibility from the native helper tests alone.
