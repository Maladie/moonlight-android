# Wake & Play Host Gateway

Gateway exposes a small authenticated HTTPS API to Wake & Play while keeping
Discord, Vibepollo and the Game Provider Bridge bound to `127.0.0.1`.

For a complete installation use `../install/Install-WakePlayHost.ps1`. For
development, run `Start-WakePlayGateway.ps1` and enter its six-digit code under
Wake & Play's host integrations panel within ten minutes.

The installer registers `MoonWakerGateway` as an automatic Windows service, so
the HTTPS API is available before anyone signs in. It requires a system-wide
Python installation under Program Files. Interactive Bridges still start at
user logon because Discord RPC and user credentials are bound to that session.

The first start generates `gateway.json`, a private TLS key and a certificate.
They are ignored by Git. Paired client tokens are stored only as SHA-256 hashes,
and Wake & Play pins the certificate received during pairing. The private-LAN
firewall rule must never be exposed to the Internet.

## Profiles

Use one Gateway per physical host and one Bridge instance per Windows
integration profile. `profiles` maps stable profile IDs to distinct loopback
ports. Authenticated requests select a profile with `X-WakePlay-Profile`; the
header defaults to `default` when omitted. Unknown IDs and non-loopback Bridge
URLs are rejected.

A Discord Bridge must run in the same interactive Windows session as its
Discord client because named-pipe RPC and DPAPI credentials are user-bound.
Discord itself can only own one machine-global RPC endpoint at a time, so fully
exit it in the previous profile before switching. Vibepollo also needs a
per-profile Bridge when its credentials or runtime state differ.

The profile registry uses stable IDs and authoritative Windows account SIDs.
Paired clients store `profile_grants` separately for every profile. Legacy
clients migrate with `use_profile` for retained profiles and without
`remote_sign_in`; upgrades never turn remote sign-in on automatically.
An optional four-digit MoonWaker app PIN is stored only as a versioned salted
verifier. Profile listings expose only `pin_required`. Protected profile routes
require an in-memory unlock lease. Android sends a fresh UUID in
`X-MoonWaker-Profile-Session` for each app process; that lease is bound to the
paired client, profile, verifier and UUID and survives screensaver idle time.
The UUID is never persisted. A new UUID must verify again, a new verification
replaces the prior session for that client/profile, and profile grant,
verifier, or Gateway restart invalidates the authorization. Clients that omit
the header retain the fixed five-minute lease for compatibility.

## API v1

- `GET /api/v1/hello` - unauthenticated discovery response.
- `POST /api/v1/pair` - exchanges a short-lived pairing code for a client token.
- `POST /api/v1/vibepollo/pair/ticket` - creates a short-lived, client-bound
  authorization for one automated Moonlight pairing attempt.
- `POST /api/v1/vibepollo/pair` - submits the pending Moonlight PIN through the
  selected profile Bridge and verifies the resulting client permissions.
- `GET /api/v1/capabilities` - reports Gateway, selected profile and Bridges.
- `POST /api/v1/microphone/stream` - one authenticated chunked stream of 48 kHz
  mono PCM16 (960-sample frames) for the selected profile. It requires
  `Content-Type: application/vnd.moonwaker.microphone-pcm;format=s16le;rate=48000;channels=1`,
  `X-Request-Id`, and
  `X-Microphone-Session-Id`.
- `GET /api/v1/profiles` - lists safe profile names and Bridge health summaries.
- `POST /api/v1/profiles/pin/verify` - verifies the selected profile's four-digit
  app PIN for a paired client with `use_profile`; when
  `X-MoonWaker-Profile-Session` is present, success creates a process-scoped
  in-memory unlock lease, otherwise it creates a fixed five-minute lease.
  Failures use a per-client/profile cooldown.
- `POST /api/v1/system/session/ensure` - returns `ready` for an already-active
  selected profile, or starts a bounded remote Windows sign-in attempt when the
  client also has the `remote_sign_in` grant.
- `POST /api/v1/system/session/switch` - explicitly disconnects the active local
  console session through Fast User Switching and starts or resumes the selected
  profile; it requires `use_profile`, `remote_sign_in`, and an idempotent request ID.
- `GET /api/v1/system/session/status` - returns coarse session state, or polls a
  bound attempt when `attempt_id` and `request_id` are supplied.
- `POST /api/v1/system/session/cancel` - idempotently cancels a bound attempt.
- `GET /api/v1/diagnostics/network/download?size=...` - authenticated, no-store
  generated downlink bytes (8 MiB default, 512 MiB maximum), with one active
  test per client/profile pair. Android uses an 8 MiB probe, then targets an
  approximately eight-second second sample within those bounds.
