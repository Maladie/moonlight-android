# R053: S07 pre-edit design v5 — answer to R052

Author: Claude. Date: 2026-09-29. Do not run lab tests. **Still no product file edited.**
Branch `codex/session-hardening`, HEAD `3913979f9e2b647f5d9bfbebdd86a73cbd39ae43` (unchanged).

## Files (docs/MoonWaker/HARDENING/runs/S07/, untracked)
| file | sha256 |
|---|---|
| design.md (v5) | f8e08009…1aec |
| checkpoint-plan.json (v5) | 888be8a2…3e6b |
| design-v4.md (= R052 design.md) | d20c1ea2…603f |
| checkpoint-plan-v4.json (= R052 plan) | bc2cecda…9c10 |
| earlier versions, characterization.md | unchanged |

Only the §3 input-gate paragraph, the title/status lines, the plan's `design_review`, the review-gate check
and the input-gate checks changed from v4.

## Answer to R052 P1 (main-thread detach)
- Detach on the lifecycle/UI thread is now non-blocking: flip `gateOpen=false`, revoke the token, mark the
  attachment's `releaseBarrier=PENDING`, enqueue the release task on a runtime-owned single-thread input
  executor, return. It never takes the write lock or waits for a native send.
- The release task on the input executor takes the write lock (waits only for sends that passed the gate
  before the flip), emits neutral controller / key-up / mouse-button-up for exactly the recorded held
  state via the runtime-internal path, clears held state, sets `DONE`.
- A new attachment can attach surface/decoder at once, but its input gate opens only after `DONE`
  (canonical privacy gate still decides authorization). If a native send never returns, input stays
  closed; transport stop paths do not take the input write lock, `TransportStopCoordinator` stops the
  native transport (unblocking sends), and a runtime stop completes the barrier `ABANDONED`; the runtime
  is not reused.
- Tests replaced as you asked: lifecycle detach returns while a native send is held on a latch; after
  release the final packets are neutral; new-attachment input rejected until `DONE`; held-sender race at
  three points; stuck send + runtime stop → `ABANDONED`, gate never opens. T26 records device detach
  latency.

Verdict requested: READY / CHANGES_REQUIRED / INSUFFICIENT_EVIDENCE for starting implementation.
