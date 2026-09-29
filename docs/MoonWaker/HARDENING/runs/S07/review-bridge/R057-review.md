# R057 independent review — S07 follow-up

**Verdict: CHANGES_REQUIRED.** Source and submitted-evidence review only. I ran no build, unit suite, or lab test.

## Identity and evidence

Branch codex/session-hardening at HEAD 3913979f9e2b647f5d9bfbebdd86a73cbd39ae43, with the uncommitted S07 candidate. The current git diff --binary exactly matches work/s07/s07-tracked.diff (SHA-256 c1b01d57dbabfef1e989b98848a5bfbe1815ef8ca99fd53dcc497863a26c48a1). All entries in source-sha256.txt and apk-sha256.txt match the current files. git diff --check exits successfully with line-ending warnings only. work/s07/android-build-10.log reports BUILD SUCCESSFUL for the unit task and both APK assemblies; the existing XML reports total 1174 tests, 0 failures and 0 errors. Device checks remain pending.

The direct R056 queue-window case is addressed: a free host slot gets a PENDING entry before enqueue; a stale worker cannot claim after detach, and the adopter can start exactly once. The new deterministic tests cover that order, wrong-spec rejection and ending a detached PENDING attempt. The finding below is a concurrent cleanup edge created by the gap between the initial state check and that new reservation.

## Finding

### [P1] Cleanup can finish before reservePendingStart creates an orphan PENDING owner

Locations: app/src/main/java/com/limelight/console/ConsoleStreamTransitionCoordinator.java:497-522, 594-635, 1018-1052, 1071-1081, 274-294; app/src/main/java/com/limelight/stream/StreamRuntime.java:588-597; app/src/main/java/com/limelight/Game.java:4691-4694, 5531-5555.

Evidence/impact: requestProviderGameStart checks isCurrent and providerCleanupRequested under the coordinator lock and sets providerStartRequested, then releases that lock before reservePendingStart. A concurrent transport termination can call Game.cleanupUnrevealedProviderLaunch -> onStreamFailed -> requestProviderCleanup on the transport callback thread. It calls releaseOwnPendingStart while no entry exists, marks cleanup requested, stops the coordinator and reports completion. The first thread can then enter reservePendingStart, which does not recheck the coordinator epoch/cleanup state, and inserts a PENDING owner with this cancelled coordinator as holder. Its queued worker immediately returns on !isCurrent. No later cleanup releases that entry; endDetachedOwner also skips entries with a non-null holder. A subsequent attempt at the same host sees the stranded PENDING slot and cannot claim or start its own game. This interleaving is absent from the three new queued-start tests and has not been observed on a device here.

Correction: Perform the current-epoch and cleanup check atomically with PENDING reservation under the existing provider-owner and coordinator locks (in their established order). If cleanup won, leave no entry and do not enqueue. Preserve the current single-start and exact-adoption rules.

Verification: Add a controlled interleaving where cleanup completes after providerStartRequested is set but before PENDING reservation. Assert no owner remains, no external start/stop is sent, and a later exact new attempt can start once. Keep the R057 queued-adoption, STARTING and STARTED tests. The current 1174-pass suite does not cover this race.

## Disposition

The R056 pre-reservation recreation case is addressed for an otherwise live coordinator. The concurrent cleanup window still blocks readiness. No device outcome is inferred from local tests.

**Verdict: CHANGES_REQUIRED.** Submit a correction with refreshed diff identity and evidence. No lab test was run during this review.