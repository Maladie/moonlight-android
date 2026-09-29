# S07 design note v2 — physical stream runtime behind RetainedStreamSessionCoordinator

Status: PRE-EDIT v2, answers R049 (CHANGES_REQUIRED). v1 kept as `design-v1.md`. No product file is
edited before an independent READY.

## 1. Owner and seams (unchanged from v1 unless noted)
New plain-Java `com.limelight.stream.StreamRuntime`, one instance per transport generation,
process-scoped, held **strongly** by `RetainedStreamSessionCoordinator` as its `Controller`
(replaces the `WeakReference`). It owns: `NvConnection` + its `TransportStopCoordinator` (S04 stop
completion unchanged), the MoonBridge `NvConnectionListener`, `AndroidAudioRenderer` (application
context), `SwitchableVideoDecoderRenderer`, Wi-Fi locks, the foreground-service protocol (§4), the
immutable launch identity (host/ports/uniqueId/cert/appId/streamSessionId, retained
`LaunchTransitionSpec`), whole-quit transport/host/privacy effects (§5), and the control-generation
binding (§6). Mutable session facts (game, actor, control generation, state) stay in the coordinator.

Seams (no Activity crosses them): `Transport` (start/stop/controlGeneration/reauthorize/send*),
`WifiLocks`, `StreamService` (§4), `ResumeStore` (SessionResumeManager), `ForegroundState`
(process-level "an app Activity is started", fed by `MoonWakerApplication`'s existing
ActivityLifecycleCallbacks), `PresentationLauncher` (starts the attaching Game from the application
context; only called while `ForegroundState` is true).

## 2. Survival rule and stop-entrypoint inventory (R049 P1 #4)
A runtime survives loss of its presentation **only** when all hold at the moment of detach:
Activity is not finishing; the coordinator snapshot names this runtime's session in `HOME_LIVE`,
`PARKED_LIVE`, or `PREPARING` with the home frame already accepted; the transport is connecting or
established; and the FGS protocol is `ESTABLISHED` or the app is foreground (§4). Otherwise detach
first requests the shared runtime stop (one `TransportStopCoordinator` result) and only then drops
the attachment. `onDestroy` never calls `NvConnection` directly.

Inventory of every current Game stop entrypoint (HEAD 3913979f) and its new owner:

| Game line (method) | Today | S07 |
|---|---|---|
| 1731/1736 `onDestroy` | stops retained and non-retained | `runtime.detach(token, DETACH_DESTROY)`: survive per rule above, else shared stop + reconnect record as today |
| 1898 `onStop` (not parkable) | stop | `runtime.stop(STOP_PRESENTATION_LEFT)` |
| 1952 `onStop` decoder lost | stop | same |
| 3195 `stageStarting` while finishing | stop | runtime listener: callback with no/finishing attachment during a non-surviving detach → shared stop already requested; no second stop |
| 3282 `parkPreparingTransport` retention off | stop+finish | runtime stop (warm-up reconnect record) then presentation finish |
| 3302 `cancelPreparingTransport` | stop+finish | runtime stop, then finish |
| 3331 `disconnectRetainedStream` | stop | runtime-owned (Controller method) |
| 3829 actor-handoff discard compensation | stop | runtime stop keyed on transport generation (§6) |
| 4014 `abortStalePreparingSwitch` | stop | runtime stop |
| 4396 whole quit local stop | stop | runtime-owned (§5) |
| 4537 `reconnectRetainedStream` (restore watchdog) | stop+finish+resume intent | presentation asks runtime stop(RECONNECT); relaunch after shared result |
| 4553 `endExpiredBackgroundStream` | stop+finish | runtime-owned (service EXPIRED/END reach the runtime, not a Game receiver) |
| 4563/4569 `closeStreamWithPrivacy(false)` | stop+finish | runtime stop after opaque frame, then finish |
| 4696/4713/4741 `connectionTerminated` | stop | runtime listener stops once; attached presentation gets UI callback |
| 4848/4866 `connectionStarted` finishing / warm-up not completable | stop | runtime stop |
| 5686 `cancelTransition` | stop+finish | runtime stop then finish |
| 5866 `retryTransition` | stop+finish+relaunch | runtime stop; relaunch after shared STOPPED |
| 6119 `surfaceDestroyed` not parkable | stop | runtime stop |
| 6769 `applyBitrateAndReconnect` | stop+finish+relaunch | runtime stop; relaunched Game starts a new generation only after predecessor result (§7) |
| 7022 warm-up connection failure | stop | runtime stop |

