# Codex Task C1A — Unify MoonWaker Gateway Transport

Repository: `Maladie/moonlight-android`
Branch: `feature/steam-operations`

## Goal

Refactor Android Gateway networking so the application has exactly one implementation of authenticated MoonWaker Gateway HTTP/TLS transport behavior.

This is a behavior-preserving architectural task. Do not add product features. This task is C1A only; do not perform the follow-up C1B cleanup.

## Why this task exists

Gateway behavior is currently duplicated across at least:

- `app/src/main/java/com/limelight/console/HostGatewayClient.java`
- `app/src/main/java/com/limelight/ui/overlay/DiscordGatewayClient.java`

Both implement security-critical behavior such as:

- `HttpsURLConnection`;
- TLS setup;
- certificate fingerprint pinning;
- hostname-verifier behavior for the pinned self-signed Gateway certificate;
- `Authorization: Bearer`;
- `X-WakePlay-Profile`;
- `X-Request-Id` for mutations;
- timeouts;
- JSON response parsing;
- HTTP error handling.

`DiscordOverlayController` currently uses both clients in the same feature path.

There are also three overlapping connection descriptors in the Android code:

- `GatewayConnection`;
- `HostGatewayClient.Connection`;
- `DiscordGatewayClient.Connection`.

For C1A, the priority is **one transport implementation**, not a broad DTO/caller migration. `GatewayConnection` is conceptually a validated immutable request descriptor (endpoint/token/certificate pin/profile), not a live socket/session. Duplicate descriptors may remain temporarily if consolidating them would materially widen the diff. That cleanup belongs to C1B.

The end state of C1A must remove duplicated HTTP/TLS security behavior. It does not need to eliminate every compatibility type.

## Required architecture

Introduce one shared transport-level abstraction in a neutral MoonWaker package, for example:

```text
GatewayTransport
GatewayRequest / GatewayResponse helpers if needed
```

The exact class names may differ if a better fit exists after inspection.

`GatewayTransport` should be a request executor, not an invented persistent connection/session manager. The current Gateway protocol is ordinary request/response HTTPS (plus long-poll requests), so do not add lifecycle/retry/session machinery that the protocol does not require.

The transport owns:

- endpoint concatenation/validation;
- pinned TLS context;
- certificate SHA-256 validation;
- hostname-verifier policy required by the pinned self-signed Gateway certificate;
- connect/read timeouts;
- common request headers;
- Bearer authorization;
- integration-profile header;
- mutation request IDs;
- bounded UTF-8 JSON reads;
- bounded binary reads where already needed;
- consistent non-2xx error decoding.

Domain clients own:

- endpoint paths;
- request-body construction;
- domain-specific DTOs;
- domain-specific response parsing.

## Compatibility

It is acceptable for `HostGatewayClient` to remain temporarily as a compatibility facade because it has many callers.

If it remains:

- it must delegate transport behavior to the shared transport;
- it must no longer own its own TLS/HTTP stack.

`DiscordGatewayClient` must likewise stop owning a separate TLS/HTTP implementation. Prefer converting it into a thin domain client or migrating its callers to a Discord domain client backed by the common transport.

There must not be two independent certificate-pinning implementations after this task.

Do **not** require full consolidation of `GatewayConnection`, `HostGatewayClient.Connection`, and `DiscordGatewayClient.Connection` in C1A. Prefer canonical use of `GatewayConnection` where it is trivial and safe, but leave broader normalization for C1B.

## Existing security behavior to preserve

Do not weaken:

- HTTPS-only paired Gateway connections;
- certificate fingerprint normalization;
- leaf certificate SHA-256 pinning;
- rejection when the Gateway certificate changes;
- profile ID validation;
- Bearer authorization;
- request IDs on mutating calls;
- response-size limits;
- current separation between Gateway and loopback Bridges.

Do not replace certificate pinning with trust-all TLS.

Do not add a generic arbitrary Gateway proxy API.

## Existing product behavior to preserve

All existing Gateway-backed features must continue to work, including where currently supported:

- pairing/capabilities;
- profiles;
- Playnite library;
- Playnite transition/readiness calls;
- GameOps calls;
- Vibepollo operations;
- system sleep;
- Discord panel;
- Discord stream overlay;
- VirtualHere;
- artwork/binary reads.

Do not redesign their public behavior.

## Characterization/testing requirement

Before or during extraction, add characterization tests for transport behavior that is practical to test without building a large new test framework.

At minimum cover deterministic helpers/contracts around:

- certificate fingerprint normalization;
- profile/header selection;
- request-ID behavior for mutations;
- error decoding/mapping;
- response size/bounds where practical;
- shared connection validation.

