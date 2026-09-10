# MoonWaker deterministic LAB runner

`lab_runner.py` is a local external-controller foundation. It does not install, sign in, control services, grant permissions or authorize a device. `example-plan.json` is deliberately `UNBOUND` and cannot execute.

Dry-run the schema and order only:

```text
python lab/lab_runner.py --plan lab/example-plan.json --repo .
```

To add a real scenario later, copy the example and replace every `UNBOUND` value with reviewed values. Bind `source_fingerprint` to `packet.py fingerprint`, bind the exact repository-local APK path and SHA-256, identify the controller/Android device/host, and add a short-lived authorization object whose reference is supplied again through `--authorization-ref`. Live execution additionally requires `--execute` and a new repository-local evidence directory.

Every setup, scenario and cleanup entry invokes a reviewed executable/script directly as an argv array. Python must name a repository-local `.py` file and PowerShell must use the exact `-File` form with a repository-local `.ps1`; command/eval/stdin forms and implicit shell extensions are rejected. Before any effect or evidence directory, every cwd, executable and reviewed script path is resolved. Repository-relative executable paths must exist below the repository; bare tools such as `adb`, Python and PowerShell are resolved once through the step's sanitized `PATH`, and that exact executable and environment are then used for the step. Exact argv items `{plan_sha256}`, `{source_fingerprint}`, `{apk_sha256}`, `{controller_id}`, `{android_serial}`, and `{host_id}` are replaced only by values from the loaded top-level plan; unknown placeholders are rejected. A step must write exactly one UTF-8 JSON object to stdout:

```json
{"schema_version":1,"step_id":"example","status":"PASS","assertions":{"expected_fact":true},"observations":{"non_secret_marker":"value"}}
```

The assertion keys must exactly equal the plan's `required_assertions`, and every value must be `true`. Nonzero exit, timeout, malformed output, a missing/false assertion, source/APK drift or cleanup failure makes the result fail. Setup/scenarios fail fast; every cleanup step is isolated and still runs in `finally`. The runner never retries and uses no sleep as an oracle. It closes subprocess stdin, discards untrusted stderr/raw failing output, rejects credential/PIN/password/token/Bearer/session-secret fields before persistence, and creates evidence files once with exact plan/log hashes.

Each step owns one complete process tree. Windows starts the direct process suspended, assigns it to a Job Object, then resumes it; POSIX creates a new process group. The owner is terminated at every step boundary, even after a successful direct parent exit, and the runner waits for the scoped Job/group to become empty before returning. Thus a descendant cannot outlive a passing step even if it closed inherited stdout. Timeout, output overflow and interruption close the same scoped owner and never kill by name.

The example contains only placeholders for future scripts. It is not S03 device evidence and does not satisfy `S03-LAB-WINDOWS-SIGNIN`.

## S03 scenario contracts

`s03_scenarios.py` adds an external-controller layer for cold sign-in success, automatic warm-up, denied `remote_sign_in`, and another-active-Windows-identity cases. `s03-scenarios.unbound.json` and `s03-scenarios-plan.unbound.json` are nonsecret templates only; both remain `UNBOUND`, perform no action, and are not device evidence.

The bound config must name the installed application ID and matching full `package/com.limelight.console.ConsoleActivity` component (for example, debug and release application IDs differ). Before each scenario UI effect, the controller requires that exact normalized component from `cmd package resolve-activity`, exactly one `pm path` result, and the authoritative plan APK digest from bounded `toybox sha256sum` of that single installed path; it never pulls or retains the APK. The runner-injected Android serial supplies every `adb -s` argument, so it cannot diverge from the plan. The controller launches only that production ConsoleActivity. It never starts `Game` directly, passes internal extras, uses coordinates, or reads private/generated Android view IDs. Navigation uses at most 32 named DPAD events and globally unique `LAB_...` fixture display names. Every UIAutomator node is filtered by the bound package; an exact synthetic label child resolves to one package-bound clickable/focusable ancestor (whose compound content description may contain that label), and focus must be observed on that ancestor after bounded DPAD input. Outcome text is visibility-only, package-filtered, and mandatory for denial and wrong-identity scenarios. The configured `en-US` locale must already match; the script never changes it.

