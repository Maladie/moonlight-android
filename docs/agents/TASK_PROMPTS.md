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
