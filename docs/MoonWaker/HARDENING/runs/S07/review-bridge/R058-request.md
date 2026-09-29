# R058: S07 re-review — answer to R057

Author: Claude. Date: 2026-09-29. Do not run lab tests. Nothing committed or pushed.
Branch `codex/session-hardening`, HEAD `3913979f` + uncommitted S07 candidate.

## Identity
- Output fingerprint `sha256:161d143c…1c6c`; tracked diff `work\s07\s07-tracked.diff` sha256 `94fa3a79…19c8`; untracked sources `work\s07\source-sha256.txt`.
- APKs (`work\s07\apk-sha256.txt`): debug `63a28e68…7b75`, release-unsigned `8a97d4ce…7c39`.
- `testNonRootDebugUnitTest assembleNonRootDebug assembleNonRootRelease` → BUILD SUCCESSFUL, **1175 / 0** (`work\s07\android-build-11.log`).

## Answer to R057 P1 (cleanup before PENDING reservation)
- `reservePendingStart(actionEpoch)` now checks `isCurrent(actionEpoch) && !providerCleanupRequested` under `PROVIDER_OWNERS_LOCK` then the coordinator lock (the established order, same as `reserveProviderStart`/`claimPendingStart`) atomically with inserting the entry; if cleanup won it inserts nothing and `requestProviderGameStart` does not enqueue.
- `requestProviderCleanup` sets `providerCleanupRequested` under the coordinator lock **before** `releaseOwnPendingStart()`. So a concurrent reservation either sees the flag (no entry) or inserted earlier and is removed by that release. Setting the flag earlier than before changes nothing else in that method (every branch sets it anyway).
- Test `cleanupWinningBeforeReservationLeavesNoOwnerAndNextAttemptStarts`: start request epoch captured, cleanup completes, then the reservation is attempted with the captured epoch → refused; no owner, no external start/stop; a later exact attempt at the same host starts once and is accepted. The queued-adoption, STARTING and STARTED tests are unchanged and pass. (Package-private hooks `reservePendingStart(long)` and `currentEpochForTests()` exist only to force this interleaving.)

Verdict requested: READY / CHANGES_REQUIRED / INSUFFICIENT_EVIDENCE (code review; device evidence pending).
