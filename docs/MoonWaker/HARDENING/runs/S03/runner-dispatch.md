# MoonWaker bounded task dispatch

```json
{
  "task_id": "S03",
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
  "expected_input_fingerprint": "sha256:02e6f8918a8027db3cb7b1270a22b1ed4ac520345c1f368093d85b2f95ca31bd",
  "resource_locks": [
    "android-console",
    "android-preflight"
  ],
  "source_hints": [
    "app/src/main/java/com/limelight/console/HostLaunchPreflight.java",
    "app/src/main/java/com/limelight/console/HostReadiness.java",
    "app/src/main/java/com/limelight/console/ConsoleActivity.java",
    "host-services/windows-login/login-broker/BrokerCore.cs"
  ],
  "write_scope_note": "Named responsibility, its state/effects, all direct production callers, required test/build/packaging changes and assigned run evidence. No unrelated formatting, dependency upgrades or replacement framework.",
  "pre_edit_contract_review": false,
  "blocked_reasons": [],
  "scenario_assertion_scope": "Complete independently usable outcome in this task. Unfixed pre-existing findings assigned later may remain listed, but no behavior introduced or changed here may depend on a future task to work.",
  "independently_usable_outcome": "Authorized unattended start no longer waits for a streamer that needs login first, and no ambiguous login result is accepted as READY.",
  "decomposition_requirement": "Move sign-in sequencing, typed results and cancellation ownership out of ConsoleActivity into the existing preflight/session owner; Activity only reports progress and forwards intent.",
  "test_risk": "DEVICE_CRITICAL",
  "mandatory_real_device_topics": [
    "Authorized Windows sign-in with Gateway before streamer; denial/timeout"
  ],
  "dependency_handoffs": {
    "S02": {
      "status": "CHECKPOINT_ACCEPTED",
      "assigned_run": "S02-worker-20260909T0920Z",
      "input_fingerprint": "sha256:ff1458b66850bbb923c134c88d1e042072d66c0f456029440be35ee2988155fc",
      "output_fingerprint": "sha256:02e6f8918a8027db3cb7b1270a22b1ed4ac520345c1f368093d85b2f95ca31bd",
      "implementation_report": "runs/S02/implementation.json",
      "review_report": "runs/S02/review.json",
      "design_review_report": null,
      "blockers": [],
      "checkpoint_plan": "runs/S02/checkpoint-plan.json"
    }
  },
  "this_task_state": {
    "status": "CODE_VERIFIED_DEVICE_PENDING",
    "assigned_run": "S03-worker-20260909T1319Z",
    "input_fingerprint": "sha256:02e6f8918a8027db3cb7b1270a22b1ed4ac520345c1f368093d85b2f95ca31bd",
    "output_fingerprint": "sha256:3ed2e7d8169345305c9d8a61dfe8c4110b7491ffc36f4c7b4d8d49d8087a639e",
    "implementation_report": "runs/S03/implementation.json",
    "review_report": null,
    "design_review_report": null,
    "blockers": [
      "Mandatory S03-LAB-WINDOWS-SIGNIN evidence must be executed by the user on the exact bound APK and independently reviewed before checkpoint acceptance."
    ],
    "checkpoint_plan": "runs/S03/checkpoint-plan.json"
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

# S03 — Complete Windows preparation with positive readiness proof

## Integrated outcome
Authorized unattended start no longer waits for a streamer that needs login first, and no ambiguous login result is accepted as READY.

## Required work
Complete the start path: wake if needed → reachable Gateway → authorized actor/device/execution identity → positively confirmed correct interactive Windows session → profile components → streamer → existing launch. Warm-up uses this same corrected preparation entrypoint where already present.
Move preparation sequencing, typed results and cancellation into existing preflight/session owners outside ConsoleActivity. READY alone may proceed. Null, timeout, unavailable, denied, wrong identity and cancellation do not silently succeed. No repeated password guessing or implicit takeover/logoff.
Tests control Gateway/streamer availability, login callbacks and clocks; force late success after cancel and another active Windows user. Include an authorized Windows integration smoke where Gateway exists before the streamer and a denial case. Preserve unrelated warm-up concurrency until S08; the corrected start path must work now.

## Required architecture outcome
Move sign-in sequencing, typed results and cancellation ownership out of ConsoleActivity into the existing preflight/session owner; Activity only reports progress and forwards intent.

## Completion gate
Follow CHECKPOINTS.md and the input-bound checkpoint plan. All touched production paths and packaging work now. Add focused behavioral regressions, run the seven-flow protected baseline and cumulative fixed-path tests, and obtain independent behavioral + architecture review. Missing mandatory device evidence is CODE_VERIFIED_DEVICE_PENDING, not an accepted checkpoint. Save gate/architecture/compatibility evidence and the no-future-task-needed statement.

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
    "id": "I07",
    "name": "Actor is not execution profile",
    "rule": "A child may execute in the parent Windows profile, but actor identity, allowed games, schedule, time budget and endpoint permissions remain those of the child. Shared Windows identity does not transfer privilege."
  },
  {
    "id": "I08",
    "name": "Effects are generation-fenced",
    "rule": "Stop, launch, reveal, input enablement and status callbacks must match immutable host/actor/execution/session-operation identity. Old results cannot mutate a newer session. A device ID or game name alone is not a fence."
  },
  {
    "id": "I13",
    "name": "Preparation order",
    "rule": "Wake when needed, await Gateway, authorize device/actor/execution profile, confirm the correct interactive Windows session, ensure profile components, await streamer, establish one stream. Streamer availability must not gate the login needed to start it."
  },
  {
    "id": "I14",
    "name": "Positive sign-in proof",
    "rule": "Only confirmed READY for the requested authorized Windows identity continues unattended launch. Null, timeout, denied, missing credentials, wrong active identity and cancelled work are not READY. No automatic retry loop using a rejected password."
  },
  {
    "id": "I21",
    "name": "No security downgrade for compatibility",
    "rule": "Unsupported host protocol returns an explicit restricted/unavailable path, never silent downgrade of child enforcement, correlation, permissions, TLS or readiness. Reuse existing version/capability machinery where feasible."
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
    "id": "F01",
    "name": "Launch from sleeping or locked host",
    "steps": [
      "Accept latest explicit game intent and immutable actor/execution context.",
      "Wake and run authorized positive-proof preparation in I13 order.",
      "Create or reuse one owned transport; launch the authorized target once.",
      "Cover until coherent target readiness and fresh current video; then enable permitted input."
    ],
    "postconditions": [
      "Target runs under the intended Windows profile.",
      "No user interaction when stored approval/credentials and dependencies are valid.",
      "Missing approval or readiness fails explicitly without a hidden fallback."
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
  },
  {
    "id": "F07",
    "name": "Child gameplay and expiry",
    "steps": [
      "Use child actor context with the parent Windows execution identity.",
      "Apply the same retained-stream launch/home/switch semantics while enforcing child game eligibility.",
      "Deny Discord, child-account management and all child-library mutation at the API as well as UI.",
      "On expiry/schedule end/revocation, block input/reconnect and stop exactly the bound game and stream on client and host."
    ],
    "postconditions": [
      "Budget and schedule survive switches and client loss.",
      "No delayed child operation affects a later authorized parent session.",
      "No inherited parent-management or Discord privileges."
    ]
  }
]
```

