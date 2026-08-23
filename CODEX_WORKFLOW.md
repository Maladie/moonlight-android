# MoonWaker Codex Workflow

Status: draft for review

This document defines how architecture decisions are made in ChatGPT and how implementation work is delegated to Codex.

## 1. Roles

### Architecture chat

The main MoonWaker chat owns:

- architecture;
- task decomposition;
- sequencing;
- acceptance criteria;
- review of Codex output;
- identification of regressions and follow-up work;
- keeping long-term project rules consistent.

The architecture chat should not ask Codex to "improve" broad areas without a concrete contract.

### Codex task window

A Codex window owns one bounded implementation task.

It should:

- inspect the relevant code;
- implement the specified scope;
- add/update tests;
- run the relevant test/build commands;
- report exact files changed;
- report assumptions and unresolved risks;
- not silently expand scope.

## 2. One Codex window per architectural task

Default rule:

> Start a fresh Codex window for each architectural task.

Reasons:

- less context pollution;
- easier review;
- lower risk that obsolete assumptions carry forward;
- clearer diff ownership;
- easier rollback.

Reuse the same window only for:

- immediate fixes to the same task;
- review corrections;
- test failures caused by that task;
- very small follow-up adjustments that do not alter architecture.

## 3. Task packet format

Every Codex task should include:

### Context

- repository;
- branch;
- product goal;
- current architectural boundary;
- relevant existing components.

### Objective

One explicit outcome.

### Required behavior

Observable behavior that must remain or be introduced.

### Constraints

What must not be changed.

### Scope

Specific directories/classes likely involved.

### Non-goals

Features explicitly excluded.

### Tests

Required characterization/regression/unit/integration coverage.

### Validation

Commands Codex should run.

### Deliverable

What Codex must report back.

## 4. Before implementation: inspect first

Codex must first inspect the code named by the task and verify the prompt's assumptions.

If it discovers a materially different architecture:

- do not silently redesign the project;
- stop implementation at the smallest safe point;
- report the mismatch and propose a bounded adjustment.

Small implementation details may be resolved autonomously.

## 5. Preserve behavior before moving code

For refactor tasks:

1. characterize behavior;
2. introduce seams/interfaces;
3. move implementation;
4. switch callers;
5. remove duplication;
6. run regression tests.

Avoid simultaneous redesign + refactor unless the task explicitly requires it.

## 6. Security-critical code rule

For:

- TLS;
- certificate pinning;
- authentication;
- request IDs;
- permission scopes;
- pairing;
- launcher automation;
- privacy gates;

the task must prefer behavior-preserving extraction.

Any semantic change must be explicit in the prompt and testable.

## 7. Moonlight core rule

Codex should avoid modifying stock Moonlight streaming internals when the task can be implemented in MoonWaker-specific layers.

If core changes are necessary, the report must explain why no external seam is sufficient.

## 8. State ownership rule

Before adding persistence or caches, Codex must identify the authoritative owner.

Examples:

- GameOps durable lifecycle -> host `OperationJournal`;
- in-process retained stream -> `RetainedStreamSessionCoordinator`;
- intentionally suspended session -> `SuspendedSessionStore`;
- reconnect material -> `SessionResumeManager`;
- transition privacy/readiness -> `LaunchTransitionController`.

Do not add a second durable store for the same truth.

## 9. Review loop

After Codex reports completion, architecture chat review should check:

### Architecture

- boundary respected;
- no duplicate state owner;
- no parallel transition state machine;
- no unnecessary Moonlight core changes.

### Correctness

- stale async results rejected;
- host/game/transition correlation preserved;
- cancellation behavior preserved;
- errors mapped deterministically.

### Safety

- privacy gate preserved;
- launcher automation remains allow-listed;
- Gateway security unchanged unless explicitly intended.

### Tests

- new behavior covered;
- characterization tests added for moved behavior;
- build/test commands actually run.

### Diff quality

- no unrelated cleanup;
- no generated artifacts;
- no debug-only hacks;
- no credentials/secrets.

## 10. Follow-up policy

If review finds issues:

- use the same Codex window for corrections to the same task;
- provide a numbered defect list;
- require Codex to address each item and report mapping from issue -> fix;
- only open the next architectural task after the current one passes review.

## 11. Commit policy

Default during architecture/refactor work:

- Codex may modify the working tree;
- do not commit/push unless the task explicitly asks;
- architecture chat reviews the diff first;
- commit should represent one architectural task.

Generated build artifacts and diagnostic screenshots must stay out of commits unless explicitly required.

## 12. Model-selection guideline

Use the strongest reasoning-oriented Codex mode for:

