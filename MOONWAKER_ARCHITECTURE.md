# MoonWaker Architecture

Status: draft for review
Target branch: `feature/steam-operations`
Purpose: define architectural boundaries before Instant Play, Guardian, additional game stores, and further host automation.

## 1. Product boundary

MoonWaker is a console-oriented Android TV client built on Moonlight. It coordinates:

- Moonlight/Sunshine-compatible game streaming;
- Vibepollo for stream host integration;
- Playnite as the game library and lifecycle source;
- MoonWaker Gateway as the authenticated client-facing host API;
- per-profile host Bridges for Playnite, Vibepollo, and Discord;
- host-side control, installation, repair, and lifecycle tooling.

The architecture should preserve Moonlight's streaming core and isolate MoonWaker-specific orchestration around it.

## 2. Architectural principles

### 2.1 One authoritative source per kind of state

Do not maintain competing durable truths on Android and Windows for the same operation.

Examples:

- installation/uninstallation lifecycle is host-authoritative through GameOps / `OperationJournal`;
- currently retained in-process transport is Android-authoritative through `RetainedStreamSessionCoordinator`;
- an intentionally suspended host session is persisted through `SuspendedSessionStore`;
- reconnect data remains owned by `SessionResumeManager`;
- transition privacy/readiness state remains owned by `LaunchTransitionController`.

Android may keep short-lived presentation state, but it must not become a second durable operation journal.

### 2.2 State machines and side effects are separate

`LaunchTransitionController` is the canonical transition state machine.

It owns:

- transition state;
- privacy-overlay state;
- input gating;
- transition correlation;
- readiness gates;
- manual reveal authorization.

It must not become the component that performs host effects.

Future orchestration components may execute:

- Wake-on-LAN;
- Gateway requests;
- Playnite focus;
- game launch;
- host repair;
- stream launch/reconnect.

They must feed observations back into the state machine instead of maintaining a competing transition state machine.

### 2.3 One authenticated Gateway transport on Android

All Android calls to MoonWaker Gateway must use one shared transport implementation.

The following behavior must have one implementation:

- TLS setup;
- certificate pinning;
- endpoint validation;
- Authorization header;
- integration-profile header;
- request IDs for mutations;
- connect/read timeouts;
- JSON response decoding;
- HTTP error mapping;
- bounded response sizes;
- binary/artwork reads.

Domain clients may expose different methods, but they must delegate network/security behavior to the shared transport.

### 2.4 Keep Moonlight streaming core isolated

MoonWaker-specific orchestration should not spread through core Moonlight networking, decoding, input, and native streaming code unless a streaming-specific change genuinely requires it.

Prefer narrow adapters around stock Moonlight behavior.

### 2.5 Prefer pragmatic simplicity over speculative robustness

MoonWaker should be robust where failure matters, but it should not accumulate defensive complexity for hypothetical edge cases.

Prioritize defensive handling for:

- security/authentication;
- privacy;
- transition/state corruption;
- destructive game operations;
- persistent data integrity.

Elsewhere:

- trust established internal invariants;
- avoid redundant validation layers;
- avoid speculative fallback paths;
- do not introduce abstractions only for possible future scenarios;
- do not solve extremely improbable corner cases unless their consequence would be severe.

The default implementation should be the simplest design that correctly handles realistic product behavior.

## 3. Android architecture

### 3.1 Target shape

```text
ConsoleActivity
    |
    +-- UI / focus / navigation / rendering
    |
    +-- SessionStateResolver
    |
    +-- GameOperationsController
    |
    +-- SessionOrchestrator
    |       |
    |       +-- HostLaunchPreflight
    |       +-- HostGateway domain clients
    |       +-- Moonlight launch adapter
    |
    +-- repositories / stores
```

```text
Game
    |
    +-- Moonlight stream lifecycle
    +-- decoder/input/audio
    |
    +-- ConsoleStreamTransitionCoordinator
            |
            +-- PlayniteTransitionGateway
            +-- LaunchTransitionController
```

### 3.2 `ConsoleActivity`

`ConsoleActivity` is currently an oversized composition root containing UI, state resolution, GameOps actions, host actions, library behavior, and session orchestration.

Its long-term responsibility should be:

- construct/wire controllers;
- render state;
- handle Android TV focus/navigation;
- show panels/dialogs;
- forward user intents to controllers/orchestrators.

It should not own:

- durable GameOps interpretation;
- launch/wake orchestration;
- session truth resolution;
- Gateway transport details.

Do not rewrite `ConsoleActivity` wholesale. Extract vertical slices incrementally.

### 3.3 `SessionStateResolver`

Add a dedicated component that converts several lower-level observations into one deterministic `SessionSnapshot`.

Inputs may include:

- `ComputerDetails.runningGameId`;
- resolved Playnite game ID;
- `RetainedStreamSessionCoordinator`;
- `SuspendedSessionStore`;
- `SessionResumeManager`;
- relevant host sleep state.

Example result:

