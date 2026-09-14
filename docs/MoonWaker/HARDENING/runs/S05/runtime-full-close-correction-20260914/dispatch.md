# MoonWaker bounded task dispatch

```json
{
  "task_id": "S05",
  "role": "worker",
  "review_stage": null,
  "mode": "PREVIEW_NOT_EXECUTION_AUTHORIZATION",
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
  "expected_input_fingerprint": "sha256:63435284b9fc9879c9c410ebbdf2636fda1f0530b165b12f683b6bd3ef2b68aa",
  "resource_locks": [
    "gateway-api",
    "provider-bridge",
    "host-child-policy",
    "android-game",
    "android-console",
    "retained-runtime"
  ],
  "source_hints": [
    "host-services/gateway/wakeplay_gateway.py",
    "host-services/bridges/playnite/GameProviderBridge.py",
    "host-services/bridges/",
    "app/src/main/java/com/limelight/Game.java",
    "app/src/main/java/com/limelight/console/SessionOrchestrator.java",
    "app/src/main/java/com/limelight/console/ConsoleActivity.java",
    "app/src/main/java/com/limelight/stream/RetainedStreamSessionCoordinator.java",
    "app/src/main/java/com/limelight/ui/overlay/"
  ],
  "write_scope_note": "Named responsibility, its state/effects, all direct production callers, required test/build/packaging changes and assigned run evidence. No unrelated formatting, dependency upgrades or replacement framework.",
  "pre_edit_contract_review": true,
  "blocked_reasons": [
    "unaccepted dependencies: ['S04']"
  ],
  "scenario_assertion_scope": "Complete independently usable outcome in this task. Unfixed pre-existing findings assigned later may remain listed, but no behavior introduced or changed here may depend on a future task to work.",
  "independently_usable_outcome": "Whole quit disconnects locally regardless of RPC success; child expiry is terminal on Android and host; safe subsequent Play still works.",
  "decomposition_requirement": "Extract MoonWaker termination orchestration and client-side child-terminal reactions out of Game; isolate host effect-time authorization/termination policy from HTTP routing while reusing authoritative lease/journal owners.",
  "test_risk": "DEVICE_CRITICAL",
  "mandatory_real_device_topics": [
    "Parent/child full quit, game-only retain, child expiry with Android absent, stale cleanup versus new session"
  ],
  "dependency_handoffs": {
    "S04": {
      "status": "CODE_VERIFIED_DEVICE_PENDING",
      "assigned_run": "S04-worker-20260910T0820Z",
      "input_fingerprint": "sha256:c3d825c397e9c7a38296c8a0d0409da5066f419174b6393ff61d89927dfb7800",
      "output_fingerprint": "sha256:dedbbd549d3fb7f24caa80154a1de701f6ceaacaa91f5225c6af000e30f623d8",
      "implementation_report": "runs/S04/implementation.json",
      "review_report": "runs/S04/review.json",
      "design_review_report": null,
      "blockers": [
        "The user reports S04-LAB-NATIVE-STOP-RECONNECT passed functionally, but the available post-install TV trace contains no attributable native stop/reconnect sequence. Candidate-bound native ordering evidence remains required for checkpoint acceptance."
      ],
      "checkpoint_plan": "runs/S04/checkpoint-plan.json"
    }
  },
  "this_task_state": {
    "status": "CODE_VERIFIED_DEVICE_PENDING",
    "assigned_run": "s05-runtime-switch-correction-20260914",
    "input_fingerprint": "sha256:63435284b9fc9879c9c410ebbdf2636fda1f0530b165b12f683b6bd3ef2b68aa",
    "output_fingerprint": "sha256:d831297638e49f5b9a17739254cf907a5d39375d099022074d34f05000ee7b03",
    "implementation_report": "runs/S05/runtime-switch-correction-20260914/implementation.json",
    "review_report": "runs/S05/runtime-switch-correction-20260914/review.json",
    "design_review_report": null,
    "blockers": [
      "The first physical child retained A-to-B switch failed because the deployed Gateway kept the exact stopped process token in the retained stream record after a successful verified stop.",
      "The source correction is independently reviewed with no findings and passes Gateway 92/92 required, 128/128 directly related and 294/294 full discovery, plus Android targeted tests and NonRoot Debug 1113/1113 on JDK 17.",
      "Gateway 0.7.116 was delivered through MoonWakerHotUpdater; the package, installed source and /api/v1/hello hashes match the reviewed repository source. Android production source did not change, so no APK was rebuilt or installed.",
      "Delivery evidence is recorded in runs/S05/runtime-switch-correction-20260914/delivery.json.",
      "S05-LAB-CHILD-RETAINED-A-TO-B and all remaining candidate-bound Windows/TV functional acceptance must be repeated against the installed correction."
    ],
    "checkpoint_plan": "runs/S05/runtime-switch-correction-20260914/checkpoint-plan.json"
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

# S05 — Complete local and host termination, including child expiry

## Integrated outcome
Whole quit disconnects locally regardless of RPC success; child expiry is terminal on Android and host; safe subsequent Play still works.

## Required work
Before editing, independently review actual host stop capabilities, actor lease epoch, game/process identity, transport generation, effect-time serialization and compatibility. Never invent a per-session streamer API; a global quit needs a real authoritative barrier covering all launch paths or the unsafe capability remains blocked.
Extract MoonWaker session termination/child-terminal orchestration from Game into a narrow existing/new owner; Activity forwards intents/presents outcomes. On the host, isolate termination/authorization policy from HTTP dispatch but keep existing authoritative lease/accounting/journal ownership.
Explicit full quit and terminal child expiry/revocation immediately block local input, request privacy, start actual local stop and navigate Home without waiting on RPC or a drawable view. Separately reconcile safely fenced host game+stream cleanup. Failure cannot resurrect local transport or manufacture Online. Return-Home cannot auto-warm-up until new explicit allowed intent.
Host enforcement must work with Android absent. Ordinary game stop/switch retain; budget/schedule/revocation disconnect. Revalidate exact process identity and actor epoch at the destructive effect. Old expiry must not terminate a newer parent session even on the same device/transport. Retries/lost responses reconcile the same operation, not a replacement.
Wire Android + Gateway + actual streamer/Bridge paths and advertised capabilities together. Test old/new combinations, actual entrypoints, no-view termination, no-RPC-response, game-only retain, time continuity, stale cleanup and immediate subsequent Play. Perform the required child-without-client host/TV proof. A completed host endpoint with an unchanged Android path is not accepted.

## Required architecture outcome
Extract MoonWaker termination orchestration and client-side child-terminal reactions out of Game; isolate host effect-time authorization/termination policy from HTTP routing while reusing authoritative lease/journal owners.

## Completion gate
Follow CHECKPOINTS.md and the input-bound checkpoint plan. All touched production paths and packaging work now. Add focused behavioral regressions, run the seven-flow protected baseline and cumulative fixed-path tests, and obtain independent behavioral + architecture review. Missing mandatory device evidence is CODE_VERIFIED_DEVICE_PENDING, not an accepted checkpoint. Save gate/architecture/compatibility evidence and the no-future-task-needed statement.

## Non-goals
No unrelated rewrite, generic framework, duplicate authoritative state, source-only behavioral proof, production debug bypass or unauthorized deployment. Later slices may improve separate existing defects but must not complete this slice's introduced half-working behavior.

## 2026-09-13 child-facade correction

The child profile is now treated as policy and presentation over the parent's
single execution lifecycle. Android uses the common launch, retained switch,
recovery/retry, quit and hard-reset owners. A permitted already-running game is
adopted without restarting its process; a different permitted game switches on
the retained stream; a hidden/disallowed running game is closed through the
common exact fenced full-close path before the requested permitted game starts.
Actor changes preserve the execution profile, stream ID, app ID, process
identity, control generation and actor lease epoch while moving the child-time
attachment. Parent-to-child, child-to-parent and child-to-child transfers are
durable actor handoffs: the stream actor, generation and child-time attachment
move under one fence, and a failed second policy check leaves the previous
actor metered. Child suspend holds that same exact session/stream/generation/
actor-epoch fence through scheduling the common suspend effect and releasing
the time attachment, so a concurrent handoff cannot be suspended by a stale
actor.

Gateway child sessions account for time only. Playnite Bridge no longer owns a
child session, lease, heartbeat, end endpoint or child-specific process
lifecycle. Terminal child events join the same whole-session termination owner.
Housekeeping retries `cleanup_required` with the original terminal reason and
the exact durable operation instead of releasing time and leaving an uncertain
control barrier. Every normal, retained-switch, end-game and transition-cancel
provider stop carries the observed game ID and exact process token to
`/game/stop-verified`; a complete process scan may retire the old token without
touching a replacement process. Transition-start compensation additionally
binds that token to the exact provider owner when it is first observed and
refuses cleanup if a later process of the same game has a different token.
The public child action surface rejects legacy `/game/stop`; it cannot bypass
the verified common stop path.

For an already-running child attachment, housekeeping treats a replacement
process as a terminal shared-stream event: a complete scan retires only the old
token, never stops the replacement, and finishes the exact stream cleanup.

Automated evidence for the current source fingerprint is recorded in
`runs/S05/unified-child-lifecycle-20260913/implementation.json`: requested
Gateway 82/82, full Gateway 284/284, Playnite Bridge 267/267 and Android
NonRoot Debug 1104/1104 on JDK 17. These results are code evidence only. No APK,
host package or installer was deployed in this correction. A NonRoot Debug APK
and MoonWaker Host installer 0.7.112 were built after the independent review and
verified against the same source fingerprint; exact paths, hashes, signatures,
versions and embedded-payload checks are recorded in
`runs/S05/unified-child-lifecycle-20260913/artifacts.json`. The reviewed APK was
subsequently installed and launched on `BRAVIA_4K_VH22`; the installed APK hash
matches the built artifact. This is delivery evidence only, recorded in
`runs/S05/unified-child-lifecycle-20260913/tv-apk-deployment.json`. No host
component was deployed and the mandatory candidate-bound Windows/TV functional
acceptance remains pending. Independent
adversarial review accepted the exact recorded source fingerprint; the reviewer
also repeated the requested Gateway set at 82/82 but did not independently
complete the Android run.

## 2026-09-14 retained handoff, terminal join and library isolation correction

This correction starts from `e049e9f8` and keeps the child profile as a facade
over the one parent execution lifecycle. After Gateway successfully commits a
fenced reauthorization, Android may transfer the retained provider owner across
actors only with a one-shot authorization bound to the exact previous owner,
replacement transition, host, stream ID, Sunshine app ID, Playnite game ID,
execution profile, process token/PID, previous and new control generation, and
new actor lease epoch. The ordinary cross-actor transfer remains rejected. A
successful parent-to-child, child-to-parent or child-to-child handoff neither
stops nor restarts the provider, process or retained stream.

Gateway terminal cleanup now reserves the exact child session before any slow
provider observation. Reservation and publication of a new time attachment use
the same lock, so a start cannot pass between an already-started end/full-close
and its single-flight cleanup. A parent full-close joins that exact terminal
operation and aliases only its terminal receipt, including matching generation
and actor lease epoch. Retry reuses the same durable permit and all process,
stream, app, execution and actor fences. A separately recorded
`provider_idle_confirmed` state lets a launch-pending session retry an uncertain
stream close without falsely comparing the empty observed provider identity to
the requested game. The terminal marker is cleared after joined completion, so
the next start is immediately available without hard reset.

Android no longer sends `/child/session/end` for a structured start rejection.
Only an accepted exact attachment is ended; an ambiguous response is first
resolved by status. There are still no normal-lifecycle child bind or renew
calls, and child end releases time accounting rather than creating a separate
provider/stream owner.

The active Playnite projection is bound to the full `HostProfileKey`. Profile
changes clear the projection, rendered items, pending artwork generation and
session presentation immediately. Cache fast paths, delayed library callbacks,
tile rendering, metadata and artwork requests/apply paths all require the exact
captured host/profile key. The host-selection screensaver uses its own explicit
captured key, preserving its cache behavior without treating that cache as the
active library projection.

Independent adversarial review initially found five issues: an idle-provider
retry trap, a start/terminal reservation race, a missing actor-epoch check on a
terminal alias, source-only regression gaps, and a screensaver cache regression.
All five were corrected. The final automated evidence is recorded in
`runs/S05/runtime-race-correction-20260914/implementation.json` and the review
record in `runs/S05/runtime-race-correction-20260914/review.json`. Gateway passes
the requested 89/89 tests, the directly related 125/125 tests, and full discovery
291/291. Android targeted regressions pass 280/280 and the full NonRoot Debug
unit suite passes 1113/1113 on JDK 17. The final source fingerprint is
`sha256:01240b077d6186b623c67a5afcf400eb0665ac9a01dac13c382b62581cc6ad97`.

At the code-verification gate no artifact had been built or deployed for this
fingerprint. The subsequently authorized delivery is recorded below. Physical
Windows/TV functional acceptance remains mandatory and pending.

## 2026-09-14 authorized APK and Gateway hot-update delivery

After explicit authorization, the exact reviewed source fingerprint
`sha256:01240b077d6186b623c67a5afcf400eb0665ac9a01dac13c382b62581cc6ad97`
was packaged and delivered. The NonRoot Debug APK was built on JDK 17, its v1
and v2 signatures were verified, and it was installed and launched on the
rediscovered Sony BRAVIA 4K VH22 at `192.168.1.31:5555`. The SHA-256 reported
for the installed `base.apk` exactly matches the local artifact.

Gateway was updated only through the installed `MoonWakerHotUpdater` service.
The monotonic package `0.7.114+2026.09.14` contained only
`gateway/wakeplay_gateway.py` and `gateway/child_terminal_operation.py`.
The updater reported the transaction applied and healthy. Both installed files
match their repository SHA-256 values, the Gateway service is Running, and
`/api/v1/hello` reports the expected new Gateway source hash. The base host
metadata remains 0.7.113 because a hot update intentionally does not replace
`version.json`.

Exact artifact, hash and delivery evidence is recorded in
`runs/S05/runtime-race-correction-20260914/delivery.json`. Installation and app
launch prove delivery only; the candidate-bound parent/child game, stream,
terminal-race and library-isolation acceptance scenarios remain pending.

## 2026-09-14 child retained A-to-B physical-test correction

The first physical child retained-switch attempt exposed a host-state defect.
Gateway successfully completed the exact fenced `/game/stop-verified` operation
for game A, and the child time attachment ended, but `control.stream` still
contained game A's stopped process token. When the Bridge could not provide a
complete idle-process scan, the immediate attachment for game B failed closed
with `retained_process_unconfirmed`. This produced the Android message
"Nie udało się zamknąć poprzedniej sesji hosta" even though the streamer itself
correctly remained ready.

The common Gateway provider-transition completion now retires only the exact
stopped process identity. The successful receipt must match the current client,
actor, execution profile, stream ID, Sunshine app ID, generation, actor lease
epoch, child session, game and process token. Stream ownership, stream ID, app
ID, generation and actor lease epoch remain unchanged, and no provider or
streamer stop is added. Gateway startup also repairs the exact persisted
pre-fix state, but never while another control operation is active. A receipt
for an older process cannot clear a replacement process.

Independent adversarial review accepted fingerprint
`sha256:d831297638e49f5b9a17739254cf907a5d39375d099022074d34f05000ee7b03`
with no findings after checking the process/stream fences and absence of a
child-only lifecycle. Automated validation passes at Gateway 92/92 required,
128/128 directly related and 294/294 full discovery. The three new regressions
pass, Android targeted retained-session tests pass, and the full NonRoot Debug
unit suite remains 1113/1113 on JDK 17.

Evidence is recorded in
`runs/S05/runtime-switch-correction-20260914/implementation.json` and
`runs/S05/runtime-switch-correction-20260914/review.json`. After separate
authorization, Gateway 0.7.116 was packaged with only the corrected
`wakeplay_gateway.py` and applied through `MoonWakerHotUpdater.v1`. The updater
reported a healthy transaction; the installed file and `/api/v1/hello` runtime
hash both match the repository. Android production source did not change, so no
APK was rebuilt or reinstalled. Exact delivery evidence is recorded in
`runs/S05/runtime-switch-correction-20260914/delivery.json`.

Delivery is verified, but the child A-to-B physical test and the remaining
Windows/TV S05 acceptance must still be repeated. S05 remains
`CODE_VERIFIED_DEVICE_PENDING`.

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
    "id": "I06",
    "name": "Child expiry is terminal",
    "rule": "Budget exhaustion, schedule end and authorization revocation block child input and reconnect and disconnect the bound stream; host enforces without a live Android client. Never silently become the parent."
  },
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
    "id": "I17",
    "name": "Durable host authority",
    "rule": "GameOps durable state stays host-authoritative. Child budget does not reset across game switches, retries, service restart or day/clock handling. Use the configured schedule timezone, not an assumed user/device timezone."
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
    "id": "I23",
    "name": "Budget covers retained running game",
    "rule": "Home does not stop charging a still-running child game or reset remaining time. Mere pre-launch warm-up/neutral no-game transport does not consume gameplay budget. Preserve a stricter explicit existing parent policy if present; record it."
  },
  {
    "id": "I04",
    "name": "Game-only stop retains transport",
    "rule": "Stopping the managed game or switching A to B preserves transport identity/generation; never invoke whole-stream quit as a fallback for uncertain game state."
  },
  {
    "id": "I05",
    "name": "Full quit is locally unconditional",
    "rule": "Explicit whole-session quit immediately blocks input and initiates actual local stop without waiting for a host RPC. Host cleanup failure must not resurrect the locally closed transport."
  },
  {
    "id": "I09",
    "name": "One real stop completion",
    "rule": "All callers stopping one transport generation observe the same actual stop completion. Acceptance of a request and clearing connected flags are not completion. Do not start a replacement native transport before the old one is safe to replace."
  },
  {
    "id": "I10",
    "name": "Honest host projection",
    "rule": "ONLINE_IDLE means observed reachable host with no active relevant stream; if absence is unconfirmed, report STOPPING/UNKNOWN, not a fabricated idle state. Distinguish a local stream from another client’s session."
  },
  {
    "id": "I20",
    "name": "Every accepted operation terminates",
    "rule": "Every accepted operation/barrier has exactly one explicit terminal outcome. View detachment, cancellation and supersession cannot silently drop required cleanup or leave a pending result forever."
  },
  {
    "id": "I22",
    "name": "Explicit quit suppresses automatic warm-up",
    "rule": "Returning Home because of full quit must not itself restart warm-up. A fresh explicit Play/Prepare or new intentional host-entry can clear that suppression after old-generation cleanup is safely fenced."
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
  }
]
```

