# MoonWaker bounded task dispatch

```json
{
  "task_id": "S02",
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
  "model_hint": "Sol",
  "expected_input_fingerprint": "sha256:ff1458b66850bbb923c134c88d1e042072d66c0f456029440be35ee2988155fc",
  "resource_locks": [
    "android-retention-preferences"
  ],
  "source_hints": [
    "app/src/main/java/com/limelight/stream/BackgroundStreamPreferences.java",
    "app/src/main/java/com/limelight/stream/BackgroundStreamService.java",
    "app/src/main/java/com/limelight/Game.java"
  ],
  "write_scope_note": "Named responsibility, its state/effects, all direct production callers, required test/build/packaging changes and assigned run evidence. No unrelated formatting, dependency upgrades or replacement framework.",
  "pre_edit_contract_review": false,
  "blocked_reasons": [],
  "scenario_assertion_scope": "Complete independently usable outcome in this task. Unfixed pre-existing findings assigned later may remain listed, but no behavior introduced or changed here may depend on a future task to work.",
  "independently_usable_outcome": "A fresh release no longer silently disables the verified retention policy; explicit user choices survive migration.",
  "decomposition_requirement": "Put defaults/migration/retention decisions in the existing preference/policy owner, not a second Activity branch.",
  "test_risk": "POLICY_AND_WIRING",
  "mandatory_real_device_topics": [],
  "dependency_handoffs": {
    "S01": {
      "status": "CHECKPOINT_ACCEPTED",
      "assigned_run": "S01-worker-20260909T0850Z",
      "input_fingerprint": "sha256:db6880c0f0597149222d926db820b8438b2ca836d046cdb8c86dac8aacc7a387",
      "output_fingerprint": "sha256:ff1458b66850bbb923c134c88d1e042072d66c0f456029440be35ee2988155fc",
      "implementation_report": "runs/S01/implementation.json",
      "review_report": "runs/S01/review.json",
      "design_review_report": null,
      "blockers": [],
      "checkpoint_plan": "runs/S01/checkpoint-plan.json"
    }
  },
  "this_task_state": {
    "status": "IN_PROGRESS",
    "assigned_run": "S02-worker-20260909T0920Z",
    "input_fingerprint": "sha256:ff1458b66850bbb923c134c88d1e042072d66c0f456029440be35ee2988155fc",
    "output_fingerprint": null,
    "implementation_report": null,
    "review_report": null,
    "design_review_report": null,
    "blockers": [],
    "checkpoint_plan": "runs/S02/checkpoint-plan.json"
  },
  "contract_amendments": [
    {
      "id": "D01-20260909-30MIN",
      "decision": "D01",
      "status": "USER_AUTHORIZED",
      "value": "For an absent stored preference, default background stream retention to 30 minutes in both debug and release. Preserve every explicit stored value, including zero/off. Do not apply this timeout to app Home or host-picker retention.",
      "source": "User message: Ustawmy domyślnie 30min"
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

Read code and direct call sites before editing. Capture input fingerprint, planned checks, compatibility, extraction, source scope and test authorization. Widen scope only for the bounded whole responsibility and integration it needs, with recorded review. Characterize before extraction; test adverse orderings with controlled clocks/latches/queues, not sleeps. Tests must invoke production wiring and assert forbidden effects. Missing tools/devices and zero-test discovery are not PASS.

Preserve HTTPS, pinning, pairing, authentication, profile headers, request IDs, bounded responses and endpoint permissions. Bridges remain loopback-only. Child execution in a parent's Windows profile never transfers actor privileges. Do not add production debug bypasses, trust-all TLS, generic Bridge proxies or process-name-wide kills. Secrets/PINs/tokens must not appear in artifacts, URLs or command arguments.

The coordinator is explicitly authorized to fetch the named base and create/switch to codex/session-hardening under BRANCHING.md before S00. Product/test/build edits on the base branch are forbidden. Run the read-only branch-check before each mutating task. No commit, push, PR, deployment, installation, account/credential changes, sleep, forced sign-in or destructive live host actions are authorized by this packet alone. Read-only inspection and local safe builds/tests are allowed. Build and execute a local-first verification pipeline; GitHub Actions is not required. Device installation/testing requires an authorized disposable/lab environment. Never run public untrusted PR code on the user's Windows/TV lab.

Only CHECKPOINT_ACCEPTED / justified ACCEPTED_NO_CHANGE satisfy task dependencies. CODE_VERIFIED_DEVICE_PENDING is useful progress, not a safe-stop claim or an accepted dependency. The coordinator owns STATE.json and input-bound gate plans. Source fingerprint, executed check artifacts and separate review determine acceptance, not a checkmark in a handoff. S00/S10 are explicitly setup/qualification; do not invent gameplay improvements for them.

Keep context bounded: use tools/packet.py context Sxx. Load actual repository code as needed, not every task/audit at once. On interruption save owned diff, last verified result and exact next action. Do not mark incomplete work accepted to get past a context limit. Do not expose private chain-of-thought; handoffs contain only concise decisions, evidence and actions.

# Checkpoint contract — normative

## Meaning
An IMPLEMENTATION task is a complete vertical slice, not a layer, interface, file move or unfinished migration. On acceptance, the integrated app/host combination can be used indefinitely without the next task. It preserves the prior working behavior and delivers its stated improvement. The whole product need not already satisfy every later target; original defects are tracked by stable IDs and bounded reproductions. A new regression is never a permissible original defect.
S00 is explicitly preparation/test infrastructure with unchanged gameplay; S10 is cumulative qualification. Neither is advertised as a gameplay fix. Interrupted work is not a completed checkpoint.

## Before mutation
Coordinator writes runs/Sxx/checkpoint-plan.json from templates/CHECKPOINT_PLAN.json at the actual input source fingerprint. Record the intended visible improvement, full production path, affected artifacts, extraction boundary, required check IDs and seven protected flows. Resolve the actual baseline and scope before edits. Run branch-check against the prepared work branch; source changes on the base branch are not eligible for acceptance. Reviewer checks design first for S05/S06/S07. Required checks may only be amended transparently with reason and independent review; never delete a failed check to pass.
For every cross-component change specify old-client/new-host, new-client/old-host and new/new behavior; reject unsupported unsafe operations before effects. Use compatible expansion/migration inside the slice. Do not publish a schema-breaking intermediate checkpoint. If the boundary is too large for one worker context, use smaller internal passes and handoffs; only the integrated parent slice is a checkpoint. Do not solve context limits with a half-functional milestone.

## Required completion evidence
1. The task outcome is exercised through production wiring, not a disconnected new class. Its regression(s) fail before and pass after where reproducible. Characterization is allowed for behavior-preserving extraction.
2. The affected components compile and package. Android debug and release compilation/build checks apply to Android changes; discovered host build/import/packaging checks apply to moved host modules. Retention and minimized builds must not be debug-only successes.
3. Seven protected flow checks run: F01 cold/authorized start; F02 game-only stop; F03 retained switch; F04 whole quit + subsequent start; F05 Home/resume; F06 warm-up; F07 child permissions/time. Baseline expected results distinguish already-fixed contracts from exact pre-existing defects. They are not broad exemptions from a flow.
4. All earlier fixed-path regressions rerun on the candidate. Targeted failure injection and declared compatibility cases pass. No new uncaught failures, compile failures, permission changes or disconnected paths remain.
5. T44 architecture and T45 packaged wiring are reviewed. Extraction moves state/effects/callers and removes obsolete ownership; passing line-count checks alone are insufficient.
6. Required device/native/Windows checks in the input-bound plan pass. Use an explicitly authorized lab. Missing device tests yield CODE_VERIFIED_DEVICE_PENDING, not CHECKPOINT_ACCEPTED; this status does not satisfy downstream mutation dependencies.
7. Independent review accepts the exact fingerprint, gate plan, executable check artifacts and the statement that no future task is needed. The reviewer records both behavioral and architectural verdicts. The coordinator alone advances state.

A prior original defect may remain only when not worsened, not part of this slice's promised outcome and recorded with owner task and evidence. No quarantining newly failing tests, assertion weakening, arbitrary timeout inflation, or default-disabled implementation as a substitute for integration.

## Candidate versus accepted workspace
Prefer an isolated candidate worktree where available, based on the complete previously accepted source including intentional uncommitted input. Never assume HEAD represents the working tree. Do not use git reset/clean/stash/checkout to discard user work. Commit/push is not authorized by this packet. Keep the last accepted build and the reviewed patch manifest so an interrupted candidate is not mistaken for an accepted app. Recovery/rollback must be described and safe for migrated data; destructive rollback needs separate authorization.

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
Write runs/Sxx/implementation.json, architecture.json, compatibility/recovery notes and gate artifacts using templates. Describe what works when work stops here and why it needs no later task. Record output source/artifact fingerprints. Return REVIEW_PENDING or blocked/device-pending status; never self-accept. No commits, deployment or live unsafe actions without authorization.

Before edits, run packet.py branch-check --repo . and verify the prepared workspace/context. Branch setup is the coordinator's prerequisite; never implement on the base branch or create a substitute branch. Run available safe local checks yourself and record actual results.

# S02 — Unify retention policy and preserve explicit preferences

## Integrated outcome
A fresh release no longer silently disables the verified retention policy; explicit user choices survive migration.

## Required work
Align fresh debug/release retention defaults with the verified product policy, preserving every explicit setting including zero/off. Distinguish absent settings from explicit preferences. Keep app Home/picker retention distinct from any bounded TV-background preference; do not introduce a new time-based cut-off just to share a setting.
Centralize default/migration semantics in the existing preference/policy component, migrate callers and remove divergent Activity logic. This is a finished policy fix; S07 later changes physical lifetime independently.
Exercise absent/explicit/upgrade cases in debug and release variants. A code seam with behavior still disabled by the previous default does not pass. D01 unresolved duration remains a truthful release-policy limitation, not permission to claim an unapproved timeout was requested.

## Required architecture outcome
Put defaults/migration/retention decisions in the existing preference/policy owner, not a second Activity branch.

## Completion gate
Follow CHECKPOINTS.md and the input-bound checkpoint plan. All touched production paths and packaging work now. Add focused behavioral regressions, run the seven-flow protected baseline and cumulative fixed-path tests, and obtain independent behavioral + architecture review. Missing mandatory device evidence is CODE_VERIFIED_DEVICE_PENDING, not an accepted checkpoint. Save gate/architecture/compatibility evidence and the no-future-task-needed statement.

## Non-goals
No unrelated rewrite, generic framework, duplicate authoritative state, source-only behavioral proof, production debug bypass or unauthorized deployment. Later slices may improve separate existing defects but must not complete this slice's introduced half-working behavior.

## Selected invariants

```json
[
  {
    "id": "I03",
    "name": "Home is presentation-only",
    "rule": "App Home, TV Home and host picker without changing host do not stop a healthy transport during the configured retention window. Hidden presentation cannot inject game input."
  },
  {
    "id": "I17",
    "name": "Durable host authority",
    "rule": "GameOps durable state stays host-authoritative. Child budget does not reset across game switches, retries, service restart or day/clock handling. Use the configured schedule timezone, not an assumed user/device timezone."
  },
  {
    "id": "I19",
    "name": "Lifecycle honesty",
    "rule": "Recreating only an Activity must not destroy an otherwise retained transport. Whole-process death cannot preserve a socket; represent loss and use controlled reconnect, with authorization revalidated."
  },
  {
    "id": "I23",
    "name": "Budget covers retained running game",
    "rule": "Home does not stop charging a still-running child game or reset remaining time. Mere pre-launch warm-up/neutral no-game transport does not consume gameplay budget. Preserve a stricter explicit existing parent policy if present; record it."
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
  }
]
```

## Selected decisions

```json
[
  {
    "id": "D01",
    "status": "PROVISIONAL_DEFAULT",
    "topic": "Retention default",
    "value": "Align absent-setting behavior in release/debug with the verified product retention policy and preserve explicit values including disabled. The inherited debug default of 5 minutes is only an interim migration candidate, NOT user approval of a new timeout. S00 must record a documented/user-authorized policy or leave D01 PRODUCT_POLICY_PENDING. S02 may implement the parity/migration seam and clearly retain the interim value without calling F05 release-accepted. Do not introduce time-based disconnection on app Home/host-picker merely to reuse the background-TV preference. S10 cannot certify F05 until its duration/policy is explicit and consistent with the user requirement."
  },
  {
    "id": "D03",
    "status": "PROVISIONAL_DEFAULT",
    "topic": "Child accounting",
    "value": "Running child game continues consuming time on Home. Neutral prelaunch warm-up consumes none. Preserve existing explicit parent-selected stricter policy; S00 records the rule, not invent a new billing/time system."
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
  }
]
```

## Selected findings

```json
[
  {
    "id": "H04",
    "priority": "P1",
    "title": "Release default disables background retention",
    "symbols": [
      "BackgroundStreamPreferences.readMinutes",
      "Game.java::canParkBackgroundStream",
      "Game.java::onStop"
    ],
    "inherited_static_observation": "Inherited audit reports default debug=5 minutes and release=0.",
    "repair_contract": "Unify absent-setting policy, preserve explicit choices and validate fresh release as well as debug.",
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
    "id": "T27",
    "title": "Release/debug and preference migration",
    "given": "Fresh release/debug installs; existing settings absent, explicitly disabled and explicitly enabled.",
    "when": "Read/migrate retention policy and run Home resume.",
    "must_observe": [
      "Same absent-setting policy on release/debug; explicit values preserved.",
      "Migration and configured retention boundaries tested."
    ],
    "must_not_observe": [
      "Overwriting an explicit disabled choice or treating debug-only success as release evidence."
    ],
    "code_evidence": [
      "unit: preferences/migration",
      "build: debug and release configuration verification"
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
    "title": "Extracted modules ship and upgrade coherently",
    "given": "APK/host artifacts built from the candidate source.",
    "when": "Build/package and verify import/registration plus declared mixed-version deployment order.",
    "must_observe": [
      "Extracted Java classes and host modules are included and wired in produced artifacts.",
      "Old/new capability combinations work or fail explicitly before any unsafe effect."
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
  }
]
```

## Required output
Use templates/TASK_RESULT.json (worker) or templates/REVIEW_RESULT.json (reviewer). Read actual dependency reports referenced above. Resolve paths relative to docs/MoonWaker/HARDENING. Do not load the full reference audit or unrelated tasks by default. Do not mark a task accepted yourself as worker.
