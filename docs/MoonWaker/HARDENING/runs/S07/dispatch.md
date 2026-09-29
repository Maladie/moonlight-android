# MoonWaker bounded task dispatch

```json
{
  "task_id": "S07",
  "role": "worker",
  "review_stage": null,
  "mode": "VERIFY_LIVE_REPO_BEFORE_EXECUTION",
  "repository": "Maladie/moonlight-android",
  "expected_branch": "codex/session-hardening",
  "branch_policy": {
    "remote": "origin",
    "base_branch": "codex/multi-profile-remote-sign-in",
    "work_branch": "codex/session-hardening",
    "fresh_start": "Fetch the named base branch at execution time. Use its fetched tip unless the local named base is a strict descendant; in that case preserve and use the local tip. Divergence blocks setup.",
    "pin_once": true,
    "create_before": "S00",
    "local_branch_creation_authorized": true,
    "unrelated_dirty_work": "BLOCK_SETUP_WITHOUT_DISCARDING",
    "existing_target": "RESUME_ONLY_WITH_MATCHING_RECORDED_BINDING_OTHERWISE_BLOCK",
    "resume": "Keep the recorded base and existing work branch; verify ancestry and source evidence. Do not refork or automatically merge a moving base."
  },
  "workspace": {
    "status": "PREPARED",
    "remote": "origin",
    "base_branch": "codex/multi-profile-remote-sign-in",
    "work_branch": "codex/session-hardening",
    "base_head": "00366fd92a1f283bf3aa04171ed3455ce62897ac",
    "fetched_remote_head": "00366fd92a1f283bf3aa04171ed3455ce62897ac",
    "branch_creation_head": "00366fd92a1f283bf3aa04171ed3455ce62897ac",
    "branch_setup_report": "runs/branch-setup.json"
  },
  "audit_reference": "00366fd92a1f283bf3aa04171ed3455ce62897ac",
  "model_hint": "Astra",
  "expected_input_fingerprint": null,
  "resource_locks": [
    "android-game",
    "android-console",
    "retained-runtime",
    "android-presentation"
  ],
  "source_hints": [
    "app/src/main/java/com/limelight/Game.java",
    "app/src/main/java/com/limelight/stream/RetainedStreamSessionCoordinator.java",
    "app/src/main/java/com/limelight/stream/BackgroundStreamService.java",
    "app/src/main/java/com/limelight/stream/BackgroundStreamPreferences.java",
    "app/src/main/java/com/limelight/console/StreamHomeActivity.java",
    "app/src/main/AndroidManifest.xml"
  ],
  "write_scope_note": "Named responsibility, its state/effects, all direct production callers, required test/build/packaging changes and assigned run evidence. No unrelated formatting, dependency upgrades or replacement framework.",
  "pre_edit_contract_review": true,
  "blocked_reasons": [],
  "scenario_assertion_scope": "Complete independently usable outcome in this task. Unfixed pre-existing findings assigned later may remain listed, but no behavior introduced or changed here may depend on a future task to work.",
  "independently_usable_outcome": "Presentation/Activity replacement does not own or accidentally stop the retained transport; explicit quit still works.",
  "decomposition_requirement": "Move the physical runtime and its state behind RetainedStreamSessionCoordinator; Game becomes a lifecycle/presentation adapter for these responsibilities. No duplicate socket owner or long-lived Activity capture.",
  "test_risk": "DEVICE_CRITICAL",
  "mandatory_real_device_topics": [
    "Home/host picker/Activity recreation continuity, decoder/audio/input restoration, process-death recovery"
  ],
  "dependency_handoffs": {
    "S06": {
      "status": "CLOSED_BY_OWNER",
      "assigned_run": "S06-worker-20260914",
      "input_fingerprint": "sha256:5e78588cdd4512554e4c2ef1d047b7bb0beba87e2809759231ad86b2df38145e",
      "output_fingerprint": "sha256:60915ff34598b96acbb7e9d85377baec3cff2a887aaa1ec3191606d874afdb50",
      "implementation_report": "runs/S06/implementation.json",
      "review_report": "runs/S06/review-sol-no-compat-rereview.json",
      "design_review_report": "runs/S06/pre-edit-review.json",
      "blockers": [
        "Closed by the owner (amendment S03-S06-20260929-OWNER-CLOSURE); no formal CHECKPOINT_ACCEPTED evidence claimed."
      ],
      "checkpoint_plan": "runs/S06/checkpoint-plan.json",
      "closure_decision": "S03-S06-20260929-OWNER-CLOSURE"
    }
  },
  "this_task_state": {
    "status": "NOT_STARTED",
    "assigned_run": null,
    "input_fingerprint": null,
    "output_fingerprint": null,
    "implementation_report": null,
    "review_report": null,
    "design_review_report": null,
    "blockers": [],
    "checkpoint_plan": null
  },
  "contract_amendments": [
    {
      "id": "D01-20260909-30MIN",
      "decision": "D01",
      "status": "USER_AUTHORIZED",
      "value": "For an absent stored preference, default background stream retention to 30 minutes in both debug and release. Preserve every explicit stored value, including zero/off. Do not apply this timeout to app Home or host-picker retention.",
      "source": "User message: Ustawmy domyślnie 30min"
    },
    {
      "id": "S04-20260910-DEPENDENCY-OVERRIDE",
      "decision": "PROCEED_WITH_S04_WHILE_S03_DEVICE_PENDING",
      "status": "USER_AUTHORIZED",
      "value": "Proceed with S04 implementation on the exact code-reviewed S03 candidate while S03 device scenarios are tested in parallel. This does not accept S03, waive S03 or S04 device evidence, or authorize S05. Any S03 failure overlapping S04 must be reconciled before review or acceptance.",
      "source": "User message: Rozumiem, ale wyrażam na to bezpośrednią zgodę. Przejdź do S4"
    },
    {
      "id": "S05-20260910-DEPENDENCY-OVERRIDE",
      "decision": "BEGIN_S05_PRE_EDIT_REVIEW_WITH_S04_NATIVE_TRACE_PENDING",
      "status": "USER_AUTHORIZED",
      "value": "Commit the complete S04 code-verified candidate and begin S05 pre-edit review on that exact commit. The user's functional S04 PASS is recorded, but this does not fabricate candidate-bound native ordering evidence or accept S04. No S05 product mutation may begin before the input-bound checkpoint plan incorporates the independent capability review.",
      "source": "User message: Jest sukces dla S04. Wszystko przebiegło zgodnie z założeniami. Wcommituj i rozpocznij S05."
    },
    {
      "id": "S05-A01-GATEWAY-CONTROL-PLANE",
      "decision": "GATEWAY_OWNS_ALL_MOONWAKER_CONTROL",
      "status": "USER_AUTHORIZED",
      "value": "All MoonWaker launch/resume/reconnect/replacement/warm-up/full-quit operations require one exclusive Gateway generation and actor-lease permit. Android's existing riKey/sessionUrl0 Sunshine bootstrap may execute directly only inside that exact permit; media remains direct. Concurrent legacy clients are unsupported; unmanaged, uncertain or uncorrelated sessions fail closed and are never globally cleaned up automatically.",
      "source": "User messages: Nie musimy gwarantować starszych klientów... Pasuje, rozpocznij"
    },
    {
      "id": "S06-20260914-DEPENDENCY-OVERRIDE",
      "decision": "PROCEED_WITH_FULL_S06_WHILE_S03_S05_FORMALLY_PENDING",
      "status": "USER_AUTHORIZED",
      "value": "Proceed with full S06 implementation on the exact cumulative input fingerprint sha256:5e78588cdd4512554e4c2ef1d047b7bb0beba87e2809759231ad86b2df38145e after the independent pre-edit design review. This does not accept S03, S04 or S05, waive their missing formal evidence, authorize S06 checkpoint acceptance without its own evidence, or authorize commit, push, APK installation, Gateway update or service restart.",
      "source": "User message: Nie preedit. Wykonaj w pełni. Znoszę blokadę"
    },
    {
      "id": "S06-20260929-CLOSURE",
      "decision": "S06_CLOSED_ON_FORMAL137_GATE",
      "status": "USER_AUTHORIZED",
      "value": "S06 is closed on formal137 PASS 3/3 and R048 READY for candidate164 in the owned lab. Recorded limits: repeated Gateway lock-held-slow in _child_time_stop_stream, Playnite setup flake, memory pressure. Does not accept S03-S05, promote a baseline, or authorize push, publication or real-device deployment.",
      "source": "User message: Tak, zamykamy S06"
    },
    {
      "id": "S03-S06-20260929-OWNER-CLOSURE",
      "decision": "CLOSE_S03_S06_WITHOUT_FORMAL_ACCEPTANCE",
      "status": "USER_AUTHORIZED",
      "value": "The owner closes S03, S04, S05 and S06 as CLOSED_BY_OWNER instead of pursuing formal CHECKPOINT_ACCEPTED on fingerprints of code that has since moved on. The recorded evidence stays as it is (S03 operator confirmation; S04 final-acceptance-20260914; S05 acceptance.json; S06 formal137 PASS 3/3 with review R048 READY, runs/S06/formal137-closure.json). It is not re-labelled as formal acceptance. S07 onward proceed on the current branch; S10 still requires the full release evidence on the exact final artifacts. Does not authorize push, publication, baseline promotion or real-device deployment.",
      "source": "User message 2026-09-29: \"Wybieram B. Zamykamy i zapominamy o tym\""
    }
  ]
}
```

