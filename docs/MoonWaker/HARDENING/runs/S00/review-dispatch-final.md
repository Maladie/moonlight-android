# MoonWaker bounded task dispatch

```json
{
  "task_id": "S00",
  "role": "reviewer",
  "review_stage": "CODE",
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
  "expected_input_fingerprint": "sha256:70df35295a70dd9bc033c1adb7d8571cc0b67de7dde09e14d10066f5a964d8a7",
  "resource_locks": [
    "audit-contract"
  ],
  "source_hints": [
    "docs/MoonWaker/HARDENING/BRANCHING.md",
    "AGENTS.md",
    "CODEX_WORKFLOW.md",
    "MOONWAKER_ARCHITECTURE.md",
    "CHILD_PROFILES_DESIGN.md",
    "app/src/main/java/com/limelight/Game.java",
    "app/src/main/java/com/limelight/console/ConsoleActivity.java",
    "host-services/gateway/wakeplay_gateway.py",
    "host-services/bridges/playnite/GameProviderBridge.py"
  ],
  "write_scope_note": "Named responsibility, its state/effects, all direct production callers, required test/build/packaging changes and assigned run evidence. No unrelated formatting, dependency upgrades or replacement framework.",
  "pre_edit_contract_review": false,
  "blocked_reasons": [],
  "scenario_assertion_scope": "Complete independently usable outcome in this task. Unfixed pre-existing findings assigned later may remain listed, but no behavior introduced or changed here may depend on a future task to work.",
  "independently_usable_outcome": "No gameplay change is claimed: the current product still builds; repeatable baseline and explicit checkpoint evidence are available.",
  "decomposition_requirement": "Map responsibilities in Game, ConsoleActivity, Gateway and provider bridge; identify existing owners, state, effects, callers, packaging and tests before naming any new class.",
  "test_risk": "SETUP_ONLY",
  "mandatory_real_device_topics": [],
  "dependency_handoffs": {},
  "this_task_state": {
    "status": "REVIEW_PENDING",
    "assigned_run": "S00-worker-20260909T0720Z",
    "input_fingerprint": "sha256:70df35295a70dd9bc033c1adb7d8571cc0b67de7dde09e14d10066f5a964d8a7",
    "output_fingerprint": "sha256:db6880c0f0597149222d926db820b8438b2ca836d046cdb8c86dac8aacc7a387",
    "implementation_report": "runs/S00/implementation.json",
    "review_report": "runs/S00/review.json",
    "design_review_report": null,
    "blockers": [],
    "checkpoint_plan": "runs/S00/checkpoint-plan.json"
  },
  "contract_amendments": []
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

# Independent reviewer — fresh actual context
Inspect actual source/diff and direct production wiring before adopting the author's summary. Verify input/output fingerprints and required-check plan. Review stage DESIGN before edits where required, CODE for integrated behavior/architecture, RELEASE for final exact artifacts/device evidence.
Try to falsify the claim: "We can stop after this slice and use the app; no later task is needed to finish introduced changes." Reject unused new APIs, missing consumers, new baseline failures, hidden implementations, missing package modules and deferred migration debt. A later original defect can remain only if outside this outcome and demonstrably not worsened.
Check one owner for each moved responsibility, state/effects/caller migration, removed legacy paths, Activity lifetime and no whole-Activity helper. Preserve canonical state machines, authentication, child authority and native lifecycle rules. Review compatibility and effect-time fencing, not just schema fields.
Run independent high-value checks and inspect executable evidence. A missing required lab result is not an accepted checkpoint. Unit flags/counters on mocks cannot prove real stream continuity, secure Windows sign-in or display/input behavior. Check seed/event trace for concurrency claims and zero-test/skip traps.
Write review.json with behavioral_verdict and architecture_verdict, actual independent run ID, reviewed plan/source fingerprints and findings. Source stays read-only; corrections return to worker and need new evidence/review. ACCEPT requires whole-slice completion and all mandatory checkpoint checks, not just positive code review.

Verify the candidate is on the recorded work branch and its base comes from the observed named base according to BRANCHING.md. Branch-check is a local consistency guard, not remote-fetch proof.

# S00 — Bind the baseline, checkpoint gates and safe test environment

## Integrated outcome
No gameplay change is claimed: the current product still builds; repeatable baseline and explicit checkpoint evidence are available.

## Required work
Require the coordinator's completed BRANCHING.md setup and runs/branch-setup.json before editing test/build files. Run packet.py branch-check --repo .; verify the new work branch, pinned base/initial HEAD and dirty baseline. Revalidate each H01–H11 at current callers. Read applicable repository instructions and map all seven flows to owners and tests. Inspect safe streamer termination capabilities and actor/execution/transport binding before assuming per-session APIs. Resolve product decisions D01–D09 with existing policy or explicit bounded blockers.
Create the responsibility map and original-defect register. Inspect existing JVM, Python and PowerShell suites; classify source-text checks separately from behavioral tests. Bind executable local baseline/build commands, expected test discovery and safe configuration. Local execution is the deliverable; GitHub Actions is not required. Use the shipped runner for observed results, not invented test totals.
Set up the smallest test/build-only scaffold needed for incremental checks: verified run plans, targeted production-behavior tests, and Android instrumentation configuration where toolchain-compatible. Do not restructure product owners in S00. Establish protected F01–F07 smoke contracts using actual entrypoints and record exact baseline failures; missing live lab access is explicit, not fabricated baseline success. Remaining device-dependent implementation slices cannot be accepted without their required lab evidence.
Describe the authorized lab contract: exact device/host identity, trusted source, allow-listed synthetic titles/profiles, operations permitted, timeouts, cleanup, artifact redaction. Packet possession is not authorization. Provisioning/installing services or forcing login is excluded.
The outcome is unchanged working product plus repeatable verification infrastructure. Report independently accepted setup evidence; do not claim H fixes or full desired flow compliance.

## Required architecture outcome
Map responsibilities in Game, ConsoleActivity, Gateway and provider bridge; identify existing owners, state, effects, callers, packaging and tests before naming any new class.

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
    "id": "I12",
    "name": "Canonical privacy gate",
    "rule": "LaunchTransitionController stays the reveal/input gate. Preserve artwork and existing privacy UX. Parent manual reveal remains an escape hatch, but cannot bypass terminal child denial or grant restricted desktop access."
  },
  {
    "id": "I18",
    "name": "Process and session safety",
    "rule": "Host destructive operations target verified process identity and the bound stream/session. Revalidate immediately before the effect. No name-wide kills, arbitrary desktop automation or delayed global quit that can affect a replacement session."
  },
  {
    "id": "I21",
    "name": "No security downgrade for compatibility",
    "rule": "Unsupported host protocol returns an explicit restricted/unavailable path, never silent downgrade of child enforcement, correlation, permissions, TLS or readiness. Reuse existing version/capability machinery where feasible."
  },
  {
    "id": "I24",
    "name": "No false acceptance",
    "rule": "Unit/integration success is not physical TV/Windows validation. Native transport counts, host process/session observations and actual frame/input checks have distinct evidence requirements."
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
    "id": "D01",
    "status": "PROVISIONAL_DEFAULT",
    "topic": "Retention default",
    "value": "Align absent-setting behavior in release/debug with the verified product retention policy and preserve explicit values including disabled. The inherited debug default of 5 minutes is only an interim migration candidate, NOT user approval of a new timeout. S00 must record a documented/user-authorized policy or leave D01 PRODUCT_POLICY_PENDING. S02 may implement the parity/migration seam and clearly retain the interim value without calling F05 release-accepted. Do not introduce time-based disconnection on app Home/host-picker merely to reuse the background-TV preference. S10 cannot certify F05 until its duration/policy is explicit and consistent with the user requirement."
  },
  {
    "id": "D02",
    "status": "PROVISIONAL_DEFAULT",
    "topic": "Concurrent game intents",
    "value": "Before host dispatch, latest explicit Play intent wins. After host effects start, serialize cancellation/reconciliation before the next target. Do not queue multiple duplicate launches or detach from a still-active stop."
  },
  {
    "id": "D03",
    "status": "PROVISIONAL_DEFAULT",
    "topic": "Child accounting",
    "value": "Running child game continues consuming time on Home. Neutral prelaunch warm-up consumes none. Preserve existing explicit parent-selected stricter policy; S00 records the rule, not invent a new billing/time system."
  },
  {
    "id": "D04",
    "status": "REQUIRED",
    "topic": "Host cannot safely target a stream",
    "value": "Inspect actual streamer capabilities. Prefer existing session-scoped termination. If only host-wide quit exists, require a single authoritative generation guard and serialized replacement barrier covering all relevant launch paths; otherwise block the unsafe mode and report the capability gap. Never claim per-session termination based on a local-only token."
  },
  {
    "id": "D05",
    "status": "REQUIRED",
    "topic": "Other Windows identity active",
    "value": "No implicit takeover, logoff or account switching beyond configured user authorization. Credentials remain in the existing broker/provider trust boundary. S00 documents the authorized device/profile mapping."
  },
  {
    "id": "D06",
    "status": "REQUIRED",
    "topic": "Protocol evolution",
    "value": "Prefer a minimal extension of existing capability/schema machinery over a new protocol framework. Older peers must not silently downgrade safety. Explain compatible paths and unsupported paths; never pretend older builds meet this release contract."
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
    "id": "H01",
    "priority": "P0",
    "title": "Child time expiry retains stream",
    "symbols": [
      "Game.java::finishChildSession",
      "Game.java::endManagedGameKeepingStream",
      "GameProviderBridge.py::enforce_child_session",
      "wakeplay_gateway.py::_child_time_finish"
    ],
    "inherited_static_observation": "Client follows keep-stream termination and host enforces game process, not the stream.",
    "repair_contract": "Terminal child expiry must disconnect the bound stream without affecting a newer parent session.",
    "evidence_status": "INHERITED_STATIC_AUDIT_REQUIRES_REVALIDATION",
    "runtime_reproduced": false
  },
  {
    "id": "H02",
    "priority": "P1",
    "title": "Child full quit uses parent-style Gateway snapshot",
    "symbols": [
      "Game.java::stopVerifiedProviderGame",
      "PlayniteTransitionGateway.connect",
      "PlayniteTransitionGateway.snapshot"
    ],
    "inherited_static_observation": "The overload defaults childSession=false and snapshot reads health forbidden to a child.",
    "repair_contract": "Propagate actor/execution context; do not expand child endpoint permission to hide a caller bug.",
    "evidence_status": "INHERITED_STATIC_AUDIT_REQUIRES_REVALIDATION",
    "runtime_reproduced": false
  },
  {
    "id": "H03",
    "priority": "P1",
    "title": "Full quit waits for host before local disconnect",
    "symbols": [
      "Game.java::terminateWholeSessionVerified",
      "Game.java::verifySunshineSessionStopped",
      "Game.java::finishWholeSessionTermination"
    ],
    "inherited_static_observation": "Host verification/polling precedes local stop, and failure can retain the stream.",
    "repair_contract": "Separate unconditional local stop from asynchronous fenced host cleanup and honest status.",
    "evidence_status": "INHERITED_STATIC_AUDIT_REQUIRES_REVALIDATION",
    "runtime_reproduced": false
  },
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
  },
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
  },
  {
    "id": "H06",
    "priority": "P1",
    "title": "Game identity mismatch invalidates transport projection",
    "symbols": [
      "SessionStateResolver.resolve",
      "ConsoleActivity.resolveSessionSnapshot",
      "ConsoleActivity.hostStatus",
      "ConsoleActivity.consoleHostState"
    ],
    "inherited_static_observation": "retainedLive depends on gameId agreement, and multiple projections can turn unknown game into idle host.",
    "repair_contract": "Independent axes and one derived projection for badge, tile and launch decisions.",
    "evidence_status": "INHERITED_STATIC_AUDIT_REQUIRES_REVALIDATION",
    "runtime_reproduced": false
  },
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
  },
  {
    "id": "H09",
    "priority": "P1",
    "title": "Stop callbacks can precede actual native stop",
    "symbols": [
      "Game.java::stopConnection(Runnable)",
      "Game.java::finishWholeSessionTermination"
    ],
    "inherited_static_observation": "Flags clear before background stop; later callers or upper completion can finish early.",
    "repair_contract": "Shared actual stop result per generation, tested with a deliberately blocked stop.",
    "evidence_status": "INHERITED_STATIC_AUDIT_REQUIRES_REVALIDATION",
    "runtime_reproduced": false
  },
  {
    "id": "H10",
    "priority": "P1",
    "title": "Readiness combines different host moments",
    "symbols": [
      "PlayniteTransitionGateway.snapshot",
      "ConsoleStreamTransitionCoordinator.applySnapshot"
    ],
    "inherited_static_observation": "Separate health/current/readiness responses lack a shared host revision.",
    "repair_contract": "Coherent atomically versioned observation and epoch fencing, not only local transition IDs.",
    "evidence_status": "INHERITED_STATIC_AUDIT_REQUIRES_REVALIDATION",
    "runtime_reproduced": false
  },
  {
    "id": "H11",
    "priority": "P2",
    "title": "Cover callback can be lost with view detachment",
    "symbols": [
      "ConsoleStreamLoadingView.doAfterNextFrame",
      "StreamHomeActivity.onCreate"
    ],
    "inherited_static_observation": "Two postOnAnimation callbacks do not prove frame submission; detachment can drop continuation.",
    "repair_contract": "Explicit frame barrier outcomes, canonical reveal gate and terminal cleanup independent of drawing.",
    "evidence_status": "INHERITED_STATIC_AUDIT_REQUIRES_REVALIDATION",
    "runtime_reproduced": false
  }
]
```

## Selected tests

```json
[
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
  }
]
```

## Required output
Use templates/TASK_RESULT.json (worker) or templates/REVIEW_RESULT.json (reviewer). Read actual dependency reports referenced above. Resolve paths relative to docs/MoonWaker/HARDENING. Do not load the full reference audit or unrelated tasks by default. Do not mark a task accepted yourself as worker.