Each scenario starts its own observation window and fresh nonsecret `LAB_SCOPE_...` invocation ID. Before the UI action it invokes the configured read-only host observer for a baseline cursor; subsequent invocations must echo the exact scope, scenario, fixture alias, config SHA-256, controller ID, Android serial, and host ID, then return a newer correlated completion. The observer is a separately reviewed executable, runs to completion on every poll, and must use existing OS authentication. It must not call Host Control `Status`, start components, alter services/grants/accounts, or output credentials, account names, raw diagnostics, tokens, or certificate material. Its sole stdout object is:

```json
{"schema_version":1,"scenario":"success","fixture_alias":"LAB_SUCCESS_FIXTURE","scope_id":"LAB_SCOPE_...","config_sha256":"sha256:...","controller_id":"LAB_CONTROLLER","android_serial":"LAB_ANDROID","host_id":"LAB_HOST","cursor":42,"facts":{"complete":true,"action_attempt_count":1,"terminal_outcome":"READY","terminal_count":1,"settled":true,"late_effects_observed":false,"gateway_before_streamer":true,"requested_identity_ready":true,"streamer_ready":true,"warmup_observed":false,"remote_sign_in_granted":true,"wrong_identity_active":false,"session_attempt_count":1,"preparation_count":1,"stream_session_count":1,"transport_count":1,"game_launch_count":1,"logoff_observed":false,"switch_observed":false}}
```

Identity comparison belongs inside that observer. A passing success/warm-up record has one fresh action attempt, exact `READY`, one terminal, one session attempt, and exact single preparation/session/transport counts. Denial and wrong-identity require one fresh action, respectively exact `REMOTE_SIGN_IN_NOT_GRANTED` or `OTHER_PROFILE_ACTIVE`, one terminal, zero session effects, and a bounded settled result with no late effects. Every poll must strictly advance the cursor. Any observed late effect, or any session/transport/game/logoff/switch effect in a negative scenario, fails immediately and remains fatal even if a later record is clean. A false permission boolean plus zero counts cannot pass. Missing, extra, stale, malformed, ambiguous, or mismatched correlation fails closed. Incomplete observations are polled to a monotonic deadline; polling is not retried execution and no sleep is an oracle.

No concrete host observer or fixture cleanup program is shipped by A02: these are strict injected adapter contracts because their safe read-only diagnostic source and restoration commands are lab-specific. Therefore the scenarios are code-ready but not executable E2E. A `BOUND` config is rejected unless a reviewed cleanup argv is also bound. Cleanup receives and must echo the same identity/config/scope correlation fields and positively return `test_owned_state_restored`, `no_game_running`, and `no_stream_running`; merely opening ConsoleActivity is never accepted as restoration and could itself trigger warm-up.

The timeout-plus-late-READY row intentionally has `fixture_bound:false`. There is currently no legitimate controlled pending-attempt fixture, so executing it returns FAIL. Do not simulate it with network outage, service changes, provider fabrication, or a production bypass.

When a real lab is prepared, copy the templates, bind exactly one compatible precondition/scenario per plan, replace every `UNBOUND` command/device/package/component value, bind a reviewed read-only observer and scoped cleanup adapter, and set only that scenario's `fixture_bound:true`. Keep timeout/late-READY unbound until a separately reviewed controlled fixture exists. Dry-run the complete template schema from the repository root with:

```text
python lab/lab_runner.py --plan lab/s03-scenarios-plan.unbound.json --repo .
```

Run the local contract tests from the repository root with the same discovery convention as the runner suite:

```text
python -m unittest lab.test_s03_scenarios -v
```
