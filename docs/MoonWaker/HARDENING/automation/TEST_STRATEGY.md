# Automated verification strategy — implementation specification

Status: proposed product-test architecture. The packet ships a command/evidence runner and metadata tests, NOT implemented MoonWaker UI/Windows/TV test suites. Existing commands were inspected, not executed during packaging. S00 binds test commands; each slice adds real production-path regressions and extends the common smoke suite.

## Layers and cadence
| Lane | System under test | Automatic oracle | When |
|---|---|---|---|
| L1 deterministic logic | Existing JVM owners/resolvers and Python host policies with controlled effects, clocks and queues | Exact effects/identities/order; forbidden calls; no sleep-based race proof | Narrow loop after edits; all affected suites at every checkpoint |
| L2 component/contract | Real Gateway handler + production Bridge/consumer boundary, synthetic config/storage and transport adapters | Actual endpoint/auth/schema behavior, compatibility, producer coherence, process identity and persisted budget | Cross-component slices + cumulative checkpoint gate |
| L3 Android lifecycle/UI | Emulator or lab TV, AndroidX test + UI Automator, controlled host | Actual UI entrypoints, focus, Home/picker/overlay/pad equivalence and lifecycle outcomes | Targeted navigation/lifecycle slices; seven-flow protected smoke |
| L4 real host/stream | Authorized Windows + real streamer + Android TV + allow-listed probe games | Real native connection identity/counters, OS process/session observation, frames and input acknowledgements | Mandatory for changed native/Windows/streaming contracts; full matrix/soak at S10 |

Local command runs cover L1/L2, packaging and an appropriate emulator subset of L3. An emulator is not evidence for the target TV's native decoder/retention behavior or the real Windows credential-provider path. Avoid a promise that one emulator replaces the lab.

## Existing starting points, not a new test ecosystem
At the inspected SHA app/build.gradle declares JUnit 4 test dependencies and nonRoot debug/release variants, but no instrumentation runner or androidTest dependencies. Add compatible test-only instrumentation config; do not upgrade app dependencies merely to obtain the latest API. UI Automator can operate across the launcher/system UI and outside the app process, including minimized release targets [A1, A2]. Verify the chosen library version against the actual toolchain; no alpha dependency is mandated by this plan.
Use repository unit target testNonRootDebugUnitTest [R1]. Python suites exist in gateway and bridges/playnite; the inspected files use unittest, mocks and temporary config [R3,R4]. Login Broker has a PowerShell self-test that compiles its .NET Framework test executable [R5]; use that actual script rather than inventing dotnet test.
See COMMAND_INVENTORY.json. These are command candidates verified from files, not evidence of success. Inspect each suite for side effects, dependencies and test discovery before authorizing it on a developer machine. Run Windows-only pieces on Windows.

## Deterministic regression harness
Wrap narrow external effects used by actual production owners: connection start/stop, provider launch/stop, observation source, clock, presentation attachment. Reuse existing seams; do not implement a second session state machine just for tests. Inject a manual clock and controlled executor/queue only in test code or constructor dependencies; no production HTTP time-travel/safety bypass endpoint.
High-value orderings: two stop callers before stop completes; old stop after new Play; A observation after B readiness; host boot/revision reset; cancel then late login READY; warm-up + Play + repeated button; surface detach before frame callback; child expiry then parent reauthorization on the same transport.
A small repeatable event generator with fixed seeds can supplement named cases. Store the seed and minimized event trace on failure. Test assertions specify the invariant independently of the production resolver. Count forbidden effects, not only desired final state. Do not retry until green or call arbitrary sleeps a concurrency test.

## Seven-flow protected smoke at each checkpoint
Keep one fixed lightweight smoke entry per F01–F07 and a cumulative list of contracts already fixed. Record target defect reproductions separately from original baseline defects. The pre-hardening app is known imperfect: the floor compares behavior with the accepted baseline, it does not falsely assert that all final requirements already pass.
Each slice must pass its complete promised outcome now, plus the protected floor and prior fixes. No new regression is allowed in any path. A later task may fix a separate original problem. A smoke case with an expected original defect must still assert the non-regression facts and retain exact evidence/owner; do not skip the whole flow.
Select physical checks by the input-bound risk plan. The entire 100-switch soak is not required after a preference-only edit, but actual transport lifetime/termination changes require targeted real-runtime checks before a checkpoint is accepted. Missing lab evidence is device-pending and blocks dependent mutation, not a fabricated pass.

## Real lab design
Use an external controller process/machine that is not the application under test. The coordinator of sleep/reboot/WoL tests must stay awake; a script on the host it just put to sleep cannot execute the wake step. A dedicated UI driver/test package or external ADB controller must survive app force-stop/process death. In-app instrumentation alone must not be assumed to survive termination of its own process.
UI Automator drives actual menu/resource IDs, D-pad/focus, overlay Home and system Home. ADB is transport/control and log collection, not the oracle. Injected keys validate app handling but do not prove physical DualSense/Bluetooth/TV firmware behavior; retain a small physical-input check for those boundaries. Test Activity recreation separately from process death; Android may kill the process without onDestroy, so a lost process implies reconnect, not retained socket [A3].
Create two tiny Windows probe titles A/B installed only in the test profile through the normal permitted catalog path. They render unmistakable game+frame markers, acknowledge input, expose PID+creation identity, support controlled start delay, normal exit and ignore-close mode. Their test-control channel is loopback-only/allow-listed; it is not a production Gateway bypass. Routine smoke switches these titles deterministically; final acceptance adds real Steam, secondary-launcher and emulator cases.
Observe independent facts: stream library actual successful connection ID and stop boundary; host streamer session evidence if supported; OS process identity and interactive Windows identity; probe frame marker and input acknowledgements. Correlate client/host boot IDs and transport/actor/game generations. UI badges and orchestrator flags alone cannot satisfy L4.
For Windows sign-in use a disposable VM/test Windows profile, existing credential broker and explicitly granted device/profile permissions. Do not type or dump real credentials through the test driver. An RDP-maintained interactive session may alter the condition being tested; verify actual cold/locked state. VM tests cover broker/credential integration but not physical WoL/GPU capture.

