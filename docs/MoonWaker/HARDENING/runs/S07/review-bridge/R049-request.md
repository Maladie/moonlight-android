# R049: S07 pre-edit contract/design review — physical stream runtime out of Game

Author: Claude. Date: 2026-09-29. Do not run lab tests. **No product file has been edited.**
This is the mandatory S07 pre-edit independent contract review (CHECKPOINTS.md "Reviewer checks
design first for S05/S06/S07"). Please review the design before any mutation.

## Repository / identity
- Repo `D:\Maladie\moonlight-android`, branch `codex/session-hardening` (local only, never pushed),
  HEAD `3913979f9e2b647f5d9bfbebdd86a73cbd39ae43`. S07 is worked directly on this branch (not s06-int).
- Source fingerprint (`packet.py fingerprint --repo .`, includes untracked worktree files):
  `sha256:eb5978f82809e316b87fffaae80001220e403d66dd409116cf9c87a91e75ea4f`.
- Lineage: history was rewritten before S07; old tip `b05f3e2c` is the local branch
  `backup/session-hardening-b05f3e2c`. `STATE.workspace.base_head` `00366fd9` is an ancestor of the
  backup, not of HEAD, so `packet.py branch-check` fails at `merge-base --is-ancestor` (exit 1).
  Recorded in the plan, STATE not edited (owner instruction). `validate` = VALID_PACKET_METADATA;
  `next` = S07 dispatchable (S03–S06 CLOSED_BY_OWNER, amendment S03-S06-20260929-OWNER-CLOSURE).
- Untracked leftovers (root PNG/XML, host-services/dist-*, .tmp/, work/) and the moonlight-common-c
  submodule pointer difference are not S07 input and will not be staged or changed.

## New files (all untracked, under docs/MoonWaker/HARDENING/runs/S07/)
| file | sha256 |
|---|---|
| dispatch.md (`packet.py context S07 --role worker`) | 19e0f557…1839 |
| characterization.md | 21638d6e…0fc2 |
| design.md | 7dd2ac52…189d |
| checkpoint-plan.json | 48d31ac6…43d9 |

Full hashes: run `sha256sum docs/MoonWaker/HARDENING/runs/S07/*`.

## What to review
1. `characterization.md`: is the current ownership map accurate (Game owns NvConnection,
   TransportStopCoordinator, MoonBridge static listener, audio/switchable video renderers, Wi-Fi locks,
   ControllerHandler/USB; `onDestroy` stops the retained transport; coordinator holds the Controller
   weakly; FGS only started from park paths)? Please spot-check Game.java lines cited there.
2. `design.md`: the proposed owner `StreamRuntime` behind `RetainedStreamSessionCoordinator`, seams,
   attach/detach identity token, onDestroy = detach only, no-presentation handling (termination,
   expiry/end, Console instant resume launching an attaching Game), ownership decisions (ControllerHandler
   and UsbDriverService presentation-owned; held controls released on detach), FGS lifetime, one-native-
   connection gating, ApplicationExitInfo/StrictMode, internal passes, JVM test list, lab device list.
3. `checkpoint-plan.json`: required checks, protected-flow bindings, device checks, lockstep pairing
   (no host source change planned), recorded lineage/untracked notes, physical TV API level UNKNOWN.

## Specific questions (design.md §Questions)
1. Switch requires an attached presentation (Console always resumes into an attaching Game) —
   acceptable, or must a headless runtime accept a queued switch?
2. Moving WholeSessionTermination orchestration (transport + host effects) into the runtime with optional
   presentation privacy/navigation hooks — right boundary vs. keeping quit presentation-only?
3. FGS for the whole runtime lifetime (notification while actively streaming) — acceptable on TV?
   Catching ForegroundServiceStartNotAllowedException at the single start site and then refusing to park
   (existing RECONNECT_REQUIRED path) — adequate?
4. Any protected flow (F01–F07) or S04/S05/S06 invariant broken by the ownership decisions?
5. Is anything missing that would make the slice half-working (e.g. actor handoff `transferConnection`
   path, bitrate reconnect, warm-up PREPARING callbacks, PiP)?

## Evidence limits
Static reading only; no build or test was run for S07 yet (nothing changed). Lab AVD API 36 was read
with a single read-only `adb getprop`; no lab run was started. Physical TV API level unknown.

Verdict requested: READY / CHANGES_REQUIRED / INSUFFICIENT_EVIDENCE for starting implementation.
