# S06 pre-edit characterization

- Task: `S06`
- Branch: `codex/session-hardening`
- HEAD at characterization: `b05f3e2cdbccdc6a04e8b800c00e26bf80f16110`
- Input fingerprint: `sha256:5e78588cdd4512554e4c2ef1d047b7bb0beba87e2809759231ad86b2df38145e`
- Dependency authority: the user's 2026-09-14 statement `Nie preedit. Wykonaj w pelni. Znosze blokade` is a narrow S06 dependency override. It does not accept S03-S05, waive their missing deterministic evidence, or authorize deployment, installation, service restart, commit, or push.

## Producer and consumer graph

| Boundary | Current responsibility | Current consumers | Finding |
|---|---|---|---|
| `GameProviderBridge.BridgeState` | Owns provider connection, catalog, canonical current game, verified running-process inventory and window readiness under one `RLock`. | Loopback `/health`, `/game/current`, `/window/readiness`, provider events and control effects. | The necessary facts share a serialization boundary, but HTTP snapshots split them. There is no observation schema, boot identity, monotonic observation revision or explicit evidence status. |
| Gateway `GatewayState.playnite_state` | Authenticates LAN callers, resolves actor and execution profiles, proxies the loopback Bridge, and owns control-operation generation and actor lease epoch. | `HostGatewayClient`, child and parent launch/stop/switch flows. | Current/readiness/health are proxied independently. Parent health is an extra dependency; child skips it. Actor/execution and control correlation are not returned with the observation. |
| `HostGatewayClient` | Authenticated transport, response bounds and JSON parsing. | `PlayniteTransitionGateway`, `ConsoleActivity`. | Exposes separate `PlayniteHealth`, `PlayniteCurrentGame`, and `PlayniteReadiness` reads. No strict version/freshness/order validation. |
| `PlayniteTransitionGateway` | Narrow streaming facade over Gateway. | `ConsoleStreamTransitionCoordinator`, `Game` retained switch/stop/recovery. | `snapshot()` serially performs health, current and readiness calls, then synthesizes process identity. The result can combine different host moments. Child readiness depends on a special connector shortcut rather than the same contract. |
| `SessionStateResolver` | Intended deterministic projection of host, retained, suspend and resume observations. | `SessionOrchestrator`, host status and dashboard paths. | Its input is a large mutable bag assembled in the Activity. It has no producer ordering/freshness contract. `retainedMatches` requires remote game agreement, so unknown or mismatched remote evidence can erase a known-live retained transport. `UNCERTAIN` has no self-contained bounded liveness. A single Activity instance owns it today, so `Game` cannot share the same accepted projection. |
| `RetainedStreamSessionCoordinator` | Android-authoritative owner of retained transport/session, actor/execution binding, transition, attempt and local control generation. | `Game`, `ConsoleActivity`, `SessionOrchestrator`, resume and switch paths. | Correct transport authority already exists and must remain separate. Callers reread its snapshot independently of the projected session, causing time-of-check/time-of-use drift. |
| `LaunchTransitionController` | Android-authoritative privacy, frame/readiness and input gate for one transition. | `Game`, `ConsoleStreamTransitionCoordinator`. | Correct presentation owner. Its host evidence currently comes from the incoherent synthesized Gateway snapshot and event stream. |
| `ConsoleStreamTransitionCoordinator` | Polls provider, enforces transition timeouts, records exact process identity and drives `LaunchTransitionController`. | `Game`. | Has bounded state timeouts, but accepts observations without producer identity/revision/freshness or expected transition correlation. A delayed response can be applied after a newer one. |
| `Game` | Owns active streaming Activity, retained provider switch, exact stop and whole-session recovery. | User-visible stream session. | Retained switch, provider stop and transfer fallback call the synthesized snapshot directly. Local transport generation exists but is not carried beside host observation diagnostics as a distinct axis. |
| `ConsoleActivity` | Dashboard presentation and `SessionOrchestrator.Effects`. | Host badges, game/app tiles, Play actions, end-game and refresh flows. | Maintains parallel maps for current game, state, app, resolution time and running inventory; also rereads Sunshine and retained state. Host badges, tiles, Play, close and retained-switch eligibility reconstruct truth differently. Async refresh can be followed immediately by a stale resolve. |

## Inconsistent reads and parallel truth

1. `PlayniteTransitionGateway.snapshot()` reads provider health, current game and window readiness in separate HTTP calls. Any game start/stop, connector change or window transition between them creates an impossible composite.
2. `ConsoleActivity.resolveActivePlayniteGame()` caches current state while `resolveSessionSnapshot()` separately reads Sunshine and `RetainedStreamSessionCoordinator`; callers often start the async refresh and immediately resolve the old cache.
3. Host badges and Playnite tiles use different mixtures of `SessionSnapshot`, `activePlayniteGame*`, `RunningGameObservation`, Sunshine `runningGameId`, warm-up state and a fresh retained snapshot.
4. `canAttemptRetainedSwitch`, `closePreviousSession`, quick resume, end-game and app actions reread these sources instead of consuming the same projection used for presentation.
5. `Game` transition observation and compensation obtain another synthesized snapshot independent of the dashboard projection and without producer ordering.
6. Provider `/events` can directly advance the transition controller without the same producer boot/revision fence, bypassing any coherent snapshot contract.
7. `ConsoleStreamTransitionCoordinator.applySnapshot()` substitutes the requested game when the producer returns no game ID, turning missing evidence into apparent target evidence.