## Example acceptance trace, not an implemented API
```text
start probe A through the carousel
record actual transport G, native starts C, stops D and A process identity
Home -> carousel -> choose probe B
await exact A process exit + B identity/readiness + fresh B frame
assert same live G, native starts == C, native stops == D
assert host badge == active stream and only B tile == running
full quit while Gateway reply is deliberately withheld
assert local input blocked and local stop initiated before releasing reply
assert local stop completion is real; delayed cleanup cannot target next session
```
The analogous child case gives the disposable child a short legitimate budget, closes the client observation channel, and checks host process + bound-stream termination independently. Do not accelerate the actual Windows clock or alter a family child's policy as a routine test.

## Privacy, build variants and evidence
Diagnostics are bounded, non-secret and preferably read-only at narrow existing boundaries. Test mutation/injection adapters stay in test/debug artifacts, not the release security surface. No trust-all TLS or fake administrator identity. Prove release behavior externally and by safe diagnostics; debug-only counters do not automatically certify an uninstrumented release. The build's optional Discord vendor SDK is a separate declared capability; absent proprietary assets cannot be hidden as full release coverage [R2].
Each result carries task/plan/source/artifact hashes, toolchain/Windows/TV/streamer versions, seed, observed assertions, exit status and relevant trace artifacts. Never publish auth headers, tokens, PINs, credentials, personal user names or private desktop video. Automatic captures are limited to synthetic test screens; actual physical-display flash validation may require bounded recording/manual confirmation, not just an app screenshot. Frame submission is not physical display proof.
Timeout, crash or abort always produces a failed/incomplete result and best-effort scoped cleanup of test-owned resources. Never kill by process name or globally log off the host. Cleanup needs the same authorization/identity discipline as the operation being tested.

## Local pipeline and command execution
Local execution is the required delivery mode. Do not add GitHub Actions, hosted-runner configuration or branch-protection rules merely to complete this plan. Agents should run available safe local checks themselves, not only return commands for the operator. Hardware authorization and missing access remain explicit gates.
Use one small reviewed command runner and existing test frameworks. The supplied tools/run_checks.py defaults to a dry run, requires an explicitly BOUND trusted plan and --execute, captures per-command results, rejects missing/zero/stale test output and exits nonzero unless mandatory checks pass. It does not provision devices, impersonate authorization or implement missing tests. The example plan remains UNBOUND until S00 verifies it.
Keep routine builds/JVM/Python/contract checks separate from the explicitly enabled device suite. Use an independent controller for host sleep/reboot and process-death tests. Keep one device lease/lock and one test at a time. Write sanitized local JSON/XML reports and artifact hashes; store sensitive raw traces only in restricted local evidence.
Do not route public fork PRs or arbitrary external workflow input to home Windows/TV equipment. Self-hosted environments can be persistently compromised by untrusted code [A4]. This packet does not change GitHub settings or authorize remote execution.

## Delivery order
S00 verifies commands, captures baseline, binds a minimal repeatable gate and test-only instrumentation scaffold. S01/S02 establish endpoint/policy tests; S03 adds controlled login sequence + lab probe; S04/S05 add native stop/host-terminal assertions and fixture use; S06 adds producer/UI observation assertions; S07/S08 add lifecycle/warm-up automation; S09 adds presentation/input coverage; S10 runs the full exact-release matrix. No massive new general testing framework is required before the first bug fix.

References and inspected source URLs are in SOURCES.json. Source inspection, proposed tests and executed packet-tool tests are distinct evidence categories.

## Running and collecting evidence

`tools/run_checks.py` executes reviewed argv lists only when explicitly invoked with `--execute`.
The example plan is intentionally UNBOUND: replace it with inspected commands and bind it to the
exact candidate source fingerprint and approved checkpoint-plan hash. A dry run is not evidence.
Each TEST must produce fresh JUnit XML, a unittest summary or a machine JSON test summary with
nonzero executed tests. Unexpected skips, stale output, timeout and nonzero exit fail the gate.
LAB checks additionally require actual bounded authorization, `--allow-lab`, and a fresh
`device_evidence_path`. The flag and authorization-reference string do not grant permissions.
Only test-owned processes are eligible for the runner's timeout cleanup. Review commands and
make them redact secrets; this tool is not a command sandbox or a general log scrubber.

```text
python docs/MoonWaker/HARDENING/tools/run_checks.py --plan <bound-run-plan.json> --repo .
python docs/MoonWaker/HARDENING/tools/run_checks.py --plan <bound-run-plan.json> --repo . --output docs/MoonWaker/HARDENING/runs/<task>/<unique-run> --execute
```

When several machines contribute results, collect their original reports and logs into the
packet's run directory. Preserve SHA-256 digests; normalize artifact paths to relative paths
only, without changing evidence content. The coordinator's composite gate must link each
original report and show that all checks exercised the same source candidate or the exact
build-artifact hashes derived from it. Do not copy PASS rows from an older candidate.
A hardware runner tests the deployed artifacts from that build; it must not rebuild silently
from another checkout. Checkpoint validation checks consistency, not truth of agent statements.
The independent reviewer still inspects source wiring, observed behavior and device evidence.
