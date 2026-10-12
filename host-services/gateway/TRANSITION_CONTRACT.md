# Console transition contract

## Current transport

`GET /api/v1/playnite/events` is an authenticated long-poll channel. The Android
client sends `after=<sequence>` and `transition_id=<id>`. Gateway validates and
echoes `transition_id`; the client rejects a mismatched echo and the transition
controller rejects callbacks for an inactive transition or host.

`GET /api/v1/session/observation` is the sole versioned source of readiness evidence.
`window.ready=true` means the provider-reported game process
or Playnite Fullscreen owns a visible foreground window on the configured
streamed display with stable geometry for three consecutive samples. For games
this is strong technical readiness, not proof that a main menu is visible.

### Durations (display only)

The host owns how long a stream and a game have run; the observation carries both starts on
the host's wall clock, next to `observed_at_unix_ms` from that same clock. A client shows
`observed_at_unix_ms - start` plus its own elapsed time since it received the observation
and never compares a start with its own clock. Both fields are required, which is what
observation `schema_version` 2 means (the Bridge sets it, the Gateway passes it through, the
client accepts only 2: `unsupported_observation_schema`); `0` means the fact does not exist
or is not this caller's to know. A duration over 7 days is not shown. Neither is evidence
for readiness, Play or close, and neither opens a new `state_episode`.

- `correlation.stream_started_unix_ms` (Gateway): when the retained stream record first
  succeeded. It is stored in the stream record (so it survives a Gateway restart exactly as
  the record does) and is carried into every later successful start on that record by the
  same actor: reconnect, resume, warm-up, replacement, reauthorize and the fresh start of
  "Retry after a lost transport". It ends with the record (a succeeded stop, the idle
  proof, a hard reset); the next start is a new stream. A start by another actor (a child
  or parent taking the stream over on the same TV) begins that actor's own time, and so
  does an actor change in place without a start operation (a child's time session bound to
  the retained stream, a child session ended in favour of its parent). Only the
  actor and execution profile that own the record read it; everyone else reads `0`.
- `process.started_unix_ms` (Bridge): the start of the verified game process
  (`processStartedFiletime`), `0` unless `process.evidence` is `VERIFIED`. Another game in
  the same stream reports its own process start. For a child actor the Gateway keeps it
  only when the observed game is the game of that child's own live time session and
  answers `0` otherwise, without another provider read. A child's client holds a running
  game for its status line only with a non-zero start, so it reads "Online" beside a game
  its parent runs.

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

## Client start that never reports its result

A control permit is released by its holder's terminal write. The one host-side exception
is a client start (not `reauthorize`, not a stop or provider transition): once the holder
has been silent for 60 s after the permit, or 15 s after its own uncertain report
(`complete` with `succeeded=false`; every repeat restarts the 15 s), the Gateway ends it
itself, but only on the streamer's fresh, authoritative and coherent answer about what the
start left (the rule the abandon below uses). A Gateway restart starts the 60 s anew.

| The streamer shows | The permit ends as | Effect |
| --- | --- | --- |
| Neither a transport nor a running application | `failed/start_effect_absent`, logged `control-start-auto-resolved` (`effect=absent`) | Nothing is recorded; the next `acquire` is granted. |
| This start's own effect: its application is the only one running and no connected client has another UUID than the holder's (nobody connected included) | `succeeded/start_effect_present`, logged `control-start-auto-adopted` | Exactly the holder's own `complete` with `succeeded=true`: the stream record is written on the permit's client, actor, execution profile and child session with the permit's generation, and keeps or sets the stream start time by the same rule. |
| Anything else (another application, another client, a transport without the application) | `failed/start_effect_foreign`, logged `control-start-auto-resolved` (`effect=foreign`) | Nothing is recorded: what runs is not this holder's and stays unmanaged, as for any later `acquire`. |
| No authoritative, coherent answer | - | The permit stays. |

An `acquire` of the next start waits out a grace that ends within its request, leaving 10 s
for the proof; closes are answered at once.

An adopted stream is an ordinary retained stream. Its owner's next start connects to it
(resume or reconnect on a new transport, or a replay under the same operation ID); every
other caller gets the refusals a retained stream gives; and when nobody uses it, the idle
reconciliation retires the record once the streamer reports it idle, abandoning a game start
that never produced a process. The Gateway does not close the application itself: while
the streamer keeps it running the record stays its close handle. Adoption checks nothing
`complete` does not check and grants nothing the permit did not: a child actor's record
carries its child session, and without a live session that child may still only close.

The holder's late terminal write reads the receipt. After an adoption both
`succeeded=true` and `succeeded=false` read `succeeded/start_effect_present` with the
permit's generation, and change nothing. After `start_effect_absent`, a late
`succeeded=true` is accepted as succeeded and its stream recorded while nothing has been
granted since (same generation, no active permit, no stream record); after any newer grant
the `failed` receipt stands and the Gateway logs `control-start-late-effect`. After
`start_effect_foreign` the `failed` receipt always stands.

