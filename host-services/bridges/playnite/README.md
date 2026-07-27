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
