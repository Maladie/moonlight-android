# S03 owned changes

Input fingerprint: `sha256:02e6f8918a8027db3cb7b1270a22b1ed4ac520345c1f368093d85b2f95ca31bd`

Output fingerprint: `sha256:a9190d904a6eb83d319672a81f4d3b7383c7fdb0bcc3371e2ad5aa8abea991c8`

Owned product/test files:

- `app/src/main/java/com/limelight/console/HostLaunchPreflight.java`
- `app/src/main/java/com/limelight/console/HostGatewayClient.java` (Windows-session correlation parsing only)
- `app/src/main/java/com/limelight/console/HostReadiness.java` (narrow one-shot wake adapter only)
- `app/src/main/java/com/limelight/console/ConsoleActivity.java` (preflight adapters and warm-up caller migration only)
- `app/src/main/java/com/limelight/console/SessionOrchestrator.java` (exact neutral warm-up reconnect routing only)
- `app/src/main/java/com/limelight/console/SessionStateResolver.java` (exact neutral retained-reconnect precedence only)
- `app/src/main/java/com/limelight/Game.java` (pending warm-up capability handoff only)
- `app/src/test/java/com/limelight/console/HostLaunchPreflightTest.java`
- `app/src/test/java/com/limelight/console/HostGatewayClientTest.java` (correlation assertions only)
- `app/src/test/java/com/limelight/console/ConsoleActivityEnsureContractTest.java`
- `app/src/test/java/com/limelight/console/ConsoleHostPreparationContractTest.java`
- `app/src/test/java/com/limelight/console/SessionOrchestratorTest.java`
- `app/src/test/java/com/limelight/console/SessionStateResolverTest.java`
- `lab/lab_runner.py`
- `lab/test_lab_runner.py`
- `lab/README.md`
- `lab/example-plan.json`
- `lab/s03_scenarios.py`
- `lab/test_s03_scenarios.py`
- `lab/s03-scenarios.unbound.json`
- `lab/s03-scenarios-plan.unbound.json`

Run/evidence files under `docs/MoonWaker/HARDENING/runs/S03/` are also owned evidence. `local-run-02` through `local-run-06` are preserved superseded/interrupted attempts; sandboxed `local-run-07` records unavailable Gradle-cache access. `local-run-08` remains historical Android/product evidence, but its APKs do not contain the later warm-up regression correction. `runner-only-09` binds the base runner correction; `scenario-only-10` is preserved as the pre-review A02 result; `scenario-only-11` binds the first review correction; `scenario-only-12` adds strict per-poll cursor monotonicity and sticky fail-closed irreversible-observation handling; `warmup-regression-fix-13` binds the targeted and cumulative code-only checks for the manual B regression. All accepted S00-S02 changes and every unrelated dirty/untracked file are preserved input, not S03 ownership. The S03 review boundary is the accepted input fingerprint to the output fingerprint, not the full uncommitted diff against HEAD.
