# R059: S07 owned-lab device evidence on s07-candidate-1 — review request

Author: Claude. Date: 2026-09-29. Do not run lab tests. Product source unchanged since R058 READY
(committed locally as `022b3c43` on `codex/session-hardening`; nothing pushed).

## Evidence
- Summary: `docs\MoonWaker\HARDENING\runs\S07\device-evidence.json` (uncommitted, written after the runs).
- Candidate: `E:\Documents\ChatGPT\MoonWaker\outputs\s07-candidate-1\candidate.json` (sha256 `f8c4a24d…4e9d`), built by `work\build_s07_candidate.ps1` from the clean LF clone `D:\Maladie\moonlight-android\work\s07-int` at `022b3c43`, submodules at the recorded `1cc32cf6` taken locally from `work\s06-int` (no network). Host `0a36490d…5c59`, debug APK `a07f5856…22ac`.
- Harness (versioned copies, lab originals unchanged): `work\s07_transport_observer_2.py`, `work\run_s07_lifecycle_{1..8}.py`, `work\run_s07_diag_{1..8}.py`; hashes of the final ones in device-evidence.json. Base = unchanged `run_s06_lifecycle_141.py`.
- Runs: `E:\Documents\ChatGPT\MoonWaker\outputs\s07-diag-{1a,2a,3a,4a,5a,6a,7a,8a,8b}` (result.json, timeline, per-check artifacts, s2x diagnostics); every run restored the environment.

## Results
- `s07-diag-4a` **PASS 23/23** (S06 01-20 + S07 21-23). Complete 01-23 also in 5a, 6a, 7a, 8b (those failed only at the new check 24).
- 21 End game → relaunch reuses the transport; 22 Activity recreation keeps the runtime (same `connection_incarnation_id` and `transport_generation`, new presentation object, frames+input); 23 process death: fresh process has no transport, exit reason `USER_REQUESTED`, reconnect gets a new incarnation.
- Harness-caused failures (1a–3a, 5a–7a) are listed with causes; all are harness/trigger issues, not product results.
- 8a: **unclassified** failure at 18 (child dashboard resume, decoded picture frozen at probe frame 235 while host advanced to 320 and the Android overlay updated). No S07 runtime path is involved there (Game stays attached under the translucent Home). Not reproduced in 8b. Same class as S06 diag-141f/145a/145b/164d on pre-S07 code. Recorded, not waived.
- 24 (PREPARING recreation) **BLOCKED** in the lab: configuration changes are deferred for the paused hidden warm-up Game until resume, PREPARING ends in ~3.8 s; DKA is not settable by adb and, with DKA, the app is backgrounded before the FGS exists (design: honest stop, not survival). JVM coverage cited.
- Deviation: T26 recreation forced by an undeclared configuration change (fontWeightAdjustment) instead of Developer-options DKA.
- NOT_RUN: T07 host picker, child expiry, T08 headless switch (unreachable through the lab UI), physical TV (API 32).
- Observation: an extra computer entry `6DEC648A-…` appeared in the AVD host DB during 5a/6a.

## Questions
1. Is the evidence faithful to the artifacts (please spot-check 4a result.json, 22/23 artifacts, 8a frames)?
2. Is the recreation-trigger deviation and the BLOCKED classification of the PREPARING variant acceptable, or do you see a lab-reachable trigger?
3. Anything that should move S07 back from CODE_VERIFIED_DEVICE_PENDING, or any product defect indicated by 8a?

Verdict requested on the device evidence: ACCEPTABLE_AS_LAB_EVIDENCE / CHANGES_REQUIRED / INSUFFICIENT_EVIDENCE.
