# Console transition contract

## Current transport

`GET /api/v1/playnite/events` is an authenticated long-poll channel. The Android
client sends `after=<sequence>` and `transition_id=<id>`. Gateway validates and
echoes `transition_id`; the client rejects a mismatched echo and the transition
controller rejects callbacks for an inactive transition or host.

The existing state endpoints remain the source of readiness evidence:

- `GET /api/v1/playnite/health`
- `GET /api/v1/playnite/game/current`
- `GET /api/v1/playnite/window/readiness`

`window/readiness.ready=true` currently means the Playnite-reported game process
or Playnite Fullscreen owns a visible foreground window on the configured
streamed display with stable geometry for three consecutive samples. For games
this is strong technical readiness, not proof that a main menu is visible.

## Current lifecycle events

The Bridge currently publishes:

- `bridge-connected`
- `bridge-disconnected`
- `game-starting`
- `game-running`
- `game-stopping`
- `game-stopped`
- `target-window-ready`
- `privacy-gate-closed`
- `stream-display-resolved`

These events are combined with the state endpoints because Sunshine launches
are intentionally kept on Moonlight's existing launch path and therefore do not
originate in the Gateway.

## Required host-side extension

For native end-to-end transition correlation, planned-close guarantees, and a
host privacy surface, add a transition resource with this minimum event schema:

```json
{
  "event": "playnite.fullscreen_ready",
  "host_id": "stable-host-id",
  "transition_id": "host:playnite:123:timestamp:sequence",
  "session_id": "sunshine-session-id",
  "timestamp": 1785140000000,
  "target": {
    "kind": "playnite",
    "sunshine_app_id": 123,
    "playnite_game_id": null
  },
  "state": "PLAYNITE_FULLSCREEN_READY",
  "error_code": null,
  "diagnostic": "foreground window stable on streamed display"
}
```

Minimum normalized event names:

- `transition.started`
- `stream.connecting`
- `playnite.starting`
- `playnite.process_running`
- `playnite.fullscreen_ready`
- `game.starting`
- `game.process_running`
- `game.window_ready`
- `game.ready`
- `game.stopping`
- `playnite.returning`
- `playnite.stopping`
- `session.stopped`
- `transition.error`

The host agent should expose idempotent `privacy/show` and `privacy/hide`
operations. Both must require a transition ID and return the effective
transition ID plus an activation/removal acknowledgement. `privacy/show` must
put an opaque fullscreen window on the streamed display before a planned close
or target replacement. Android must not hide its own privacy surface until the
target readiness gate is open and the host confirms that its privacy window was
removed.

The current host services do not implement this host privacy window. Consequently
the Android layer masks all normal client-driven transitions, but cannot promise
to conceal a frame that was already captured before notification of an
unexpected process crash.
