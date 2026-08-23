# Playnite Bridge

This per-profile loopback service uses the existing `launcher` role exposed by
the installed Sunshine Playnite Connector. It talks to Playnite through the
extension's named pipe and never automates the Playnite UI with keystrokes.

The Bridge exposes library pages, current-game state, readiness and lifecycle
events to the authenticated host Gateway. Commands are limited to launching a
Playnite GUID, graceful game stop and restoring Playnite Fullscreen. Forced
process termination is intentionally unavailable.

The readiness response is privacy-sensitive. `ready=false` means MoonWaker must
keep its opaque loading surface visible. A timeout is not permission to reveal
the Windows desktop. Readiness requires the Playnite-reported game process, a
visible foreground window on the configured streamed display and three
consecutive samples with identical geometry. Set `streamed_display` to the
profile's Win32 display name (for example `\\.\DISPLAY15`). An empty or
mismatched value deliberately keeps the privacy gate closed. During an active
session the Bridge also reads its profile's loopback Vibepollo diagnostics and
accepts a display only when exactly one valid Win32 output name is reported;
this safely replaces a stale configured value when Vibepollo selects a virtual
display dynamically.

The current Sunshine connector already supports the launcher handshake,
`launch` commands and game start/stop status. The next integration step extends
that connector with `Install-WakePlayConnectorPatch.ps1`. The patch is
idempotent, validates exact structural anchors and creates a
`.wakeplay-backup` before changing the installed module. It reuses the
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

## Steam operations

For a numeric Steam AppID, the Bridge resolves the current user's Steam install
from `HKCU\Software\Valve\Steam` and invokes `steam.exe` directly with the fixed
`-silent +app_install <appid>` or `-silent +app_uninstall <appid>` argument list.
If direct dispatch is unavailable, or produces no Steam activity within 30
seconds, the Bridge sends the existing Playnite install/uninstall command once.
It captures a fresh window baseline before that fallback, then uses the existing
Steam-owned-window UI Automation and verified visual confirmation path if a
prompt appears. If neither activity nor a usable prompt appears within another
20 seconds, the operation becomes `attention_required` with `steam.exe` as the
launcher so the manual desktop flow remains available.

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
activity and identifies `direct`, `playnite_fallback`, or `restart_observation`
as its path. Fallback dispatch, UI automation success/failure, manual attention,
and final manifest-authoritative completion have separate events. Thus a direct
zero-touch success has no Playnite or automation event, while any click-script
use is explicit. The audit contains no Steam credentials or arbitrary command
strings and is append-only across Bridge restarts.