A source-contract test pins that Game contains no `NvConnection`, `TransportStopCoordinator`,
`WifiLock`, `MoonBridge` listener implementation, or direct `conn.stop`. Non-retained teardown,
PREPARING cancellation and bitrate restart get JVM tests on the runtime.

## 3. Presentation attach/detach and retained switch (R049 P1 #2)
`attach(Presentation)` returns an immutable `Attachment(transportGeneration, serial)`; every
presentation effect carries it; stale tokens are recorded no-ops. Detach always: send neutral state
for every known controller, key-up for held keyboard modifiers, button-up for mouse buttons,
disable sensors, unbind USB, mute audio, detach decoder — before the token is invalidated, so no
stale sender reaches the live connection. ControllerHandler/UsbDriverService stay
presentation-owned.

Headless switch route (Home/dashboard visible, Game destroyed, runtime live):
1. `RetainedStreamSessionCoordinator.switchGame` hands the request to the runtime as today.
2. Attached → delivered to Game unchanged. Not attached → the runtime stores **one** pending switch
   slot `(request, requestSerial)` and calls `PresentationLauncher` with a Game intent carrying the
   session id and `EXTRA_ATTACH_PENDING_SWITCH=requestSerial`. A newer switch supersedes the slot
   (older completes CANCELLED).
3. The new Game attaches in the **opaque** closing/transition state (B artwork, audio muted, input
   suppressed through LaunchTransitionController), then claims the slot with its serial and runs the
   existing attached switch (`Game.switchGame`). Reveal of B stays with the canonical gate.
4. The slot terminates exactly once: delivered, superseded (CANCELLED), request `cancelled()` true
   (CANCELLED), runtime stop/termination (FAILED), or launch refused because the app is not
   foreground (FAILED `presentation_unavailable`). No timer is invented.
Same-game return: `returnToRetainedStream` and Console `onCreate` instant-resume launch the attaching
Game when `hasAttachedPresentation()` is false (resume intent with session id) instead of finishing
onto nothing. PREPARING switch uses the same slot with `request.preparing`; the attaching Game
restores warm-up identity from the runtime. Actor handoff after presentation loss: Console's
existing `startResumeActivity`/handoff intent now reaches a Game that attaches to the live runtime
(same session) and performs the handoff against the runtime transport (§6), instead of building a
new connection. Tests: headless same-game return, headless A→B, PREPARING switch headless, handoff
after presentation loss, superseded and cancelled slots, stop while slot pending.

## 4. Foreground-service protocol (R049 P1 #3)
States in the runtime: `NONE → REQUESTED(gen) → ESTABLISHED(gen) → ENDED`.
- On `connectionStarted` the runtime requests FGS **only if `ForegroundState` is true**, calling
  `startForegroundService(ACTION_ESTABLISH, session, generation)` in try/catch.
- The service calls `startForeground` and acknowledges in-process via
  `StreamRuntime.serviceEstablished(session, generation)`; a stale generation ack is ignored.
- `ForegroundServiceStartNotAllowedException`/`IllegalStateException` at the call, or a late
  `connectionStarted` while not foreground, leaves the state `NONE`. The stream keeps working while a
  presentation is foreground; at the first detach/park without `ESTABLISHED` the runtime performs an
  **honest stop**: save reconnect record, request the shared native stop, release locks, then
  `RECONNECT_REQUIRED` after the stop result (coordinator `parkForBackground` failure no longer just
  clears the controller: `Controller.parkRetainedTransport()` returning false means the runtime has
  already requested its stop).
- Park/resume send `ACTION_PARK/ACTION_RESUME(generation)` via `startService` to the already
  foreground service: they only change notification text and arm/cancel the retention timer; RESUME no
  longer calls `stopForeground/stopSelf`. The service no longer requires a pending resume record to
  accept runtime commands; correlation is `(session, generation)` (`BackgroundStreamCorrelation` gains
  the generation). Expiry/END call into the runtime, which stops and then sends `ACTION_END`; the service
  stops itself only on END/LOST of the current generation.