- `GET /api/v1/vibepollo/repair/status` - Vibepollo health summary.
- `POST /api/v1/vibepollo/apps/ensure` - start idempotent creation or migration of a Playnite-backed Vibepollo app.
- `GET /api/v1/vibepollo/apps/status?playnite_game_id=...` - read its host-owned preparation state.
- `POST /api/v1/vibepollo/repair/{restart|reset-display|export-logs}` - repair action.
- `GET /api/v1/discord/status` - Bridge, RPC and authorization status.
- `GET /api/v1/discord/home` - favorites, recent channels and servers.
- `GET /api/v1/discord/channels?guild_id=...` - allow-listed voice channels.
- `GET /api/v1/discord/voice` - selected channel and voice state.
- `GET /api/v1/discord/audio` - Windows and Discord audio state/devices.
- `GET /api/v1/discord/audio/stream` - one authenticated, no-store chunked
  downlink per selected profile. It carries 48 kHz stereo PCM16 in 960-sample
  frames with
  `Content-Type: application/vnd.moonwaker.discord-audio-pcm;format=s16le;rate=48000;channels=2`.
- `POST /api/v1/discord/start` - starts Discord in the Bridge user session.
- `POST /api/v1/discord/{connect|join|leave|mute|deafen}` - Discord action.
- `POST /api/v1/discord/{user-volume|user-mute}` - participant control.
- `POST /api/v1/discord/audio/{select|volume|mute}` - audio control.
- `GET /api/v1/virtualhere/state` - VirtualHere state and shared devices.
- `POST /api/v1/virtualhere/{use|stop|auto|restart}` - VirtualHere action.
- `POST /api/v1/system/sleep` - schedule Windows sleep after the authenticated response is sent.
- `GET /api/v1/playnite/health` - compatibility path for the selected profile's Game Provider Bridge state.
- `GET /api/v1/library?cursor=...&limit=...` - paged provider-neutral library and library descriptors.
- `POST /api/v1/game/{start|install|uninstall|stop}` - dispatch to the record's authoritative provider.
- `GET /api/v1/playnite/library/list?cursor=...&limit=...` - compatibility alias for `/api/v1/library`.
- `GET /api/v1/playnite/game/current` - current Playnite game lifecycle state.
- `GET /api/v1/playnite/window/readiness` - streamed-window readiness sample.
- `POST /api/v1/playnite/game/{start|install|uninstall|stop}` - one-cycle compatibility aliases for provider-neutral game actions.
- `POST /api/v1/playnite/show-fullscreen` - restore Playnite Fullscreen.

Game-provider readiness is a privacy boundary. The TV must keep its opaque loading
surface visible until the Bridge confirms that the requested game, or Playnite
Fullscreen during a return transition, owns a visible stable window on the
streamed display. A timeout must not reveal the desktop automatically. Desktop
reveal remains a separate explicit user action.

All endpoints except `hello` and `pair` require `Authorization: Bearer ...`.
Mutating actions also require a unique `X-Request-Id`. Discord snowflakes,
participant volume, audio device IDs and VirtualHere addresses are validated;
Gateway never exposes a general-purpose Bridge proxy.

`capabilities.remote_windows_sign_in` is available only when the Login Broker
answers the v1 protocol and the fixed MoonWaker Credential Provider is enabled,
registered, and present. Per-profile credential readiness is reported separately
by `profiles` and the session status endpoint.

### Remote Windows session contract

All session routes use the normal pinned-TLS Gateway transport,
`Authorization: Bearer ...`, and `X-WakePlay-Profile`. `ensure` additionally
requires a stable `X-Request-Id` and an empty JSON object; it never accepts a
password. `switch` is a separate explicit mutation with the same empty-body and
request-ID contract. It never changes `ensure` semantics, logs off a user, or
uses a shell command. A successful response is either `200 ready` or `202` with a 32-hex
`attempt_id`. Android polls:

```text
GET /api/v1/system/session/status?attempt_id=<id>&request_id=<original-request-id>
```

Cancellation sends the original request ID in both `X-Request-Id` and body
`{"attempt_id":"...","request_id":"<original-request-id>"}`. Broker and
Gateway bind every operation to the authenticated client, selected profile,
original request ID, and attempt. Repeating `ensure` with that same binding is
idempotent and cannot submit a second password. Repeating `switch` cannot
disconnect the console session twice. Missing credentials after a successful
disconnect return `attention_required`; RDP, multiple or unresolved active
sessions, disabled Fast User Switching, and disconnect failure are rejected.

LAN responses expose only coarse states/reasons: `ready`, `pending`,
`session_starting`, `action_required`, `expired`, or `cancelled`, with reasons
such as `remote_sign_in_not_granted`, `manual_sign_in_required`,
`credential_action_required`, `other_user_active`, and `broker_unavailable`.
They never expose credentials or detailed Windows authentication status codes.

Microphone availability is reported only when the packaged renderer can open
exactly one active render endpoint whose invariant name contains
`Steam Streaming Microphone`. Gateway starts that renderer only for an accepted
stream, pipes bounded audio through stdin, and terminates it on EOF, timeout, or
failure. It never changes the default Windows audio endpoint.

Discord audio availability reports only stable reasons. The packaged worker
captures the Discord process tree in the interactive Bridge session through
Windows process-loopback audio. The private loopback Bridge target and its PID
are never returned to LAN clients. A Discord restart ends the stream so the TV
can reconnect to the new process.
