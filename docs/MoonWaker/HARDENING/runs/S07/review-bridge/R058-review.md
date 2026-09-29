# R058 independent review — S07 follow-up

**Verdict: READY for the owned-lab device gate.** Source and submitted-evidence review only. I ran no build, unit suite, or lab test.

## Identity and evidence

Branch codex/session-hardening at HEAD 3913979f9e2b647f5d9bfbebdd86a73cbd39ae43, with the uncommitted S07 candidate. The current git diff --binary exactly matches work/s07/s07-tracked.diff (SHA-256 94fa3a796cc45b7595ea5f09b9b81fa699fb90e306cda9f273c500b2ea0819c8). All entries in source-sha256.txt and apk-sha256.txt match the current files. git diff --check exits successfully with line-ending warnings only. work/s07/android-build-11.log reports BUILD SUCCESSFUL for testNonRootDebugUnitTest, assembleNonRootDebug and assembleNonRootRelease. Existing XML results total 1175 tests, 0 failures and 0 errors.

## R057 finding disposition

**Resolved.** ConsoleStreamTransitionCoordinator.reservePendingStart(actionEpoch) now checks the exact current epoch and providerCleanupRequested while holding PROVIDER_OWNERS_LOCK and the coordinator lock, atomically with inserting PENDING. requestProviderCleanup marks providerCleanupRequested before releasing an existing PENDING entry. Therefore cleanup that wins before reservation prevents insertion; cleanup that follows insertion removes the never-invoked entry. A stale worker still cannot claim it. The new cleanupWinningBeforeReservationLeavesNoOwnerAndNextAttemptStarts test verifies refusal after cleanup, an empty owner slot, no external start/stop, and one accepted start for the successor. The R057 queued-adoption, STARTING and STARTED coverage remains present.

I found no blocking source issue in the R058 correction. The new test calls the reservation hook directly instead of scheduling two real threads; the lock and state checks in the reviewed method provide the relevant atomicity. The documented branch-check exception remains an exception, not a PASS.

## Scope and next gate

READY is a code-review verdict. The candidate is still uncommitted and the S07 owned-lab device checks, including T26-preparing-recreation and its converted-game variant, are pending. Local JVM and APK evidence does not establish device or formal S05 acceptance. Claude remains the lab owner under the bridge protocol.

**Verdict: READY.** Proceed to the documented owned-lab device gate; no lab test was run during this review.