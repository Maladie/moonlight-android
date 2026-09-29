# MoonWaker task execution

Use for delegated work, independent review, or multi-task checkpoints. Ordinary fixes do not require an architecture-chat ceremony. Repository invariants remain in `AGENTS.md`; do not paste them into every task.

## Task contract

Specify the observable outcome, acceptance evidence, scope/non-goals, and any extra authority needed. Supply relevant paths and known facts, not a repository dump or an assumed implementation recipe. [Task prompts](docs/agents/TASK_PROMPTS.md) are optional starting points, not another required reading step.

For refactors, acceptance means the responsibility, state, effects, and callers have moved; the old owner is gone or is an explicit compatibility facade. Each accepted implementation checkpoint is independently integrated and usable. Internal passes are not accepted checkpoints.

## Execution and autonomy

Start one fresh worker context per substantial task; keep its review fixes in that context when practical. A fresh context does not require a new application window, branch, or worktree. Respect the task's branch policy. Preserve the recorded base on resume.

Continue until the assigned outcome and required validation are complete or a concrete blocker remains. Local implementation choices and bounded extraction belong to the worker. Changes to agreed authority, product semantics, or irreversible operations require the designated decision-maker.

Do not treat an initial implementation, failed test, or reviewer defect as a new assignment. Correct change-caused failures and rerun affected checks. Missing device access is an evidence blocker, not a passing result.

## Delegation and review

A single worker is sufficient unless the task or risk requires more. If a packet prescribes a coordinator and independent reviewer, retain that topology and every design/acceptance gate. For auth, privacy, and stream-lifecycle contract changes, obtain independent review before acceptance.

Use a real separate reviewer context, not a second persona in the worker's response. Default to one writer per workspace. The reviewer needs the current task delta, new/untracked files, contract, and evidence—not all previous transcripts. A new worktree does not automatically contain uncommitted work.

Coordinator: dispatch and reconcile evidence; avoid duplicating worker exploration. Reviewer: inspect production wiring and challenged claims, run justified independent checks, and return actionable defects. After corrections, inspect the changed interactions and refreshed evidence rather than reopening unrelated work.

Select actual available model/effort settings for the task. Sol is a reasonable starting choice; use Astra for difficult boundaries when useful. These are routing preferences, not model IDs, automatic settings, or substitutes for independent review.

## Handoff

Record task, branch/HEAD, task-delta identity, outcome, changed ownership, validation commands/results, and remaining blockers. Link long logs instead of pasting them. Record source identity beyond HEAD when edits are uncommitted.

Reuse valid evidence for unchanged source and environment; rerun checks invalidated by corrections. Never relax mandatory checks, device requirements, or independence to reduce tokens.

For the hardening packet, its designated state, branch binding, and checkpoint tools remain authoritative for workflow progress. Read only the assigned task and needed contracts. Do not reset state, restart setup, or advance a dependent task while a required gate is pending.