# Branch setup — mandatory before S00

## Fixed identities
Repository: Maladie/moonlight-android
Remote: origin (verify that it is this repository, not upstream moonlight-stream)
Base branch: codex/multi-profile-remote-sign-in
New work branch: codex/session-hardening

The coordinator may perform the read-only Git inspections, fetch the named base, and create/switch to this local work branch. This permission does not authorize a commit, push, PR, reset, clean, forced checkout, stash, history rewrite or live-device action. Do not create a branch on GitHub as part of package installation. Never edit product/test/build code on the base branch.

## Fresh start
Perform these steps before dispatching S00. Read root and applicable nested repository instructions first.

1. Verify the repository root and remote identity. Inspect `git status --short`, the current branch/HEAD, and whether the target branch already exists or is checked out in another worktree. The supplied packet under docs/MoonWaker/HARDENING/ may be untracked or changed. Record and preserve it. Unrelated staged/unstaged/untracked source changes, unresolved conflicts, or an in-progress merge/rebase block setup: report the exact conflict without discarding, stashing, committing or silently carrying it. Ignored build artifacts do not require cleanup. Do not perform automatic submodule resets.
2. Fetch only the named base, then immediately capture its immutable commit ID. Use the actual result, not a remembered audit SHA or a cached remote-tracking tip. The common commands are:
   ```text
   git fetch --no-tags origin refs/heads/codex/multi-profile-remote-sign-in
   git rev-parse --verify FETCH_HEAD^{commit}
   git show-ref --verify refs/heads/codex/multi-profile-remote-sign-in
   ```
   Failure to fetch or a missing remote base blocks a fresh start; do not silently fall back to stale local data. Record the observation time and selected SHA before another fetch can replace FETCH_HEAD. Fetching does not authorize changing the local base ref.
3. Compare the local named base tip, when present, with the freshly fetched remote tip, using commit ancestry (not dates). Local missing/equal/behind: select the fetched tip. Local strictly ahead and containing the fetched tip: select the local tip and explicitly record the additional local commits being preserved. Divergent tips: block and report; do not auto-merge, rebase, select an arbitrary branch or discard either side. Never use `main`, the most recently edited arbitrary branch, or the old audit SHA as a substitute.
4. Freeze the chosen SHA as `base_head`. If `codex/session-hardening` already exists without a matching prepared workspace record, stop rather than overwriting it or silently starting from it. Resolve the collision explicitly. For a new branch run:
   ```text
   git switch --no-track -c codex/session-hardening <resolved-base-head>
   ```
   Replace the placeholder with the captured full commit ID. Do not reset an existing branch. If switching would overwrite packet or user files, stop and preserve them; do not force checkout. All further product/test/build changes belong on this work branch.
5. Read the checked-out repository instructions again in case the fetched base changed them. Verify current branch equals `codex/session-hardening` and initial HEAD equals the frozen base SHA. Save `runs/branch-setup.json` from `templates/BRANCH_SETUP.json`, including inspected local/remote tips, selection reason and actual command results. Use local_vs_remote = MISSING, EQUAL, BEHIND or AHEAD; divergence is never PREPARED. Set report status to PREPARED only after these checks. Set STATE.workspace status to PREPARED and bind its base_head, fetched_remote_head, branch_creation_head and branch_setup_report; set STATE.baseline_head and MANIFEST.execution_head to the initial base SHA. No task becomes accepted merely because a branch exists.
6. Execute the read-only guard:
   ```text
   python docs/MoonWaker/HARDENING/tools/packet.py branch-check --repo .
   ```
   Only after success dispatch S00. Its baseline, builds and tests are tied to this actual worktree, including uncommitted input where applicable. Branch setup is not another implementation task and does not change the S00–S10 order.

## Resume and subsequent tasks
Read STATE.workspace and runs/branch-setup.json. Do not create a second work branch, refetch/rebase to a moving base, or restart S00 because the agent context changed. Run branch-check and verify the last accepted source fingerprint, owned uncommitted work and pending evidence before each mutating task. Unexpected branch/ancestry changes block mutations; do not fix them with a destructive Git command.

