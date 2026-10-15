# MoonWaker host services

This directory contains the versioned Playnite and (optional) Discord Bridges, the
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
and shows "Tray działa jako administrator"; the installer reopens the tray it closed through the
signed-in user's Explorer, so it runs unelevated. A full installation closes the trays
of every signed-in account (their hosted processes end with them); the installer
reopens only its own account's tray, and the trays of other signed-in accounts come
back at their next sign-in.

- **Agent** = `bridge-source\playnite\GameProviderBridge.py --config
  profiles\<id>\game-provider\config.json` under `pythonw.exe` from the directory
  of the Gateway service's `--python` (the one Python the installation trusts).
- **Discord Bridge** = `bridge-source\discord\DiscordBridge.ps1 -ProfileDirectory
  profiles\<id>\discord`, only while the Agent runs, when Discord is installed and
  when the profile has a `discord_bridge` endpoint, `discord_bridge_config.json` and
  `client_secret.dpapi`.

Discord is an optional component. It is installed exactly when
`bridge-source\discord\DiscordBridge.ps1` exists, which is when the package was built
with `Build-HostServices.ps1 -WithDiscord`; only such a package also carries
`gateway\MoonWakerDiscordAudioWorker.exe`. An installation from a package without
Discord removes both. Without Discord, Host Control shows no Discord card on
**Integracje**, the tray starts no Discord Bridge, and the
Gateway reports `discord` and `discord_audio` as unavailable, as for an offline
Bridge. A hot update with `bridges/discord` files is refused by an installation
without Discord (`Required runtime target is missing`).

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
stops responding for two checks in a row (about 20 s). Saving Discord or Playnite
settings in Host Control restarts the hosted processes.

Profile directories hold only data, configuration and secrets; code runs from the
installation.

## Host Control

`control\MoonWakerHostControl.exe` is one WPF application on .NET Framework 4.8 (built into Windows 10 1903+), compiled by
`Build-MoonWakerHostControl.ps1` with the framework's `csc.exe` (references
`PresentationFramework`, `PresentationCore`, `WindowsBase`, `System.Xaml`; no MSBuild or
NuGet). The UI is built in C# (`control\HostControlWindow.cs` for the window and its pages,
`control\HostControlUi.cs` for the palette, building blocks and dialogs); only the scroll
bar's template is a XAML string. The tray (`TrayHost` in `MoonWakerHostControl.cs`) keeps the
WinForms `NotifyIcon`, the Ctrl+Alt+Shift+End hotkey and its secure-desktop pipe; closing or
minimising the window hides it to the tray.

The window follows the accepted mock-up (`.claude/handoffs/mockups/host-control`): navigation
**Przegląd**, **Profile**, **Urządzenia**, **Integracje**, **Diagnostyka** (Ctrl+1…5; F5
refreshes and re-probes the microphone; Esc hides), a status bar with progress and messages,
and at most one notice per page with one action (a failed action, or Diagnostyka's first
problem with **Napraw**). It is dark by default and light when
`HKCU\Software\Microsoft\Windows\CurrentVersion\Themes\Personalize\AppsUseLightTheme` is 1
(read only, followed live), with a dark title bar in the dark theme and per-monitor DPI
awareness (manifest PerMonitorV2, `TargetFramework` 4.8). Every fact on a page comes from
`HostBackend.ReadStatus`; the page is rebuilt only when a shown fact changes. Profile, PIN,
remote sign-in, device, child-profile, removal and Vibepollo-token edits open the elevated
Configurator with its `--mode` (buttons with the UAC shield); Steam key and Discord data are
asked in WPF dialogs and Playnite's directory in the system folder picker. Paired TVs and
their grants are shown read-only from `gateway.json` (name, `paired_at`, `last_seen_at` or
the newer `client-activity.json`, `profile_grants`; never the token digest). The Gateway
publishes no record of the running session, so **Zamknij sesję** is offered without its
game, TV or duration. The **Pierwsza konfiguracja** card lists four steps (Vibepollo token,
a paired TV, this account's profile, a connected library) and is shown exactly while one
of them is not met.

Host Control's backend (`control\HostControlBackend.cs`) runs inside the tray; no
PowerShell is involved. Its status refresh (every 2 s) starts no process and changes
nothing: it reads what the Gateway publishes (`gateway.json`, `gateway-service-state.json`,
`gateway-runtime.json`, `pairing-code.json`, `client-activity.json`; always with delete sharing, so the atomic
replacement of a file is never blocked), the size and time of `vibepollo-api.json` (whether the
computer's token is saved), the loopback `/health` of each profile's Agent and
`/health` of its Discord Bridge, the tray's own process host, and, for the signed-in
account's profile only, its Steam key (DPAPI), Legendary's `user.json`, the Playnite
settings and the Discord files (with the public Client ID). The microphone worker's probe
runs only when the window opens and on F5.

The account's actions use the existing channels: a pairing code is written in place into
`pairing-code.json` (only its SHA-256, valid 10 minutes); **Zamknij sesję** writes
`host-close-request.json` and reads `host-close-result.json`
(`gateway\TRANSITION_CONTRACT.md`); the Steam Web API key and the Discord client secret
are encrypted with the account's DPAPI in the tray's process; Epic signs in and out
through Legendary with `LEGENDARY_CONFIG_PATH` = `profiles\<id>\state\legendary`;
**Połącz** (Playnite) checks and applies `PatchPlayniteConnector.py` with the
installation's Python and points the Agent's `config.json` at Playnite. Every profile
action requires the profile's `windows_account_sid` to be the signed-in account.

The one **Napraw** restarts what does not work and changes no setting: the
`MoonWakerGateway` service when it is not running or runs another version than the
installed one (the installer's service DACL lets interactive users start and stop it,
so no UAC prompt), and this account's hosted processes when its Agent or its configured
Discord Bridge does not answer. A tray running elevated, or an Agent blocked after losing
its profile authority (exit 78), is not restarted: Napraw names the cause instead. The
Gateway is an automatic service; Host Control has no start or stop for it.

Everything that needs administrator rights runs in `MoonWakerHostConfigurator.exe`
after UAC: profiles, devices, remote sign-in, child profiles and the Vibepollo token. A
new profile's data directory is created there as well (`ProfileProvisioning`): exactly
`profiles\<id>` without reparse points, never an existing one, the Agent's and, with
Discord installed, Discord Bridge's configuration seeded from `bridge-source`'s
examples with the ports `ReserveProfile` allocated, then a protected ACL on the whole
tree, owned by Administrators (SYSTEM and Administrators full, the profile's account
Modify), the root last. The registry entry is written by the Configurator under the
`gateway.json.lock` byte lock before and after that, as before.

Build and test the complete host installer from the repository root:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\Build-HostServices.ps1
```

Add `-WithDiscord` to include the optional Discord Bridge and its audio worker.
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
Vibepollo token are kept. With Discord installed, use the Discord card on **Integracje** in Host
Control after adding a profile to enter the Discord application data.

Vibepollo is one per computer and so is its token. **Pobierz / odnów token** on Host
Control's Integracje page opens the elevated Configurator, which either requests a
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
