# Execute MoonWaker hardening — complete usable checkpoints

Repository: Maladie/moonlight-android
Base branch: codex/multi-profile-remote-sign-in
New work branch: codex/session-hardening
Audit reference only: 00366fd92a1f283bf3aa04171ed3455ce62897ac
Packet: docs/MoonWaker/HARDENING
Coordinator hint: Sol. Astra is preferred for cross-component slices; independent review uses a separate context, usually the other model.

Read actual root/nested AGENTS.md and repository architecture/workflow, then BRANCHING.md, RULES.md, CHECKPOINTS.md, DECOMPOSITION.md, roles/COORDINATOR.md, MANIFEST.json, STATE.json and automation/TEST_STRATEGY.md.

## Start on a new branch, not the base
On a fresh run, the coordinator must execute BRANCHING.md before any product/test/build edits or S00 dispatch: fetch the named base, preserve a strictly newer local base if present, record the exact selected SHA, create and switch to codex/session-hardening, and bind workspace state/evidence. This local branch creation is explicitly authorized. Divergent refs, a colliding target or unrelated dirty work are blockers, not permission to reset, stash or discard. The audit SHA is not a checkout instruction.

After setup, run from the repository root:
```text
python docs/MoonWaker/HARDENING/tools/packet.py branch-check --repo .
python docs/MoonWaker/HARDENING/tools/packet.py validate
python docs/MoonWaker/HARDENING/tools/packet.py next
python docs/MoonWaker/HARDENING/tools/packet.py context S00 --role worker --output docs/MoonWaker/HARDENING/runs/S00/dispatch.md
```
Metadata/context tools do not create the branch or authorize working on the wrong branch. S00 starts only after setup. When resuming, retain the recorded branch and base, preserve STATE/runs and use RESUME.md rather than creating another branch or restarting the baseline.

## Execution contract
Every accepted implementation slice is integrated and usable without a later task; no newly introduced regression is deferred. Decompose affected monolith responsibilities as part of each slice, moving state/effects/callers and removing old ownership. A helper passing through Game is not decomposition.

Use actual delegation/model-selection tools. If unavailable, prepare the next bounded dispatch for a separately selected session and state the limitation. Do not impersonate subagents or independent review. One mutating worker; S05/S06/S07 require pre-edit independent contract review. Coordinator owns input-bound check plans and STATE; acceptance follows the ordered checkpoint chain.

Build the pipeline locally. In S00 bind safe local build/test commands and baseline evidence; extend targeted and cumulative automation in each task. GitHub Actions and remote CI configuration are not required. The shipped run_checks.py is an opt-in runner, not a completed MoonWaker E2E suite. Routine safe checks should be executed by agents when tools are available; live Windows/TV tests require separate bounded authorization.

A task cannot be accepted with required checks missing, zero tests, pending production integration or a needed future fix. CODE_VERIFIED_DEVICE_PENDING records useful progress but blocks dependent mutations. The code must remain a coherent candidate; actual Windows/TV/native evidence remains mandatory for the affected checkpoint. Only S10 can certify the final release with the full candidate evidence.

No commit, push, PR, deployment, installation, credential changes, sleep or live sign-in is authorized merely by this prompt. Safe local checks and the specific local branch setup above are allowed. Do not run untrusted public PR code on the user's Windows/TV equipment.
