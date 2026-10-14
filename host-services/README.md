# MoonWaker host services

This directory contains the versioned Discord and Playnite Bridges, the
authenticated Gateway (which talks to Vibepollo in process), profile supervisor,
installers and Host Control source. Runtime configuration and secrets are deliberately not stored here.

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
Gateway clients, TLS key/certificate files, DPAPI-protected Bridge tokens and the
Vibepollo token are kept. After adding a profile, use **Integracje** in Host
Control to enter the Discord application data.

Vibepollo is one per computer and so is its token. **Vibepollo…** in Host
Control's Gateway panel opens the elevated Configurator, which either requests a
token with the Vibepollo administrator login (used once, never stored) or takes
a pasted one, checks it against the API and writes `gateway\vibepollo-api.json`.
The installer creates that file readable only by Administrators, SYSTEM and the
Gateway service. The installer and every token save then grant the service read
access to Vibepollo's `sunshine.conf` and the state file and certificate it names
(by default `sunshine_state.json` and `credentials\cacert.pem`), plus traverse
on the directories down to them; the installer warns about a file that does not
exist yet, and saving the token again repairs access after Vibepollo regenerates
its certificate or is updated. Requested
tokens use `control\vibepollo-token-scopes.json`: exactly the calls the Gateway
makes, including `GET` and `POST` on `/api/apps` for the neutral
`MoonWaker Stream` application, and no `DELETE`.

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