```text
SessionSnapshot
  hostId
  streamingState
  hostGameAppId
  playniteGameId
  retainedTransport
  suspended
  reconnectRequired
  resumeAvailable
```

Important: this component resolves state. It does not launch, stop, sleep, wake, or reconnect anything.

`ActiveSessionSnapshot` is already a useful partial model, but it is not yet sufficient as the single resolver output. Its current fields cover active app/game/retained transport only. Extend or supersede it only as needed by the resolver; do not create parallel snapshot types for each UI path.

`PlayniteSessionPresentation` should become a presentation projection over the resolved session snapshot (or a narrow derived view), rather than independently re-resolving session truth from a long list of primitive arguments. The goal is one resolution step followed by many read-only projections.

### 3.4 `GameOperationsController`

Move install/uninstall orchestration and UI-facing operation projection out of `ConsoleActivity`.

Responsibilities:

- request install/uninstall;
- expose current operation state from host-authoritative data;
- map GameOps state to UI presentation;
- detect `attention_required`;
- request verification/focus flows when explicitly needed;
- trigger library refresh after authoritative state changes.

It must not create a second durable operation journal.

Local state should be limited to request-in-flight or UI animation metadata.

### 3.5 `SessionOrchestrator`

Introduce only after session resolution is deterministic.

Responsibilities:

- accept a high-level `PlayIntent`;
- resolve current session;
- determine whether host wake is required;
- invoke `HostLaunchPreflight`;
- prepare/replace the current target;
- launch or reconnect Moonlight;
- coordinate transition spec creation.

It must delegate transition readiness/privacy state to `LaunchTransitionController`.

### 3.6 `HostLaunchPreflight`

`HostReadiness` remains a low-level Wake-on-LAN/network readiness primitive.

`HostLaunchPreflight` is the intent-aware higher-level check.

Possible stages:

```text
NETWORK_READY
GATEWAY_READY
PROFILE_READY
PLAYNITE_READY
VIBEPOLLO_READY
TARGET_READY
```

Only dependencies required for the requested action should block launch.

Examples:

- Discord failure must not block normal game launch.
- VirtualHere failure must not block launch unless the selected play intent explicitly requires it.

### 3.7 `Game` / stream Activity

`Game` should remain primarily the Moonlight streaming Activity.

MoonWaker transition polling and recovery should move to a narrow `ConsoleStreamTransitionCoordinator`.

Candidate responsibilities to extract:

- Playnite/Gateway transition observation;
- event long-polling;
- readiness polling;
- focus recovery;
- Playnite Fullscreen recovery;
- transition timeout policy;
- conversion of host observations into calls to `LaunchTransitionController`.

Do not move decoder, input, or core NvConnection lifecycle into this coordinator.

## 4. Gateway client architecture

### 4.1 Current issue

Android currently contains duplicated authenticated Gateway transport behavior in:

- `HostGatewayClient`;
- `ui/overlay/DiscordGatewayClient`.

This is a security and maintenance risk.

### 4.2 Connection terminology and target shape

The current Android code has three overlapping connection descriptors:

- `GatewayConnection`;
- `HostGatewayClient.Connection`;
- `DiscordGatewayClient.Connection`.

They represent essentially the same credential-bearing request context: endpoint, token, pinned certificate fingerprint, and integration profile. This duplication should be removed progressively, but **not all in C1A**.

For the current request/response HTTPS architecture, use these terms precisely:

- **`GatewayConnection`** — immutable validated connection descriptor / request context. It is not a live socket and does not own retries or connection lifecycle.
- **`GatewayTransport`** — the single request executor for HTTPS/TLS, headers, response bounds, and error mapping. It may create a new `HttpsURLConnection` per request.
- **domain API/client** — endpoint-specific request construction and DTO parsing.

Do not introduce a `GatewaySession` abstraction unless MoonWaker later gains an actual long-lived logical Gateway session that needs independent lifecycle. Do not model a persistent transport connection that does not exist.

Target shape:

```text
GatewayConnection  (canonical immutable descriptor)
        |
GatewayTransport   (single HTTPS/TLS implementation)
        |
        +-- HostSystemGatewayClient
        +-- PlayniteGatewayClient
        +-- DiscordGatewayClient
        +-- VibepolloGatewayClient
        +-- VirtualHereGatewayClient
```

`GatewayTransport` owns HTTP/TLS/security behavior. Domain clients own endpoint paths, request bodies, domain DTO parsing, and domain-specific validation.

Migration is intentionally staged:

- **C1A**: introduce the canonical shared transport and domain split; both console and stream-overlay Gateway calls must use it. `HostGatewayClient` may remain as a compatibility facade and duplicate connection DTOs may remain temporarily where removing them would widen the diff.
- **C1B**: follow-up cleanup after C1A is accepted; consolidate duplicate connection descriptors/callers and remove compatibility transport/domain paths where this can be done mechanically. C1B must not be smuggled into C1A.

## 5. Windows host architecture

