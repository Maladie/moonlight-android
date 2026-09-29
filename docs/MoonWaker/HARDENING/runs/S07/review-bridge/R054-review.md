# R054 independent post-edit review — S07 StreamRuntime

**Verdict: CHANGES_REQUIRED.** This is source review only; I ran no build, unit suite, or lab test.

## Identity and evidence

Branch codex/session-hardening, HEAD 3913979f9e2b647f5d9bfbebdd86a73cbd39ae43, uncommitted S07 candidate. The submitted tracked diff hash 7980ea946f6f7b2f812e444de84be4445fc39badb5f006f7eb8b0c863b5214ca and the current StreamRuntime.java / AndroidStreamRuntime.java hashes match the submitted manifest. The submodule pointer is declared unrelated. I inspected the production paths and changed/new tests listed in R054. The test XML has 1164 tests, 0 failures, 0 errors; android-full-4.log and android-build-7.log end in BUILD SUCCESSFUL; both APK hashes match apk-sha256.txt. android-build-5.log is an earlier NDK-path failure, not the final build. Device evidence is pending. The implementation report preserves the failed branch-check exception.

## Findings

### [P1] Accepted PREPARING warm-up is treated as unaccepted after presentation recreation

Location: app/src/main/java/com/limelight/Game.java:253, 861-862, 976-981, 1729-1733, 3279-3289, 6549-6557; app/src/main/java/com/limelight/stream/RetainedStreamSessionCoordinator.java:303-304, 381-395; app/src/main/java/com/limelight/console/StreamHomeActivity.java:178-184.

Evidence/impact: The accepted Home frame is recorded in the coordinator, but autoWarmUpHomeFrameAccepted starts false in a recreated Game. beginPreparing returns true for the same runtime and attempt without transferring that field. Game launches another preparing Home; its frame submission is rejected because preparingHomeFrameClaimed is already true, so that Home finishes. If the recreated Game is destroyed before connectionStarted, cancelUnstartedAutoWarmUp can cancel the accepted attempt. This can lose the protected PREPARING stream or stall provider start. The current runtime test covers unaccepted preparing loss only.

Correction: Restore accepted-frame/attempt state from the exact coordinator snapshot on reattach, avoid a second preparing Home submission, and resume the new presentation/coordinator without another native or provider start.

Verification: Test accepted PREPARING -> old detach -> new attach while still CONNECTING, with second-destroy and connection-start branches. Assert one Home acceptance, one provider start, no erroneous cancel, correct retained state. Then perform the planned T26 preparing-recreation device check.

### [P1] Late stop observer receives false STOPPED after a failed native stop

Location: app/src/main/java/com/limelight/stream/StreamRuntime.java:233-240, 257-289.

Evidence/impact: onStopped(FAILED) marks state STOPPED and gives existing waiters FAILED. A later stop(completion) takes the STOPPED branch and hardcodes stopCoordinator.fence(STOPPED). Without a successor generation, it reports STOPPED although the only native stop failed. This violates the shared-result contract and can falsely report local transport safety to a late disconnect or whole-session termination.

Correction: Cache and replay the actual terminal stop result for late observers, with the same generation fence. Never infer a successful native stop from the terminal state enum.

Verification: Add a failing-stop test with callbacks registered before and after completion, with and without a successor generation; assert an unsafe result for both and no successor native start.

### [P1] Holderless provider cleanup cannot stop even an observable exact process

Location: app/src/main/java/com/limelight/console/ConsoleStreamTransitionCoordinator.java:272-295, 621-648, 1044-1070, 1087-1108; approved docs/MoonWaker/HARDENING/runs/S07/design.md:208-224.

Evidence/impact: When the runtime ends while a provider start is holderless and STARTING, completion schedules compensation. compensateProviderStart first claims an already-bound token and immediately reports unconfirmed if empty; it never takes the authenticated snapshot that could bind the exact process. Observation was stopped at detach, so this path normally lacks a token. The declared stricter rule avoids stopping an unknown process but removes the approved observable-success branch: even a verifiable exact launched process can remain running with an owner entry. The new test covers only the unobservable case.

Correction: Implement the approved bounded authenticated snapshot and exact process-token binding for this attempt before claiming cleanup; retain unconfirmed for ambiguity or absence of proof.

Verification: Test holderless STARTING -> successful start -> authenticated exact process observable, assert one exact stop and owner retirement. Preserve an unobservable/ambiguous case that never stops an unknown process and reports unconfirmed.

### [P2] Recreation leaves the old provider observation executor running

Location: app/src/main/java/com/limelight/console/ConsoleStreamTransitionCoordinator.java:129-133, 194-200, 236-248, 433-440; app/src/main/java/com/limelight/Game.java:1742-1752.

Evidence/impact: Production coordinators allocate a two-thread fixed pool. detachForRecreation calls stop but never shuts the pool down, then Game drops the coordinator reference. close is the only shutdown path. Once observation has started, the pool's worker threads remain alive after that task ends; repeated recreation accumulates threads.

Correction: Shut down the old observation executor on detach, retaining only the owner/custodian state required for in-flight start completion or exact cleanup. The static provider-action executor can serve deferred compensation.

Verification: Test start observation -> detach -> adopt: old executor terminates, no late old callbacks, and the single in-flight provider-start result still reaches the new holder.

## Scope and owner question

The one-transport registry, input gate ordering, headless termination and generation-tagged service commands are plausible in the reviewed source. I found no separate proven bitrate-restart or actor-handoff regression; the above gaps and planned device checks still matter. The owner-observed physical-TV "previous stream still closing" predates this uninstalled S07 candidate, so these sources cannot attribute it to the new stream_runtime_busy toast. That toast is reached only when Game tries to create a runtime while another live, unstopped runtime is registered. For S07-END-GAME-RELAUNCH, correlate runtime.busy, runtime.stop_requested and runtime.stopped with session IDs and generations to distinguish the two issues.

**Verdict: CHANGES_REQUIRED.** Address all findings and submit a new candidate/diff and refreshed local evidence before the owned-lab gate.