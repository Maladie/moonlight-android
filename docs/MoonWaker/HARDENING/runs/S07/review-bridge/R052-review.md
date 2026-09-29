# R052 independent pre-edit review — S07 design v4

**Verdict: CHANGES_REQUIRED.** The R051 gaps are resolved at the design level: SessionOrchestrator now has a distinct PRESENTATION_REQUIRED outcome with target-bearing attach, and provider STARTING joins the existing in-flight start rather than taking the STARTED reuse path. One proposed input-gate behavior can block the Android lifecycle thread indefinitely and needs a narrower correction before implementation.

I read R052, R051, the actual v3-to-v4 design and checkpoint-plan diffs, and the relevant SessionOrchestrator, Console, Game, provider coordinator, NvConnection and manifest code. HEAD remains 3913979f9e2b647f5d9bfbebdd86a73cbd39ae43 on codex/session-hardening. The submitted hashes match: design v4 d20c1ea2...603f, plan v4 bc2cecda...9c10, design v3 4d82cf1d...c193, plan v3 7884ce9c...a1e3. The S07 files remain untracked and no product file changed. I ran no build, unit suite or lab test.

## Finding

### [P1] Main-thread detach can wait indefinitely behind a synchronous native input send

Design v4 section 3 puts one transport.send under the runtime read lock and makes detach acquire the write lock on the main thread. It explicitly says a blocked native send delays detach until that send returns; its JVM test asserts that wait, and the lab merely records the delay without a bound. NvConnection.java:600-629 calls MoonBridge native send synchronously. There is no guarantee in this design that the call returns promptly or at all under a stalled native/input path. Game.onDestroy runs on the lifecycle/UI thread (Game.java:1698), while its current connection stop is dispatched through TransportStopCoordinator rather than waiting for native stop on that thread (Game.java:4584-4605). The proposed read/write-lock wait could therefore turn presentation recreation or Home into an ANR, precisely when S07 must preserve the runtime.

Keep the input ordering guarantee, but close/revoke the presentation gate on a short UI-thread operation and move any wait for in-flight native sends plus neutral release to a runtime-owned worker/serialized path. Do not permit a replacement presentation to send input until that drain-and-release barrier completes or the old transport is declared unsafe and stopped. An equivalent bounded nonblocking design is fine. Replace the current blocked-send test with one that holds a native send, proves the lifecycle detach returns without waiting for the latch, then releases it and verifies neutral is the final input before a new attachment is enabled. Record actual device detach latency in T26 as planned.

## Confirmed R051 corrections and limits

- SessionOrchestrator now maps PRESENTATION_REQUIRED to attachPresentationForSwitch without a fresh launch or untargeted return. The plan includes SessionOrchestrator and real effect-wiring tests. Game's new onNewIntent handler and opaque attach are explicit implementation work. Current SessionOrchestrator.java:469-523 and ConsoleActivity.java:13860-13884 support why this new result is needed.
- The provider state distinction is explicit: STARTING cannot take the current reuse branch at ConsoleStreamTransitionCoordinator.java:441-455, and its in-flight result is delivered to the adopting holder. The plan includes success, failure and terminal-unconfirmed cases. An unobservable process is reported unconfirmed rather than falsely stopped.
- The plan preserves R049-R051 CHANGES_REQUIRED, requires the first later READY, and retains the physical-TV API as an unverified expectation. The branch-check failure remains recorded with its owner exception.

The input-gate correction is the only blocker found in v4. Submit the updated design and required test before product edits. **Verdict: CHANGES_REQUIRED.**