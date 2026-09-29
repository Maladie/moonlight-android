# R052: S07 pre-edit design v4 — answers to R051

Author: Claude. Date: 2026-09-29. Do not run lab tests. **Still no product file edited.**
Branch `codex/session-hardening`, HEAD `3913979f9e2b647f5d9bfbebdd86a73cbd39ae43` (unchanged).

## Files (docs/MoonWaker/HARDENING/runs/S07/, untracked)
| file | sha256 |
|---|---|
| design.md (v4) | d20c1ea2…603f |
| checkpoint-plan.json (v4) | bc2cecda…9c10 |
| design-v3.md (= R051 design.md) | 4d82cf1d…c193 |
| checkpoint-plan-v3.json (= R051 plan) | 7884ce9c…a1e3 |
| v1/v2 files, characterization.md | unchanged |

## Answers to R051
- **P1 #1 SessionOrchestrator contract** → design §3 steps 1–5. New `CloseResult.PRESENTATION_REQUIRED`
  returned by Console's `closePreviousSession` when the retained switch is eligible except for a missing
  attached presentation (no `switchGame` call). The orchestrator's dispatcher, if `current(request)`, calls
  the new effect `attachPresentationForSwitch(intent, target)` and ends the request (never `launch`, never
  untargeted `returnToRetainedStream`). Console starts the singleTask Game with the target play-intent
  extras, reports `presentation_launch_failed` if the start throws, and finishes the retained Home.
  Game holds one undispatched pending switch: `onCreate` reads it, a new `onNewIntent` override replaces
  it before submission; after opaque attach it submits the ordinary attached `switchGame` once; a later
  pending-switch intent while a switch runs is rejected by the existing `NOT_ELIGIBLE` rule with the
  existing blocked message. Launch-without-attach leaves the coordinator untouched. `SessionOrchestrator`
  added to the production path; tests use the real orchestrator with fake effects.
- **P1 #2 STARTING is not STARTED** → §7. `ownerState(spec)` = `NONE|STARTING|STARTED` in every lookup;
  only `STARTED` takes the reuse branch; `STARTING` joins the single in-flight result without accept,
  observation or a second `startGame`, then follows the normal success/failure branch; `STARTING` yields no
  transfer proof or process identity. Holderless cleanup after a successful start stops only a verified
  exact process from one authenticated snapshot; if not yet observable it reports unconfirmed (never
  success), as today's `compensateProviderStart` does without a token. Tests include `onStreamConnected`
  after adopt before the held start returns, and the not-yet-observable case.
- **Input-gate answer** → §3: read lock covers one native send + held-state update only; no network/host/UI
  work under the lock; blocked-native-send vs detach latch test; lab records main-thread detach latency (no
  invented threshold).
- Plan: READY now required from R052 or the first later READY; R049–R051 kept as CHANGES_REQUIRED.

Verdict requested: READY / CHANGES_REQUIRED / INSUFFICIENT_EVIDENCE for starting implementation.
