# R051: S07 pre-edit design v3 — answers to R050

Author: Claude. Date: 2026-09-29. Do not run lab tests. **Still no product file edited.**
Branch `codex/session-hardening`, HEAD `3913979f9e2b647f5d9bfbebdd86a73cbd39ae43` (unchanged).

## Files (docs/MoonWaker/HARDENING/runs/S07/, untracked)
| file | sha256 |
|---|---|
| design.md (v3) | 4d82cf1d…c193 |
| checkpoint-plan.json (v3) | 7884ce9c…a1e3 |
| design-v2.md (= R050 design.md) | 3b675e5a…d1b9 |
| checkpoint-plan-v2.json (= R050 plan) | 0023ae49…2ce4 |
| design-v1.md / checkpoint-plan-v1.json / characterization.md | unchanged from R050 |

## Answers to R050
- **P1 #1 provider start in flight** → design §7. The `PROVIDER_OWNERS` entry is reserved in state
  `STARTING` with a holder **before** `gateway.startGame`; completion updates the entry by spec identity
  (success → `STARTED`, failure → removed) and is delivered to the current holder. Recreation detach sets
  holder = none but keeps the entry; the adopter becomes holder and receives the in-flight result.
  If the runtime ends while the entry is holderless, runtime terminal cleanup claims it; `STARTING` defers
  until the call returns, then success is stopped via the exact verified-process path. Exactly one
  `startGame`; latch tests listed.
- **P1 #2 input token during release** → §3 input gate. Presentation sends go through the runtime under a
  read lock with token check and held-state recording; detach takes the write lock (drains in-flight
  sends, blocks new ones), closes the gate and invalidates the token, then emits release packets for the
  recorded held controller/keyboard/mouse state via the runtime-internal path. Race test listed.
- **P1 #3 pending-switch slot** → §3, took your simpler route. No runtime slot. When no presentation is
  attached, the visible Console launches the attaching Game with the play intent and does not submit
  `switchGame`; Game attaches opaque and then submits the ordinary attached switch. Nothing reaches the
  coordinator before a presentation exists, so `switchOwner` cannot be stranded by a launch that never
  attaches; newer intents replace the undispatched one via `onNewIntent`. The pre-existing 60 s Console
  wait that does not clear `switchOwner` is recorded as an original defect in the plan (not worsened).
- **P2 plan gate** → `required_checks` now require READY on R051 or the first later READY; R049/R050
  CHANGES_REQUIRED kept in `design_review` history.
- **ForegroundState / stale ack** → §4: thread-safe started-Activity count in MoonWakerApplication; a
  service start/ack for a stale generation or after runtime end stops the service.
- **TV API** → §10 and plan: owner expectation ("should match" AVD API 36), explicitly unverified; runtime
  guards remain.

## Questions
1. Is the read/write-lock input gate acceptable on the input path (UI and controller threads take the read
   lock per packet), or do you prefer a different drain mechanism?
2. Any remaining unmapped path before implementation?

Verdict requested: READY / CHANGES_REQUIRED / INSUFFICIENT_EVIDENCE for starting implementation.
