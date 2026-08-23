# MoonWaker repository guidance

## Sources of truth

- For MoonWaker architecture work, read `docs/MoonWaker/MOONWAKER_ARCHITECTURE.md` before changing code.
- Follow `docs/MoonWaker/CODEX_WORKFLOW.md` for task boundaries, review, commits, and handoff reporting.
- For C1A, also follow `docs/MoonWaker/CODEX_C1A_GATEWAY_TRANSPORT.md` exactly. Do not include C1B cleanup.
- A task packet may narrow scope, but it must not weaken the security, privacy, state-ownership, or Moonlight-core boundaries below.

## Repository boundaries

- `app/src/main/java/com/limelight/console/` owns MoonWaker console UI, presentation, orchestration, and the current Gateway facade.
- `app/src/main/java/com/limelight/console/transition/` contains the canonical launch-transition state machine.
- `app/src/main/java/com/limelight/ui/overlay/` contains stream overlays and overlay-specific domain behavior.
- `app/src/main/java/com/limelight/Game.java` and `app/src/main/java/com/limelight/stream/` own Android stream lifecycle integration.
- `host-services/gateway/` is the only LAN-facing host API. Bridges under `host-services/bridges/` remain loopback-only, per-profile integrations.
- `host-services/control/`, `host-services/install/`, `host-services/installer/`, and `host-services/profile-agent/` own Windows host lifecycle and deployment tooling.
- Treat `app/src/main/jni/moonlight-core/` as upstream Moonlight code. Avoid changes there unless a streaming-specific requirement cannot be implemented through a narrow MoonWaker seam; explain any exception in the handoff.

## Working rules

- Inspect the named code and direct callers before implementation. If the task assumes a materially different architecture, stop at the smallest safe point and report a bounded correction.
- Keep each diff focused on one architectural task. Do not bundle opportunistic cleanup, unrelated renames, reformatting, dependency upgrades, warning fixes, or nearby TODOs.
- For refactors, characterize existing behavior, introduce the smallest required seam, move the responsibility, switch callers, remove duplicated implementation, and then run regression tests.
- Prefer a concrete, simple implementation over speculative interfaces, retry frameworks, generic utilities, or fallback paths.
- Do not add a second durable owner, cache, journal, or state machine for existing truth. Preserve the ownership rules in the architecture document.
- Reject stale asynchronous results and preserve host, game, request, and transition correlation wherever those contracts already exist.
- Keep generated build output, diagnostics, credentials, tokens, certificates, machine-specific paths, and secrets out of commits.
- Do not commit or push unless the user or task packet explicitly asks. Never push merely because a commit was requested.

## Security, privacy, and host safety

- Do not weaken HTTPS, certificate pinning, pairing, Bearer authentication, integration-profile headers, request IDs, permission scopes, response bounds, or endpoint-specific validation.
- Never replace certificate verification with trust-all TLS and never expose a generic arbitrary Bridge proxy through Gateway.
- Preserve `LaunchTransitionController` as the authoritative transition/privacy gate. Do not reveal video or unblock input before its existing readiness and fresh-frame conditions are satisfied.
- Keep GameOps durable lifecycle host-authoritative through `OperationJournal`; Android may hold only short-lived request or presentation state.
- Keep launcher automation allow-listed and preserve existing process/window validation. Treat install, uninstall, sleep, repair, and other destructive or host-wide actions as safety-critical.

## Validation and handoff

- Run the narrowest relevant tests first. For Android changes, the default full unit-test target is `./gradlew testNonRootDebugUnitTest`; also run the compile/build target required by the task.
- For host-service changes, run the directly affected Python or PowerShell tests described by the component and add broader validation only when the changed contract crosses components.
- For documentation-only changes, inspect links and paths and run `git diff --check`; a full Android build is unnecessary unless the documentation change depends on generated or verified code behavior.
- Before handoff, run `git status --short` and review the complete diff for unrelated changes, generated artifacts, and secrets.
- Report the branch and HEAD, files changed, contracts moved or introduced, compatibility paths left in place, exact validation commands and results, limitations, and follow-up observations.
