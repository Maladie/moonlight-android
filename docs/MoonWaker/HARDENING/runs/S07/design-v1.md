# S07 design note — physical stream runtime behind RetainedStreamSessionCoordinator

Status: PRE-EDIT, awaiting independent contract review (request R049). No product file is edited
before the review verdict.

## Owner
New plain-Java class `com.limelight.stream.StreamRuntime` (one instance per transport generation,
process-scoped). It is the only owner of:
- `NvConnection`, its `TransportStopCoordinator` (S04 stop completion moves here unchanged),
- the MoonBridge listener (`StreamRuntime` implements `NvConnectionListener`; Game no longer does),
- `AndroidAudioRenderer` (application context) and `SwitchableVideoDecoderRenderer`,
- Wi-Fi locks, acquired when the runtime starts its transport and released when its stop completes,
- the foreground-service lifetime (see below),
- immutable launch identity needed for host termination (host/port/httpsPort/uniqueId/cert/appId/
  streamSessionId) — mutable identity (game, actor, control generation) stays in the coordinator and
  is read from its snapshot, so there is one owner per fact.

Android-specific effects sit behind four narrow seams so the owner is JVM-testable without
Robolectric: `Transport` (start/stop/controlGeneration/reauthorize; production = NvConnection),
`WifiLocks` (acquire/release), `StreamService` (established/park/resume/lost/end; production =
BackgroundStreamService statics with the application context), `ResumeStore` (save/clear;
production = SessionResumeManager). No seam receives an Activity.

`RetainedStreamSessionCoordinator` holds the runtime as its `Controller` with a **strong** reference
(replacing the `WeakReference`, which was only safe because an Activity kept itself alive). Its state
machine, termination epoch, `underStateLock`, snapshots and S06 fences are unchanged; the runtime is
the physical fact behind `isRetainedTransportLive()`.

## Presentation attach/detach
`Game` becomes the presentation adapter. `runtime.attach(Presentation p)` returns an immutable
`Attachment` token `(transportGeneration, attachmentSerial)`. Every presentation-originated effect
(surface attach/detach, decoder restore, audio volume, input send, sensor enable) passes its token;
a stale token is a no-op and is recorded. `detach(token)` of a non-current token is a no-op.
Listener fan-out: stage/connection/rumble/LED/motion callbacks go to the current attachment or to
none.

`Game.onCreate`: if the coordinator holds a live runtime for the intent's `streamSessionId`, Game
attaches and restores the parked stream (existing `restoreParkedStream` path: new decoder on the
current surface, IDR, restore watchdog) instead of constructing a new `NvConnection`. Otherwise it
builds the configuration and asks the runtime registry to start a new generation.
`Game.onDestroy`: detaches only. It stops the runtime only when the Activity is finishing through an
explicit end (quit, game-only close without retention, classic non-MoonWaker launch finishing) —
those paths already call the runtime stop before `finish()`, so `onDestroy` itself never stops the
transport. A detached, non-parked live runtime is parked by the runtime (decoder detached, audio
muted) so a destroyed presentation never leaves hidden audio/input.

When no presentation is attached:
- `connectionTerminated` → coordinator `markReconnectRequired`, resume record saved, service
  `transportLost`, runtime stop, locks released (concrete item 1).
- `ACTION_EXPIRED` / `ACTION_END_REQUESTED` are handled by the runtime (stop + clear), not by a
  per-Activity broadcast receiver.
- `ConsoleActivity` "resume instantly" (onCreate finish / `returnToRetainedStream`) launches Game with
  the resume intent when `hasAttachedPresentation()` is false, instead of finishing onto nothing.
- `switchGame` requires an attached presentation (the switch is a presentation/privacy-gate flow);
  `canSwitchGame` gains that requirement. In the UI this case is unreachable because Console always
  resumes into an attaching Game first; the check prevents a switch against a headless runtime.
- `terminateRetainedSession` (whole quit from Home) runs `WholeSessionTermination` from the runtime
  with presentation privacy/navigation hooks supplied by the attachment when present and no-ops
  otherwise; host termination uses runtime identity + coordinator snapshot. S05 whole-quit and child
  expiry semantics are preserved; the code moves out of Game.

## Ownership decisions (concrete item 3)
- `ControllerHandler`: **presentation-owned** (needs Activity for vibrator/sensor/input manager,
  toasts, `finish`). Created per attachment against the runtime transport; on detach it first sends
  neutral state for every known controller (held buttons/triggers/sticks released), disables
  sensors and stops, then is destroyed. On attach the new handler re-announces controllers
  (arrival packets carry capabilities only; the privacy gate still controls input).
- `UsbDriverService` binding: **presentation-owned** (its listener is the ControllerHandler);
  unbound on detach.
- Keyboard modifiers / mouse buttons held at detach: released (key-up/button-up) on detach.
- Hidden input: while detached or parked there is no input source; while attached, the existing
  `isTransitionInputBlocked`/`setInputSuppressed` privacy gate remains the only authority.

