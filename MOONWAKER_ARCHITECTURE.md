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
PROFILE_AUTHORIZED
INTERACTIVE_SESSION_READY
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

`MoonWakerGateway` is an automatic Windows service running under the restricted
`NT SERVICE\MoonWakerGateway` virtual account. It supervises only the machine
Gateway process and is available before interactive sign-in. It must not start
profile Bridges in session 0.

Keep:

- authenticated HTTPS;
- certificate pinning model;
- Bearer tokens;
- integration profiles;
- loopback-only Bridge endpoints;
- idempotency/request IDs;
- strict endpoint-specific validation.

Do not expose a generic arbitrary Bridge proxy.

### 5.2 Host profiles and paired-client grants

The machine profile registry owns non-secret definitions keyed by a stable,
opaque `profile_id`. The Windows account SID is the authoritative account
identity; the account name is display metadata. A profile rename never changes
its ID, SID, root, grants, or Android cache identity.

Each paired Gateway client owns explicit per-profile `use_profile` and
`remote_sign_in` grants. Every profile-scoped endpoint enforces `use_profile`;
listing only authorized profiles is a presentation aid, not the authorization
boundary. `remote_sign_in` is checked only when a locked or signed-out target
needs a credential submission. An already-active authorized profile remains
usable with `use_profile` alone.

### 5.3 Profile Bridge supervisor

The Profile Bridge supervisor already owns:

- per-user process lifecycle;
- Playnite/Vibepollo health checks;
- restart behavior;
- profile ownership;
- runtime status;
- process adoption.

Guardian should use host-side repair actions rather than reimplement process supervision on Android.

### 5.4 Game Provider Bridge

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

### 5.5 `OperationJournal`

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

### 5.6 Host Control, installer, and Windows sign-in

The installer owns machine binaries, Windows services, Credential Provider
registration, ACLs, the private-LAN firewall rule, and version-preserving
upgrades. It never asks for a Windows account or password and never enables
remote sign-in for a device.

Host Control owns profile and device management. Its separate elevated
Configurator enumerates local accounts, creates/edits/removes MoonWaker
profiles, manages grants, and collects a password directly in the elevated
window. Passwords are sent only over the local management pipe to
`MoonWakerLoginBroker`; they never pass through Gateway, Android, PowerShell
arguments, environment variables, ordinary configuration, or logs.

`MoonWakerLoginBroker` is a LocalSystem service and the sole owner of LSA private
secrets and short-lived sign-in attempts. Attempts are bound to client, profile,
request, and SID, expire after two minutes, and issue a credential once. A
failed submission consumes the attempt and changes the credential to
`action_required` until Host Control replaces or validates it.

The native x64 MoonWaker Credential Provider supports only Windows logon and
workstation unlock. It has no network code or provider filter, never hides
built-in providers, and enumerates a credential only for a pending Broker
attempt. Its fixed registration can be disabled or removed without changing
other providers.

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

## 7. Boot, sleep, and remote sign-in

The implemented bounded flow is:

```text
Wake-on-LAN / network ready
    -> pre-logon Gateway ready
    -> selected profile still authorized
    -> target Windows session active (or one Broker attempt)
    -> selected profile Bridges ready
    -> target ready
    -> existing Moonlight launch and transition privacy gates
```

`PlayIntent` pins `(hostId, profileId)` for the entire operation. Android polls
one attempt for at most two minutes and cancels it best-effort when orchestration
is cancelled or profile selection changes. Broker expiry is the fallback.
`INTERACTIVE_SESSION_READY` is separately observable; it is not a second
transition state machine. `LaunchTransitionController` remains authoritative
for stream privacy, fresh-frame readiness, reveal, and input gating.

Supported automatic authentication is limited to Windows 10/11 x64 local,
password-based accounts at LogonUI, when no other Windows user is active.
Windows Hello, Microsoft/Entra/domain accounts, RDP sessions, automatic user
switching/logout, BitLocker preboot prompts, UEFI passwords, and similar
preboot interaction require manual action.

Wake from full shutdown is hardware/firmware dependent and is not guaranteed.
The machine must first reach Windows and LogonUI; MoonWaker cannot cross a
BitLocker, firmware, or boot failure screen.

Recovery is always manual Windows sign-in with the built-in providers. An
administrator may run `Disable-MoonWakerCredentialProvider.ps1` or unregister
only MoonWaker's provider, then repair credentials in Host Control. Service-only
uninstall preserves profile data and secrets; full uninstall explicitly purges
configured Broker secrets before removing the Broker.

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
- preboot authentication and non-local Windows account automation.

## 11. Review rule

Every large MoonWaker change should answer:

1. What component owns the durable truth?
2. Is a new state machine being introduced unnecessarily?
3. Does this duplicate Gateway transport/security behavior?
4. Does Android implement host process supervision that belongs on Windows?
5. Does it modify Moonlight core without a streaming-specific reason?
6. Can the change be extracted as one vertical slice with characterization tests?

If any answer indicates duplicated ownership, stop and redesign before implementation.