A later separately authorized commit may advance HEAD on the work branch; its recorded base must remain an ancestor, and candidate fingerprints/reviews must be refreshed. The initial base branch is not used for implementation. Any isolated candidate worktree must be explicitly coordinated, preserve the entire accepted source including intentional uncommitted input, and not become an alternative unreviewed baseline. Default: one work branch and one mutating worker.

## Guard scope
`branch-check` performs local read-only checks against recorded setup evidence and current Git branch/ancestry. It does not fetch, create branches, mutate STATE, prove the truth of a hand-written log or independently establish remote freshness. The coordinator must perform and record the actual fetch and branch creation above; the reviewer checks their evidence.

# Execution rules — normative

Read applicable root/nested AGENTS.md and actual architecture/workflow files. Resolve real paths: at the audit SHA AGENTS mentions docs/MoonWaker paths but workflow/architecture were at repository root. Preserve user work and repository security/privacy/ownership boundaries. User's seven flows and the checkpoint/decomposition requirements are the product contract; a historical description of a bug is not a requirement to preserve it.

The H01–H11 audit is inherited static evidence at 00366fd92a1f283bf3aa04171ed3455ce62897ac, not runtime proof. Revalidate symbols/callers at actual HEAD. Do not reset the branch to match the audit. The reference SHA identifies audit provenance, not the required execution base. BRANCHING.md governs current base selection and the new work branch.

CHECKPOINTS.md and DECOMPOSITION.md are mandatory. Each completed implementation slice is integrated, independently usable and no worse on the protected baseline. Each relevant monolith responsibility is moved with state, effects and callers, tested and removed from its old owner. No pending consumer, dormant feature, broad cleanup or future task may be required to make the new behavior functional. Layer-only acceptance is forbidden.

Retain canonical owners: LaunchTransitionController for launch/privacy gates; RetainedStreamSessionCoordinator as retained facade; SessionOrchestrator for intent; OperationJournal and existing host child state for durable truth. Physical runtime may move behind the facade, not become a competing truth. Resume records are not live sockets. Do not rewrite JNI/upstream Moonlight core without a narrowly reviewed necessary seam.

One candidate slice per fresh worker context; internal passes and corrections stay within it. One mutating worker by default. Independent reviewer is a separate actual context inspecting the real diff, not the author's second role. Use actual delegation tools and model labels when exposed; never fabricate model selection or independent review. Sol/Astra are routing hints, not API IDs or special proprietary prompt syntax.

Read code and direct call sites before editing. Capture input fingerprint, planned checks, lockstep artifact pairing, extraction, source scope and test authorization. Widen scope only for the bounded whole responsibility and integration it needs, with recorded review. Characterize before extraction; test adverse orderings with controlled clocks/latches/queues, not sleeps. Tests must invoke production wiring and assert forbidden effects. Missing tools/devices and zero-test discovery are not PASS.

Preserve HTTPS, pinning, pairing, authentication, profile headers, request IDs, bounded responses and endpoint permissions. Bridges remain loopback-only. Child execution in a parent's Windows profile never transfers actor privileges. Do not add production debug bypasses, trust-all TLS, generic Bridge proxies or process-name-wide kills. Secrets/PINs/tokens must not appear in artifacts, URLs or command arguments.

The coordinator is explicitly authorized to fetch the named base and create/switch to codex/session-hardening under BRANCHING.md before S00. Product/test/build edits on the base branch are forbidden. Run the read-only branch-check before each mutating task. No commit, push, PR, deployment, installation, account/credential changes, sleep, forced sign-in or destructive live host actions are authorized by this packet alone. Read-only inspection and local safe builds/tests are allowed. Build and execute a local-first verification pipeline; GitHub Actions is not required. Device installation/testing requires an authorized disposable/lab environment. Never run public untrusted PR code on the user's Windows/TV lab.

Only CHECKPOINT_ACCEPTED / justified ACCEPTED_NO_CHANGE satisfy task dependencies, or CLOSED_BY_OWNER: a closure the user explicitly authorized in a USER_AUTHORIZED contract amendment (named by closure_decision). CLOSED_BY_OWNER claims no evidence and does not waive S10. CODE_VERIFIED_DEVICE_PENDING is useful progress, not a safe-stop claim or an accepted dependency. The coordinator owns STATE.json and input-bound gate plans. Source fingerprint, executed check artifacts and separate review determine acceptance, not a checkmark in a handoff. S00/S10 are explicitly setup/qualification; do not invent gameplay improvements for them.

Keep context bounded: use tools/packet.py context Sxx. Load actual repository code as needed, not every task/audit at once. On interruption save owned diff, last verified result and exact next action. Do not mark incomplete work accepted to get past a context limit. Do not expose private chain-of-thought; handoffs contain only concise decisions, evidence and actions.

# Checkpoint contract — normative

## Meaning
An IMPLEMENTATION task is a complete vertical slice, not a layer, interface, file move or unfinished migration. On acceptance, the integrated app/host combination can be used indefinitely without the next task. It preserves the prior working behavior and delivers its stated improvement. The whole product need not already satisfy every later target; original defects are tracked by stable IDs and bounded reproductions. A new regression is never a permissible original defect.
S00 is explicitly preparation/test infrastructure with unchanged gameplay; S10 is cumulative qualification. Neither is advertised as a gameplay fix. Interrupted work is not a completed checkpoint.

## Before mutation
Coordinator writes runs/Sxx/checkpoint-plan.json from templates/CHECKPOINT_PLAN.json at the actual input source fingerprint. Record the intended visible improvement, full production path, affected artifacts, extraction boundary, required check IDs and seven protected flows. Resolve the actual baseline and scope before edits. Run branch-check against the prepared work branch; source changes on the base branch are not eligible for acceptance. Reviewer checks design first for S05/S06/S07. Required checks may only be amended transparently with reason and independent review; never delete a failed check to pass.
Android APK and host artifacts ship in lockstep from the same candidate source: no mixed-version support, migration or old-shape detection. For every cross-component change the checkpoint plan records only the new/new behavior and the artifact pairing (the single lockstep entry of artifact_compatibility_matrix; compatibility_artifact/compatibility_notes carry the same pairing). Unsafe operations are still refused before effects when a required capability is actually absent at runtime (e.g. streamer without session-scoped termination, D04). A checkpoint must be complete: do not publish a half-migrated or schema-breaking intermediate. If the boundary is too large for one worker context, use smaller internal passes and handoffs; only the integrated parent slice is a checkpoint. Do not solve context limits with a half-functional milestone.