- cross-cutting architecture refactors;
- security-sensitive transport;
- lifecycle/state-machine work;
- large Java/Python boundary changes.

Use a faster coding model/mode for:

- mechanical extraction after the design is settled;
- isolated tests;
- straightforward UI wiring;
- small follow-up fixes.

Do not optimize for model cost when a task can silently corrupt session, privacy, or authentication behavior.

## 13. Context handoff between Codex windows

Each finished task report should contain a compact handoff:

- branch/head state;
- objective completed;
- files changed;
- new classes/contracts;
- deleted compatibility paths;
- tests run;
- known limitations;
- next recommended task.

The next Codex prompt should repeat only the architectural facts needed for that task rather than pasting the entire previous transcript.

## 14. Definition of done for refactor tasks

A refactor is done only when:

- target responsibility has moved;
- old callers use the new boundary;
- duplicate implementation is removed or reduced to an explicit compatibility facade;
- behavior is characterized by tests;
- relevant tests/build pass;
- no new TODO is required to make the new abstraction actually authoritative.

## 15. Pragmatic scope and engineering guardrails

Codex must optimize for a small, reviewable, production-appropriate diff.

### 15.1 No unrelated work

Do not modify code that is not required to complete the current task.

This includes:

- opportunistic cleanup;
- renaming unrelated classes or methods;
- reformatting unrelated files;
- reorganizing packages because they could be cleaner;
- fixing nearby TODOs;
- modernizing APIs unrelated to the task;
- changing comments or style outside the touched behavior;
- broad dependency or build-system upgrades.

If Codex notices unrelated problems, report them under `Follow-up observations` and leave the code unchanged.

### 15.2 Do not over-test

Tests should protect the contract being changed, not attempt to exhaustively prove the whole application.

Prefer:

- a small number of high-value regression tests;
- tests for behavior that could realistically regress because of the diff;
- characterization tests around security, state ownership, correlation, or protocol boundaries when those are being moved.

Avoid:

- large test matrices for equivalent cases;
- tests that merely duplicate language/library behavior;
- dozens of near-identical input-validation cases;
- building elaborate test harnesses for very unlikely scenarios;
- increasing test count as a goal by itself.

A refactor that can be safely covered by 3 focused tests should not receive 30.

### 15.3 Avoid speculative defensive programming

Do not add guards, retries, fallback paths, validation layers, abstractions, or exception handling for hypothetical failures unless:

- the repository already demonstrates that failure mode;
- the task explicitly requires it;
- the external API/protocol makes it reasonably likely;
- failure would have severe security, privacy, or data-loss consequences.

Normal application code should remain readable and direct.

Do not optimize implementation complexity around extremely improbable corner cases unless their impact is catastrophic.

### 15.4 Distinguish critical invariants from rare edge cases

Be conservative around:

- TLS/certificate pinning;
- authentication and authorization;
- transition correlation;
- privacy surfaces;
- state-machine integrity;
- destructive game operations;
- persistent state corruption;
- unsafe launcher automation.

Be pragmatic around:

- cosmetic race conditions with harmless outcomes;
- impossible or near-impossible malformed internal states;
- redundant null checks where ownership already guarantees non-null values;
- exotic platform behavior unsupported by the product;
- theoretically valid but practically irrelevant input combinations.

### 15.5 Prefer the simplest sufficient implementation

When two solutions satisfy the task:

- choose the one with fewer new abstractions;
- choose the one with fewer changed files;
- choose the one that reuses existing project conventions;
- choose the one that is easier to review;
- do not generalize for hypothetical future use unless the task explicitly requires that extension point.

Do not introduce an interface, factory, adapter layer, retry framework, result hierarchy, or generic utility only because it may become useful later.

### 15.6 Report instead of expanding scope

When Codex finds something adjacent but unrelated:

```text
Follow-up observation:
- <problem>
- why it may matter
- suggested future task
```

Do not implement it in the current task unless it blocks correctness of the requested work.

## 16. Staged migration rule

When a refactor exposes two separable goals, prefer two reviewable tasks over one broad migration.

For Gateway work specifically:

- **C1A** establishes one authoritative HTTPS/TLS transport and isolates domain APIs.
- **C1B** removes or normalizes compatibility connection/client representations only after C1A passes review.

Do not combine C1B cleanup into C1A merely because the new transport makes it possible. A compatibility facade or duplicate DTO may be acceptable temporary debt when it keeps the first diff small and behavior-preserving.

Terminology must match actual runtime semantics. Do not invent a long-lived `Connection` or `Session` lifecycle for a stateless request/response API unless the implementation genuinely has one.
