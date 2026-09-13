# Local validation

Choose checks for the changed contract; an assigned checkpoint's mandatory suite is the minimum. This guide lists existing entry points, not results or a pre-verified lab configuration.

## Permission boundary

Read-only inspection, repository edits, local compilation, and tests confirmed to use mocks/disposable fixtures are within normal task authority. On first use, inspect unknown scripts and their invoked setup for live-network, account, service, installer, or destructive effects. Reuse that classification until relevant code/configuration changes; do not ask again for each safe rerun.

Commands that contact or alter the user's real Windows/TV environment need a bounded lab authorization: target, permitted operations, and fixture/credential handling. `deploy-tv.ps1` and installer execution are deployment, not unit testing. Building an installer does not authorize installing it. Never assume all PowerShell tests or anything named “test” is harmless. Observe tool sandbox/network permissions; do not bypass them.

## Existing entry points

From repository root; use the appropriate shell/launcher available locally.

| Change | Candidate command |
| --- | --- |
| Documentation only | `git diff --check`, changed links/paths |
| Android focused regression | `./gradlew testNonRootDebugUnitTest --tests '<actual.test.Class>'` |
| Android full unit gate when required | `./gradlew testNonRootDebugUnitTest` |
| Android debug APK when required | `./gradlew assembleNonRootDebug` |
| Gateway Python | `python -m unittest discover -s host-services/gateway -p 'test*.py'` |
| Playnite Bridge Python | `python -m unittest discover -s host-services/bridges/playnite -p 'test*.py'` |

On Windows PowerShell, substitute `.\gradlew.bat` for `./gradlew`. Replace the focused-test placeholder with a real test class. Inspect `app/build.gradle` and component test entry points if variants or layout differ. Do not invent release/instrumentation tasks or count zero discovered tests as success.

Windows-specific tests are invoked by `Build-HostServices.ps1`, including Gateway Service, Login Broker, Host Control, and Credential Provider checks. Select the affected entry point when packaging is not required. Review the script's side effects and prerequisites first. Full host packaging belongs to the `moonwaker-host-artifacts` skill, not every Python edit.

## Evidence and reruns

During implementation, use targeted checks. At the candidate checkpoint, execute the required cumulative/build/device gates. Retain full logs in non-secret, non-committed artifacts; report exit status, executed/skipped counts, failure excerpts, source identity, and environment/device identity where relevant.

A command list is not evidence of execution. A compile pass is not a device pass. Report missing prerequisites or required hardware as blocked/not run; preserve coherent progress without declaring an accepted checkpoint.

Fix failures introduced by the change. Distinguish a demonstrated baseline defect from a new regression. Rerun invalidated checks; avoid repeating unchanged full suites solely because another agent started. An independent reviewer may run focused checks necessary to challenge the candidate.

For assigned hardening work, use its approved check plan and existing runner; do not build a competing evidence format or pipeline. Local execution is sufficient unless that task explicitly requires CI.