## Required completion evidence
1. The task outcome is exercised through production wiring, not a disconnected new class. Its regression(s) fail before and pass after where reproducible. Characterization is allowed for behavior-preserving extraction.
2. The affected components compile and package. Android debug and release compilation/build checks apply to Android changes; discovered host build/import/packaging checks apply to moved host modules. Retention and minimized builds must not be debug-only successes.
3. Seven protected flow checks run: F01 cold/authorized start; F02 game-only stop; F03 retained switch; F04 whole quit + subsequent start; F05 Home/resume; F06 warm-up; F07 child permissions/time. Baseline expected results distinguish already-fixed contracts from exact pre-existing defects. They are not broad exemptions from a flow.
4. All earlier fixed-path regressions rerun on the candidate. Targeted failure injection and runtime capability-absence cases pass; installed artifacts pair as one candidate. No new uncaught failures, compile failures, permission changes or disconnected paths remain.
5. T44 architecture and T45 packaged wiring are reviewed. Extraction moves state/effects/callers and removes obsolete ownership; passing line-count checks alone are insufficient.
6. Required device/native/Windows checks in the input-bound plan pass. Use an explicitly authorized lab. Missing device tests yield CODE_VERIFIED_DEVICE_PENDING, not CHECKPOINT_ACCEPTED; this status does not satisfy downstream mutation dependencies.
7. Independent review accepts the exact fingerprint, gate plan, executable check artifacts and the statement that no future task is needed. The reviewer records both behavioral and architectural verdicts. The coordinator alone advances state.

A prior original defect may remain only when not worsened, not part of this slice's promised outcome and recorded with owner task and evidence. No quarantining newly failing tests, assertion weakening, arbitrary timeout inflation, or default-disabled implementation as a substitute for integration.

## Candidate versus accepted workspace
Prefer an isolated candidate worktree where available, based on the complete previously accepted source including intentional uncommitted input. Never assume HEAD represents the working tree. Do not use git reset/clean/stash/checkout to discard user work. Commit/push is not authorized by this packet. Keep the last accepted build and the reviewed patch manifest so an interrupted candidate is not mistaken for an accepted app. Recovery/rollback must be described and safe for persisted data; destructive rollback needs separate authorization.

## Evidence vocabulary
PASS means observed required result. FAIL means observed violation. NOT_RUN, BLOCKED and UNBOUND do not pass. A check that executes zero applicable tests cannot pass. Metadata can check internal consistency but is not proof that logs or physical claims are truthful. Passing bounded tests is not a mathematical guarantee of absence of bugs.
CHECKPOINT_ACCEPTED requires the checks selected above, not the complete final soak each time. S10 requires all release gates on the exact final artifacts. Any post-review source edit invalidates the affected fingerprint evidence; revalidate the corrected candidate.

# Bounded mandatory decomposition — normative

## Goal
Reduce responsibilities owned by affected monoliths during the behavioral fixes. This requirement authorizes cohesive extraction; it does not authorize a repository-wide rewrite or cosmetic churn. Existing owners take precedence over new classes. Class names in handoffs are provisional until actual code is inspected.

## Responsibility map
S00 records path/symbol, mutable state, effects, callers, lifetime, desired existing/new owner, package/deployment dependencies and regression seam. Prioritize Game.java, ConsoleActivity.java, wakeplay_gateway.py and GameProviderBridge.py in the changed paths. Record original metrics for review context; do not impose an arbitrary maximum file length.

| Slice | Required ownership result |
|---|---|
| S01 | One immutable actor/execution Gateway context and unambiguous construction at all affected callers. |
| S02 | Retention defaults/migration live in the existing policy owner, not divergent Activity branches. |
| S03 | Windows preparation/cancellation/typed results owned outside ConsoleActivity. |
| S04 | One concrete transport-stop/completion owner, integrated into Game and retained callers. |
| S05 | MoonWaker termination/child-terminal orchestration outside Game; host effect-time fencing separated from HTTP routing. Existing authoritative budgets/journal stay authoritative. |
| S06 | Coherent producer plus one observation/projection path; remove competing UI/start decisions from Activities. |
| S07 | Physical transport runtime/state outside Activity behind the retained facade; view attachment is separate from resource lifetime. |
| S08 | Shared warm-up/Play sequencing outside ConsoleActivity in existing orchestrator/preflight owners. |
| S09 | Small generation-fenced presentation barrier, not a second privacy state machine. |

## Extraction sequence inside a task
Characterize behavior → choose the existing owner/narrow seam → move cohesive state and effects → switch ALL relevant callers → remove old implementation/fields → build packaged artifacts → run behavior, integration and risk-based device checks. Internal passes may temporarily be incomplete only inside the unaccepted candidate. There is no accepted 'add empty manager now, wire later' slice.

## Reject
- A helper receiving Game or ConsoleActivity to reach their mutable fields.
- Copying flags into a helper while Activity remains the competing owner.
- A new SessionManager with all the old responsibilities under a different filename.
- Static singletons retaining Activity/views/listeners beyond their lifetime.
- Merely moving methods, counting lines, adding interfaces with no concrete need, or masking dependency cycles through generic callbacks.
- Reimplementing LaunchTransitionController, SessionOrchestrator, OperationJournal or durable child accounting.
- Moving Python modules without updating packaging/install/import paths and related isolated tests.

## Acceptance and scope
Every task supplies architecture.json from the template: old/new owner, moved state/effects, caller migration, deleted paths, lifetime and packaging proof. An already-well-factored small component may receive an independently reviewed NO_EXTRACTION_NEEDED entry with code evidence; large affected MoonWaker responsibilities listed above may not be deferred wholesale. No requirement to split untouched upstream Moonlight decoder/input code solely because the containing file is long.
Structural checks can enforce forbidden imports/references and removed owner fields; they complement, not replace, behavioral tests. Improve names/duplication/error contracts in the responsibility being moved. Keep unrelated formatting, upgrades and general cleanup out of the diff.

# Worker — one complete slice
Read applicable repository instructions, RULES, CHECKPOINTS, DECOMPOSITION and assigned context. Inspect actual code, direct callers, state/effect owners and accepted input. Do not rely on chat history.
Before edits validate the input-bound checkpoint plan and map every producer/consumer/UI/build change needed for the whole outcome. For S05/S06/S07 return a short design note and wait for real independent design review. No missing capability is solved with a hypothetical API.
Implement the full vertical slice and bounded responsibility extraction. Characterize production behavior first, move state/effects, migrate callers and delete obsolete ownership. Internal passes can span context handoffs, but no half-functional pass is a completed task. No huge catch-all helper, unrelated formatting or default-disabled fix counted as delivered.
Tests exercise real owners/callers with controlled side effects, clock and ordering; assert forbidden effects. Run target, protected-seven-flow and cumulative fixed-path checks plus builds/packaging and mandatory authorized lab checks. Mark original unfixed findings precisely; do not conceal a new regression as a baseline defect. Missing evidence is NOT_RUN/BLOCKED.
Write runs/Sxx/implementation.json, architecture.json, lockstep-pairing/recovery notes and gate artifacts using templates. Describe what works when work stops here and why it needs no later task. Record output source/artifact fingerprints. Return REVIEW_PENDING or blocked/device-pending status; never self-accept. No commits, deployment or live unsafe actions without authorization.

