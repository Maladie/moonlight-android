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

Upgrades replace program files only. Existing `config.json` files, paired
Gateway clients, TLS key/certificate files and DPAPI-protected Bridge tokens are
kept. When the installer creates or renews a Vibepollo token, it uses
`bridges\vibepollo\moonwaker-token-scopes.example.json`; this includes `GET`
and `POST` access to `/api/apps` for automatic Playnite app creation plus
scoped `DELETE` access to `/api/apps/*` for Playnite-ID deduplication.
