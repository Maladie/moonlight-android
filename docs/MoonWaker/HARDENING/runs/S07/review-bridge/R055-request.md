# R055: S07 post-edit re-review — answers to R054

Author: Claude. Date: 2026-09-29. Do not run lab tests. Nothing committed or pushed.
Branch `codex/session-hardening`, HEAD `3913979f` + uncommitted S07 candidate.

## Identity
- Output fingerprint: `sha256:1702cdbe…8d32`.
- Tracked diff `work\s07\s07-tracked.diff` sha256 `50fee102…df66` (pre-existing unrelated submodule pointer line included, not S07); untracked sources in `work\s07\source-sha256.txt`.
- APKs (`work\s07\apk-sha256.txt`): debug `6981e62c…d820`, release-unsigned `abfa0dcf…67ca`.
- `gradlew :app:testNonRootDebugUnitTest :app:assembleNonRootDebug :app:assembleNonRootRelease` → BUILD SUCCESSFUL, **1169 tests / 0 failures** (`work\s07\android-build-8.log`).
- `runs\S07\implementation.json` updated (deviation text, checks).

## Answers
- **P1 accepted PREPARING warm-up after recreation** — `Game.onCreate` warm-up branch: `if (reattachPending && resumeAcceptedWarmUp())` adopts the coordinator's accepted Home frame (`isPreparingHomeFrameAccepted` on the exact preparing snapshot, or a retained HOME/PARKED warm-up owned by the runtime): sets `autoWarmUpHomeFrameAccepted`, runs `overlayRendered → transitionCoordinator.start() → startConnectionIfReady` (reattach branch: no native/provider start; the adopted provider owner joins/reuses). A second Home is opened only for a resume intent (`savedInstanceState == null`); a task restore recreates the Home itself, and `StreamHomeActivity` skips re-submission when the frame is already accepted. `resumeAttachedTransport` completes an exact PREPARING when the transport is already connected, mirroring `connectionStarted`. `cancelUnstartedAutoWarmUp` in `onDestroy` is now unreachable for an accepted attempt. Tests: `StreamRuntimeTest.acceptedWarmUpSurvivesRepeatedRecreationWhileConnecting` (two recreations while CONNECTING: one native start, one accepted Home frame, connectionStarted only to the current presentation, state stays PREPARING/accepted) and `GameTransitionCancelContractTest.reattachedWarmUpAdoptsOnlyAnAcceptedFrameOrRetainedWarmUp` (decision helper `Game.shouldResumeAcceptedWarmUp`). T26-preparing-recreation remains a device check.
- **P1 late stop observer** — `StreamRuntime.stop` in STOPPED now replays the cached terminal result via `stopCoordinator.whenStopped(...)` with the generation fence. Test `lateObserversOfAFailedStopNeverSeeStopped` (before/after completion, with a successor: FAILED everywhere, successor never starts native).
- **P1 holderless cleanup** — `endDetachedOwner` marks `bindOnCleanup`; holderless compensation (immediate or after the in-flight start) binds the exact process from one authenticated snapshot (`captureCurrentAttemptProcess`) before claiming; unobservable/ambiguous stays unconfirmed. Attached cancel keeps the existing observed-identity-only rule (existing test unchanged). Tests `holderlessOwnerStopsTheObservableExactProcessOnce` (one exact stop with PROCESS_TOKEN, owner retired, cleanup success) and the earlier unobservable test.
- **P2 observation pool** — `detachForRecreation` now calls `close()` (stop + `executor.shutdown()`), keeping only the owner entry/custodian; compensation uses the provider executor. Test `detachForRecreationReleasesTheOldObservationPool` (pool terminates). The in-flight start result still reaches the adopter (`recreatedPresentationJoinsTheOneInFlightStartWithoutAccepting`).
- **Owner question** — noted: for S07-END-GAME-RELAUNCH the lab run will correlate `runtime.busy`, `runtime.stop_requested`, `runtime.stopped` and `retained_stream.state_changed` (a TERMINATING projection yields the existing "Zamykanie sesji hosta…" rejection).

Verdict requested: READY / CHANGES_REQUIRED / INSUFFICIENT_EVIDENCE (code review; device evidence pending).
