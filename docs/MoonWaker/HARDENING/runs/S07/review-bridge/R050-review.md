# R050 independent pre-edit review — S07 design v2

**Verdict: CHANGES_REQUIRED.** Version 2 resolves much of R049's ownership map, including mandatory runtime privacy, native-stop fallback on FGS failure, and explicit stop-entrypoint inventory. Three reachable races remain in the proposed transfer/attach path. No S07 product file has been edited.

I read R050, R049 and its review, both versions of design.md and checkpoint-plan.json, and the unchanged characterization. HEAD is 3913979f9e2b647f5d9bfbebdd86a73cbd39ae43 on codex/session-hardening. The five submitted S07 file hashes match R050: design v2 3b675e5a...d1b9, plan v2 0023ae49...2ce4, design v1 7dd2ac52...189d, plan v1 48d31ac6...43d9, characterization 21638d6e...0fc2. The S07 directory is untracked; the only tracked worktree difference remains the pre-existing moonlight-common-c pointer. I compared the actual v1/v2 document diffs and spot-checked the production call sites. I ran no lab test, build, unit suite or source mutation.

## Findings

### [P1] Provider start can complete after the owner is detached, leaving a started game unowned

Design v2 section 7 assumes detachForRecreation can retain and mark the process-wide PROVIDER_OWNERS entry. That entry does not necessarily exist yet. ConsoleStreamTransitionCoordinator.java:441-470 invokes gateway.startGame at line 463 and creates the owner entry only after it returns, via acceptProviderStart at lines 914-923. If Game is destroyed while startGame is in flight, detach advances the old coordinator epoch; when startGame later succeeds, acceptProviderStart rejects the stale epoch. The host game has started but no owner record is available for adoptRecreatedProviderOwnership, and the new Game can neither adopt nor reliably stop that exact process. Define a handoff or completion barrier for in-flight provider start, including how a late success is bound to the new owner or exactly compensated. Test with startGame held on a latch across detach/new attach, then complete it successfully and unsuccessfully; assert one provider operation, exact process identity, and no orphan.

### [P1] Detach leaves the input token valid while sending release packets

Design v2 section 3 says neutral controller/key/mouse packets, mute and decoder detach happen before the attachment token is invalidated. A concurrent input sender can still validate that token and send a button-down after the neutral packet, leaving input held on a live stream. Correct the ordering: atomically close the presentation send gate and fence/drain in-flight sends, then emit neutral packets through a runtime-internal cleanup path; stale presentation sends remain rejected. Add a controlled interleaving test where a held-button sender races with detach and verify the last packet is neutral, including keyboard/mouse. This also protects the privacy gate while the new opaque Game attaches.

### [P1] Pending-switch supersession and eventual completion conflict with the coordinator

Design v2 section 3 promises one pending slot, superseded by a newer switch, and exactly-once terminal completion without a timer. In current RetainedStreamSessionCoordinator.java:739-747, canSwitchGame requires switchOwner == null; switchGame sets switchOwner before calling the runtime (lines 809-824). A second switch cannot reach the slot to supersede it. If PresentationLauncher issues startActivity but Game never attaches, there is no launch acknowledgment or terminal event. ConsoleActivity.java:14747-14770 stops waiting after 60 seconds, but does not complete the coordinator callback or clear switchOwner; later switches remain blocked. Either make Console start/attach Game before submitting the switch (simpler) or define coordinator-level replacement and a bounded unclaimed-launch cancellation that clears both slot and switchOwner exactly once. Test second request during pending attach and launch accepted-but-no-attach, not just synchronous launch refusal.

### [P2] The checkpoint plan still requires an impossible R049 READY

checkpoint-plan.json required_checks still says "independent pre-edit design review R049 READY before product edits", while R049's published verdict is CHANGES_REQUIRED and plan.design_review acknowledges it. Replace that check with R050 READY (or the eventual successful review), preserving R049's actual result in the history. Otherwise the implementation gate cannot be satisfied honestly even after design approval.

## Other review answers and limits

- **Pending slot versus headless switch:** An opaque attaching Game is a valid route; a headless provider switch is unnecessary. The current slot/coordinator protocol must be made complete first. Launching from the visible Console before switch may remove the slot and its second state machine.
- **ForegroundState:** Counting started Activities through MoonWakerApplication's registered lifecycle callbacks is a reasonable request gate when paired with the specified start exception handling and native-stop fallback. Its onActivityStarted/onActivityStopped hooks are currently empty (MoonWakerApplication.java:157-161), so implementation must supply a thread-safe current count/state. Treat an in-process service acknowledgment as current only for the exact generation; if an old service start reaches startForeground after the runtime ended, the service must also stop itself rather than leave an orphan notification.
- **Coverage:** The stop-entrypoint inventory matches the Game stopConnection call sites I found. The revised test list covers the main R049 cases, subject to the three race tests above.
- **TV evidence:** The plan labels API 36 as owner-declared, not device-read. The quoted statement says it "should" match the emulator; keep it as an unverified expectation for design assumptions. APK version 12.2.23 and AVD API 36 do not establish physical-TV OS version. Final device evidence remains pending.
- **Branch:** The failed branch-check and owner-closure exception are recorded rather than presented as a passing check; preserve that record in the implementation report.

Revise the design and checkpoint plan for the three races and the review gate, then submit another pre-edit review. **Verdict: CHANGES_REQUIRED.**