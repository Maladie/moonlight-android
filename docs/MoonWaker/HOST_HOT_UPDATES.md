# MoonWaker Host Hot Updates

Hot updates replace only interpreted Gateway and Bridge runtime files. They do not
update the Credential Provider, Login Broker, Windows services, firewall rules,
native worker executables, dependencies, configuration, credentials, or user data.
Those machine components still require the full elevated installer.
Running that installer also clears any pending hot-update transaction and makes the
full package version authoritative again.

## Local development trust

The full installer installs `MoonWakerHotUpdater` once with administrator rights.
During that setup it pins the SID of the local Windows account running the installer.
That account can then send unsigned `.mwhu` packages without UAC. Re-run the elevated
installer to change the pinned account.

The service accepts packages only from that pinned account (plus SYSTEM and local
administrators for recovery) over
`MoonWakerHotUpdater.v1`. Package bytes are copied into an administrator-only
`%ProgramData%\MoonWaker\HotUpdater` staging directory before validation. The pipe
denies network identities, has a 64 MiB limit, and exposes no command or destination
parameters.

## Package and apply

Create a version-increasing package:

```powershell
.\host-services\updater\New-MoonWakerHotUpdatePackage.ps1 `
  -Version 0.7.107 -Build 2026.09.12 `
  -Files gateway/wakeplay_gateway.py,gateway/provider_control_operation.py,bridges/playnite/GameProviderBridge.py `
  -OutputPath .\MoonWaker-0.7.107.mwhu
```

Apply it without elevation:

```powershell
& '<MoonWaker install root>\updater\Invoke-MoonWakerHotUpdate.ps1' `
  -Package .\MoonWaker-0.7.107.mwhu
```

The `manifest.psd1` is imported only through `Import-PowerShellDataFile`; it is
never executed. Its allowlist is fixed to top-level `.py` files in `gateway` and
`bridges/playnite`, and `.ps1`/`.psm1` files in `bridges/vibepollo` and
`bridges/discord`. Every size and SHA-256 hash must match, undeclared ZIP entries are
rejected, and versions cannot be replayed or downgraded.

The updater stops Gateway and active profile tasks with bounded waits, stages complete
copies beside each affected runtime directory, renames them into place, restarts the
same runtime, and requires Gateway plus the previously active Bridge ports to become
healthy within 30 seconds. Health means the expected `/api/v1/hello` or `/health`
contract response from the expected listener PID, script path and profile; Gateway
also verifies its Windows service executable and the SHA-256 reported by the newly
started Python process. A failed switch or health check rolls all switched directories
back before returning failure.

## Security boundary

- The pinned local account is trusted to author runtime code. Other local users and
  network identities cannot submit packages.
- A package cannot choose commands, destinations, configuration files, native binaries,
  or machine components.
- Staging, version state, updater scripts, and the service executable are writable
  only by SYSTEM and administrators.
- Before touching Gateway, the updater rejects a custom host root whose parent ACL
  lets an unpinned account create, delete, or replace children. Use the full installer
  to repair such a location; the default Program Files location is the intended setup.
- Existing bootstrap paths containing reparse points or foreign ACL entries are rejected;
  replacement binaries are compiled outside the destination and copied only after the
  protected administrator-owned target exists.
- Directory switches are atomic individually and recorded in an administrator-only
  transaction journal. Detected failures roll back immediately; an interrupted machine
  shutdown is rolled back before the updater accepts another package after service start.
- SHA-256 and size are checked on extraction, again in protected staging, and again
  immediately before runtime restart. A monotonic high-water version survives failed
  updates and rollback, preventing replay of an older package.

This is deliberately a single-developer local tool, not a distribution/update channel.
If packages must later come from other machines or users, add signed packages then;
do not broaden the pipe ACL.
