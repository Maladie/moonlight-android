# S07 design note v4 — physical stream runtime behind RetainedStreamSessionCoordinator

Status: PRE-EDIT v4, answers R049, R050 and R051 (all CHANGES_REQUIRED). v1-v3 kept as
`design-v1.md`..`design-v3.md`. No product file is edited before an independent READY.

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
ActivityLifecycleCallbacks). (v2's `PresentationLauncher` seam is removed in v3: the visible Console
launches the attaching Game, §3.)

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

## 3. Presentation attach/detach, input gate and retained switch (R049 P1 #2, R050 P1 #2/#3, R051 P1 #1)
`attach(Presentation)` returns an immutable `Attachment(transportGeneration, serial)`.
ControllerHandler/UsbDriverService stay presentation-owned, but every presentation input send goes
through the runtime **input gate** (`runtime.send(token, packet)`), never through the transport
directly.

Input gate (R050 P1 #2): a `ReentrantReadWriteLock` in the runtime. A presentation send takes the read
lock, checks `token == current && gateOpen`, sends, and records the resulting held state in the
runtime (per controller number: last non-neutral button/trigger/stick state; keyboard keys down;
mouse buttons down). Detach takes the write lock — this waits for in-flight sends to drain and
blocks new ones — then atomically sets `gateOpen = false` and invalidates the token, then (still
holding the write lock) emits release packets for exactly the held state through the runtime-internal
transport path (neutral controller state per held controller, key-up per held key, button-up per held
mouse button), then releases. Any later send with the old token is a recorded no-op. After that the
presentation disables sensors, unbinds USB and destroys its ControllerHandler; audio mute and decoder
detach are runtime effects in the same detach. The privacy gate (LaunchTransitionController /
`setInputSuppressed`) remains the authority for *whether* an attached presentation may send; the input
gate only fences *which* presentation and releases held input. The write-lock critical sections are
short: a presentation send holds the read lock only for one native send plus the held-state update; no
network/host call, UI callback or blocking wait runs under the lock. Detach from the main thread waits
only for such in-flight sends; a JVM test blocks one native send on a latch and asserts detach waits for
it and completes as soon as it returns, and the lab records main-thread detach latency (no invented
threshold, D09). Test: a sender holding a button races
detach on controlled latches (send-before-lock, send-during-drain, send-after-close); the last packet on
the fake transport is neutral for controller, keyboard and mouse, and no post-detach packet appears.

Headless switch (R050 P1 #3, R051 P1 #1): the runtime has **no** pending-switch slot. The route goes
through the real `SessionOrchestrator` result contract:
1. `ConsoleActivity` effect `closePreviousSession` (executor thread): when the retained switch is
   otherwise eligible (`canAttemptRetainedSwitch`, same host/execution/app) but
   `RetainedStreamSessionCoordinator.hasAttachedPresentation()` is false, it does **not** call
   `switchGame` and returns the new `CloseResult.PRESENTATION_REQUIRED`.
2. `SessionOrchestrator` handles `PRESENTATION_REQUIRED` on its dispatcher: if `current(request)` it
   calls the new effect `attachPresentationForSwitch(intent, target)` and the orchestration request ends
   there (terminal, like REUSED). It never falls through to `launch` (no second connection) nor to
   `returnToRetainedStream` (no untargeted resume). A non-current request does nothing.
3. `attachPresentationForSwitch` (Console, UI thread) starts the singleTask Game with the session id and
   the target play-intent extras (`EXTRA_PENDING_RETAINED_SWITCH_*`: host, execution/actor profile, child
   flag, app id, game id/name, artwork, orchestration request id). If `startActivity` throws, it reports
   `previousSessionCloseFailed("presentation_launch_failed")`. After a successful start the retained Home
   finishes itself (as `returnToRetainedStream` does), so the dashboard cannot accept another selection
   while Game appears.
4. Game (new or existing instance) keeps **one** undispatched pending switch. `onCreate` reads it from the
   intent; a new `onNewIntent` override (new production wiring) replaces it if not yet submitted. After
   attaching in the opaque transition state (B artwork, audio muted, input suppressed by the canonical
   gate) Game submits the ordinary attached `switchGame` exactly once and clears the pending value. A
   pending-switch intent arriving after submission while a switch is running is rejected through the
   existing `NOT_ELIGIBLE` rule (`switchOwner != null`) and shown with the existing
   `console_stream_home_switch_blocked` message; it is not queued.
5. Terminal outcomes: the orchestration request ends at step 2; the switch operation exists only after
   step 4 and then has the coordinator's existing exactly-once completion. Launch without attach leaves
   the coordinator unchanged (no `switchOwner`), so the next selection is eligible. Cancellation before
   step 2's dispatch is the orchestrator's `current(request)` check; after Game is visible, the user's Back
   goes through Game's existing transition cancel.
Same-game return and Console `onCreate` instant resume use `attachPresentationForSwitch` without a play
intent (plain attach + restore). PREPARING (warm-up) and actor handoff after presentation loss use the
same result: Game attaches, then runs the existing warm-up switch/handoff against the runtime transport
(§6).
Pre-existing, not introduced by S07: `ConsoleActivity.switchRetainedProviderGame` (14747–14770) stops
waiting after 60 s without completing the coordinator callback or clearing `switchOwner`. Recorded as an
original defect with evidence in the plan; S07 does not worsen it (the headless route never reaches it).
Tests use the real `SessionOrchestrator` with fake effects: `PRESENTATION_REQUIRED` calls only
`attachPresentationForSwitch` (never `launch`/`returnToRetainedStream`), non-current request does nothing,
launch failure reports `presentation_launch_failed`, second orchestration before Game attach replaces the
pending intent (Game pending-switch holder JVM test: onNewIntent before submit replaces, after submit
rejected), launch without attach leaves the coordinator eligible; plus headless same-game return,
headless PREPARING switch and handoff after presentation loss.

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
- `ForegroundState` (R050 answer): `MoonWakerApplication` maintains a thread-safe started-Activity
  count (`AtomicInteger` in `onActivityStarted/onActivityStopped`, currently empty hooks) exposed through
  the seam.
- An ack or `startForeground` for a generation that is no longer the runtime's current generation (or
  after the runtime ended) makes the service stop itself immediately, so no orphan notification
  remains. Test: service start arriving after runtime end.

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

## 7. Provider transition across recreation (R049 P1 #1, R050 P1 #1, R051 P1 #2)
Provider-start reservation: `runProviderGameStart` creates the `PROVIDER_OWNERS` entry for the exact
spec **before** `gateway.startGame`, in state `STARTING` with `holder = this coordinator`. Start
completion updates that registry entry by spec identity (not by coordinator epoch): success → `STARTED`,
failure → entry removed. Then the result is delivered to the entry's **current holder** (if any).
Owner states are explicit in **every** lookup (R051 P1 #2): `isOwnedByCurrentAttempt` becomes
`ownerState(spec)` returning `NONE | STARTING | STARTED`. In `runProviderGameStart` only `STARTED` takes
the reuse branch (`onProviderGameStartAccepted` + observation). `STARTING` **joins**: the coordinator
becomes/stays holder and waits for the single in-flight result without calling `startGame`, without
`onProviderGameStartAccepted` and without starting observation; on success it proceeds exactly as the
normal success branch, on failure as the normal failure branch. `transferProviderOwnershipTo`,
`adoptDetachedProviderOwnership`, `providerProcessIdentityForTransfer` and cleanup claims treat
`STARTING` as not-yet-started (no transfer proof, no process identity).
- Non-finishing destroy of a surviving runtime does not call `cleanupUnrevealedProviderLaunch`; it calls
  `transitionCoordinator.detachForRecreation()`: stop observation/pending callbacks (epoch advance),
  set the entry's `holder = none`, keep the entry (`STARTING` or `STARTED`, cleanup not claimed). The
  runtime keeps the current `LaunchTransitionSpec`.
- The new Game builds its coordinator from that spec and `adoptRecreatedProviderOwnership()` succeeds
  only for the exact holderless entry (same key/spec/profile, not cleanup-claimed); it becomes holder.
  If the entry is `STARTING`, the adopter receives the start result when the in-flight call returns; if
  already `STARTED`, it starts observation immediately. Exactly one `startGame` call is ever made.
- Orphan prevention: when the runtime ends (whole quit, terminal failure, non-surviving detach) and the
  entry has no holder, the runtime's terminal cleanup claims cleanup on the entry. `STARTING` defers
  cleanup until the in-flight start returns; failure needs no stop. On success the cleanup first binds the
  process identity from one authenticated snapshot (`captureCurrentAttemptProcess`); only a verified exact
  process (`verifiedStopTarget` + process token) is stopped and reported as success. If the process is not
  yet observable, cleanup reports **unconfirmed** (`completeProviderCleanup(false)`, diagnostic
  `provider.cleanup_unconfirmed`), never success, exactly as today's `compensateProviderStart` does
  without a token; a whole quit then reports failure/transportUnsafe honestly under S05 semantics.
- LaunchTransitionController restarts unrevealed; reveal needs fresh readiness as always. Failed
  adoption keeps today's terminal cleanup.
Tests (latch on `startGame`): detach during in-flight start, then adopt, then complete success / failure;
`onStreamConnected` on the adopter before the held `startGame` returns (no accept, no observation, no
second start) then success / failure; complete before adoption; runtime ends while holderless
`STARTING` then start succeeds with the process observable / not yet observable (unconfirmed, not
success) / fails. Assert one
`startGame` call, exact process identity bound to the adopter or stopped exactly once, no orphan entry,
and old coordinator callbacks inert.

## 8. One connection per process
Registry refuses a new runtime while one is live and not stopping; a new generation's native start
waits for the predecessor `TransportStopCoordinator` result (FAILED predecessor ⇒ no native start).
JVM test with latches.

## 9. Exit reasons / StrictMode, recreation vs process death
As v1: `ApplicationExitInfo` allowlisted fields (`reason`, `sub_reason`, `status`, `importance`,
`timestamp`, `pid`), new entries only; StrictMode `detectAll().penaltyLog()` only when
`BuildConfig.DEBUG`. Recreation keeps runtime; process death → NONE → authorized reconnect.

## 10. Target platform
targetSdk 34. Owner statement (2026-09-29, in chat): the physical target TV "should match" the lab AVD,
API 36. Recorded as an **unverified owner expectation** used for design assumptions, not device
evidence; physical-TV OS evidence stays pending. Design assumes API ≥ 31 (FGS background-start
restriction) and ≥ 30 (ApplicationExitInfo) and still guards both at runtime.

## 11. Internal passes, tests, device evidence, non-changes
Passes (a) listener/sink, (b) resources + FGS + quit + control generation into the runtime, (c) Game
adapter, Console attach routes, provider recreation handoff, removal of Game's duplicate ownership.
JVM tests as listed in §§2–8 plus v1 list; existing coordinator/stop/termination/resolver tests
unchanged. Lab: T05, T06, T07, T25, T26 (DKA + PREPARING recreation + am kill/force-stop), T50, S05
whole quit + child expiry, headless switch. No JNI change, no host change (lockstep = unchanged host
build + new APK from the same source), LaunchTransitionController remains the only reveal/input gate.
