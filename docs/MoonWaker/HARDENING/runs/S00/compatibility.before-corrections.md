# S00 compatibility

No Android, host production, wire schema, dependency or packaging source changed. Old-client/new-host, new-client/old-host and new/new combinations therefore retain the same baseline binary contracts and limitations. Host version remains 0.7.95, protocol 6; the generated installer is baseline build evidence only, not a new version offered for installation. A host self-test reached an unsafe installed-service boundary; see safety-incident.json. Host service state cannot be claimed unchanged.

The local verification plan uses the existing Gradle wrapper, JUnit, Python unittest and Windows self-tests. It selects nonRoot debug and release builds; release remains the existing unofficial application ID and unsigned unless the user separately chooses signing. No credentials were intentionally changed. The consolidated plan is now UNBOUND because the host package test can stop a real service even with a temporary fixture directory.

Two packet test fixture corrections make the supplied tooling usable here: initial scheduler state is explicit even while live STATE is IN_PROGRESS, and disposable Git repositories use the non-forced checkout equivalent supported by Git 2.22. They do not alter the real work branch or product state.

S00-F01 through S00-F07 execute selected production-owner deterministic suites. Their assertions are the non-regression floors described in baseline.json. They are not complete TV/Windows/native E2E tests. Source-text contract tests in the full JVM suite are recorded as structural checks, never physical evidence. No fixed product path exists yet in this hardening run, so the cumulative fixed-path set is empty; full existing suites establish the initial regression baseline.

Later safety extensions must preserve HTTPS, pinning, paired identity, actor/execution separation and host capability checks. A client UUID disconnect endpoint is not proof of session-generation termination. D01/D04/D05 remain bounded future acceptance constraints.