Before edits, run packet.py branch-check --repo . and verify the prepared workspace/context. Branch setup is the coordinator's prerequisite; never implement on the base branch or create a substitute branch. Run available safe local checks yourself and record actual results.

# S07 — Move physical stream ownership out of Activity without regressions

## Integrated outcome
Presentation/Activity replacement does not own or accidentally stop the retained transport; explicit quit still works.

## Required work
Independently review ownership/lifetime of NvConnection/native stop, decoder/surface, audio, controller input, executors and service/Application/Activity references. Follow actual platform background restrictions; no assumed immortal service.
Characterize → introduce narrow owner → move physical runtime/state behind RetainedStreamSessionCoordinator → migrate callers → remove Activity's duplicate ownership → verify. Move accepted S04 stop completion with resources. Serialize real starts/stops/replacements in one place. Game becomes the UI/lifecycle adapter for this responsibility.
Identity-fence surface/input/audio attachment. onDestroy detaches the presentation; explicit quit still ends runtime. Clear held controls and block hidden input. No long-lived owner captures Activity. Preserve upstream Moonlight streaming behavior and avoid JNI changes unless narrowly necessary/reviewed.
Distinguish Activity recreation from full process death: the latter loses the socket and requires honest authorized reconnect. Exercise lifecycle/late-surface/stop-new-intent races automatically. Lab evidence must show actual connection identity/counters, frame restoration, audio and input for Home/picker/recreation. Whole-quit and child expiry from S05 still pass.

## Concrete required work (verified in current code)
1. Listener: MoonBridge.setupBridge stores the NvConnectionListener in a static field, and today that listener is Game (an Activity). The runtime owns its own listener/sink that forwards to the currently attached presentation or to none. connectionTerminated with no attached presentation still moves RetainedStreamSessionCoordinator to RECONNECT_REQUIRED and ends the background service.
2. Wi-Fi locks: the high-perf/low-latency locks (Game.java, acquired near the stream start, released in onDestroy) move to runtime lifetime: held exactly while the transport lives, released on stop.
3. Ownership decision: explicitly decide and record runtime vs presentation ownership for ControllerHandler, UsbDriverService binding and held buttons/sensors. Held controls are released on detach.
4. One connection per process: moonlight-common-c has global native state and MoonBridge has static renderers. Hard invariant with a test: a new start waits for the TransportStopCoordinator result of the previous generation; native start/stop never overlap.
5. Foreground service: BackgroundStreamService.park() calls startForegroundService from onStop/surfaceDestroyed (app leaving foreground, no try/catch); with targetSdk 34 on Android 12+ this risks ForegroundServiceStartNotAllowedException. Start the mediaPlayback foreground service when the transport is established while the app is foreground and keep it for the whole runtime lifetime; parking only changes its state/notification and the retention timer. Record the target TV's Android API level in the checkpoint plan.
6. Test strategy: no androidTest/Robolectric exists (JUnit only); do not add Robolectric. Design the runtime owner as plain Java behind narrow seams (native bridge, surface, Wi-Fi lock, service) and test races in the JVM with controlled latches/queues. Lifecycle evidence (T05, T25, T26) comes from the existing adb lab runner.
7. Activity recreation is rare because Game declares almost all configChanges: force it for T26 with the "Don't keep activities" developer option; force process death with am kill / force-stop.
8. Exit-reason evidence (supports T26, owned here; see T50): at app start record ApplicationExitInfo (API 30+) into the existing MoonWakerDiagnostics so lab evidence distinguishes LMK/crash/ANR/process kill from Activity recreation. StrictMode is enabled in debug builds only. Diagnostics never contain credentials or private desktop data.
9. Internal passes: three internal passes are allowed within the single checkpoint (the checkpoint is still only the integrated slice): (a) runtime-owned listener/sink; (b) move NvConnection, stop completion, audio renderer, SwitchableVideoRenderer and Wi-Fi locks into the runtime; (c) Game becomes the presentation attach/detach adapter.

## Required architecture outcome
Move the physical runtime and its state behind RetainedStreamSessionCoordinator; Game becomes a lifecycle/presentation adapter for these responsibilities. No duplicate socket owner or long-lived Activity capture.

## Completion gate
Follow CHECKPOINTS.md and the input-bound checkpoint plan. All touched production paths and packaging work now. Add focused behavioral regressions, run the seven-flow protected baseline and cumulative fixed-path tests, and obtain independent behavioral + architecture review. Missing mandatory device evidence is CODE_VERIFIED_DEVICE_PENDING, not an accepted checkpoint. Save gate/architecture/lockstep-pairing evidence and the no-future-task-needed statement.

## Non-goals
No unrelated rewrite, generic framework, duplicate authoritative state, source-only behavioral proof, production debug bypass or unauthorized deployment. Later slices may improve separate existing defects but must not complete this slice's introduced half-working behavior.

# Correlation semantics — normative; reuse existing equivalent fields

Names below define meanings, not mandatory new classes or duplicate stores. S00 maps each meaning to an actual owner/field. Add a field only where equivalent evidence is missing.

- `transportGeneration`: one actual local/native transport lifetime. Home and A→B do not change it. A real disconnect/reconnect does.
- `authorizationEpoch` / host-issued child lease identity: permission binding of actor to execution profile and transport. Rebinding parent→child or child→parent changes this even when the native transport is reused. A stale child's expiry must not terminate a later parent's authorization on the same device/socket.
- `gameExecutionId`: one launch instance, not a game catalog ID. Bind to verified PID plus creation identity/token and intended execution profile. A second launch of the same title is a different execution.
- `operationId`: one accepted intent/effect workflow. Retries for that operation cannot trigger a second execution. Idempotency must be authorization-scoped; another actor cannot replay an ID to receive privileges/results.
- `transitionId` and `surfaceGeneration`: current presentation attempt and rendering attachment. A fresh surface frame alone does not prove the correct game's readiness.
- `bootId` + `revision`: producer epoch and monotonic snapshot version within that epoch. After restart, revision 1 is not comparable to revision 1000 from the old epoch. Resolve ownership and resynchronize before destructive decisions.

Every effect checks the identity appropriate to its target. A switch-to-B operation can legitimately stop captured execution A while the UI target is already B; do not reject this valid stop merely because currentGameId changed. Conversely an old stop must never resolve its target from the mutable current game when it finally runs.

Snapshots must be coherent across the facts used for a decision. Merely tagging separate reads with the caller's same requestId, or adding a Gateway revision around uncoordinated Bridge reads, does not make those facts atomic. Use the authoritative provider's existing observation/serialization boundary where possible.

