# MoonWaker Login Broker

`MoonWakerLoginBroker` is a small .NET Framework Windows service that runs as LocalSystem. It validates local Windows credentials, stores them as LSA private data, and releases a password only to the LocalSystem Credential Provider channel for one bounded login attempt.

## Build and test

```powershell
.\Test-MoonWakerLoginBroker.ps1
.\Build-MoonWakerLoginBroker.ps1
```

The tests compile with the Windows .NET Framework `csc.exe` and use fake account, secret, clock, and session backends. They do not call `LogonUser`, LSA, or WTS session APIs.

To install or update the service after building, run an elevated PowerShell:

```powershell
.\Install-MoonWakerLoginBroker.ps1
```

To stop and remove it:

```powershell
.\Uninstall-MoonWakerLoginBroker.ps1
```

The service-only uninstall intentionally preserves credentials. A full product
uninstall first runs `Remove-MoonWakerLoginCredentials.ps1`, which sends the
existing delete operation for each configured Gateway profile while the Broker
is still running; it never reads a password.

## Local IPC contract

All pipes use the Configurator's bounded binary v1 framing: request magic `MWLB`, response magic `MWLR`, UTF-8 TLV fields, at most 8 fields, 4096 bytes per field, and 32768 bytes per message.

- `MoonWakerLoginBroker.Management.v1`: LocalSystem and elevated administrators. Operations 1–6 match `LoginBrokerClient`: list supported enabled local accounts, configure, test, delete, credential state, and session state. List returns compact JSON in response field 3. Profile/SID/account/password are fields 1/2/3/4; delete nonce/generation fields 5/6 are accepted and ignored.
- `MoonWakerLoginBroker.Gateway.v1`: local authenticated users. Operation 1 begins an attempt with client/profile/request/SID/account in fields 1–5. Operation 2 reads state and operation 3 cancels with client/profile/request/attempt in fields 1–4. Operation 4 reports coarse session and credential state for a profile. Operation 5 reports v1 availability only when the Credential Provider is enabled, registered, and its DLL exists. Operation 6 explicitly switches the local console session with the same client/profile/request/SID/account binding as operation 1. The attempt id is response field 3.
- `MoonWakerLoginBroker.Provider.v1`: LocalSystem only. Operation 1 immediately observes the oldest pending attempt; response fields 3–8 contain attempt/client/profile/request/SID/account, or state `idle`. Operation 2 acquires once using attempt/client/profile/request fields 1–4 and returns account/password/SID in fields 3–5. Operation 3 reports `success` or `failure` in field 5 and an optional reason in field 6.

The auto-reset event `Global\MoonWaker.LoginAttempt.v1` wakes the LocalSystem provider worker when a new attempt becomes pending. Observe remains immediate, so service shutdown never waits for a provider poll.

Attempts live in memory for two minutes, are idempotent by client/profile/request, and bind every state/acquire/report call to the same values. A password is issued once. A provider failure makes the attempt `action_required` and deletes the LSA credential, so another submit requires explicit reconfiguration.

The service tracks Windows lock/unlock notifications. Session state distinguishes `active`, `locked`, `connected`, `disconnected`, `signed_out`, `other_user_active`, and `unknown`.
The explicit switch operation uses `WTSDisconnectSession`; it never logs off a
session or invokes `tsdiscon.exe`. It rejects RDP, multiple or unresolved active
sessions, disabled Fast User Switching, and disconnect failures before creating
the target credential attempt. A successful disconnect without a stored target
credential records terminal `attention_required` state for idempotent replay.
