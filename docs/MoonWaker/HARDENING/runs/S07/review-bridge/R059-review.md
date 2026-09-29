# R059 independent review — S07 owned-lab evidence

**Verdict: ACCEPTABLE_AS_LAB_EVIDENCE.** This accepts the recorded diagnostic observations at their stated scope. It is not S07 device completion, physical-TV validation, or formal S05 acceptance. I ran no build, unit suite, or lab test.

## Identity and checks

The product checkout and clean LF clone are both at `022b3c436cd94c3f178d6b15783bd3364fa67e3c`, the locally committed R058-approved source. The shared checkout has an unrelated modified submodule pointer and untracked files; I made no source changes. The pinned candidate manifest hashes to `f8c4a24df5caf31d43b309441739f7b3e2fa58e8f58bc924c106f958835e4e9d`. Every 1a–8b run contains a byte-identical candidate manifest and reports `environment_restored=true`; 4a's before/after restore artifacts both report PASS with VM/AVD canaries, absent temporary secret, and running Gateway.

The raw `s07-diag-4a/result.json` reports diagnostic PASS 23/23, `first_failure=null`, `formal_acceptance_eligible=false`, and no evidence errors. Check 22 is supported by the Android event sequence `activity.destroyed` → `runtime.survived_presentation_loss` → `runtime.reattached` (mono 969418–969603), the same PID 6421, connection incarnation and transport generation 7, and renewed decoded frames/input. The changed JDWP presentation object is corroborating evidence, not the sole proof of recreation. Check 23 records a controlled `force-stop`: fresh PID 11711 has no loaded runtime or transport, `app.exit_reason=USER_REQUESTED`, and reconnection has a new incarnation and generation 1. This establishes the controlled-death behavior, not behavior after every possible OS kill.

The `s07-diag-8a/result.json` retains its genuine first failure at `s18-resume-ready / s18-resume_frame_or_input_failed` after 17 passes. Its before/after/final screenshots remain on Probe B frame 235 while the Android overlay clock changes; host probe evidence advances to tick 320. That supports a frozen decoded picture symptom. It does not identify the stalled component or exclude an S07 regression. The earlier S06 `frame_or_input_failed` reports show that this symptom class existed before S07, but do not prove the same cause. The 8b run passes 01–23 and then fails at 24; it does not erase 8a.

For check 24, 8b's `s24-diagnostic.json` shows PREPARING at mono 30339, Activity paused at 30624, HOME_LIVE at 34121, and no `activity.destroyed` or `runtime.reattached` before the recreation timeout. The 7a and 8b artifacts support **BLOCKED with the current hidden-Activity configuration-change trigger on this AVD**. They do not establish that a different instrumented trigger is impossible. The foreground check 22 uses an undeclared `fontWeightAdjustment` change; the deviation from Developer-options DKA is disclosed and still exercises the observed destroy/reattach path. It does not cover the PREPARING variant.

## Disposition and scope

The evidence is usable for the 4a diagnostic PASS and the separately identified 8a failure. Keep `CODE_VERIFIED_DEVICE_PENDING`: T07 host picker, child expiry, T08 headless switch, PREPARING recreation/converted game, and physical TV remain unverified; T25 is code-only. None of these diagnostic runs counts toward three formal S05 passes. The extra AVD computer entry is an observation; the artifacts here do not establish its origin.

Two wording cautions for any downstream summary: replace “no S07 runtime path involved” with “no lifecycle reattach event observed during 8a”; the S07 runtime still carries the active stream. Describe 24 as blocked by the **current lab trigger**, rather than proving no lab-reachable trigger exists. Retain the exact child first failures and the controlled `USER_REQUESTED` exit reason. These scope clarifications need no new lab run to preserve the evidence verdict.

**Verdict: ACCEPTABLE_AS_LAB_EVIDENCE.** No lab test was run during this review.
