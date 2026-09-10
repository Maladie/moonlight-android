# S01 recovery

No migration, service mutation, installation, deployment, sign-in, credential change or device action occurred. The generated APKs are local validation artifacts only.

Recovery is source-only: revert the seven S01-owned source/test files as one coherent slice, then rebuild. Do not restore only the removed overloads or only some callers, because that would reintroduce ambiguous parent routing. There is no persisted data or host schema to roll back.

The accepted S00 change in `host-services/control/Test-MoonWakerHostControl.ps1`, the prepared branch/base record, all unrelated dirty/untracked user files and historical `local-run-01` failed evidence must remain preserved. Resume on `codex/session-hardening`, run branch-check and verify the last recorded fingerprint; do not refetch/refork/reset/stash/clean.
