# Optional task prompts

Use one matching section. Replace placeholders. These prompts do not override `AGENTS.md`, explicit task gates, or tool permissions.

## Ordinary implementation

```text
Outcome: <observable improvement>.
Acceptance: <behavior and evidence needed to call it complete>.
Scope/non-goals: <relevant area and excluded changes>.
Context: <only useful paths, current facts, or defect reproduction>.

Implement this through the actual callers, improve the touched responsibility,
and complete relevant verification and change-caused corrections. Do not stop
at a first draft. Resolve implementation details within this contract; report
only genuine scope/authority/evidence blockers. No commit, push, or deployment.
Return the outcome, concise task delta, actual validation, and limitations.
```

## Independent review

```text
Review <task> independently against <acceptance contract>.
Candidate: <workspace and task-specific delta/source identity>.
Evidence: <report paths>. Your workspace must contain the candidate, including
new/untracked files; do not assume HEAD or a fresh worktree contains them.

Inspect production wiring, moved state/effects, stale-result handling, and the
changed security/privacy boundaries. Use targeted independent checks where
needed. Do not repeat a repository-wide audit or copy the implementation report
as proof. Return actionable defects with locations and acceptance impact, or
state that no blocking defect was found with remaining evidence limitations.
Do not edit the candidate or accept on behalf of a coordinator when the packet
assigns acceptance to that role.
```

## Resume the assigned hardening workflow

```text
Resume docs/MoonWaker/HARDENING/RESUME.md using its existing STATE.json.
Verify recorded branch, baseline, source identity, and dependency gates.
Continue the current unfinished task, otherwise select one eligible next task.
Do not recreate the branch, reset state, restart S00, or advance past a pending gate.

Keep the coordinator focused on dispatch and evidence. Use a fresh worker for
one new task and a real independent reviewer as required by the packet. Keep
corrections in the task context. Pass dispatch/report paths rather than dumping
full documents and logs into every conversation. Preserve design reviews,
complete checkpoints, decomposition, and local/device test requirements.
Stop after one accepted checkpoint or a genuine blocker; record the next action.
```

This last prompt is for an already initialized run. For a fresh run, use that packet's `START_HERE.md` and branch-creation policy. The packet is optional and is not installed by this agent-guidance proposal. Do not silently trim its generated dispatch or change acceptance to reduce context.