If an existing function cannot be directly tested because it constructs `HttpsURLConnection` internally, introduce the smallest seam needed to make the transport testable rather than duplicating behavior in tests.

Do not create a fake second TLS implementation solely for testing.

Preserve existing `HostGatewayClientTest` behavior.

## Scope guidance

Inspect at least:

- `HostGatewayClient.java`
- `GatewayConnection.java`
- `HostGatewayStore.java`
- `DiscordGatewayClient.java`
- `DiscordOverlayController.java`
- Gateway-related unit tests
- direct constructors/usages of both Gateway clients

Search the complete Android source tree for:

- `HttpsURLConnection`
- `X-WakePlay-Profile`
- `X-Request-Id`
- `Authorization`
- certificate pinning / SHA-256 fingerprint code

Determine whether there are any additional MoonWaker Gateway transport implementations and include them if they duplicate this same connection contract.

Do not refactor unrelated internet/network code.

## Non-goals

Do not:

- refactor `ConsoleActivity`;
- implement `GameOperationsController`;
- implement `SessionStateResolver`;
- implement `SessionOrchestrator`;
- alter `LaunchTransitionController`;
- redesign Gateway server endpoints;
- change the host-side authentication model;
- migrate to a new networking library unless there is a compelling compatibility reason discovered during inspection;
- perform broad package cleanup;
- fully normalize/remove every Gateway connection DTO or migrate all callers (C1B);
- introduce a persistent `GatewaySession` or live-connection abstraction that the current HTTPS protocol does not need;
- change UI.

`okhttp` is already a dependency, but this task is not permission to perform an unnecessary networking-stack migration. A behavior-preserving shared `HttpsURLConnection` transport is acceptable.

## Pragmatic implementation guardrails

Keep this diff intentionally narrow.

### No unrelated changes

Do not:

- clean up nearby code unless required by this task;
- rename unrelated APIs;
- reformat unrelated files;
- restructure packages beyond what the shared Gateway transport requires;
- fix unrelated warnings/TODOs;
- perform dependency upgrades;
- refactor unrelated callers merely because the new abstraction makes it possible.

Report unrelated findings instead of changing them.

### Keep testing proportional

Add only tests that meaningfully protect the transport extraction.

Focus on the highest-risk regressions:

- shared connection validation;
- TLS/pinning semantics where practical to characterize;
- auth/profile/request-ID header behavior;
- error mapping;
- response bounds;
- removal of duplicate transport behavior.

Do not add large combinatorial test suites or dozens of validation edge cases.

A handful of focused characterization/regression tests is preferred over exhaustive coverage.

### Avoid speculative defensive code

Do not add new fallback paths, retries, layers of validation, wrappers, or exception handling for extremely unlikely hypothetical failures unless they affect:

- TLS/security;
- credentials/authentication;
- protocol correctness;
- privacy;
- destructive operations.

Prefer straightforward code that assumes established internal invariants.

Do not engineer around one-in-a-million corner cases with harmless outcomes.

### Prefer the smallest sufficient design

Do not create additional interfaces or generic abstractions unless they are necessary to remove the duplicated Gateway transport.

A concrete shared transport plus focused domain clients/adapters is preferred over a large framework.

The goal is not to create a perfect networking architecture. The goal is to eliminate duplicated security-critical transport behavior with the smallest maintainable diff.

## Important architectural constraints

`LaunchTransitionController` is outside scope and must remain behaviorally untouched.

Do not introduce new persistent state.

Do not modify Moonlight streaming core for this task.

Avoid changing host services unless a test or compatibility issue proves it is strictly necessary. This should primarily be an Android refactor.

## Validation

Run the relevant Android unit tests.

At minimum:

```text
./gradlew testNonRootDebugUnitTest
```

If repository configuration requires a different equivalent unit-test target, use the appropriate target and report it.

Also run a compile/build target sufficient to catch Android source integration errors.

Report exact commands and results.

## Deliverable

Do not commit or push unless explicitly instructed.

Return a report containing:

1. architecture implemented;
2. files changed;
3. which duplicate transport implementation(s) were removed;
4. whether `HostGatewayClient` remains as a compatibility facade;
5. whether `DiscordGatewayClient` remains and, if so, what responsibility it now has;
6. tests added/updated;
7. exact validation commands and results;
8. any behavior you could not characterize safely;
9. any remaining connection/client compatibility debt that should become C1B;
10. any other follow-up work recommended, but do not implement follow-up scope.

## Definition of done

This task is complete only if:

- Android has one authoritative MoonWaker Gateway HTTP/TLS transport implementation;
- both normal console Gateway calls and stream Discord overlay calls use it;
- duplicate pinning/header/request logic is removed;
- existing Gateway product behavior is preserved;
- relevant tests pass;
- no unrelated architectural work is bundled into the diff.
