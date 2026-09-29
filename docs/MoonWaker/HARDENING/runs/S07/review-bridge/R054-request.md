# R054: S07 post-edit review — StreamRuntime implementation (design v5, R053 READY)

Author: Claude. Date: 2026-09-29. Do not run lab tests. Nothing committed or pushed.
Repo `D:\Maladie\moonlight-android`, branch `codex/session-hardening`, HEAD `3913979f` + **uncommitted S07 candidate**.

## Identity
- Output fingerprint (`packet.py fingerprint --repo .`, includes untracked worktree): `sha256:c64c68da…628f`.
- Tracked diff: `work\s07\s07-tracked.diff` (sha256 `7980ea94…14ca`; contains the pre-existing, unrelated moonlight-common-c pointer line — not S07).
- New untracked source files with hashes: `work\s07\source-sha256.txt`.
- APKs: debug `3ec006c4…e3da`, release-unsigned `d8612818…d4a2` (`work\s07\apk-sha256.txt`).
- Reports: `docs\MoonWaker\HARDENING\runs\S07\implementation.json`, `architecture.json`, `checkpoint-plan.json`, `design.md` (v5 + §12 API 32 note).

## Checks run
- `gradlew :app:testNonRootDebugUnitTest` → **1164 tests, 0 failures** (baseline before S07 1133/0). Logs `work\s07\android-full-4.log`, `android-build-5.log`.
- `assembleNonRootDebug` + `assembleNonRootRelease` → PASS (`work\s07\android-build-7.log`).
- No lab run (device evidence pending; S07 would at best be CODE_VERIFIED_DEVICE_PENDING).

## What to review (map to design v5)
1. `stream/StreamRuntime.java`: lifecycle (NEW→CONNECTING→CONNECTED→STOPPING→STOPPED), registry/one-connection rule, stop sharing + fence, Wi-Fi locks, FGS protocol (§4), park/resume, `presentationLost(attachment, finishing)` survival rule (§2), headless connectionTerminated/stageFailed, Controller delegation, headless whole quit (§5), input gate + release barrier (§3/R052).
2. `stream/AndroidStreamRuntime.java`: production seams (NvConnection/renderers, Wi-Fi, service, SessionResumeManager launch record, HostSessionTermination for headless quit, runtimeEnded → provider owner end).
3. `Game.java`: attach/reattach in onCreate (`StreamRuntime.forSession`), `startConnectionIfReady` reattach branch + `resumeAttachedTransport`, `stopConnectionResult`, `onDestroy` (detach + provider `detachForRecreation`), park via `runtime.park()`, `onTransportParked/onRuntimeDisconnected/onTransportStopping`, actor handoff fence (`transferRuntime/transferAttachment`), pending switch (`PendingRetainedSwitch.Slot`, `onNewIntent`), all 25 coordinator owner args → runtime.
4. `BackgroundStreamService` + `BackgroundStreamCorrelation` (session+generation protocol); Console `resumed→dismiss`.
5. `ConsoleStreamTransitionCoordinator` owner states (§7): reservation before `startGame`, `completeReservedStart`, join, `detachForRecreation`, `adoptRecreatedProviderOwnership`, `endDetachedOwner`, deferral.
6. `SessionOrchestrator.PRESENTATION_REQUIRED` + Console `attachPresentationForSwitch`, `returnToRetainedStream`, `onCreate` instant resume.
7. `ExitReasonRecorder` + debug-only StrictMode in `MoonWakerApplication`.
8. Migrated source-contract tests (please confirm none were weakened in intent) and new JVM tests.

## Deviations I am declaring
- §7: I did **not** bind process identity from a snapshot at holderless cleanup; the existing test `cancelledProviderStartWithoutObservedIdentityCannotStopUnknownProcess` encodes the stricter rule (stop only an identity bound by the attempt's own observation). Holderless cleanup therefore reports unconfirmed unless observation bound the process.
- Input sent between attach and the asynchronous gate opening (after the release barrier) is dropped.
- Headless child countdown/heartbeat is presentation-owned; while detached, host-side child expiry ends the stream and the runtime reports RECONNECT_REQUIRED.

## Questions
1. Behavioral + architecture verdicts on the candidate?
2. Any regression in F01–F07 you can see from the code (esp. warm-up PREPARING reattach, bitrate restart, actor handoff)?
3. Owner-reported pre-existing issue (not an S07 blocker): after overlay "End game" the next launch was refused as "previous stream still closing" on the physical TV. Do you see a code path explaining it (the new `stream_runtime_busy` refusal only applies when a different live runtime exists)?

Verdict requested: READY / CHANGES_REQUIRED / INSUFFICIENT_EVIDENCE (code review; device evidence pending).
