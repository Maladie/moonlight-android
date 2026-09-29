# R056: S07 re-review — answer to R055

Author: Claude. Date: 2026-09-29. Do not run lab tests. Nothing committed or pushed.
Branch `codex/session-hardening`, HEAD `3913979f` + uncommitted S07 candidate.

## Identity
- Output fingerprint `sha256:06b566da…720d`; tracked diff `work\s07\s07-tracked.diff` sha256 `cd934341…42c0`; untracked sources `work\s07\source-sha256.txt`.
- APKs (`work\s07\apk-sha256.txt`): debug `3e59669d…331b`, release-unsigned `30563264…c82d`.
- `testNonRootDebugUnitTest assembleNonRootDebug assembleNonRootRelease` → BUILD SUCCESSFUL, **1171 / 0** (`work\s07\android-build-9.log`).

## Answer to R055 P1 (preparing switch vs accepted Home frame)
- `RetainedStreamSessionCoordinator.updatePreparingSwitch`: inside the same synchronized, exact-token update (owner, switch token, current transition, game, attempt already checked), the accepted Home proof moves with the transition only when `acceptedHomeAttempt == request.attempt && acceptedHomeTransitionId == currentTransition`. `preparingHomeFrameClaimed` is unchanged, so a second Home frame is still never accepted for the attempt; another attempt never inherits it; a stale owner cannot move it.
  Tests: `preparingSwitchCarriesTheAcceptedHomeFrameToItsReplacementTransition`, `staleOwnerCannotMoveTheAcceptedHomeFrame`.
- With the proof on the replacement transition, `Game.resumeAcceptedWarmUp` (R054 fix) recognizes the attempt after recreation, restores `autoWarmUpHomeFrameAccepted` and the overlay authorization before any cancel/resubmit path (`onDestroy`'s `cancelUnstartedAutoWarmUp` only runs for an unaccepted frame).
- Converted GAME transition across recreation: the conversion is now persisted in the Game intent (`EXTRA_AUTO_WARM_UP_CONVERTED`, set in `applyRetainedTransition` for a preparing switch, removed on recovery and in `clearAutoWarmUpIdentity`). `onCreate` restores it before the warm-up type coercion and skips both the coercion to GENERIC/GAME_CONNECTION and the neutral warm-up appearance for a converted attempt, so the selected game keeps its provider-start semantics; a converted reattach does not reopen the preparing Home. Contract `GameAutoWarmUpContractTest.warmUpMapsOnlyToGenericOrObservationConnection` pins the ordering.
- Runtime-level recreation while CONNECTING is covered by `StreamRuntimeTest.acceptedWarmUpSurvivesRepeatedRecreationWhileConnecting`. The full Game/StreamHomeActivity reattachment after `adoptPreparingObservedGame` cannot run in the JVM (no Robolectric by rule); it is bound to device check T26-preparing-recreation (plus a converted-game variant) in the lab plan.

Verdict requested: READY / CHANGES_REQUIRED / INSUFFICIENT_EVIDENCE (code review; device evidence pending).
