# Game Provider Bridge

`GameProviderBridge.py` is the per-profile loopback service for the Steam,
Epic/Legendary and Playnite providers. It also uses the existing `launcher`
role exposed by the installed Sunshine Playnite Connector for Playnite IPC and
never automates the Playnite UI with keystrokes.

It is the profile's **Agent**: Host Control's tray runs it from the installation
(`bridge-source\playnite`) with `--config <install>\profiles\<id>\game-provider\config.json`;
that profile directory holds only its configuration, caches, journal, logs and DPAPI
secrets. The Gateway key is `game_provider_bridge`. `AgentGuard.py` makes the Agent
exit with code 78 when its profile authority is lost (including a child profile), with
code 74 when the registry stays unreadable, with code 75 when another Agent already
serves its port, and with code 70 when its HTTP loop or state lock hangs; see `host-services/README.md` ("Profile processes").

The Bridge exposes one provider-neutral library, current-game state, readiness
and lifecycle events to the authenticated host Gateway. Steam, Epic/Legendary
and Playnite are separate execution providers; Playnite entries use exact GUIDs,
Steam exact numeric AppIDs, and Epic exact Legendary AppNames. Forced process
termination is intentionally unavailable.

Before dispatching a non-Steam game, the Bridge requests `steam://close/bigpicture`
from the verified Steam process in the same Windows user session. Steam itself
remains running. This is a close request, not an acknowledgement of input isolation.
`/observation` also returns `capabilities.host_guide_allowed`, based on the current game's
provider (not other running games). Android filters Guide from every outgoing
controller packet for non-Steam games; local hold gestures and other buttons are
unchanged. Managed sessions block Guide until a correlated host decision arrives,
including after a retained game switch or reconnect.

Steam ownership comes from `IPlayerService/GetOwnedGames`; the active SteamID is
read from the trusted local `loginusers.vdf`. Configure the Web API key for the
profile in Host Control (**Połącz Steam**). The key is stored with
the current Windows user's DPAPI protection and is never returned by health or
Gateway responses. Local `appmanifest_*.acf` files remain the sole authority for
Steam installation state and install directories. Epic ownership and install
state come from `legendary list --json` and `legendary list-installed`.

Steam descriptions and artwork references come from the public Store Browse
response keyed by exact numeric AppID. Epic descriptions and artwork references
come from each exact Legendary record's catalog metadata. Artwork is downloaded
only when Android requests it and is then retained under the profile's
`cache/provider-artwork` directory. The existing `library-cache.json` preserves
the last successful direct metadata when a later provider refresh is offline.

Playnite records whose source is Steam or Epic are ignored; titles are never
correlation keys and their metadata cannot overwrite the direct providers.
Playnite is disabled by default and can be connected or disconnected per Windows
profile in MoonWaker Host Control. Other Playnite entries then remain full
Playnite-provider games, including GOG, emulators and manual entries, with their
Playnite metadata and artwork unchanged.

Steam playtime and last-played time come from the Steam ownership response. A
manifest-only/offline refresh keeps the last known Steam usage until the next
successful API refresh.

Epic playtime is owned by the profile Bridge in `library-cache.json`
(`epic_playtime_seconds`). It imports available Playnite history once, then adds
time between matching verified game-process samples, including games started
outside MoonWaker. Catalog refreshes and Bridge restarts preserve the total.
Steam and native Playnite games are not counted again. Samples are taken every
five seconds; gaps longer than fifteen seconds, process changes and unavailable
probes do not add time. Totals are saved on minute boundaries and when observation
ends, so an abrupt Bridge exit can lose less than a minute. This measures process
lifetime (including in-game pauses), not active input, and cannot recover Epic
sessions missed while the Bridge was offline. Back up the profile library cache
to retain this history when reinstalling the host.

The readiness response is privacy-sensitive. `ready=false` means MoonWaker must
keep its opaque loading surface visible. A timeout is not permission to reveal
the Windows desktop. Readiness requires the Playnite-reported game process, a
visible foreground window on the configured streamed display and three
consecutive samples with identical geometry. Set `streamed_display` to the
profile's Win32 display name (for example `\\.\DISPLAY15`). An empty or
mismatched value deliberately keeps the privacy gate closed. During an active
session the Bridge also enumerates the session's active displays itself
(`StreamDisplayResolver`, Win32 `EnumDisplayMonitors`) and picks the display that
exactly one active display marked
`stream_virtual_display` (a Vibepollo virtual display adapter), however many
physical displays remain active. When no active display is marked virtual and
exactly one display is active, that display is the streamed display. Any other
topology (two or more virtual displays, or several active displays with none
virtual) yields no display. The detected display replaces a stale configured
value, for example when Vibepollo creates its virtual display dynamically.

