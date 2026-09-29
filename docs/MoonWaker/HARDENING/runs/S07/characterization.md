# S07 characterization — physical stream ownership at HEAD 3913979f

Read-only inspection of `codex/session-hardening` HEAD `3913979f9e2b647f5d9bfbebdd86a73cbd39ae43`
(2026-09-29). Line numbers refer to that HEAD.

## Branch / lineage note
- The branch history was rewritten before S07. The old tip `b05f3e2c` survives only as local
  branch `backup/session-hardening-b05f3e2c`. `STATE.workspace.base_head` (`00366fd9…`) belongs to
  the old lineage and is an ancestor of the backup branch, not of HEAD.
- `packet.py branch-check --repo .` therefore fails at `git merge-base --is-ancestor 00366fd9 3913979f`
  (exit 1, no stderr). Per the owner's instruction this is recorded, not "repaired"; STATE is not
  edited. `validate` = VALID_PACKET_METADATA, `next` = S07 dispatchable (S03–S06 CLOSED_BY_OWNER).

## Who owns what today

| Resource | Owner today | Created | Released |
|---|---|---|---|
| `NvConnection conn` | `Game` field (Game.java:185) | `onCreate` (865) | `stopConnectionResult` via `TransportStopCoordinator` (4584) |
| `TransportStopCoordinator transportStop` | `Game` field (186) | `onCreate` (869), stop effect `conn::stop` on a new thread | shared completion, fenced by process-global generation |
| static `NvConnectionListener` | `MoonBridge.connectionListener` (MoonBridge.java:129) = **Game** | `NvConnection.start` → `MoonBridge.setupBridge(video, audio, listener)` (NvConnection.java:553) | `MoonBridge.cleanupBridge()` in `NvConnection.stop` |
| static renderers | `MoonBridge.audioRenderer/videoRenderer` | same `setupBridge` | `cleanupBridge` |
| `AndroidAudioRenderer streamAudioRenderer` | `Game` (187), built with the **Activity** context | `startConnectionIfReady` (6039) | with native stop |
| `SwitchableVideoDecoderRenderer` | `Game` (188) | `onCreate` (737) | never explicitly; detached/attached on park/restore |
| `MediaCodecDecoderRenderer decoderRenderer` | `Game` (295), Activity context + surface | `onCreate` (736), `restoreParkedStream` (4469) | `park` detaches (3235) |
| Wi-Fi high-perf / low-latency locks | `Game` (301/302) | `onCreate` (651–661) | `onDestroy` (1775–1780) |
| `ControllerHandler` | `Game` (178), takes `Activity`, `conn`, `GameGestures` | `onCreate` (871) | `onDestroy` `destroy()` (1765) |
| `UsbDriverService` binding | `Game` (304–319), listener = controllerHandler | `onCreate` (911) | `onDestroy` (1782) |
| `RetainedStreamSessionCoordinator.Controller` | **Game** (implements it, line 152), kept in a `WeakReference` | `enterHome/beginPreparing/...` | `clear/markReconnectRequired` |
| Background FGS | `BackgroundStreamService.park()` → `startForegroundService` from `onStop`/`surfaceDestroyed`/`parkForBackground` (no try/catch) | park | `resumed` → `stopForeground+stopSelf` |
| Whole-session quit (S05) | `Game.terminateWholeSessionVerified` (4347) runs `WholeSessionTermination` with Game privacy/navigation hooks and `HostSessionTermination.run` using Game intent extras | quit | direct/indirect coordinator completion |

## Characterized behavior
1. **Activity destroy stops the retained transport.** `onDestroy` (1698): if `hasOwnRetainedSession() && (connecting||connected)`, it saves the resume record, marks `RECONNECT_REQUIRED` (or warm-up reconnect), calls `BackgroundStreamService.transportLost` and `stopConnection()`. Non-retained: `stopConnection()` as well. `isChangingConfigurations()` does not protect the transport. Hence Activity recreation (e.g. "Don't keep activities" after TV Home) ends the transport → H05 reproduced by code path.
2. **Home is translucent.** `StreamHomeActivity` (ConsoleActivity subclass) runs over Game so the surface stays alive; Game is paused, not stopped. `ConsoleActivity.onCreate` (1036) finishes itself when `canResumeInstantly()` to reveal the Game below; `returnToRetainedStream` (14540) only launches a resume intent when not instantly resumable. With Game destroyed but a transport alive, finishing Console would reveal nothing — any runtime-survives design must launch an attaching presentation here.
3. **Park/restore is already presentation-level.** `parkBackgroundStream` (3231) detaches the decoder from the `SwitchableVideoDecoderRenderer`, mutes audio, disables sensors, ungrabs input, saves the resume record, marks PARKED, starts the FGS. `restoreParkedStream` (4461) attaches a new decoder to the current surface, requests IDR and arms a 10 s watchdog that reconnects on no frame.
4. **Listener callbacks are Activity-bound.** `connectionTerminated` (4679) contains presentation logic (bitrate reconnect, whole-quit, unrevealed provider cleanup, warm-up failure, error dialogs) and a retained branch (`hasOwnRetainedSession`) that marks RECONNECT_REQUIRED, `transportLost`, stops and finishes. `connectionStarted` (4841) runs `isFinishing/isDestroyed → stopConnection`. Rumble/LED/motion callbacks go to `controllerHandler`.
5. **One native connection.** `NvConnection.stop` serializes `stopConnection/cleanupBridge` on `MoonBridge.class`; start is gated by `connectionAllowed` semaphore (NvConnection). `TransportStopCoordinator` chains each new generation to its predecessor: `start` waits for predecessor stop (`awaitPredecessor`). A predecessor that is ACTIVE and never stopped makes the successor wait indefinitely (no forced stop).
6. **Controller reference is weak.** Coordinator keeps `WeakReference<Controller>`; today the Activity keeps itself alive. A non-Activity runtime would be collectable unless the coordinator holds it strongly.
7. **Foreground service** is started only on park (from `onStop`/`surfaceDestroyed`, i.e. while leaving foreground). targetSdk 34, `foregroundServiceType="mediaPlayback"`. On Android 12+ a background `startForegroundService` can throw `ForegroundServiceStartNotAllowedException`; there is no guard.
8. **No exit-reason evidence / StrictMode.** No `ApplicationExitInfo` or `StrictMode` use in `app/src/main`. `MoonWakerApplication.onCreate` initializes `MoonWakerDiagnostics` and records `process.started`.
9. **Actor handoff reuses the connection** (Game.java:3594–3830: `transferConnection`, `reauthorizeControlGeneration`) and the bitrate change path (6764) reconnects via stop + relaunch; both key on Game's `conn` field.
10. **Tests.** JUnit only (no Robolectric/androidTest). `TransportStopCoordinatorTest` (7 tests) covers shared completion; `GameTransportStopContractTest` and other `Game*ContractTest` classes are source-text contracts on Game.java that will move with the code.

## Target levels
- targetSdk/compileSdk 34, minSdk 21.
- Owned lab AVD `MoonWaker_Lab_TV36` (emulator-5580, adb 5038): `ro.build.version.sdk = 36` (read-only `getprop`, 2026-09-29).
- Physical target TV API level: **UNKNOWN** — not in repository evidence; reading it from the TV is outside S07 authority. Needs the owner's value.
