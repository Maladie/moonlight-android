# MoonWaker Host Hot Updates

Hot updates replace only interpreted Gateway and Bridge runtime files. They do not
update the Credential Provider, Login Broker, Windows services, firewall rules,
native worker executables, dependencies, configuration, credentials, or user data.
Those machine components still require the full elevated installer.
Running that installer also clears any pending hot-update transaction and makes the
full package version authoritative again.

A change whose interpreted files depend on new machine state also requires the full
installer, even when every file it touches is on the allowlist. The packager has no
marker for this, so check it before building a package. Example: the change that
removed the Vibepollo Bridge (the Gateway's in-process `gateway/vibepollo_client.py`)
needs the installer to create the protected `gateway\vibepollo-api.json`, grant the
Gateway service read access to Vibepollo's configuration files, and install the
Configurator with `--mode vibepollo` and `--mode vibepollo-access`. A hot update of the
Gateway files alone leaves the Gateway without a Vibepollo token.

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
`bridges/playnite`, and `.ps1`/`.psm1` files in `bridges/discord`; the last apply only
to an installation with the optional Discord component (`bridge-source\discord`),
any other installation refuses them as a missing runtime target. Every size and SHA-256 hash must match, undeclared ZIP entries are
rejected, and versions cannot be replayed or downgraded.

Bridge code exists only in the installation (`bridge-source`); profile directories
hold data, so they are never staged or switched. The updater stops Gateway and its
workers with bounded waits, stages complete copies beside each affected runtime
directory, renames them into place and starts Gateway again. It then ends every
Agent and Discord Bridge process running from the installation; each account's Host
Control tray starts them again on the activated code. Gateway plus the Bridge ports
of the profiles whose processes ran before the update must become healthy within
30 seconds. Health means the expected `/api/v1/hello` or `/health`
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
- Before touching Gateway, the updater rejects a host root (always
  `%ProgramFiles%\MoonWaker`) whose ACL lets an unpinned account create, delete, or
  replace children. Use the full installer to repair it.
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
