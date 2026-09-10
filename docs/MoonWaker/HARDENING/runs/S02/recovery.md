# S02 recovery

No device, service, installation, deployment, sign-in, credential or host mutation occurred. APKs are local validation artifacts only. No preference migration was persisted by the test/build run.

Recovery is source-only: revert the four S02-owned product/test changes as one coherent slice and rebuild. Existing explicit preference values remain valid because S02 does not change the key or encoding. Reverting would restore the old absent behavior (debug five minutes, release off), so do it only as an intentional rollback of the authorized 30-minute policy.

Preserve the accepted S00/S01 changes, prepared branch/base record and all unrelated dirty/untracked user files. Resume on `codex/session-hardening`, run branch-check and verify the last accepted fingerprint; do not refetch/refork/reset/stash/clean.
