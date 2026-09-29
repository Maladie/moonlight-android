# S06 coherent observation contract

## Minimal wire API

Add one authenticated Gateway read, `GET /api/v1/session/observation`, backed by one loopback Game Provider read, `GET /observation`. Keep existing reads for old clients, but migrate every S06 consumer to the new endpoint.

The Game Provider creates the payload under its existing `BridgeState.lock` after its current in-lock reconciliation. One snapshot allocation increments a producer-local monotonic revision. The producer boot ID is a random opaque ID created once in `BridgeState.__init__`; revisions are comparable only within that boot.

Required envelope:

```text
schema_version
producer { boot_id, revision }
observed_at_unix_ms
fresh_for_ms
scope { actor_profile_id, execution_profile_id }
correlation { operation_id, transition_id, control_generation, actor_lease_epoch }
provider { state, connector_evidence, reason }
game { evidence, id, state }
process { evidence, process_id, process_token }
window { evidence, ready, target_kind, stable_samples, reason }
capabilities { host_guide_allowed, requires_connector, start_before_stream }
```

The envelope also carries `stream_session_id`, a producer `state_since_unix_ms` (or equivalent stable episode identity plus deadline), and the Gateway-echoed Android `request_ordinal`. Echoed request/transition values are labelled as caller correlation and are never presented as producer-observed state.

Evidence is explicit: `VERIFIED`, `ABSENT`, `PENDING`, or `UNAVAILABLE`. Empty ID/token/zero PID may accompany `ABSENT` or `UNAVAILABLE`, but must never be interpreted as proof. A `VERIFIED` process requires canonical game ID, positive PID and a 64-hex process token from the same fresh running inventory captured by the producer.

Gateway adds scope and correlation without changing provider truth. It captures the relevant control record under `GatewayState.lock`, proxies the atomic provider snapshot, then rechecks the control record under the lock. If the correlation changed across the proxy, it returns an explicit unavailable/stale envelope rather than combining the two moments. `transition_id` and positive `request_ordinal` are validated caller correlation echoed in the response. Local Android transport generation is deliberately absent from remote producer truth.

## Android acceptance and projection

`HostGatewayClient` strictly validates schema 1, boot ID, positive revision, plausible observation time/freshness, actor/execution scope, request/transition correlation and evidence consistency. Unsupported schema, malformed evidence, expired observation, profile mismatch or correlation mismatch becomes `UNAVAILABLE`; it is never silently projected as idle. Producer wall time is diagnostic; expiry is also enforced from local monotonic receipt time with a hard cap.

`SessionStateResolver` becomes one process-wide, serialized, non-durable projection owner keyed by `(host, actor, execution)`. It assigns every outbound observation request a monotonic dispatch ordinal, accepts all host responses and publishes the one `SessionSnapshot` read by dashboard and streaming consumers:

- accept only a response whose echoed dispatch ordinal is newer than the last accepted ordinal for the scope;
- same boot: additionally require a strictly newer producer revision;
- different boot: require a newer dispatch, retire the previous boot ID in a bounded in-memory set and accept the new boot; any later response from a retired boot is rejected;
- delayed A after newer B, including A from the old boot after B from a new boot: reject A without changing the projection;
- missing/failed observation: preserve known local retained transport and last safe presentation evidence until a bounded deadline;
- deadline terminal: `RECOVERY_REQUIRED` when a local live/owned session or an authorized operation needs exact remote proof, otherwise `UNAVAILABLE`;
- verified idle plus no local live/suspended/resume state: `IDLE`;
- verified matching game/window plus required local transport/frame state: `READY`.

The projection carries the accepted producer boot/revision/time, actor/execution, operation/transition/control fences, canonical game, process evidence, window evidence and reason. It also carries the separately captured local retained stream session ID, transition ID, attempt, Gateway control generation and a new local `transportGeneration`. `RetainedStreamSessionCoordinator` increments `transportGeneration` only when ownership of a new local stream is established; it does not alias control generation, transition attempt or producer revision. Remote mismatch can close readiness or require recovery, but cannot turn a known-live local transport into no transport.

`SessionOrchestrator`, host badges, tiles, quick resume, retained switch/close, end-game decisions, `Game` and `ConsoleStreamTransitionCoordinator` consume the same published `SessionSnapshot`. The current Activity maps and helper reconstructions are removed or reduced to rendering-only request bookkeeping. No second consumer-local DTO acceptance fence is allowed. The transition consumer requires `game.evidence=VERIFIED` for the exact target before process/window readiness can drive `LaunchTransitionController`; it must never substitute the requested game for missing producer evidence.

The legacy provider event stream may trigger a new observation dispatch or carry notifications unrelated to readiness. It cannot itself prove game/process/window state or directly open the readiness/reveal gate. Any stateful event retained for readiness must first be incorporated into a new boot/revision observation and pass the single owner fence.

## Bounded liveness and recovery

Use existing transition state deadlines for active launches. For dashboard/session projection, one explicit bounded episode deadline is keyed by `(scope, producer boot, operation/transition, provider state episode)`. A newer revision of the same pending/reconciling/ambiguous/locked/unavailable episode cannot reset it. `fresh_for_ms` only limits one response and is not the liveness deadline. Before expiry, state is pending without authorizing destructive or reveal actions. At expiry it deterministically becomes:

- `READY`: coherent required evidence and local gates are satisfied;
- `IDLE`: verified provider idle and no live/recoverable local session;
- `RECOVERY_REQUIRED`: local session/control exists but exact game/process/window proof is unavailable or contradictory;
- `UNAVAILABLE`: no local session to protect and the producer cannot provide a valid observation.

`RECOVERY_REQUIRED` is diagnostic/actionable state and never auto-closes a known live stream. Recovery reuses the existing separately authorized verified full-close/session reset path. It must keep the privacy controller closed while proof is missing, terminate only through exact session/app/game/control/transport-generation fences, clear the terminal operation, then execute a brand-new authorized Play with new operation, transition and local transport generation. The regression is not complete until that next Play reaches a fresh provider operation and can converge to ready; a successful reset response alone is insufficient.

## Compatibility matrix

| Client | Gateway / Provider | Behavior |
|---|---|---|
| New | New schema 1 | Full coherent observation and projection. |
| Old | New | Existing health/current/readiness endpoints remain compatible; no forced cutover. |
| New | Old, endpoint absent | Explicit `UNAVAILABLE`; preserve any known-live local retained transport, block reveal/destructive decisions lacking proof, offer bounded recovery. No unsafe reconstruction from old endpoints. |
| New | New, unsupported future schema | Fail closed as `UNAVAILABLE` or `RECOVERY_REQUIRED` according to local transport; retain local transport. |
| New parent | New | Same endpoint and schema; actor equals execution. |
| New child | New | Same endpoint and schema; child actor plus parent execution scope. No parent-only health call or hidden child-only lifecycle. |
| Mixed response or scope/correlation change | Any | Reject the observation; never merge old and new endpoint data. |

## Explicit non-goals

- No direct Android-to-Bridge access.
- No new durable observation cache or child lifecycle.
- No replacement of retained transport, control-operation, or privacy owners.
- No inference of process identity from PID/name alone.
- No deployment, service restart, APK installation, commit or push in this run.