### 5.1 Gateway

Gateway remains the only LAN-facing host API.

Keep:

- authenticated HTTPS;
- certificate pinning model;
- Bearer tokens;
- integration profiles;
- loopback-only Bridge endpoints;
- idempotency/request IDs;
- strict endpoint-specific validation.

Do not expose a generic arbitrary Bridge proxy.

### 5.2 Profile Bridge supervisor

The Profile Bridge supervisor already owns:

- per-user process lifecycle;
- Playnite/Vibepollo health checks;
- restart behavior;
- profile ownership;
- runtime status;
- process adoption.

Guardian should use host-side repair actions rather than reimplement process supervision on Android.

### 5.3 Game Provider Bridge

`GameProviderBridge.py` currently combines:

- Playnite pipe/IPC;
- library cache;
- lifecycle events;
- window readiness;
- stream-display resolution;
- install/uninstall orchestration;
- Steam probing/automation;
- Epic probing;
- HTTP serving.

Do not rewrite it wholesale.

Extract GameOps providers mechanically when the architecture is stable.

Target shape:

```text
GameProviderBridge
    |
    +-- Playnite IPC / library / lifecycle
    +-- readiness
    +-- GameOperationsService
            |
            +-- OperationJournal
            +-- SteamProvider
            +-- EpicProvider
            +-- GenericPlayniteProvider
```

### 5.4 `OperationJournal`

`OperationJournal` is the durable host-authoritative GameOps state.

Android must consume its projected state rather than recreate it.

Retain states such as:

- preparing;
- downloading;
- installing;
- uninstalling;
- attention_required;
- verifying;
- completed;
- failed;
- cancelled.

## 6. Privacy architecture

### 6.1 Android privacy gate

`LaunchTransitionController` remains responsible for ensuring Android does not reveal the stream before:

- transport is ready;
- correct host is correlated;
- correct game/target is correlated;
- target process/window is ready;
- a fresh video frame is observed after the readiness edge;
- reveal animation has completed before input is unblocked.

These invariants are architectural requirements.

### 6.2 Host Privacy Surface

Android alone cannot guarantee that a desktop frame is never captured after an unexpected target crash.

Before final v1.0 privacy guarantees, implement a host-side opaque privacy surface on the streamed display.

Required semantics:

- idempotent `privacy/show`;
- idempotent `privacy/hide`;
- transition ID required;
- acknowledgement of effective transition ID;
- host privacy surface shown before planned target replacement/close;
- Android privacy surface stays opaque until host confirms target readiness and host privacy removal.

## 7. Boot/sleep assumptions

For v1.0, treat the reliable zero-touch target as:

```text
sleeping, logged-in Windows session
    -> Wake-on-LAN
    -> Bridges/Gateway available
    -> game launch/resume
```

Do not promise full:

```text
powered off
    -> Windows boot
    -> unattended login
    -> interactive Bridges
    -> game
```

unless a separate Windows-login/service design is implemented.

## 8. Components to preserve

Do not refactor for style alone:

- `LaunchTransitionController`;
- `RetainedStreamSessionCoordinator`;
- Gateway TLS/auth/profile boundary;
- host loopback-only Bridge architecture;
- Steam safety checks that validate launcher/process/window before automation;
- Moonlight core streaming path.

## 9. v1.0 architecture roadmap

1. **C1A** — shared Gateway transport + domain split; one authenticated TLS/HTTP implementation.
2. **C1B** — bounded cleanup of duplicate Gateway connection descriptors/callers after C1A review.
3. **C2** — `GameOperationsController`.
4. **C3** — `SessionStateResolver` and snapshot-based presentation projection.
5. **C4** — extract host GameOps providers.
6. **C5** — harden Steam zero-touch.
7. **C6** — `ConsoleStreamTransitionCoordinator`.
8. **C7** — `SessionOrchestrator` + `PlayIntent`.
9. **C8** — `HostLaunchPreflight`.
10. **C9** — suspend/resume hardening.
11. **C10** — Host Privacy Surface.

Then v1.0 stabilization.

## 10. Deferred work

Post-v1.0 unless required by discovered defects:

- full Epic zero-touch;
- GOG/Xbox providers;
- generalized operation queue;
- scheduled overnight install/sleep;
- normalized host transition protocol;
- complete removal of compatibility `HostGatewayClient`;
- full DTO normalization;
- large `wakeplay_gateway.py` split;
- Guardian;
- Streaming Autopilot;
- Together;
- true cold-boot unattended Windows login.

## 11. Review rule

Every large MoonWaker change should answer:

1. What component owns the durable truth?
2. Is a new state machine being introduced unnecessarily?
3. Does this duplicate Gateway transport/security behavior?
4. Does Android implement host process supervision that belongs on Windows?
5. Does it modify Moonlight core without a streaming-specific reason?
6. Can the change be extracted as one vertical slice with characterization tests?

If any answer indicates duplicated ownership, stop and redesign before implementation.
