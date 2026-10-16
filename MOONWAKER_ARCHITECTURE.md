# MoonWaker Architecture

Purpose: the architectural boundaries between the Android client, the Gateway, and the host Bridges.

## 1. Product boundary

MoonWaker is a console-oriented Android TV client built on Moonlight. It coordinates:

- Moonlight/Sunshine-compatible game streaming;
- Vibepollo for stream host integration (called by the Gateway in process);
- Playnite as the game library and lifecycle source;
- MoonWaker Gateway as the authenticated client-facing host API;
- per-profile host processes: the Playnite Agent and (optional, `-WithDiscord` builds only) the Discord Bridge;
- host-side control, installation, repair, and lifecycle tooling.

Moonlight's streaming core is preserved and MoonWaker-specific orchestration is isolated around it.

## 2. Architectural principles

### 2.1 One authoritative source per kind of state

Do not maintain competing durable truths on Android and Windows for the same operation.

Examples:

- installation/uninstallation lifecycle is host-authoritative through GameOps / `OperationJournal`;
- session state and the persistent session records are owned by `SessionModel` (one model thread, immutable published snapshots);
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

Orchestration components execute the effects:

- Wake-on-LAN;
- Gateway requests;
- Playnite focus;
- game launch;
- stream launch/reconnect.

They feed observations back into the state machine instead of maintaining a competing transition state machine.

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

### 3.1 Shape

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

`ConsoleActivity` is the console's composition root.

Its responsibility is:

- construct/wire controllers;
- render state;
- handle Android TV focus/navigation;
- show panels/dialogs;
- forward user intents to controllers/orchestrators.

It does not own:

- durable GameOps interpretation;
- launch/wake orchestration;
- session truth resolution;
- Gateway transport details.

Extract vertical slices into their own classes; do not grow the Activity.

### 3.3 `SessionStateResolver`

`SessionStateResolver` converts several lower-level observations into one deterministic `SessionSnapshot`.

Inputs include:

