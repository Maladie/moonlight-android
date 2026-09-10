# S00 compatibility

No Android or host production behavior, wire schema, dependency, or package contents contract changed. The only tracked source correction isolates the Host Control self-test. Host version remains 0.7.95, protocol 6; generated APKs/installer are local validation outputs, never deployed. Old-client/new-host, new-client/old-host and new/new combinations retain the same baseline contracts and limitations.

The corrected local plan uses the existing Gradle wrapper, JUnit, Python unittest and Windows self-tests. Debug and unsigned minimized nonRoot release builds remain the existing variants. No dormant androidTest dependency or new test framework was added. The packet tests use explicit synthetic scheduler state and non-forced disposable Git fixture checkout compatible with Git 2.22. The runner mechanically rejects non-.json plans before reading them or creating output.

The Host Control fixture no longer copies the production stopper, which could inspect/control the installed Gateway service and fixed legacy roots. Its replacement is test-only, rejects any root but its own temporary directory, and stops only its captured listener PID with matching creation identity. Service cmdlet canaries throw. Independent pre-execution review is recorded in review.json; the corrected host log records the actual Gateway state immediately before and after.

S00-F01 through S00-F07 are existing production-owner deterministic non-regression floors, not complete TV/Windows/native E2E. Source-text contract tests remain structural evidence only. H01-H11 were statically revalidated, not fixed or physically reproduced. There are no prior fixed paths in this fresh run; the full suites establish the cumulative baseline. Later mandatory device evidence cannot be waived by this setup-only local pipeline.

See gate.json and implementation.json for the current results and pending independent whole-slice review. Historical blocked artifacts are preserved as *.before-corrections.*; historical executable bytes remain archived but cannot execute as .executed.txt plans.