## Missing identity and fences

- No schema version or compatibility disposition.
- No Game Provider boot identity; a revision cannot be scoped across provider restarts.
- No monotonic observation revision, so response A cannot be rejected after response B.
- Observation time and freshness are implicit and partly measured on different machines.
- Actor profile and execution profile are request context only, not immutable observation fields.
- Operation ID, transition ID, Gateway control generation and actor lease epoch are absent from the read model.
- Verified process identity is inferred from a current-game payload plus a separately refreshed inventory; missing identity is represented by empty strings/zero rather than an evidence state.
- Window/readiness evidence has booleans and reason strings but no explicit present/missing/unavailable status.
- Android retained transport generation is not carried as a separate local fence next to the remote observation.
- Existing `RetainedStreamSessionCoordinator.controlGeneration` is a Gateway authorization generation, not a local transport generation.

## Unbounded or ambiguous states

- Provider `reconciling`, `ambiguous`, stale inventory and unavailable connector states can remain generic `UNCERTAIN` in the dashboard resolver.
- Gateway failure is sometimes treated as parent health failure and sometimes ignored for a child.
- `SessionOrchestrator` rewrites `UNCERTAIN` to `NONE` or `ACTIVE` for generic apps, which is an alternate liveness policy rather than a proven transition.
- A known-live retained stream can become logically absent when remote game identity is missing or mismatched, inviting reconnect or replacement instead of preserving transport.
- Transition polling has state timeouts, but the individual observations have no freshness/order fence and no explicit terminal projection of `READY`, `IDLE`, `RECOVERY_REQUIRED`, or `UNAVAILABLE`.
- Fresh responses for the same `reconciling`, `ambiguous`, locked or unavailable episode could reset a naive TTL forever; freshness and liveness episode deadlines are different facts.

## Required ownership after migration

- Game Provider: sole producer of canonical game/process/window/provider evidence at one lock-protected linearization point.
- Gateway: sole LAN-facing authenticated envelope for actor, execution and host control correlation; no parent-only health prerequisite.
- `HostGatewayClient`: strict parser for the versioned envelope and explicit unsupported/malformed result.
- `SessionStateResolver`: one process-wide, serialized Android projection owner keyed by host/actor/execution; it assigns request ordinals, owns retired producer boots, accepts producer boot/revision/freshness and owns bounded unknown-episode liveness.
- `RetainedStreamSessionCoordinator`: unchanged sole owner of local retained transport and local generation.
- `LaunchTransitionController`: unchanged sole owner of reveal/input privacy.
- `ConsoleActivity`, `Game` and `ConsoleStreamTransitionCoordinator`: consumers of the shared observation/projection; no alternate state reconstruction.

## Exact migration owners and files

| Owner | Production files | Test owners |
|---|---|---|
| Game Provider producer | `host-services/bridges/playnite/GameProviderBridge.py` | `host-services/bridges/playnite/test_playnite_bridge.py`, `test_running_games.py` and focused provider tests selected by discovery |
| Gateway authenticated envelope | `host-services/gateway/wakeplay_gateway.py` | `host-services/gateway/test_gateway.py`, child-profile and control-operation focused tests selected by discovery |
| Android wire contract | `app/src/main/java/com/limelight/console/HostGatewayClient.java`, `PlayniteTransitionGateway.java` | `HostGatewayClientTest`, `PlayniteTransitionGatewayTest` |
| Android canonical projection | `SessionSnapshot.java`, `SessionStateResolver.java`, `PlayniteSessionPresentation.java` | `SessionStateResolverTest` and presentation tests |
| Local transport generation | `app/src/main/java/com/limelight/stream/RetainedStreamSessionCoordinator.java` | `app/src/test/java/com/limelight/stream/RetainedStreamSessionCoordinatorTest.java` |
| Transition consumer | `ConsoleStreamTransitionCoordinator.java` | `ConsoleStreamTransitionCoordinatorTest` |
| Dashboard/orchestration consumers | `ConsoleActivity.java`, `SessionOrchestrator.java` | `ConsoleActivityEnsureContractTest`, `ConsoleWindowsProfileSwitchTest`, `PlayniteTransitionGatewayTest`, `SessionOrchestratorTest` and focused contract tests |
| Stream-session consumer | `app/src/main/java/com/limelight/Game.java`, `app/src/main/java/com/limelight/stream/HostSessionTermination.java` | `GameTransitionCancelContractTest`, termination and retained-stream focused tests |

The list is bounded to observation production, transport parsing, projection and consumers. Existing control, retained transport and privacy owners must be extended only where a correlation field is needed; no new durable store or profile-specific lifecycle is permitted.
