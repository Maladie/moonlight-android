# R056 independent review — S07 follow-up

**Verdict: CHANGES_REQUIRED.** Source and submitted-evidence review only. I ran no build, unit suite, or lab test.

## Identity and evidence

Branch codex/session-hardening at HEAD 3913979f9e2b647f5d9bfbebdd86a73cbd39ae43, with the uncommitted S07 candidate. The current git diff --binary exactly matches work/s07/s07-tracked.diff (SHA-256 cd9343415f6f7c5185a0c84dcee6a158efb556fff3972064cb2f339326ef42c0). All submitted untracked-source and APK hashes match. git diff --check exits successfully (line-ending warnings only). work/s07/android-build-9.log reports BUILD SUCCESSFUL for the unit task and both APK assemblies; existing XML reports total 1171 tests, 0 failures, 0 errors. No device result is inferred.

R055's accepted-Home finding is fixed for an exact preparing-switch update: RetainedStreamSessionCoordinator.updatePreparingSwitch moves the accepted transition ID only after its existing owner, switch, attempt, current-transition and game checks. The new tests verify the replacement transition, rejection of the old transition and different attempt, one Home submission, and stale-owner rejection. The converted flag is restored before Game's warm-up type coercion and cleared on recovery/completion. The remaining finding is in the provider-start handoff that this newly preserved GAME type reaches.

## Finding

### [P1] Converted GAME can fail recreation before provider-start reservation

Locations: app/src/main/java/com/limelight/Game.java:422-424, 481-502, 892-904, 980-991, 3947-3952; app/src/main/java/com/limelight/console/ConsoleStreamTransitionCoordinator.java:251-265, 488-516, 1036-1052; app/src/main/java/com/limelight/Game.java:3835-3865 and 1745-1756.

Evidence/impact: R056 correctly keeps a converted preparing switch as type GAME on recreation. In the converted-game path, startRetainedGameAttempt starts the new coordinator and calls onStreamConnected when runtime.isConnected, but requestProviderGameStart only queues the provider worker. The owner entry is created later, inside runProviderGameStart/reserveProviderStart. If Activity recreation occurs after that queueing and before reservation, the old Game detaches/closes a coordinator for which no owner entry exists. Its queued worker then returns because the coordinator is no longer current. The new Game retains type GAME, so adoptRecreatedProviderOwnership returns false for the missing entry; Game treats this as lost ownership, closes the new coordinator and records provider.adoption_failed. It has no remaining path to perform the intended first provider start. The existing in-flight recreation test waits until gateway.startGame has been entered, so it covers an already-reserved owner and cannot catch this earlier queue window. This is a source-level race; its occurrence on a device has not been observed here.

Correction: Represent the exact pending start before it is queued, or carry a separately provable never-started state across recreation so the new coordinator can safely own the first start. Preserve the existing fail-closed behavior when an external start might already have occurred. The provider owner/attempt should remain single across the handoff.

Verification: Use a controllable provider executor held before runProviderGameStart. Queue a GAME start, detach for recreation before reservation, attach the exact replacement, then release the worker. Assert no adoption_failed, exactly one external startGame, and correct success/failure delivery. Also test the existing already-STARTING and STARTED cases and a mismatched/stale spec. Include the planned converted-game T26-preparing-recreation device variant. The current 1171-pass suite is not evidence for this queue window.

## Disposition

The accepted-Home token repair and converted-type persistence answer the direct R055 finding. The provider-start queue gap above blocks readiness; device checks remain pending.

**Verdict: CHANGES_REQUIRED.** Submit a correction with exact diff identity and refreshed local evidence. No lab test was run during this review.