## Abandoning a client start

`POST /api/v1/control/operation/abandon` lets the holder of a client start (not
`reauthorize`, not a stop or provider transition) declare that it will not send or finish
it. It is authenticated and authorized exactly like the other `control/operation/*` routes
(Bearer, profile headers, `use_profile`, the child capability and session for a child
actor) and `X-Request-Id` must equal `operation_id`. Body: `operation_id`,
`child_session_id` (empty for a parent) and the optional `generation`: the permit's when
the holder read it, `0` or absent when the `acquire` answer was lost. The operation must be
this client's, actor's, execution profile's and child session's start, and a `generation`
above `0` must be its generation, so an HTTP repeat of an older attempt's abandon cannot
release a newer attempt under the same deterministic operation ID. An unknown, foreign or
non-start operation ID, or another generation, is answered `409 stale_operation` whatever
the host state is.

| Host state | Answer | Effect |
| --- | --- | --- |
| A stream record is retained (resume, reconnect, replacement) | `200`, `state=failed`, `reason=start_abandoned`, `released=true` | The permit ends; the record and its generation stay the handle of what runs. |
| No record; the streamer proves neither a transport nor a running application | `200`, `state=failed`, `reason=start_abandoned`, `released=true` | The permit ends; the next `acquire` is granted. |
| No record; the streamer shows this start's own effect: its application is the only one running and no connected client has another UUID than the holder's | `200`, `state=succeeded`, `reason=start_effect_present`, `released=false`, `generation` | The start is recorded as the stream; the holder closes it with an ordinary `full_quit` on that generation. |
| No record; the streamer shows anything else (another application, another client, a transport without the application) | `200`, `state=failed`, `reason=start_abandoned`, `released=true` | The permit ends and nothing is recorded: what runs is not this holder's (a child actor included) and stays unmanaged, as for any later `acquire`. |
| No record; no authoritative, coherent streamer answer | `409`, `state=uncertain`, `reason=start_abandoned`, `released=false` | The permit stays, with no grace left: the Gateway ends it on the first answer (previous section: released, adopted or failed as foreign), also after its own restart and within the holder's next `acquire` of the same operation ID. |

A repeat reads the stored receipt, so the call is idempotent. A failed start receipt
(abandoned, absent, rejected) never answers a later `acquire`: the same operation ID is a
new attempt, also for an `acquire` that was already waiting on the abandoned one.

A late `succeeded=true` reads the `failed` receipt only after `released=true`. After the
`409` answer the permit is still the holder's, so its `complete` is accepted as for any
uncertain start; and once the Gateway has ended that permit as `start_effect_absent`, the
late success is accepted while nothing was granted since (previous section).

## Why a close is refused

A stop (`full_quit`, `child_terminal`, `hard_reset`) that has no stream to act on names the
cause in `reason`:

| `reason` | Meaning |
| --- | --- |
| `managed_stream_unavailable` | The Gateway holds no stream record. |
| `stream_owner_mismatch` | The record belongs to another client, execution profile (or actor, for `child_terminal`), or is bound to another stream session or application. |
| `stream_generation_superseded` | The record is this owner's, in a newer generation than `expected_generation`. Every successful start on the stream (resume, reconnect, warm-up, reauthorize, replacement) advances it, so the session and its game still run under the newer one. |
| `unmanaged_stream` | The streamer runs an application the record does not name (`observed_sunshine_app_id`). |

The client reads `stream_generation_superseded` by what it was closing. Of a start it gave
up (cancelled, or found started after a lost permit) nothing is left, as with
`stream_owner_mismatch`: it stays silent. A close the user ordered is repeated once with
the binding `control/operation/status` reports, and that attempt's result is the close's;
local session records are cleared only by a close that succeeded or found nothing on the
host, never by the refusal itself.

## Retry after a lost transport

A start from the owner of the retained record has two outcomes. While the streamer still
runs the application, the record is rebound to the new transport as before. When the
streamer proves that the application is closed and no transport is left, and the request
carries the record's exact `expected_generation` with the same client, actor, execution
profile, application and child attachment, the Gateway grants a fresh start in a new
generation (`control-stream-closed-restart`). The record is not withdrawn by the grant: the
game may still run in the provider, so the record stays the handle a `full_quit` on its
generation closes it with. Only the new start's `succeeded=true` replaces it; a rejected,
abandoned or host-resolved start leaves it untouched. The new record carries no game
process identity; only the Bridge binds one again. The rule does not apply while a child
time session is active. Any other mismatch keeps the record and the former answers
(`managed_stream_conflict`, `unmanaged_stream`).

## Contract version

The capability is `control_operations_v2` (`protocol_version` 7 in `version.json`). A client
and a host from different sides of this change do not negotiate control operations: the
client refuses to start a stream without the capability it knows.
