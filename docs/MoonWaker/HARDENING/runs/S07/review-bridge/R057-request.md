# R057: S07 re-review — answer to R056

Author: Claude. Date: 2026-09-29. Do not run lab tests. Nothing committed or pushed.
Branch `codex/session-hardening`, HEAD `3913979f` + uncommitted S07 candidate.

## Identity
- Output fingerprint `sha256:871f4f0a…cadf`; tracked diff `work\s07\s07-tracked.diff` sha256 `c1b01d57…48a1`; untracked sources `work\s07\source-sha256.txt`.
- APKs (`work\s07\apk-sha256.txt`): debug `c985e419…6c9e`, release-unsigned `051baf83…149e`.
- `testNonRootDebugUnitTest assembleNonRootDebug assembleNonRootRelease` → BUILD SUCCESSFUL, **1174 / 0** (`work\s07\android-build-10.log`).

## Answer to R056 P1 (queued but not yet reserved provider start)
`ConsoleStreamTransitionCoordinator` gains owner state **PENDING** (reserved, external start not invoked):
- `requestProviderGameStart` reserves PENDING (holder/custodian = this) **before** queueing the worker, when the host slot is free.
- The worker claims PENDING only under both locks while it is still current and still the holder (`claimPendingStart` → STARTING, then the one `startGame`). A detached (closed) coordinator's queued worker returns at its currency check and can never claim.
- `adoptRecreatedProviderOwnership` adopts a holderless PENDING owner; the adopter then performs the first start itself (`providerStartInvoked` stays false for PENDING).
- `endDetachedOwner` removes a holderless PENDING owner (never started → nothing to stop); cleanup/queue-failure release an own PENDING reservation; PENDING gives no transfer proof and cannot be detached for a switch.
- Another attempt's entry is **never** replaced at queue time (its cleanup must run first — `staleAttemptCleanupRunsBeforeNewAttemptStart` protects that ordering); in that case the worker reserves at run time exactly as before. A recreation inside that double race (old attempt still in the slot + queue window) fails closed (`provider.adoption_failed`, error UI, no second start). Declared in implementation.json.
Tests: `queuedButNeverInvokedStartIsAdoptedAndStartedExactlyOnce` (success and failure; stale worker never invokes; exactly one `startGame`; result to the adopter only), `mismatchedSpecCannotAdoptAQueuedReservation`, `endedRuntimeReleasesANeverInvokedReservationWithoutStopping`; the STARTING/STARTED cases remain covered by `recreatedPresentationJoinsTheOneInFlightStartWithoutAccepting`, `holderlessOwnerStopsTheObservableExactProcessOnce`, `detachedOwnerWithoutObservedProcessIsNeverReportedStopped`. Device variant: converted-game T26-preparing-recreation.

Verdict requested: READY / CHANGES_REQUIRED / INSUFFICIENT_EVIDENCE (code review; device evidence pending).
