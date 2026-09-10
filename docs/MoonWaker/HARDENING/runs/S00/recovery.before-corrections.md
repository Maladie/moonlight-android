# S00 recovery

There are no product migrations or deployed artifacts to roll back. Existing user files, historical outputs and branch/base remain in place. No commit, push, PR, installation, sign-in or sleep was performed. The host-control self-test reached an unsafe installed-service boundary: MoonWakerGateway was observed Stopped afterwards, with no captured pre-test state. Do not infer unchanged service state or automatically restart it; see safety-incident.json and obtain separate authorization for any recovery mutation.

All new reports/plans/logs live under docs/MoonWaker/HARDENING/runs/S00. Two packet-only fixture edits are described in implementation.json; the product tree is unchanged. Keep failed logs and the exact run-plan hashes so a retry cannot replace the history of a failed gate.

To resume, read STATE.workspace and runs/branch-setup.json, run packet.py branch-check --repo ., verify the last source fingerprint, and reuse codex/session-hardening with the recorded base. Do not refetch/refork/reset/stash/clean user work. Reruns use a new output directory, since run_checks.py deliberately refuses to overwrite old evidence. After a source or required-plan change, refresh the bound plan through the coordinator and rerun affected checks.

The generated host installer and APKs have not been deployed. They are validation outputs only and the host package gate is BLOCKED. Do not execute any historical run plan containing S00-HOST-PACKAGE until the self-test boundary is isolated and reviewed. The consolidated local-pipeline.json is deliberately UNBOUND. Any deletion of material evidence, rollback of user-owned source or service recovery needs exact scoped review and appropriate authorization; no destructive cleanup is prescribed here.