## Selected decisions

```json
[
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
  },
  {
    "id": "D02",
    "status": "PROVISIONAL_DEFAULT",
    "topic": "Concurrent game intents",
    "value": "Before host dispatch, latest explicit Play intent wins. After host effects start, serialize cancellation/reconciliation before the next target. Do not queue multiple duplicate launches or detach from a still-active stop."
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
  }
]
```

## Selected tests

```json
[
  {
    "id": "T11",
    "title": "Lost stop response",
    "given": "Host executes a stop with an operation ID, but its response is lost.",
    "when": "Retry/reconcile that operation after another intent arrives.",
    "must_observe": [
      "Same operation result or observed completion; no second destructive effect on a different generation."
    ],
    "must_not_observe": [
      "Blind global re-quit that closes the new stream."
    ],
    "code_evidence": [
      "host integration: response loss/idempotency"
    ],
    "device_evidence": [],
    "evidence_note": "Capture actual command/result or observed effects; this is an expected oracle, not a passed test."
  },
  {
    "id": "T17",
    "title": "Child switch preserves used time",
    "given": "Child has partially consumed budget; A running.",
    "when": "Switch A→B and retry an already accepted switch.",
    "must_observe": [
      "Spent/remaining time conserved according to policy; no new daily budget.",
      "Same transport, new verified game execution."
    ],
    "must_not_observe": [
      "Resetting time on game switch or request retry."
    ],
    "code_evidence": [
      "host unit/integration: lease/accounting clock"
    ],
    "device_evidence": [],
    "evidence_note": "Capture actual command/result or observed effects; this is an expected oracle, not a passed test."
  },
  {
    "id": "T18",
    "title": "Child budget expiry in game and on Home",
    "given": "Child lease active and close to budget limit; test both visible game and Home.",
    "when": "Advance authoritative time to expiry.",
    "must_observe": [
      "Input/reconnect denied; bound game and stream terminated on host, local transport stopped when client exists.",
      "No dependency on drawing a cover."
    ],
    "must_not_observe": [
      "Retained neutral stream after terminal expiry."
    ],
    "code_evidence": [
      "unit: terminal dispatch",
      "host integration: expiry enforcement"
    ],
    "device_evidence": [
      "authorized Windows host + target Android TV; candidate release build"
    ],
    "evidence_note": "Capture actual command/result or observed effects; this is an expected oracle, not a passed test."
  },
  {
    "id": "T19",
    "title": "Schedule end with remaining budget",
    "given": "Child has remaining daily budget but permitted time window ends.",
    "when": "Cross schedule boundary.",
    "must_observe": [
      "Same terminal denial/stream cleanup as budget exhaustion."
    ],
    "must_not_observe": [
      "Continued stream because budget remains positive."
    ],
    "code_evidence": [
      "host unit/integration: controllable schedule clock"
    ],
    "device_evidence": [
      "authorized Windows host + target Android TV; candidate release build"
    ],
    "evidence_note": "Capture actual command/result or observed effects; this is an expected oracle, not a passed test."
  },
  {
    "id": "T20",
    "title": "Host enforcement without Android",
    "given": "Child game and stream are bound; Android unavailable or network to app interrupted.",
    "when": "Allow budget/window to expire.",
    "must_observe": [
      "Host independently stops exact game and session; later client resume denied."
    ],
    "must_not_observe": [
      "Dependence on Android timer/UI callback for enforcement."
    ],
    "code_evidence": [
      "host integration: no-client lease expiry"
    ],
    "device_evidence": [
      "authorized Windows host + target Android TV; candidate release build"
    ],
    "evidence_note": "Capture actual command/result or observed effects; this is an expected oracle, not a passed test."
  },
  {
    "id": "T21",
    "title": "Stale child expiry after parent reauthorization",
    "given": "Same device, possibly same physical transport; parent has a new authorized binding after child.",
    "when": "Deliver delayed expiry/cleanup belonging to the child.",
    "must_observe": [
      "Parent execution/stream survives; authorization epoch prevents stale effect."
    ],
    "must_not_observe": [
      "Device-ID-only or unchanged transport-generation check terminating parent."
    ],
    "code_evidence": [
      "host integration: rebind race",
      "unit: client stale callbacks"
    ],
    "device_evidence": [
      "authorized Windows host + target Android TV; candidate release build"
    ],
    "evidence_note": "Capture actual command/result or observed effects; this is an expected oracle, not a passed test."
  },
  {
    "id": "T22",
    "title": "Child forbidden APIs",
    "given": "Authenticated child, including after previously using parent UI/session.",
    "when": "Call Discord, create/update child, and add/update games in own/other child library directly.",
    "must_observe": [
      "Host rejects each unauthorized operation; UI hides/blocks access separately.",
      "Child permitted gameplay/stop endpoints continue to work."
    ],
    "must_not_observe": [
      "Security only through hidden buttons.",
      "Inherited parent management token or desktop access."
    ],
    "code_evidence": [
      "host integration: actual route/auth handlers",
      "unit: UI permissions"
    ],
    "device_evidence": [],
    "evidence_note": "Capture actual command/result or observed effects; this is an expected oracle, not a passed test."
  },
  {
    "id": "T35",
    "title": "Revoke game during child play",
    "given": "Child playing an assigned game with valid schedule.",
    "when": "Parent revokes the relevant game grant.",
    "must_observe": [
      "Access revoked and bound session terminated; later resume/launch denied."
    ],
    "must_not_observe": [
      "Affecting unrelated parent session or merely hiding tile while access persists."
    ],
    "code_evidence": [
      "host integration: policy revision and revocation"
    ],
    "device_evidence": [
      "authorized Windows host + target Android TV; candidate release build"
    ],
    "evidence_note": "Capture actual command/result or observed effects; this is an expected oracle, not a passed test."
  },
  {
    "id": "T36",
    "title": "Day/DST/clock/restart accounting",
    "given": "Known budget use; configured schedule timezone and durable state.",
    "when": "Cross midnight/DST, adjust wall clock and restart Gateway in controlled tests.",
    "must_observe": [
      "Explicit documented daily-reset and schedule rules; elapsed use not erased/replayed by restart or clock rollback."
    ],
    "must_not_observe": [
      "Using device timezone implicitly or restoring a fresh allowance after reconnect."
    ],
    "code_evidence": [
      "host unit: fake monotonic/wall clocks",
      "host integration: persistence/restart"
    ],
    "device_evidence": [],
    "evidence_note": "Capture actual command/result or observed effects; this is an expected oracle, not a passed test."
  },
  {
    "id": "T39",
    "title": "Mixed client/host protocol capability",
    "given": "One peer lacks required fenced cleanup/coherent snapshot capability.",
    "when": "Attempt affected operation including child gameplay/expiry.",
    "must_observe": [
      "Explicit supported safe path or declared capability blocker; local full quit still functions.",
      "Compatibility behavior documented and tested."
    ],
    "must_not_observe": [
      "Silent downgrade to unsafe global quit or unenforced child session."
    ],
    "code_evidence": [
      "contract tests: version/capability matrix"
    ],
    "device_evidence": [],
    "evidence_note": "Capture actual command/result or observed effects; this is an expected oracle, not a passed test."
  },
  {
    "id": "T42",
    "title": "Old destructive host effect versus new session",
    "given": "Host stop delayed between validation and execution; another authorized generation may start, including another client where supported.",
    "when": "Release old stop at the adverse moment.",
    "must_observe": [
      "Authoritative serialization/session targeting prevents touching the replacement/unrelated session.",
      "Where backend cannot guarantee this, affected acceptance is BLOCKED, not passed by a local token check."
    ],
    "must_not_observe": [
      "PID/name/device-only identity or unguarded host-wide quit."
    ],
    "code_evidence": [
      "host integration: effect-time fence and replacement barrier"
    ],
    "device_evidence": [
      "authorized Windows host + target Android TV; candidate release build"
    ],
    "evidence_note": "Capture actual command/result or observed effects; this is an expected oracle, not a passed test."
  },
  {
    "id": "T02",
    "title": "Close game, keep stream",
    "given": "Game A running over transport generation G.",
    "when": "Use close-game overlay action.",
    "must_observe": [
      "Stop confirmation for exactly A; carousel; G remains active.",
      "Host badge and A tile use the same confirmed projection."
    ],
    "must_not_observe": [
      "Native transport stop or replacement.",
      "Idle host while G remains owned/live."
    ],
    "code_evidence": [
      "unit: effects and projection",
      "integration: provider game-stop"
    ],
    "device_evidence": [
      "authorized Windows host + target Android TV; candidate release build"
    ],
    "evidence_note": "Capture actual command/result or observed effects; this is an expected oracle, not a passed test."
  },
  {
    "id": "T09",
    "title": "Parent and child full quit",
    "given": "In separate runs, parent/child has an active bound game/stream.",
    "when": "Choose close-stream-and-game.",
    "must_observe": [
      "Local input blocked and actual stop initiated before any delayed host response.",
      "Host confirms correct game/stream cleanup; child requests only allowed APIs."
    ],
    "must_not_observe": [
      "Health/management access granted to child to make quit succeed.",
      "Local transport resurrection after remote error."
    ],
    "code_evidence": [
      "unit: ordered effects",
      "integration: actual child Gateway route allowlist"
    ],
    "device_evidence": [
      "authorized Windows host + target Android TV; candidate release build"
    ],
    "evidence_note": "Capture actual command/result or observed effects; this is an expected oracle, not a passed test."
  },
  {
    "id": "T10",
    "title": "Quit then immediate Play",
    "given": "G1/A active; stop processing can be delayed.",
    "when": "Full quit, immediately Play B; deliver G1 callbacks after B becomes pending/new.",
    "must_observe": [
      "B intent accepted immediately; actual replacement obeys stop/generation barrier.",
      "Old stop cannot kill B/G2 or clear its projection."
    ],
    "must_not_observe": [
      "A second active native owner before replacement is safe."
    ],
    "code_evidence": [
      "unit: controlled race",
      "host integration: delayed cleanup versus new start"
    ],
    "device_evidence": [
      "authorized Windows host + target Android TV; candidate release build"
    ],
    "evidence_note": "Capture actual command/result or observed effects; this is an expected oracle, not a passed test."
  },
  {
    "id": "T23",
    "title": "Destroyed cover view",
    "given": "A pending frame/cover barrier exists; no drawable surface after detach.",
    "when": "Destroy view before callback, then deliver any old callback.",
    "must_observe": [
      "Exactly one explicit CANCELLED/UNAVAILABLE/SUBMITTED result as appropriate.",
      "Safety-critical termination still occurs without rendering."
    ],
    "must_not_observe": [
      "Silently dropping continuation or stale input enablement."
    ],
    "code_evidence": [
      "unit: barrier lifecycle",
      "android instrumentation: renderer lifecycle"
    ],
    "device_evidence": [
      "authorized Windows host + target Android TV; candidate release build"
    ],
    "evidence_note": "Capture actual command/result or observed effects; this is an expected oracle, not a passed test."
  },
  {
    "id": "T24",
    "title": "Gateway offline during whole quit",
    "given": "G active; all cleanup RPCs fail or time out.",
    "when": "Select full quit.",
    "must_observe": [
      "Actual local stop starts without RPC completion; Home shown; host status remains honest unknown/stopping.",
      "No spontaneous auto-warm-up."
    ],
    "must_not_observe": [
      "Keeping/resurrecting stream because remote state cannot be confirmed."
    ],
    "code_evidence": [
      "unit: never-completing RPC plus actual stop seam"
    ],
    "device_evidence": [
      "authorized Windows host + target Android TV; candidate release build"
    ],
    "evidence_note": "Capture actual command/result or observed effects; this is an expected oracle, not a passed test."
  },
  {
    "id": "T34",
    "title": "Auto-warm-up after explicit quit",
    "given": "Auto-warm-up enabled with active G.",
    "when": "Full quit returns Home; then explicit fresh Play in a separate step.",
    "must_observe": [
      "Home return alone does not restart preparation; fresh Play can proceed after safe cleanup barrier."
    ],
    "must_not_observe": [
      "Self-recreating stream loop or suppression that permanently prevents explicit Play."
    ],
    "code_evidence": [
      "unit: intent/suppression lifecycle"
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