## Selected decisions

```json
[
  {
    "id": "D05",
    "status": "REQUIRED",
    "topic": "Other Windows identity active",
    "value": "No implicit takeover, logoff or account switching beyond configured user authorization. Credentials remain in the existing broker/provider trust boundary. S00 documents the authorized device/profile mapping."
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
    "id": "H07",
    "priority": "P1",
    "title": "Preflight waits for streamer before enabling login",
    "symbols": [
      "HostLaunchPreflight.runInternal",
      "ConsoleActivity.createHostLaunchPreflight",
      "HostReadiness.await"
    ],
    "inherited_static_observation": "Streamer readiness can gate the Windows sign-in that starts per-profile components.",
    "repair_contract": "Gateway availability, authorized Windows session and streamer readiness need ordered separate stages.",
    "evidence_status": "INHERITED_STATIC_AUDIT_REQUIRES_REVALIDATION",
    "runtime_reproduced": false
  },
  {
    "id": "H08",
    "priority": "P1",
    "title": "Missing or timed-out sign-in can look ready",
    "symbols": [
      "HostLaunchPreflight.awaitInteractiveSession",
      "HostLaunchPreflight.allowsManualSignIn"
    ],
    "inherited_static_observation": "null represents continue but is also returned for some unknown states and expired waiting.",
    "repair_contract": "Typed outcomes; only positive correct-identity READY permits automatic continuation.",
    "evidence_status": "INHERITED_STATIC_AUDIT_REQUIRES_REVALIDATION",
    "runtime_reproduced": false
  }
]
```

