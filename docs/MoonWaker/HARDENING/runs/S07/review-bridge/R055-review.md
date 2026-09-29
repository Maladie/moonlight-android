# R055 independent review — S07 follow-up

**Verdict: CHANGES_REQUIRED.** Source and submitted-evidence review only. I did not run a build, unit suite, or lab test.

## Candidate and evidence

Branch codex/session-hardening at HEAD 3913979f, with the uncommitted S07 candidate. The submitted tracked diff has SHA-256 50fee1028e7f0cec3837aeed5f891924fb2c2754bf81f1b7544702b23276df66; the submitted untracked source and both APK hashes match the current files. The final build log reports success for testNonRootDebugUnitTest, assembleNonRootDebug and assembleNonRootRelease. The existing XML results contain 1169 tests, 0 failures and 0 errors. This is local evidence; T26 and other device checks remain pending.

The four specific R054 changes are present: Game now adopts an accepted PREPARING Home frame for an unchanged attempt; late StreamRuntime stop observers receive the cached terminal result; holderless provider compensation can bind an authenticated exact process before stopping it; and recreation shuts down the old observation executor. The added tests cover the relevant runtime/coordinator behaviors. The finding below concerns a transition-ID change inside the same PREPARING attempt, which those tests do not exercise.

## Finding

### [P1] A preparing switch invalidates the accepted Home frame during Activity recreation

Locations: app/src/main/java/com/limelight/stream/RetainedStreamSessionCoordinator.java:381-413, 851-869; app/src/main/java/com/limelight/Game.java:976-985, 3882-3919, 6028-6053, 7101-7113; app/src/main/java/com/limelight/console/StreamHomeActivity.java:90-109, 178-185.

Evidence: preparingHomeFrameSubmitted stores acceptedHomeTransitionId and leaves preparingHomeFrameClaimed true. updatePreparingSwitch changes transitionId to the replacement transition but does not move the accepted-frame ID. The immediate adoptPreparingObservedGame path can perform that update for an already observed game while the transport is still PREPARING. If Game is then recreated, resumeAcceptedWarmUp evaluates the *new* snapshot transition ID, so isPreparingHomeFrameAccepted returns false even though this attempt already submitted its Home frame. beginPreparing can return true for the same runtime and updated transition, but it does not restore Game's autoWarmUpHomeFrameAccepted flag or overlay authorization. A new Home cannot submit another accepted frame because preparingHomeFrameClaimed remains true; a Home that recognizes the completed relay may skip submission altogether. If this recreated Game is destroyed before connection completion, cancelUnstartedAutoWarmUp can cancel the accepted attempt. The result can be a lost or stalled warm-up during a legitimate preparing switch and Activity recreation.

Correction: Carry the accepted Home proof forward atomically with an exact, same-attempt preparing-switch transition update, or have reattachment recognize the original accepted transition through an equally strict ownership check. Restore the new presentation's accepted flag and authorization before any path can cancel or resubmit. Do not clear the acceptance merely because the selected game changes.

Verification: Add a coordinator test for accepted Home -> updatePreparingSwitch -> accepted proof on the replacement transition, with a stale owner/attempt rejection. Exercise the actual Game/StreamHomeActivity reattachment after adoptPreparingObservedGame while the runtime is CONNECTING, including a second recreation/destruction: one Home submission and one native start, no erroneous cancel, and eventual completion of the exact PREPARING attempt. Include the planned T26 device check. Also check a converted GAME transition across recreation before completion: onCreate currently coerces any intent retaining EXTRA_AUTO_WARM_UP_ATTEMPT to GAME_CONNECTION (Game.java:477-487), while applyRetainedTransition leaves that extra until completion (Game.java:3923-3941, 7116-7127); the same test should prove that a selected game retains its intended provider-start semantics.

## Disposition

The R054 late-stop, holderless-cleanup and observation-pool findings are resolved in this candidate. The ordinary unchanged-transition PREPARING reattach path is addressed, but the preparing-switch variant above still blocks readiness. Device success is not inferred from the JVM suite.

**Verdict: CHANGES_REQUIRED.** Submit the correction and refreshed local evidence for re-review; no lab test was run during this review.