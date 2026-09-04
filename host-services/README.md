# MoonWaker host services

This directory contains the versioned Discord, Playnite and Vibepollo Bridges,
the authenticated Gateway, profile supervisor, installers and Host Control
source. Runtime configuration and secrets are deliberately not stored here.

Build and test the complete host installer from the repository root:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\Build-HostServices.ps1
```

The output is written to `host-services\dist`. The script runs the Python tests,
compiles Host Control and builds the installer directly from the checked-in C#
source with the complete host-services payload embedded as a resource.

The installer registers the automatic Gateway and Login Broker services, the
x64 MoonWaker Credential Provider, Host Control/Configurator, and a Private
LocalSubnet firewall rule. It never asks for a Windows account or password;
profiles, credentials, and device grants are configured afterward in Host
Control.

Upgrades replace program files only. Existing `config.json` files, paired
Gateway clients, TLS key/certificate files and DPAPI-protected Bridge tokens are
kept. After adding a profile, use **Integracje** in Host Control to enter the
Discord application data and either paste or automatically request a Vibepollo
token. Automatic token creation uses
`bridges\vibepollo\moonwaker-token-scopes.example.json`; this includes `GET`
and `POST` access to `/api/apps` for automatic Playnite app creation plus
scoped `DELETE` access to `/api/apps/*` for Playnite-ID deduplication.

An elevated service-only uninstall preserves profiles, Gateway pairing, and LSA
credentials:

```powershell
.\install\Uninstall-MoonWakerHostServices.ps1
```

For a full authentication-component uninstall, explicitly purge configured
Broker credentials before Broker removal:

```powershell
.\install\Uninstall-MoonWakerHostServices.ps1 -PurgeCredentials
```

Both modes remove only MoonWaker services and MoonWaker's provider registration.
Purge mode additionally removes configured Broker credentials and known
MoonWaker startup entries. Neither mode deletes profile/configuration files,
Windows accounts, or built-in Credential Providers.