Capture identity before asynchronous work. Host validates actor/device permissions and owns security-sensitive bindings; never trust a client-provided `child=false`, profile ID or generation to grant authority. A local-only token cannot fence a global remote quit unless all competing effects are actually serialized at the authoritative host boundary.

No wire/API design is silently mandated where backend capability is unknown. If a primitive cannot safely terminate only the intended stream, document the actual limitation, protect new starts and unrelated sessions, and mark the affected acceptance gate BLOCKED. Do not claim the product contract passes merely because the game process stops.

## Selected invariants

```json
[
  {
    "id": "I02",
    "name": "Retained means actual runtime",
    "rule": "STREAM_READY requires an owned functioning local transport. A host health response, persistent resume record or Activity boolean alone is insufficient."
  },
  {
    "id": "I03",
    "name": "Home is presentation-only",
    "rule": "App Home, TV Home and host picker without changing host do not stop a healthy transport during the configured retention window. Hidden presentation cannot inject game input."
  },
  {
    "id": "I08",
    "name": "Effects are generation-fenced",
    "rule": "Stop, launch, reveal, input enablement and status callbacks must match immutable host/actor/execution/session-operation identity. Old results cannot mutate a newer session. A device ID or game name alone is not a fence."
  },
  {
    "id": "I09",
    "name": "One real stop completion",
    "rule": "All callers stopping one transport generation observe the same actual stop completion. Acceptance of a request and clearing connected flags are not completion. Do not start a replacement native transport before the old one is safe to replace."
  },
  {
    "id": "I12",
    "name": "Canonical privacy gate",
    "rule": "LaunchTransitionController stays the reveal/input gate. Preserve artwork and existing privacy UX. Parent manual reveal remains an escape hatch, but cannot bypass terminal child denial or grant restricted desktop access."
  },
  {
    "id": "I19",
    "name": "Lifecycle honesty",
    "rule": "Recreating only an Activity must not destroy an otherwise retained transport. Whole-process death cannot preserve a socket; represent loss and use controlled reconnect, with authorization revalidated."
  },
  {
    "id": "I20",
    "name": "Every accepted operation terminates",
    "rule": "Every accepted operation/barrier has exactly one explicit terminal outcome. View detachment, cancellation and supersession cannot silently drop required cleanup or leave a pending result forever."
  },
  {
    "id": "I25",
    "name": "Every accepted slice is independently usable",
    "rule": "A completed implementation task integrates every producer, consumer, UI entrypoint and packaging change required by its outcome. No future task, disabled new path or partial API integration may be necessary to restore working behavior. A later task may fix a separate pre-existing defect, not a regression introduced here."
  },
  {
    "id": "I26",
    "name": "Monotonic protected baseline",
    "rule": "Every accepted checkpoint passes target regressions, builds, cumulative fixed-path smoke and the seven-flow protected baseline. Explicit known original defects are not new regressions; no broad skips or rewritten expected results may conceal a new failure. Missing required physical evidence prevents checkpoint acceptance."
  },
  {
    "id": "I27",
    "name": "Move responsibilities, not only methods",
    "rule": "Decompose affected monoliths in the same task as the relevant behavior change: move state, effects and production callers to one narrow owner and remove obsolete paths. No whole-Activity helper dependency, copied state, unused extraction or catch-all manager. Do not rewrite unrelated upstream Moonlight core."
  },
  {
    "id": "I28",
    "name": "Test real wiring and negative effects",
    "rule": "Use actual production policies/callers with controllable effects, clocks and event order. Assert forbidden stop/reconnect/reveal/privilege effects as well as success. Unit/mocked evidence is never physical socket, secure sign-in or display evidence. Test hooks cannot weaken production security."
  }
]
```

## Selected flows

```json
[
  {
    "id": "F02",
    "name": "Close game only",
    "steps": [
      "Submit artwork cover and block game input.",
      "Request stop of exactly the bound game, preserving the stream.",
      "Show carousel promptly; retain STOPPING until stop is observed."
    ],
    "postconditions": [
      "Same transport generation, zero transport-stop effects.",
      "Host badge reports active/ready stream; old tile clears only on authoritative stop.",
      "Next game can use the same transport."
    ]
  },
  {
    "id": "F03",
    "name": "Switch games through carousel",
    "steps": [
      "Home entry does not stop current game or transport.",
      "Selecting B displays B artwork and blocks hidden input.",
      "Stop and confirm A, then launch B on retained transport.",
      "Reveal only B with coherent readiness and fresh transition frame."
    ],
    "postconditions": [
      "One transport across A and B.",
      "Late observations or expiry belonging to A cannot affect B.",
      "No input leaks to A, a parent desktop or unready B."
    ]
  },
  {
    "id": "F04",
    "name": "Close stream and game",
    "steps": [
      "Block input, initiate real local stop and navigate Home without waiting for Gateway.",
      "Run idempotent generation-fenced host game/stream cleanup.",
      "Accept a new Play intent immediately but execute only past the safe replacement barrier."
    ],
    "postconditions": [
      "No local rollback to retained stream on host failure.",
      "ONLINE_IDLE only after confirmed host idle; otherwise honest pending/unknown.",
      "Explicit quit is not reversed by auto-warm-up."
    ]
  },
  {
    "id": "F05",
    "name": "Quick resume",
    "steps": [
      "Detach presentation/input/audio as appropriate when leaving to app Home, TV Home or host picker.",
      "Retain the healthy transport within policy while host and execution identity stay unchanged.",
      "Reattach current surface, decoder/audio/input through the canonical gate."
    ],
    "postconditions": [
      "No new connection for a presentation-only round trip.",
      "Activity recreation and full process death follow different paths.",
      "No privacy or child-authorization bypass."
    ]
  },
  {
    "id": "F06",
    "name": "Automatic stream warm-up",
    "steps": [
      "On intentional authorized host/profile entry, prepare once in the background.",
      "Report PREPARING from real progress; report STREAM_READY only after transport established.",
      "A game click joins current preparation; latest not-yet-dispatched launch intent wins."
    ],
    "postconditions": [
      "No duplicate stream, accidental launch or desktop input.",
      "No game-time lease merely for neutral warm-up.",
      "Wrong actor/profile or superseded intent cannot reuse authorization."
    ]
  }
]
```

## Selected decisions

