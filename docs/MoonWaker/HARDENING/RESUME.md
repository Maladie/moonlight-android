# Resume execution

Read START_HERE, BRANCHING, STATE and the last assigned run. Reuse the recorded codex/session-hardening branch and frozen base. Do not create another branch, advance the base, restart S00 or reset accepted progress simply because the context changed.

Run packet.py branch-check --repo . and confirm actual branch, source fingerprint and owned dirty diff. Compare output artifacts/reviews with current source; uncommitted changes matter. Unexpected changes must be reconciled explicitly, not discarded or relabeled as tested.

Use packet.py next and bounded context generation. CHECKPOINT_ACCEPTED/ACCEPTED_NO_CHANGE alone satisfy dependencies. A device-pending candidate is not a safe-stop milestone: preserve its work and last accepted build, then complete required authorized checks before downstream mutations. Internal extraction passes are not completed tasks.

Keep command logs and exact next action. Never repair a discrepancy by falsely marking tests/review passed.