- Tests: late connectionStarted after onStop, service-start refusal, missing ack at park, stale ack,
  stop/new-start races, expiry while attached/detached.

## 5. Whole quit and child expiry while detached (R049 P2 #5)
The runtime runs `WholeSessionTermination`. Mandatory runtime privacy effects, generation-correlated
and executed before any asynchronous local/host stop: mute audio, close the input fence (no sender
accepted for this generation), clear the resume record, cancel retention expiry (`ACTION_RESUME`
equivalent becomes `ACTION_END` after stop). Only view opacity and Console navigation are optional
presentation hooks. S05 `transportUnsafe`, direct termination and completion semantics are kept; late
service callbacks for an older generation/actor are ignored. Tests: whole quit detached, child expiry
detached, late old service callback after actor/generation change.

## 6. Control generation and actor handoff (R049 P2 #6)
`stageComplete` moves to the runtime listener: it binds the control generation with the runtime as
coordinator owner (`bindControlGeneration(runtime, …)`), stores it in the runtime's retained launch
intent/resume record (not an Activity intent). Game reads it from the runtime. Actor handoff
(Game.java:3594–3830) keeps its UI in Game but every connection check becomes
`runtime.transportGeneration() == captured && runtime.transport().getControlGeneration() == previous`
through the attachment; `reauthorizeControlGeneration` goes through the runtime and is rejected for a
stale attachment. Tests: stale stageComplete after a new transport generation; stale attachment
callback during actor rebind; handoff commit only on the exact runtime generation.

## 7. Provider transition across recreation (R049 P1 #1)
Non-finishing destroy of a surviving runtime does **not** call `cleanupUnrevealedProviderLaunch`.
Instead Game calls `transitionCoordinator.detachForRecreation()`: stop observation and pending
callbacks (epoch advance), keep the process-wide `PROVIDER_OWNERS` entry, mark it
`detachedForPresentation` (cleanup not claimed). The runtime keeps the current `LaunchTransitionSpec`.
The new Game builds its coordinator from that spec and calls `adoptRecreatedProviderOwnership()`,
which succeeds only for the exact detached owner (same key/spec/profile, not cleanup-claimed) and
re-arms observation; the LaunchTransitionController restarts unrevealed and requires the normal fresh
frame/readiness before reveal. If adoption fails, or the runtime did not survive, the existing
terminal cleanup path runs (fresh unrevealed launch cleanup preserved for real terminal failures).
Tests: recreation during PREPARING and during unrevealed GAME launch → one provider owner, no provider
stop, reveal only after fresh readiness or honest reconnect; old coordinator callbacks after
recreation are inert.

## 8. One connection per process
Registry refuses a new runtime while one is live and not stopping; a new generation's native start
waits for the predecessor `TransportStopCoordinator` result (FAILED predecessor ⇒ no native start).
JVM test with latches.

## 9. Exit reasons / StrictMode, recreation vs process death
As v1: `ApplicationExitInfo` allowlisted fields (`reason`, `sub_reason`, `status`, `importance`,
`timestamp`, `pid`), new entries only; StrictMode `detectAll().penaltyLog()` only when
`BuildConfig.DEBUG`. Recreation keeps runtime; process death → NONE → authorized reconnect.

## 10. Target platform
targetSdk 34. Owner declaration (2026-09-29, in chat): the physical target TV runs the same Android
API level as the lab AVD, **API 36**. Recorded as owner-declared, not device-read. API ≥ 31 ⇒ the FGS
background-start restriction applies; API ≥ 30 ⇒ ApplicationExitInfo available.

## 11. Internal passes, tests, device evidence, non-changes
Passes (a) listener/sink, (b) resources + FGS + quit + control generation into the runtime, (c) Game
adapter, Console attach routes, provider recreation handoff, removal of Game's duplicate ownership.
JVM tests as listed in §§2–8 plus v1 list; existing coordinator/stop/termination/resolver tests
unchanged. Lab: T05, T06, T07, T25, T26 (DKA + PREPARING recreation + am kill/force-stop), T50, S05
whole quit + child expiry, headless switch. No JNI change, no host change (lockstep = unchanged host
build + new APK from the same source), LaunchTransitionController remains the only reveal/input gate.