```json
[
  {
    "id": "D01",
    "status": "USER_AUTHORIZED",
    "topic": "Retention default",
    "value": "Amendment D01-20260909-30MIN (user authorized, see STATE.json contract_amendments): for an absent stored preference, default background stream retention is 30 minutes in both debug and release. Every explicit stored value, including zero/off, is preserved. This timeout does not apply to app Home or host-picker retention; do not introduce time-based disconnection there merely to reuse the background-TV preference."
  },
  {
    "id": "D07",
    "status": "REQUIRED",
    "topic": "Unresolved product choices",
    "value": "User requirements cannot be weakened by a reviewer. Routine implementation choices are autonomous. Material semantic conflict is recorded as CONTRACT_BLOCKED with a narrow proposed correction; continue independent read-only analysis; do not bypass a blocked checkpoint for dependent mutations, not an open-ended redesign."
  },
  {
    "id": "D08",
    "status": "REQUIRED",
    "topic": "Native or device limits",
    "value": "Do not promise immortality of foreground services, sockets across process death or instant remote effect during network loss. Report local completion separately from host confirmation and physical device validation."
  },
  {
    "id": "D09",
    "status": "REQUIRED",
    "topic": "Timing targets",
    "value": "No invented millisecond limits. Immediate means the local effect is not causally gated by network/UI work. Measure latency on the baseline and candidate; choose any numerical release threshold explicitly from evidence."
  }
]
```

## Selected findings

```json
[
  {
    "id": "H05",
    "priority": "P1",
    "title": "Game Activity owns live transport",
    "symbols": [
      "Game.java::NvConnection ownership",
      "Game.java::onDestroy",
      "RetainedStreamSessionCoordinator",
      "BackgroundStreamService"
    ],
    "inherited_static_observation": "Destroying the resource owner can stop a logically retained connection.",
    "repair_contract": "Move physical lifetime behind a stable existing facade; distinguish Activity recreation from process death.",
    "evidence_status": "INHERITED_STATIC_AUDIT_REQUIRES_REVALIDATION",
    "runtime_reproduced": false
  }
]
```

## Selected tests