- the Gateway observation (including `streamer`, the Sunshine app the host's streamer runs; the serverinfo `ComputerDetails.runningGameId` feeds only `SessionModel`);
- resolved Playnite game ID;
- the session model's state (`SessionModel`);
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

`SessionSnapshot` is the single resolver output; do not create parallel snapshot types for each UI path. `PlayniteSessionPresentation` and the host presentation are read-only projections over it: one resolution step, many projections.

### 3.4 `GameOperationsController`

`GameOperationsController` owns install/uninstall orchestration and UI-facing operation projection.

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

Stages (`HostLaunchPreflight.Stage`):

```text
NETWORK_READY
GATEWAY_READY
PROFILE_AUTHORIZED
INTERACTIVE_SESSION_READY
PROFILE_READY
PLAYNITE_READY
TARGET_READY
```

Only dependencies required for the requested action should block launch.

Examples:

- Discord failure must not block normal game launch.

### 3.7 `Game` / stream Activity

`Game` should remain primarily the Moonlight streaming Activity.

MoonWaker transition polling and recovery live in the narrow `ConsoleStreamTransitionCoordinator`.

Responsibilities:

- Playnite/Gateway transition observation;
- event long-polling;
- readiness polling;
- focus recovery;
- Playnite Fullscreen recovery;
- transition timeout policy;
- conversion of host observations into calls to `LaunchTransitionController`.

Decoder, input, and core NvConnection lifecycle stay out of this coordinator.

## 4. Gateway client architecture

### 4.1 Connection terminology

- **`GatewayConnection`** — immutable validated connection descriptor / request context: endpoint, token, pinned certificate fingerprint, and integration profile. It is not a live socket and does not own retries or connection lifecycle.
- **`GatewayTransport`** — the single request executor for HTTPS/TLS, headers, response bounds, and error mapping. It creates a new `HttpsURLConnection` per request.
- **domain client** — endpoint-specific request construction and DTO parsing: `HostGatewayClient`, `ChildSessionClient`, `ControlOperationClient`, `DiscordGatewayClient`.

```text
GatewayConnection  (canonical immutable descriptor)
        |
GatewayTransport   (single HTTPS/TLS implementation)
        |
        +-- HostGatewayClient
        +-- ChildSessionClient
        +-- ControlOperationClient
        +-- DiscordGatewayClient
```

`GatewayTransport` owns HTTP/TLS/security behavior. Domain clients own endpoint paths, request bodies, domain DTO parsing, and domain-specific validation and delegate network/security behavior to the transport. There is no `GatewaySession` abstraction: MoonWaker has no long-lived logical Gateway session.

## 5. Windows host architecture

### 5.1 Gateway

Gateway remains the only LAN-facing host API.

`MoonWakerGateway` is an automatic Windows service running under the restricted
`NT SERVICE\MoonWakerGateway` virtual account. Its host,
`MoonWakerGatewayService.exe`, supervises only the machine Gateway process (restart after
an exit with a 2–16 s backoff, state in `gateway-service-state.json`) and is available
before interactive sign-in. The service is the Gateway's only launch path. It must not start profile Bridges in session 0.

Keep:

- authenticated HTTPS;
- certificate pinning model;
- Bearer tokens;
- integration profiles;
- loopback-only Bridge endpoints;
- idempotency/request IDs;
- strict endpoint-specific validation.

Do not expose a generic arbitrary Bridge proxy.

The Gateway owns the machine's single Vibepollo connection in process
(`vibepollo_client.py`): one token per computer in `gateway\vibepollo-api.json`
(Administrators, SYSTEM, read for the service; written only by the elevated
Configurator), the HTTPS certificate pinned to the one in Vibepollo's
configuration directory, control snapshots, the `MoonWaker Stream` application,
Moonlight pairing, identity proofs and repair actions.

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

An optional four-digit app PIN is stored only as a versioned salted verifier in
the machine profile registry. Profile responses expose only `pin_required`.
Verification requires an authenticated `use_profile` client, is rate limited per
client and profile. Android supplies a transient process UUID through the shared
Gateway transport in `X-MoonWaker-Profile-Session`. Successful verification creates
one in-memory authorization per paired client/profile, bound to that UUID and
the current verifier. An open application process keeps its authorization across
screensaver idle time. A different or missing UUID cannot reuse it, and a
verification without the header is rejected; a new PIN verification replaces it.
Gateway restart, verifier changes, and existing grant checks revoke or reject access.
Existing profile-scoped endpoints enforce this boundary;
no plaintext PIN, session UUID, or host unlock state is persisted on Android.

Before waking an unreachable host, Android may check a device-local PIN verifier
learned only after successful Gateway verification on that paired device. This
preliminary check permits Wake-on-LAN only; it does not authorize profile access.
The verifier is bound to host, profile and pairing identity, protected by an
Android Keystore key, excluded from backup, and locally rate limited across
process restarts. No server verifier or plaintext PIN is exported or persisted.
This requires Android 6.0/API 23 or newer; older clients remain online-only.
An unlearned profile requires one online verification first. After wake the same
entered PIN is held only for the bounded foreground request and verified by the
Gateway before selected-profile persistence, protected loading or sign-in.
Cancellation discards the pending request. A stale local PIN can wake the host
but cannot override its current PIN or grants.

Android resolves the authorized profile gate before profile-scoped library
loading. A protected selection is persisted only after Gateway verification,
and a protected profile cannot remain the per-host automatic app profile.
Selecting or refreshing an app profile has no Windows-session side effect.

### 5.3 Host Control tray hosts the profile Agent

Host Control's tray, started at every sign-in by one machine `Run` entry, owns the
per-user process lifecycle of the signed-in account's profile: the Agent
(`GameProviderBridge.py` under `pythonw`) and the optional Discord Bridge (installed only
from a `-WithDiscord` package, i.e. when `bridge-source\discord` exists), both run
from the installation in a kill-on-close Job Object, restarted after an exit with a
2–30 s backoff. Its status for Host Control comes from this host plus the
children's `/health` and `/observation`; there is no state file and no process
adoption by port or PID.

Profile authority has one owner, the Agent (`AgentGuard.py`): it exits with code 78
when its registry entry is disabled, tombstoned, re-rooted, a child profile or bound to
another SID, and the tray does not restart it until that entry changes; an unreadable
registry (code 74) is only a backoff. A tray with an elevated token hosts nothing. Its watchdog ends a hung
process (code 70) so the tray restarts it.

Android does not supervise host processes; repair is Host Control's **Napraw**.

### 5.4 Game Provider Bridge

`GameProviderBridge.py` (the Agent) combines:

- Playnite pipe/IPC;
- library cache;
- lifecycle events;
- window readiness;
- stream-display resolution;
- install/uninstall orchestration;
- Steam probing/automation;
- Epic probing;
- HTTP serving.

GameOps live in `GameOperations.py` and `OperationJournal.py` beside it:

```text
GameProviderBridge
    |
    +-- Playnite IPC / library / lifecycle
    +-- readiness
    +-- GameOperationsService   (GameOperations.py)
            |
            +-- OperationJournal   (OperationJournal.py)
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

Host Control owns profile and device management. Its editors (profiles, app
PIN, remote sign-in, child profiles, device grants) run in the unelevated
window and only build a change set; "Zapisz" (UAC) hands it to the elevated
Configurator (`--apply --pipe <GUID> --client <SID>`) over a one-exchange named
pipe that the helper creates for that SID alone (first instance, no remote
clients, NETWORK denied) and that each side binds to the other's process id.
The helper treats the set as untrusted: it parses it strictly, validates every
change against the `gateway.json` revision (SHA-256) Host Control showed,
shows it without secrets in its own window, and applies it under
`gateway.json.lock` in one transaction that checks the revision again. It
creates/edits/removes MoonWaker profiles (including each new profile's data
directory and its ACL, in process) and grants, and collects the Windows
password and Vibepollo's administrator login only in its own elevated window;
the app PIN may travel in the change set. Host
Control's own backend runs in the unelevated tray without PowerShell: its status
only reads what the Gateway publishes and the loopback answers of the hosted
processes, and the account's actions (pairing code, close session, Steam key and
Discord secret under the account's DPAPI, Legendary, Playnite) use the existing
file channels. Its one **Napraw** restarts the Gateway service (start/stop are
granted to interactive users by the service DACL) and the account's hosted
processes, only where they do not work. Passwords are sent only over the local management pipe to
`MoonWakerLoginBroker`; they never pass through Gateway, Android, PowerShell
arguments, environment variables, ordinary configuration, or logs.

Host Control's "Zamknij sesję" (tray menu and `Ctrl+Alt+Shift+End`) is a request to
the Gateway, the owner of the stream record: Host Control writes a one-time, short-lived
request into `host-close-request.json` in the Gateway directory (installer-granted ACL:
local accounts overwrite it in place and cannot delete it) and shows the outcome the
Gateway publishes in `host-close-result.json`. The Gateway ends the stream and the verified
game as a host operation through its control-operation ledger; a game process it cannot
confirm is left and reported, and the stream alone is closed. By the same host operation
the Gateway closes a stream record that had no client and no game for 10 minutes. Host
Control itself calls no Bridge and the Gateway gains no local-only HTTP route. See
`host-services/gateway/TRANSITION_CONTRACT.md`.

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

### 6.1 Android privacy cover

The privacy cover is a cosmetic UX layer, not a security or privacy boundary.
`LaunchTransitionController` remains responsible for ensuring Android does not
reveal the stream automatically before:

- transport is ready;
- correct host is correlated;
- correct game/target is correlated;
- target process/window is ready;
- a fresh video frame is observed after the readiness edge;
- reveal animation has completed before input is unblocked.

When readiness fails or stays uncertain while transport delivers frames, the
user can always reveal manually ("Reveal now"); the cover never rejects them.
After any reveal, automatic or manual, the cover does not return within that
transition or stream; a lost game window or later failure there is only a notice. It may return only with a new
transition the user starts (for example a game started from the revealed
Playnite).

## 7. Boot, sleep, and remote sign-in

The implemented bounded flow is:

```text
Wake-on-LAN / network ready
    -> pre-logon Gateway ready
    -> selected profile still authorized
    -> target Windows session active (or one Broker attempt)
    -> selected profile Bridges ready
    -> target ready
    -> existing Moonlight launch and transition privacy cover
```

`PlayIntent` pins `(hostId, profileId)` for the entire operation. Android polls
one attempt for at most two minutes and cancels it best-effort when orchestration
is cancelled or profile selection changes. Broker expiry is the fallback.
`INTERACTIVE_SESSION_READY` is separately observable; it is not a second
transition state machine. `LaunchTransitionController` remains authoritative
for the cosmetic privacy cover, fresh-frame readiness, reveal, and input gating.

Changing the active Windows desktop is a separate, explicit `session/switch`
mutation. The authenticated request requires `use_profile`, `remote_sign_in`, a
valid protected-profile lease when applicable, and a stable request ID. Login
Broker tests verify that exactly one resolved local console session is passed to
`WTSDisconnectSession` before one request-bound target attempt is created; the
tested rejection paths create no attempt and never call a logoff API.

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
- `SessionModel`;
- Gateway TLS/auth/profile boundary;
- host loopback-only Bridge architecture;
- Steam safety checks that validate launcher/process/window before automation;
- Moonlight core streaming path.

## 9. Review rule

Every large MoonWaker change should answer:

1. What component owns the durable truth?
2. Is a new state machine being introduced unnecessarily?
3. Does this duplicate Gateway transport/security behavior?
4. Does Android implement host process supervision that belongs on Windows?
5. Does it modify Moonlight core without a streaming-specific reason?
6. Can the change be extracted as one vertical slice with characterization tests?

If any answer indicates duplicated ownership, stop and redesign before implementation.
