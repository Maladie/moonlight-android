# R053 independent pre-edit review — S07 design v5

**Verdict: READY for S07 implementation on the reviewed input.** This is approval of the pre-edit design contract, not a product-code review or checkpoint acceptance. The R052 main-thread input-detach blocker is resolved in v5.

I read R053, R052, the exact v4-to-v5 design and checkpoint-plan diffs, and the current Game, NvConnection, SessionOrchestrator and provider-coordinator paths used by the proposed contract. HEAD remains 3913979f9e2b647f5d9bfbebdd86a73cbd39ae43 on codex/session-hardening. The submitted SHA-256 values match the files: design v5 f8e08009...1aec, plan v5 888be8a2...3e6b, design v4 d20c1ea2...603f, plan v4 bc2cecda...9c10. The S07 directory remains untracked and contains no product edit. The v4-to-v5 diff has no whitespace error. I ran no build, unit suite or lab test.

## Design assessment

- The lifecycle/UI detach now closes the volatile input gate, revokes the attachment token and queues a runtime-owned release task without taking the write lock. The worker drains prior sends under the write lock, sends neutral controller/key/mouse packets and marks the barrier DONE. A replacement presentation can restore video while its input stays closed until that barrier completes. A stuck send plus runtime stop leaves the barrier ABANDONED and cannot reopen input. This answers R052 without losing the R050 ordering fence.
- The v4 SessionOrchestrator route remains explicit: PRESENTATION_REQUIRED carries the selected target to an attaching Game, with no premature coordinator switch, untargeted resume or second native launch. The STARTING provider reservation joins the one in-flight start and is not treated as STARTED. The required JVM checks name the relevant interleavings and the plan preserves all prior review outcomes.
- The production path includes Game, Console, SessionOrchestrator, provider coordinator, BackgroundStreamService/Correlation, the retained coordinator and the new runtime. The 27 Game stop call sites are mapped. Whole quit, child expiry, control-generation handoff, FGS refusal and no-presentation termination each have a named owner and check. No new design blocker was found.

## Implementation checks to keep

The post-edit review should verify that gate close/token revocation is visible to every input sender, all direct Game/ControllerHandler sends use the runtime gate, and the release task opens a new attachment only for the same live transport generation after DONE. The blocked-send, stop-to-ABANDONED and late callback tests in the plan are required; they are future checks, not passed evidence. If native stop cannot complete, report the transport unsafe rather than reusing it.

The physical TV API level remains an unverified expectation, and device evidence remains pending. The recorded branch-check still fails against the pre-rewrite base; the owner-closure exception permits this design review to proceed on the current branch, but the implementation report must preserve the failed check rather than call it PASS. No commit, push, installation or lab acceptance is implied.

**Verdict: READY.**