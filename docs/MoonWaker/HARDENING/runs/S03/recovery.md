# S03 recovery

No device, service, installation, deployment, sign-in, credential, permission or host mutation was performed by the worker. APKs are local validation artifacts only. `S03-LAB-WINDOWS-SIGNIN` remains `NOT_RUN`; amendments A01/A02 supply an external deterministic runner, scenario contracts and intentionally UNBOUND templates, not device evidence.

Recovery is source-only: revert the S03-owned production/test changes as one coherent slice and rebuild. That intentionally restores the prior streamer-before-login and ambiguous-session behavior, so rollback should be used only if the corrected path itself must be withdrawn. The A01/A02 tooling can be rolled back independently by removing the owned `lab/` files and their local-pipeline checks; it has no product schema or data migration.

For any later BOUND scripted lab, reviewed cleanup steps must restore only test-owned state. Do not use the superseded manual-click runbook as checkpoint evidence, change/install services or expose credentials for recovery.

Preserve accepted S00-S02 changes, the recorded branch/base binding and all unrelated dirty/untracked user files. Resume on `codex/session-hardening`, run branch-check and verify the accepted input fingerprint; do not refetch/refork/reset/stash/clean.