```json
[
  {
    "id": "T05",
    "title": "TV Home resume",
    "given": "A running; retention enabled; return within configured window.",
    "when": "TV Home then return to the app/game.",
    "must_observe": [
      "Same actual transport G; current surface attached; audio/input restored only when authorized."
    ],
    "must_not_observe": [
      "Network reconnect for a presentation-only round trip.",
      "Hidden game receiving TV navigation input."
    ],
    "code_evidence": [
      "android lifecycle/instrumentation: presentation detach/attach"
    ],
    "device_evidence": [
      "authorized Windows host + target Android TV; candidate release build"
    ],
    "evidence_note": "Capture actual command/result or observed effects; this is an expected oracle, not a passed test."
  },
  {
    "id": "T06",
    "title": "App Home resume",
    "given": "A running over G.",
    "when": "Use overlay Home and controller shortcut in separate runs; return to game.",
    "must_observe": [
      "Same G, carousel while hidden game runs, no hidden-input forwarding."
    ],
    "must_not_observe": [
      "Whole-session quit or unwanted stop A."
    ],
    "code_evidence": [
      "unit: input routing and Home intent",
      "android instrumentation: attach/detach"
    ],
    "device_evidence": [
      "authorized Windows host + target Android TV; candidate release build"
    ],
    "evidence_note": "Capture actual command/result or observed effects; this is an expected oracle, not a passed test."
  },
  {
    "id": "T07",
    "title": "Host picker without host change",
    "given": "A and G active with fixed execution identity.",
    "when": "Open host picker and re-enter the same host/profile.",
    "must_observe": [
      "G and A retained; correct active-stream host indicator."
    ],
    "must_not_observe": [
      "Session teardown or authorization leakage to a different actor."
    ],
    "code_evidence": [
      "unit: navigation/session identity"
    ],
    "device_evidence": [
      "authorized Windows host + target Android TV; candidate release build"
    ],
    "evidence_note": "Capture actual command/result or observed effects; this is an expected oracle, not a passed test."
  },
  {
    "id": "T08",
    "title": "Home during transition",
    "given": "Prepare, switch and reveal controllably paused at their async boundaries.",
    "when": "Go Home at each boundary, then deliver pending callbacks in reversed order.",
    "must_observe": [
      "Each accepted operation has one terminal result or a still-valid explicit running owner.",
      "No stale surface callback opens hidden video/input."
    ],
    "must_not_observe": [
      "Dropped continuation or permanently pending cleanup."
    ],
    "code_evidence": [
      "unit: deterministic callback schedule",
      "android instrumentation: view detachment"
    ],
    "device_evidence": [
      "authorized Windows host + target Android TV; candidate release build"
    ],
    "evidence_note": "Capture actual command/result or observed effects; this is an expected oracle, not a passed test."
  },
  {
    "id": "T12",
    "title": "Concurrent stop completion",
    "given": "Fake actual connection stop blocks on a latch; two callers share generation G.",
    "when": "Both call stop; release latch afterwards.",
    "must_observe": [
      "One actual stop invocation; neither successful completion runs before stop returns.",
      "Both callers get the same terminal result, including failure semantics if injected."
    ],
    "must_not_observe": [
      "Completion inferred from connected=false."
    ],
    "code_evidence": [
      "unit: latch-controlled actual-stop seam"
    ],
    "device_evidence": [],
    "evidence_note": "Capture actual command/result or observed effects; this is an expected oracle, not a passed test."
  },
  {
    "id": "T25",
    "title": "Activity destroy versus stop/new intent",
    "given": "Actual stop controllably blocked; lifecycle destroy and new intent can race.",
    "when": "Interleave destroy, stop and new Play across one old generation.",
    "must_observe": [
      "One owner, one actual stop result and no old completion on new resources."
    ],
    "must_not_observe": [
      "Use-after-release or double-stop from independent owners."
    ],
    "code_evidence": [
      "unit: controlled lifecycle/effect race",
      "android instrumentation"
    ],
    "device_evidence": [
      "authorized Windows host + target Android TV; candidate release build"
    ],
    "evidence_note": "Capture actual command/result or observed effects; this is an expected oracle, not a passed test."
  },
  {
    "id": "T26",
    "title": "Activity recreation versus process death",
    "given": "A/G retained and authorized.",
    "when": "Recreate Activity; separately terminate entire app process and relaunch.",
    "must_observe": [
      "Activity-only recreation keeps runtime G and safely attaches a new presentation.",
      "Process death honestly reports no live local G, reconciles host and reconnects under current authorization."
    ],
    "must_not_observe": [
      "Claiming socket continuity after process death.",
      "Old UI persisted flags acting as live evidence."
    ],
    "code_evidence": [
      "android lifecycle/instrumentation",
      "unit: restore/reconnect classification"
    ],
    "device_evidence": [
      "authorized Windows host + target Android TV; candidate release build"
    ],
    "evidence_note": "Capture actual command/result or observed effects; this is an expected oracle, not a passed test."
  },
  {
    "id": "T27",
    "title": "Release/debug retention default parity",
    "given": "Fresh release/debug installs; stored retention preference absent, explicitly disabled and explicitly set to other values.",
    "when": "Read the retention policy and run Home resume.",
    "must_observe": [
      "Absent preference yields the same 30 minute background retention on release and debug (D01).",
      "Explicit stored values, including disabled, are preserved; configured retention boundaries tested."
    ],
    "must_not_observe": [
      "Overwriting an explicit disabled choice, applying the background timeout to app Home/host-picker retention, or treating debug-only success as release evidence."
    ],
    "code_evidence": [
      "unit: preference default/explicit-value semantics",
      "build: debug and release configuration verification"
    ],
    "device_evidence": [
      "authorized Windows host + target Android TV; candidate release build"
    ],
    "evidence_note": "Capture actual command/result or observed effects; this is an expected oracle, not a passed test."
  },
  {
    "id": "T37",
    "title": "Actor rebind with retained transport",
    "given": "Parent/child share Windows execution profile; parent UI previously used.",
    "when": "Rebind to child or parent with proper authorization while retaining transport where supported.",
    "must_observe": [
      "New authorization epoch and correct actor restrictions; old management/Discord/input capabilities invalidated."
    ],
    "must_not_observe": [
      "Treating same Windows account or G as sufficient authorization."
    ],
    "code_evidence": [
      "host integration: actor rebind",
      "unit: facade/context invalidation"
    ],
    "device_evidence": [
      "authorized Windows host + target Android TV; candidate release build"
    ],
    "evidence_note": "Capture actual command/result or observed effects; this is an expected oracle, not a passed test."
  },
  {
    "id": "T40",
    "title": "Equivalent UI entrypoints and repeated buttons",
    "given": "Same operation available through overlay, game tile, controller shortcut and relevant navigation.",
    "when": "Trigger each and repeat/collide button presses around stop/start.",
    "must_observe": [
      "All routes reach the canonical operation with equivalent semantics and deduplicated effects."
    ],
    "must_not_observe": [
      "One legacy caller bypassing actor context, stop barrier or retained policy."
    ],
    "code_evidence": [
      "unit/integration: real caller wiring",
      "android input/navigation instrumentation"
    ],
    "device_evidence": [
      "authorized Windows host + target Android TV; candidate release build"
    ],
    "evidence_note": "Capture actual command/result or observed effects; this is an expected oracle, not a passed test."
  },
  {
    "id": "T43",
    "title": "Independently usable integrated checkpoint",
    "given": "Previous accepted product plus declared original defects.",
    "when": "Finish any implementation slice and stop all further implementation.",
    "must_observe": [
      "Target outcome works through real production entrypoints. All protected flows are no worse, builds and cumulative smoke pass.",
      "No later task is needed for new producer/consumer/schema/packaging changes to function."
    ],
    "must_not_observe": [
      "Dangling producers, unimplemented consumers, hidden critical paths, new known regression deferred to a future task."
    ],
    "code_evidence": [
      "behavioral regression + actual production wiring/integration + input-bound checkpoint report"
    ],
    "device_evidence": [
      "Required where the changed responsibility depends on real Windows/TV/native effects; follow approved checkpoint plan."
    ],
    "evidence_note": "Expected oracle only. No product test was run while preparing this packet."
  },
  {
    "id": "T44",
    "title": "Complete responsibility extraction",
    "given": "One responsibility is selected from Game/ConsoleActivity/Gateway/Bridge.",
    "when": "Apply and exercise its extraction.",
    "must_observe": [
      "State, effects and every affected caller have one production owner; obsolete paths are removed.",
      "Focused behavioral tests can run without constructing the entire Activity."
    ],
    "must_not_observe": [
      "Helper reaching through Game for its mutable state; duplicated session truth; new monolithic manager."
    ],
    "code_evidence": [
      "behavioral regression + actual production wiring/integration + input-bound checkpoint report"
    ],
    "device_evidence": [
      "Required where the changed responsibility depends on real Windows/TV/native effects; follow approved checkpoint plan."
    ],
    "evidence_note": "Expected oracle only. No product test was run while preparing this packet."
  },
  {
    "id": "T45",
    "title": "Extracted modules ship in lockstep",
    "given": "APK/host artifacts built from the same candidate source.",
    "when": "Build/package and verify import/registration and that the installed APK and host artifacts pair as one candidate build.",
    "must_observe": [
      "Extracted Java classes and host modules are included and wired in produced artifacts.",
      "APK and host artifacts come from the same candidate build (lockstep pairing verified)."
    ],
    "must_not_observe": [
      "Unit tests pass from repository files but deployed host lacks the extracted module."
    ],
    "code_evidence": [
      "behavioral regression + actual production wiring/integration + input-bound checkpoint report"
    ],
    "device_evidence": [
      "Required where the changed responsibility depends on real Windows/TV/native effects; follow approved checkpoint plan."
    ],
    "evidence_note": "Expected oracle only. No product test was run while preparing this packet."
  },
  {
    "id": "T46",
    "title": "Repeatable adverse event schedule",
    "given": "Production owner logic behind deterministic effect boundaries.",
    "when": "Deliver seeded duplicates, late completion, reordered observations, view replacement and cancelled work.",
    "must_observe": [
      "Same seed yields the same event trace and assertions; failures include replay input.",
      "Effects target the correct transport/actor/process generation and terminate once."
    ],
    "must_not_observe": [
      "Fixed sleeps as concurrency proof; retries turning flaky failures green; test oracle reusing production resolver as its only expected result."
    ],
    "code_evidence": [
      "behavioral regression + actual production wiring/integration + input-bound checkpoint report"
    ],
    "device_evidence": [
      "Required where the changed responsibility depends on real Windows/TV/native effects; follow approved checkpoint plan."
    ],
    "evidence_note": "Expected oracle only. No product test was run while preparing this packet."
  },
  {
    "id": "T50",
    "title": "Exit-reason evidence",
    "given": "A debug/candidate build after Activity recreation and after process kill (LMK, crash, ANR, am kill/force-stop).",
    "when": "The app starts again.",
    "must_observe": [
      "ApplicationExitInfo (API 30+) is recorded into MoonWakerDiagnostics at app start so lab evidence distinguishes LMK/crash/ANR/process kill from Activity recreation (supports T26).",
      "StrictMode is enabled in debug builds only."
    ],
    "must_not_observe": [
      "Credentials or private desktop data in diagnostics, StrictMode in release builds, or process death reported as Activity recreation."
    ],
    "code_evidence": [
      "behavioral regression + actual production wiring/integration + input-bound checkpoint report"
    ],
    "device_evidence": [
      "authorized Windows host + target Android TV; candidate release build: forced recreation (Do not keep activities) and process kill with recorded exit reasons"
    ],
    "evidence_note": "Expected oracle only. No product test was run while preparing this packet."
  }
]
```

## Required output
Use templates/TASK_RESULT.json (worker) or templates/REVIEW_RESULT.json (reviewer). Read actual dependency reports referenced above. Resolve paths relative to docs/MoonWaker/HARDENING. Do not load the full reference audit or unrelated tasks by default. Do not mark a task accepted yourself as worker.
