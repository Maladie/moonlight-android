# MoonWaker host services

This directory contains the versioned Discord and Playnite Bridges, the
authenticated Gateway (which talks to Vibepollo in process), installers and Host
Control source. Runtime configuration and secrets are deliberately not stored here.

## Profile processes

Host Control's tray (`control\MoonWakerHostControl.exe --tray`, one instance per
Windows session) hosts the processes of the profile whose `windows_account_sid` is
the signed-in account; the Configurator allows one standard profile per account and
child profiles have no processes. The installer writes one machine `Run` entry
(`HKLM\...\CurrentVersion\Run\MoonWaker Host Control`), so the tray starts for
every account at sign-in, also for profiles the elevated Configurator creates; a
tray of an account without a profile exits at once. The tray menu has no exit, and
closing the window only hides it. A tray running with an elevated token hosts nothing
and shows `TRAY JAKO ADMIN`; the installer reopens the tray it closed through the
signed-in user's Explorer, so it runs unelevated. A full installation closes the trays
of every signed-in account (their hosted processes end with them); the installer
reopens only its own account's tray, and the trays of other signed-in accounts come
back at their next sign-in.

- **Agent** = `bridge-source\playnite\GameProviderBridge.py --config
  profiles\<id>\game-provider\config.json` under `pythonw.exe` from the directory
  of the Gateway service's `--python` (the one Python the installation trusts).
- **Discord Bridge** = `bridge-source\discord\DiscordBridge.ps1 -ProfileDirectory
  profiles\<id>\discord`, only while the Agent runs and when the profile has a
  `discord_bridge` endpoint, `discord_bridge_config.json` and `client_secret.dpapi`.

Both children are created suspended, assigned to a Job Object with kill-on-close
(they end with the tray) and only then resumed; silent breakaway lets games, Steam or
Playnite they start outlive it. After an exit the tray restarts the child after 2 s,
doubling to 30 s; a run of 60 s resets the backoff. A stopped child is killed without
waiting, and nothing new starts until it has exited. The Agent binds its port
exclusively before any startup effect; a second Agent on a served port exits with
code 75 (plain backoff). The Agent checks its own authority at start and every 2 s
(standard profile, enabled, no `deletion_tombstone`, `profile_root` =
`<install>\profiles\<id>` without reparse points, SID of the process =
`windows_account_sid`) and exits with code 78 when it is lost; the tray then stops
Discord too and starts neither until that profile's registry entry changes. A registry
that stays unreadable for about 10 s is not a revocation: the Agent exits with code 74
and the tray restarts it after the usual backoff.
An Agent watchdog exits with code 70 when the HTTP serve loop or the state lock
stops responding for two checks in a row (about 20 s). **Napraw** in Host Control
restarts the hosted processes; saving integrations or Playnite does it as well.

Profile directories hold only data, configuration and secrets; code runs from the
installation.

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

The installation directory is always `%ProgramFiles%\MoonWaker` (64-bit Program
Files) and cannot be chosen: the installer, `Install-WakePlayHost.ps1`, the Gateway
and Hot Updater installers and the uninstaller take no directory parameter. The machine
`Run` entry starts the tray, and through it the Agent, from there for every account,
so the directory's parent must be one that standard users cannot rename or create in.

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
MoonWaker startup entries. Both modes remove the machine `Run` entry of the tray. Neither mode deletes profile/configuration files,
Windows accounts, or built-in Credential Providers.