## Foreground service (concrete item 5)
`startForegroundService(mediaPlayback)` is called once, from `connectionStarted`, i.e. when the
transport is established while a presentation is foreground (Game or StreamHome warm-up). The
service stays foreground for the runtime lifetime. Park/resume only switch notification text and
arm/cancel the retention timer via `startService` on the already-foreground service; they never call
`startForegroundService`. The service is stopped when the runtime stop completes.
`ForegroundServiceStartNotAllowedException` is caught at the single start site and recorded; the
runtime then keeps running only while a presentation is foreground (it cannot be parked: park
returns false → existing RECONNECT_REQUIRED path), i.e. no silent immortal assumption.

## One connection per process (concrete item 4)
The runtime registry owns "current generation". A new start constructs its
`TransportStopCoordinator` (which records the predecessor) and does not dispatch the native start
until the predecessor's stop result arrives. Registering a new runtime while the previous one is
live and not stopping is refused (callers must stop/terminate first; existing Console flows already
do). JVM test: predecessor stop blocked on a latch → successor start effect not invoked → release →
invoked exactly once; failed predecessor stop → successor FAILED, native start never called.

## Exit reasons and StrictMode (concrete item 8)
`MoonWakerApplication.onCreate` reads `ActivityManager.getHistoricalProcessExitReasons` (API 30+) for
this package, records each entry newer than the last recorded timestamp (stored in private prefs)
as `app.exit_reason` with only `reason` (name), `sub_reason` where available, `status`,
`importance`, `timestamp`, `pid`. No `description`, trace or process-state blobs. StrictMode
(`detectAll().penaltyLog()` for thread and VM policies) is enabled only when `BuildConfig.DEBUG`.

## Activity recreation vs process death
- Recreation: runtime and coordinator state survive; new Game attaches (T26 part 1); diagnostics show
  `activity.destroyed` without `process.started`/exit reason.
- Process death: static coordinator is NONE, no runtime; the persisted resume record is only a
  reconnect hint → existing authorized reconnect (S05/S06) with a new transport generation; exit
  reason recorded at next start.

## Internal passes (single checkpoint)
(a) runtime-owned listener/sink with Game as the attached sink; (b) move NvConnection, stop
completion, audio, SwitchableVideoRenderer, Wi-Fi locks, FGS lifetime and whole-quit transport/host
parts into the runtime, strong coordinator reference; (c) Game becomes attach/detach adapter,
onDestroy detaches, Console resume launches an attaching Game, remove Game's duplicate fields/owner
code (`conn`, `transportStop`, locks, listener implementation, receiver for expiry/end).

## Tests (JVM, latches/queues, no sleeps, no Robolectric)
- no-presentation `connectionTerminated` → RECONNECT_REQUIRED + service lost + stop + locks released;
- detach/attach identity fence: stale detach/surface/restore/audio/input is a no-op; late surface after
  detach does not open video/audio/input;
- destroy vs explicit stop vs new start interleavings (T25) with a blocked stop: one stop effect, one
  shared result, no old completion on the new runtime;
- predecessor stop gating (item 4);
- Wi-Fi locks held exactly between start and stop completion, including start failure;
- FGS started once on establishment, never from park; park/resume/end only update it;
- held controls released on detach (fake controller sink records neutral packets);
- exit-reason formatter: allowed fields only, only new entries, API<30 no-op;
- existing coordinator, TransportStopCoordinator, WholeSessionTermination tests unchanged and
  passing; Game source-contract tests migrated to the new owner (no weakening).

## Device evidence (lab only, owned AVD API 36 + MoonWaker-Lab VM)
T05 TV Home resume, T06 App Home, T07 picker, T26 recreation with "Don't keep activities" and process
death with `am kill`/`force-stop`, T50 exit reasons, S05 whole-quit and child expiry; evidence must
show connection identity/transport generation, frame restoration, audio and input. Missing
physical-TV evidence ⇒ CODE_VERIFIED_DEVICE_PENDING.

## Explicit non-changes
No JNI/moonlight-core changes; `MoonBridge` statics stay (the runtime is the one listener).
LaunchTransitionController remains the reveal/input gate. SessionStateResolver, PlayniteTransitionGateway
and S06 fences untouched. Gateway/host code untouched; no host artifact change, so the lockstep pair is
the unchanged host build with the new APK.

## Questions for the reviewer
1. Is "switch requires an attached presentation" (Console always resumes into an attaching Game) an
   acceptable contract, or must a headless runtime accept a queued switch?
2. Is moving `WholeSessionTermination` orchestration into the runtime (with optional presentation
   hooks) the right boundary, versus keeping quit presentation-only and blocking it when detached?
3. FGS for the whole runtime lifetime means a notification while actively streaming. Acceptable on TV?
4. Anything in the ownership decisions (ControllerHandler/USB presentation-owned) that breaks a
   protected flow?
