# S00 recovery

There are no product migrations or deployed artifacts to roll back. Existing user files, historical outputs and the recorded branch/base remain in place. No commit, push, PR, installation, deployment, sign-in, sleep or credential change was performed.

The original Host Control self-test reached an installed-service-capable boundary. The user subsequently confirmed Gateway was already Stopped before that test, so no state change is observed or attributed to it. During corrections it was externally observed Running. The corrected isolated test must preserve that newer state; do not force the service back to the earlier Stopped state. No explicit service control or automatic recovery was used by this worker.

The production stopper is unchanged. Only its test copy was replaced by a temporary-root and captured-process identity stub, with service cmdlet canaries. Keep safety-incident.json and original logs for chronology. Any future service operation still requires exact scope and authorization.

Resume by reading STATE.workspace and runs/branch-setup.json, running packet.py branch-check --repo ., and verifying the recorded source fingerprint. Reuse codex/session-hardening and its recorded base; do not refetch/refork/reset/stash/clean user work. Do not alter archived execution evidence. run_checks.py requires a .json plan path and refuses output-directory reuse.

The current corrected command plan is local-pipeline-v2.json and its evidence directory is corrected-local-run-01. All old host and mislabeled T43 plans are UNBOUND, with exact historical bytes in non-executable .executed.txt archives. Source or plan changes require rebinding and fresh applicable checks; changes to the reviewed stopper or its delegated path require renewed safety review. This handoff does not authorize deployment of the validation APKs or installer.
