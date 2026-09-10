# Correlation semantics — normative; reuse existing equivalent fields

Names below define meanings, not mandatory new classes or duplicate stores. S00 maps each meaning to an actual owner/field. Add a field only where equivalent evidence is missing.

- `transportGeneration`: one actual local/native transport lifetime. Home and A→B do not change it. A real disconnect/reconnect does.
- `authorizationEpoch` / host-issued child lease identity: permission binding of actor to execution profile and transport. Rebinding parent→child or child→parent changes this even when the native transport is reused. A stale child's expiry must not terminate a later parent's authorization on the same device/socket.
- `gameExecutionId`: one launch instance, not a game catalog ID. Bind to verified PID plus creation identity/token and intended execution profile. A second launch of the same title is a different execution.
- `operationId`: one accepted intent/effect workflow. Retries for that operation cannot trigger a second execution. Idempotency must be authorization-scoped; another actor cannot replay an ID to receive privileges/results.
- `transitionId` and `surfaceGeneration`: current presentation attempt and rendering attachment. A fresh surface frame alone does not prove the correct game's readiness.
- `bootId` + `revision`: producer epoch and monotonic snapshot version within that epoch. After restart, revision 1 is not comparable to revision 1000 from the old epoch. Resolve ownership and resynchronize before destructive decisions.

Every effect checks the identity appropriate to its target. A switch-to-B operation can legitimately stop captured execution A while the UI target is already B; do not reject this valid stop merely because currentGameId changed. Conversely an old stop must never resolve its target from the mutable current game when it finally runs.

Snapshots must be coherent across the facts used for a decision. Merely tagging separate reads with the caller's same requestId, or adding a Gateway revision around uncoordinated Bridge reads, does not make those facts atomic. Use the authoritative provider's existing observation/serialization boundary where possible.

Capture identity before asynchronous work. Host validates actor/device permissions and owns security-sensitive bindings; never trust a client-provided `child=false`, profile ID or generation to grant authority. A local-only token cannot fence a global remote quit unless all competing effects are actually serialized at the authoritative host boundary.

No wire/API design is silently mandated where backend capability is unknown. If a primitive cannot safely terminate only the intended stream, document the actual limitation, protect new starts and unrelated sessions, and mark the affected acceptance gate BLOCKED. Do not claim the product contract passes merely because the game process stops.
