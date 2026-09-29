# S06 bounded worker dispatch

```json
{
  "task_id": "S06",
  "role": "worker",
  "mode": "EXECUTION_AUTHORIZED_BY_EXPLICIT_DEPENDENCY_OVERRIDE",
  "run_id": "S06-worker-20260914",
  "repository": "Maladie/moonlight-android",
  "worktree": "D:/Maladie/moonlight-android",
  "expected_branch": "codex/session-hardening",
  "expected_head": "b05f3e2cdbccdc6a04e8b800c00e26bf80f16110",
  "expected_input_fingerprint": "sha256:5e78588cdd4512554e4c2ef1d047b7bb0beba87e2809759231ad86b2df38145e",
  "dependency_override": "S06-20260914-DEPENDENCY-OVERRIDE",
  "generator_note": "packet.py context correctly refused execution dispatch because S05 is not accepted. This coordinator dispatch records the user's explicit override without changing the predecessor statuses or claiming packet acceptance.",
  "single_writer": true,
  "pre_edit_review": "runs/S06/pre-edit-review.json",
  "checkpoint_plan": "runs/S06/checkpoint-plan.json",
  "design": "runs/S06/design.md",
  "characterization": "runs/S06/characterization.md"
}
```

## Required input

- Read all root/applicable `AGENTS.md`, `START_HERE.md`, `RESUME.md`, `RULES.md`, `CHECKPOINTS.md`, `DECOMPOSITION.md`, `automation/TEST_STRATEGY.md`, `tasks/S06.md`, `CHILD_PROFILES_DESIGN.md`, `docs/agents/SESSION_CONTRACTS.md`, this dispatch, the checkpoint plan, characterization, design and both pre-edit review records.
- Read the complete S03-S05 assigned-run reports. Bind S05 to `profile-takeover-overlay-correction-20260914`, source output `sha256:c1a7ee8942db045b33aed21f5b16abf69c95e812aa4ec119355c8c288de2e784`, installed debug APK `sha256:d619a5e72c1cd512d4c02ea881dda936feb42d061544411ed40a7e91367846be`, Gateway `0.7.117+2026.09.14-s05-full-close`.
- Preserve the entire dirty checkout. Do not reset, stash, clean, remove, overwrite or reformat unrelated work.
- Before editing, rerun branch-check, validate, fingerprint and verify the exact input fingerprint.

## Outcome and non-negotiable contract

Implement the complete S06 vertical slice:

1. One `BridgeState.lock`-serialized schema-1 producer observation with boot ID, positive monotonic revision, observation time, response freshness, stable state episode/deadline input, canonical game, verified process identity, window/readiness evidence and explicit reason/evidence states.
2. One authenticated Gateway endpoint with common parent/child behavior and no parent-only health dependency. Add actor/execution and before/after-fenced operation, caller-transition, request ordinal, stream session, control generation and actor lease correlation.
3. One process-wide serialized Android projection owner per host/actor/execution. It assigns dispatch ordinals, rejects delayed A after B within and across boots, maintains retired boots, enforces local monotonic freshness and a non-resetting episode liveness deadline, and publishes one `SessionSnapshot` to every consumer.
4. Add an independent monotonic local `transportGeneration` to `RetainedStreamSessionCoordinator`; never alias it to Gateway control generation, transition attempt or producer revision.
5. Preserve a known-live local transport under unknown, malformed, mismatched or unavailable host game evidence. Converge every unknown episode to `READY`, `IDLE`, `RECOVERY_REQUIRED` or `UNAVAILABLE` within its bound.
6. Events may trigger observation refresh but cannot independently prove process/window/readiness or open reveal/input. Missing game evidence is never replaced with the expected game.
7. Migrate host badges, tiles, Play/start/resume/retain/close/end-game decisions, transition polling, exact stop/transfer, presentation and termination. Remove obsolete Activity maps/helpers and direct old health/current/readiness use from production consumers.
8. `RECOVERY_REQUIRED` never auto-closes a known-live stream. Existing exact-fenced recovery remains a separately user-authorized action; deterministically test recovery followed by the next new authorized Play with new operation/transition/transport generation.

## Bounded production ownership

- Game Provider: `host-services/bridges/playnite/GameProviderBridge.py`
- Gateway: `host-services/gateway/wakeplay_gateway.py`
- Android transport/parser/facade: `HostGatewayClient.java`, `PlayniteTransitionGateway.java`
- Canonical projection/presentation: `SessionStateResolver.java`, `SessionSnapshot.java`, `PlayniteSessionPresentation.java`
- Local transport generation: `RetainedStreamSessionCoordinator.java`
- Consumers: `ConsoleStreamTransitionCoordinator.java`, `SessionOrchestrator.java`, `ConsoleActivity.java`, `Game.java`, `HostSessionTermination.java`
- Touch a directly required caller/test/build registration only when necessary for this complete responsibility. Record every widened file and reason. Do not add a durable store, child-only lifecycle, generic proxy or direct Android-to-Bridge path.

## Required deterministic tests

- Provider atomic game/process/window snapshot and explicit missing proof.
- Schema/boot/revision, dispatch ordinal and delayed A-after-B within/across boots.
- Gateway scope/correlation before/after fence for parent and child.
- Separate local transport generation lifecycle.
- Known-live transport survives outage, malformed schema, mismatch and unknown.
- Events cannot prove readiness; absent game cannot reach process/window/reveal.
- Non-resetting bounded liveness for reconciling, ambiguous, connector unavailable and host locked to all four terminal states.
- Exact process stop/transfer only from one verified observation.
- Recovery followed by the next authorized Play.
- Structural/contract check proving production consumers no longer call the old multi-read APIs or Activity alternate maps/helpers.
- Full S03-S05 affected regressions, Gateway suite, Android NonRoot suite and debug/release build.

Use controlled clocks, latches/queues and production wiring; do not use sleeps as race proof. Capture fail-before evidence where reproducible. Return `implementation.json` with exact commands/results, changed files, compatibility, output fingerprint and remaining device gate. Do not mark S06 accepted.

## Prohibited actions

No commit, push, APK install, Gateway update, host packaging/deployment or service restart. No physical evidence may be claimed. Do not modify S03-S05 statuses. Stop and report if the branch, HEAD, input fingerprint or overlapping user-owned source changes no longer match this dispatch.
