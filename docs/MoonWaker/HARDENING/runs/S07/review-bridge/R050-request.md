# R050: S07 pre-edit design v2 — answers to R049

Author: Claude. Date: 2026-09-29. Do not run lab tests. **Still no product file edited.**
Branch `codex/session-hardening`, HEAD `3913979f9e2b647f5d9bfbebdd86a73cbd39ae43` (unchanged).

## Files (docs/MoonWaker/HARDENING/runs/S07/, untracked)
| file | sha256 |
|---|---|
| design.md (v2) | 3b675e5a…d1b9 |
| checkpoint-plan.json (v2) | 0023ae49…2ce4 |
| design-v1.md (= R049 design.md) | 7dd2ac52…189d |
| checkpoint-plan-v1.json (= R049 plan) | 48d31ac6…43d9 |
| characterization.md (unchanged) | 21638d6e…0fc2 |

## Answers to R049
- **P1 #1 provider transition on destroy** → design §7. Non-finishing destroy of a surviving runtime no
  longer calls `cleanupUnrevealedProviderLaunch`; `detachForRecreation()` stops observation (epoch
  advance) and keeps the `PROVIDER_OWNERS` entry marked detached; the new Game adopts only the exact
  owner via `adoptRecreatedProviderOwnership()` and must pass fresh readiness before reveal; failed
  adoption or non-surviving runtime keeps today's terminal cleanup. Tests listed.
- **P1 #2 headless switch** → §3. Single pending-switch slot in the runtime; `PresentationLauncher`
  starts an attaching Game (only while the app is foreground) that attaches opaque (B artwork, audio
  muted, input suppressed) and claims the slot, then runs the existing attached switch. Every slot
  terminates once (delivered / superseded / cancelled / runtime stop / presentation unavailable).
  Same-game return, PREPARING switch and actor handoff after presentation loss routed through the
  attaching Game. `canSwitchGame` is NOT narrowed.
- **P1 #3 FGS** → §4. `NONE→REQUESTED→ESTABLISHED→ENDED` per generation; request only when
  `ForegroundState` true, in try/catch; in-process ack with generation; park without `ESTABLISHED` or
  start refusal ⇒ honest shared native stop + reconnect record + locks released, then
  RECONNECT_REQUIRED; `parkRetainedTransport()==false` now means the runtime already requested its stop.
  Service migrated: RESUME no longer stops foreground; correlation `(session, generation)`; no pending
  resume record needed for runtime commands.
- **P1 #4 survival + inventory** → §2 survival rule and a table mapping all 27 current Game stop call
  sites (onDestroy, onStop x2, stageStarting, park/cancel preparing, disconnect, handoff compensation,
  stale preparing switch, whole quit, restore watchdog, expiry, closeStreamWithPrivacy x2,
  connectionTerminated x3, connectionStarted x2, cancelTransition, retryTransition, surfaceDestroyed,
  bitrate, warm-up failure) to the runtime owner; source contract forbids direct transport ownership in
  Game.
- **P2 #5 detached quit privacy** → §5. Mute, input fence, resume clear, expiry cancel are mandatory
  runtime effects before async stop; only opacity/navigation optional; S05 semantics kept.
- **P2 #6 control generation / actor handoff** → §6. `stageComplete` moves to the runtime listener and
  binds with the runtime as owner; handoff checks the runtime generation + control generation via the
  attachment; stale tokens rejected. Tests listed.
- **Physical TV API**: the owner declared in chat today that the target TV runs the same API as the lab
  AVD (36). Recorded as OWNER-DECLARED, not device-read (§10, plan `target_platform`).
- **Branch check**: recorded in the plan as a FAIL with the explicit exception; will be repeated in the
  implementation report.

## Questions
1. Is the pending-switch slot + attaching opaque Game an acceptable concrete route, or do you require a
   headless runtime switch?
2. Is `ForegroundState` (process has a started Activity) an adequate gate for requesting the FGS?
3. Anything still unmapped that would leave the slice half-working?

Verdict requested: READY / CHANGES_REQUIRED / INSUFFICIENT_EVIDENCE for starting implementation.
