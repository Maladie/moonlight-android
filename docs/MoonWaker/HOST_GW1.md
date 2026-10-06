# HOST-GW1 — Gateway session fixes found by MW-02 captures A1–A3 (task packet)

Branch `host-gateway-session-fixes`, worktree `work/host-gw`, base `ec2d59bb`. Owner
authorised: diagnosis in Gateway code, host-side fixes, host test suites. **Not**
authorised: installing the host, building the installer (owner's decision after review),
editing the Android app (report client-side findings instead).

Evidence: `docs/MoonWaker/MW-02_CAPTURE_PL.md` §6 rows A1–A3 (Polish). Logs on this PC
(the owner's host): Gateway `C:\Program Files\MoonWaker\gateway\logs\gateway-diagnostics.jsonl`,
Sunshine (Vibepollo 1.18.4) `E:\Gry\Sunshine\config\logs\*.log`. Read them; do not modify
anything under `C:\Program Files\MoonWaker` or `E:\Gry\Sunshine`.

## Defect 1 — stale managed stream after a client-side abort (409)

Timeline 2026-10-05 (UTC): 17:35:19 client `POST /control/operation/acquire` → 200,
launch of Sunshine app "Desktop"; 17:35:34 client cancels in `CONNECTING_STREAM`
(`runtime.stop_requested`, RTSP PLAY never completed); Sunshine: 17:35:37 "Session
resuming for app [Desktop]", 17:35:40 "Ping Timeout", 17:35:41 "Session ended".
17:41:13 client `POST /control/operation/acquire` → **409
`{"state":"blocked","reason":"managed_stream_conflict"}`** (`wakeplay_gateway.py` ≈4823):
the Gateway still held the managed-stream record from 17:35 although Sunshine had ended
the session six minutes earlier. The owner had to restart the host services.

Required: the managed-stream record must be released when its stream is gone. Find the
authoritative signal the Gateway already has (Sunshine session end / no client transport
/ the client's `operation/complete` that was never sent / a bounded liveness timeout) and
release on it, consistent with `TRANSITION_CONTRACT.md` and `docs/agents/SESSION_CONTRACTS.md`
(host journal is the durability owner; reject stale effects; a resume record is not a live
connection). No blanket "clear on any acquire": a genuinely live stream owned by another
client must still conflict. Add a Gateway unit test for the exact A1→A2 sequence.

## Defect 2 — 403 on `/api/v1/session/observation` and `/api/v1/discord/status` (A3)

For profile `p-1b2bc7e67341444686afa8b35bfc55bc` (parent profile "Basia", PIN-protected)
these two routes answer 403 `builtins.PermissionError` almost always (since 2026-09-29;
only two 200s on 2026-10-05 12:37Z), while `/library`, `/profiles/pin/verify`,
`/system/session/ensure`, `/control/operation/*` succeed for the same profile and client.
Consequence: the Android client never receives a host session observation, so its UI
shows the host only as "Online" even while a stream/game runs (owner-observed O1).

Both routes pass through `authorize_profile(authenticated_client)` (handler ≈7619 →
`GatewayState.authorize_profile` ≈1873 → `require_profile_unlock` ≈2004, lease keyed by
`pin_client_key(client)` × profile × `X-MoonWaker-Profile-Session`). Determine, from the
Gateway log fields (`request_id`, `profile_id`, timing relative to `pin/verify`) and code,
why these requests are denied while others pass: requests issued before the PIN unlock
(client polling while the gate is shown), a different client identity/token for the
"transition"/"console" pollers, a lease keyed by a different session id, or a read route
that should not require the PIN lease at all. Fix on the host side only where the host
is wrong (e.g. a read-only observation for a profile the client is already authorised to
use must not depend on the PIN lease, if the contracts allow it — check
`SESSION_CONTRACTS.md` and the PIN design before relaxing anything; privacy of a locked
profile must not regress). If the root cause is the client, write it up precisely
(route, header, timing) in the hand-back — do not edit the app.

## Validation

- `python -m unittest discover -s host-services/gateway -p 'test*.py'` (see
  `docs/agents/VALIDATION.md`); add tests next to the existing ones.
- Do not run `Build-HostServices.ps1` (it fetches a pinned legendary.exe and builds the
  installer) and do not install anything.

## Hand-back

CODEX_WORKFLOW.md format: task, HEAD + delta, root cause per defect with file:line, the
fix and why it preserves the contracts, tests added and their results, anything client-side,
and what the owner must do (install) to verify on the real host.

## Follow-up — a stream that retains no process identity was stuck on a partial scan

A managed stream with no `process_token` and no `provider_game_id` (every freshly
launched stream until a later resume adopts an identity: Desktop, but also a game) could be
neither resumed, closed nor idle-reconciled: the stop branch, the resume/reconnect/warm_up
branch and `_control_stream_idle` each required `_child_time_current_game` to return
`idle`, which needs `running_scan_status == "complete"`. The Bridge reports the process
PENDING with a "partial" scan while idle, so all three answered
`retained_process_unconfirmed` (or never reconciled) until a hard reset.

Root cause of the partial scan: a Medium-integrity Bridge cannot read the owner token of
an elevated process, and any such process with a visible window (or a game executable's
image name) made the whole running scan "partial" for as long as it ran. The Bridge now
re-reads such a process's image path without the owner and marks the scan partial only
when that path is unreadable or lies in the catalog (exact game executable or a game's
install directory); the process never joins the running games. An idle host with an
elevated desktop app open now reports a "complete" scan.

Final rule (owner decision; it closes the open point of the first follow-up, which had
accepted a "partial" scan for token-less streams and is reverted): the stop branch, the
resume/reconnect/warm_up branch and `_control_stream_idle` share
`GatewayState._control_retained_process`, and every stream, with or without a
`process_token` or `provider_game_id`, needs the same provider proof: a matching active
game, or idle (game and process evidence ABSENT, state idle, no current game id, a
"complete" running scan and, for a retained target, its exact `target_status` gone or
reused). A "partial" or "unavailable" scan, an unreachable provider or a malformed
observation rejects with `retained_process_unconfirmed` and never idle-reconciles.
Ownership checks, generation fencing, the rebind condition of Defect 1 and the
child-entitlement gate are untouched. Tests: `test_control_operations.py`, the
`tokenless` / `token_bearing` / `rejected_close` cases; Bridge:
`test_playnite_bridge.py` `RunningScanCompletenessTest`.