## Selected tests

```json
[
  {
    "id": "T01",
    "title": "Cold launch with wake and Windows sign-in",
    "given": "Host asleep; authorized device and requested Windows profile have stored valid credentials; game permitted.",
    "when": "Select Play once.",
    "must_observe": [
      "Ordered WoL → Gateway → authorized interactive session → profile components → streamer → one transport → one game execution.",
      "Correct game becomes interactive only through canonical readiness/frame gate."
    ],
    "must_not_observe": [
      "A streamer-port prerequisite before necessary sign-in.",
      "Duplicate login/game/transport from retries."
    ],
    "code_evidence": [
      "unit: ordered preparation effects",
      "integration: broker/profile/streamer handoff"
    ],
    "device_evidence": [
      "authorized Windows host + target Android TV; candidate release build"
    ],
    "evidence_note": "Capture actual command/result or observed effects; this is an expected oracle, not a passed test."
  },
  {
    "id": "T29",
    "title": "Gateway before streamer",
    "given": "Gateway and broker available, correct user not yet logged in, streamer absent.",
    "when": "Launch and warm-up in separate runs.",
    "must_observe": [
      "Authorized sign-in is attempted without streamer-port readiness; streamer checked only after correct session/components."
    ],
    "must_not_observe": [
      "Circular readiness deadlock."
    ],
    "code_evidence": [
      "unit: ordered stage fake",
      "host integration: pre-login service arrangement"
    ],
    "device_evidence": [
      "authorized Windows host + target Android TV; candidate release build"
    ],
    "evidence_note": "Capture actual command/result or observed effects; this is an expected oracle, not a passed test."
  },
  {
    "id": "T30",
    "title": "No approval or invalid credentials",
    "given": "Cases: unapproved device, missing stored password, rejected password.",
    "when": "Attempt unattended launch and automatic preparation.",
    "must_observe": [
      "Explicit denial/action-needed; no game dispatch without correct-session READY.",
      "No retry loop of rejected credentials, no credential leakage."
    ],
    "must_not_observe": [
      "Silent parent fallback or bypassing device approval."
    ],
    "code_evidence": [
      "unit: typed outcomes",
      "host integration: permission/broker rejection"
    ],
    "device_evidence": [
      "authorized Windows host + target Android TV; candidate release build"
    ],
    "evidence_note": "Capture actual command/result or observed effects; this is an expected oracle, not a passed test."
  },
  {
    "id": "T31",
    "title": "Different active Windows user",
    "given": "An unrelated Windows user is interactive; desired profile differs.",
    "when": "Request launch under the configured profile.",
    "must_observe": [
      "Respect configured authorized switching policy or return explicit action-needed."
    ],
    "must_not_observe": [
      "Implicit logoff/takeover or treating any interactive user as ready."
    ],
    "code_evidence": [
      "host integration: profile identity policy",
      "unit: client identity gate"
    ],
    "device_evidence": [
      "authorized Windows host + target Android TV; candidate release build"
    ],
    "evidence_note": "Capture actual command/result or observed effects; this is an expected oracle, not a passed test."
  },
  {
    "id": "T32",
    "title": "Sign-in timeout/null/cancel/late success",
    "given": "Preflight can emit missing session, expired deadline, cancellation and delayed success.",
    "when": "Exercise each ordering with a fake monotonic clock.",
    "must_observe": [
      "Only positive READY for current requested identity continues.",
      "Cancelled/superseded generation never launches on late success."
    ],
    "must_not_observe": [
      "Null/timeout masquerading as success."
    ],
    "code_evidence": [
      "unit: all typed outcomes and stale completion"
    ],
    "device_evidence": [],
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