The current Sunshine connector already supports the launcher handshake,
`launch` commands and game start/stop status. The next integration step extends
that connector with `PatchPlayniteConnector.py`, which Host Control's **Połącz Playnite**
runs (check, then `--apply`) with the installation's Python. The patch has one
version (`WAKEPLAY-CONSOLE-BRIDGE-V17`) applied to the unmodified connector: it is
idempotent, validates exact structural anchors and creates a `.wakeplay-backup` before
changing the installed module. A connector carrying an older MoonWaker patch lacks the
anchors and is reported as unsupported; reinstall the connector, then connect again. It reuses the
connector's existing metadata functions to mirror library snapshots only to the
requesting Bridge. It also adds Playnite's `StartedProcessId` to lifecycle
status so the Bridge never accepts an unrelated foreground window. Restart
Playnite after applying it.

Graceful stop closes the privacy gate first and then sends `WM_CLOSE` only to
top-level windows owned by Playnite's reported game process; it never kills the
process. Restoring Playnite Fullscreen activates an existing Fullscreen window
or starts the official `Playnite.FullscreenApp.exe` beside the running Desktop
app. Both operations wait for the same verified-window gate before MoonWaker
may reveal the stream.

Focusing the running game (`/game/focus`, sent on reconnect and while readiness
reports `target_not_foreground` or `target_not_fullscreen`) acts only on the
verified game window already on the streamed display. When that window does not
fill the display and is resizable with its own maximize button
(`WS_THICKFRAME | WS_MAXIMIZEBOX`, not already maximized), the Bridge maximizes
it in place and re-runs the window probe before the fullscreen gate; fixed-size,
borderless and other windows are left untouched and the request fails. Focus
never restores (un-maximizes) the window.

## Steam operations

For a numeric Steam AppID, the Bridge resolves the current user's Steam install
from `HKCU\Software\Valve\Steam` and invokes `steam.exe` directly with the fixed
`-silent +app_install <appid>` or `-silent +app_uninstall <appid>` argument list.
There is no Playnite fallback. If direct dispatch is unavailable, the request is
rejected; if no authoritative Steam activity appears within 30 seconds, the
operation becomes `attention_required` with `steam_operation_not_started`.
Steam-owned-window UI Automation and verified visual confirmation remain
available for prompts created by the direct operation.

Direct Steam launch first verifies the local installed manifest and opens Big
Picture (`steam://open/bigpicture` for running Steam, `-gamepadui` for a cold
start). The request is sent even if a fullscreen Steam window already exists:
window bounds alone cannot distinguish desktop Steam from Big Picture. After a
stable fullscreen Steam window appears on the streamed monitor (or any monitor
while stream display resolution is pending), the Bridge rechecks session/UAC
safety and invokes the exact Steam executable with `steam://launch/<appid>/Dialog`.
A timeout or unsafe session prevents game dispatch. A dispatch process exit is
not launch success; the existing stable game-window readiness probe remains
authoritative and still requires the resolved streamed monitor.

Steam manifests and library directories remain authoritative for progress and
completion. An incomplete library scan never proves uninstallation, and healthy
manifest absence must remain stable for three samples. On Bridge restart, stale
window handles are cleared, current Steam evidence is inspected after library
metadata returns, and an inactive request may issue the idempotent direct command
once in the new Bridge process. `OperationJournal` remains the only durable
operation store throughout this recovery.

The Bridge appends a correlated operational audit trail to
`playnite-operation-audit.jsonl` beside the profile's `config.json`. Each JSON
line includes `game_id`, operation `kind`, `requested_at`, and the numeric Steam
`app_id`, so entries for one operation generation can be followed across a
Bridge restart. `steam_direct_dispatched` means Windows accepted the direct
`steam.exe` launch; it does not by itself prove Steam started the operation.
`steam_activity_observed` records the first authoritative post-dispatch Steam
activity and identifies `direct` or `restart_observation` as its path. UI
automation success/failure, manual attention, and final manifest-authoritative
completion have separate events. Thus a direct zero-touch success has no
Playnite or automation event, while any click-script use is explicit. The audit
contains no Steam credentials or arbitrary command strings and is append-only
across Bridge restarts.
