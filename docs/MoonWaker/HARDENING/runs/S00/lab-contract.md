# S00 lab boundary

Physical fixture authorization: NOT_AUTHORIZED. This run covers local builds and isolated tests only. No actual Windows/TV pair, dedicated profile, synthetic game set, installation, remote sign-in or sleep action was supplied. Historical personal identities are not current authorization. The user's separate narrow authorization for necessary MoonWakerGateway service control does not authorize other services or device flows; corrected isolation makes service control unnecessary.

Before a later device gate can execute, record exact TV/host identity and streamer version, accepted source and APK/installer hashes, dedicated Windows execution profile, child actor/device grants and allow-listed synthetic games A/B. Require explicit operations, bounded timeouts, an awake independent controller, and cleanup limited to test-created identities. Never log credentials, PINs, auth headers or private desktops.

Gateway/Bridge/Broker tests use synthetic identities, temporary files and controlled process/session backends. Broker tests do not submit real credentials. Host packaging compiles but never launches the installer. The corrected Host Control fixture uses a root- and PID/creation-bound stopper, not the production installed-service stopper. host-safety-scope.md and review.json record the inspected self-test call graph and independent pre-execution review. The package audio self-test uses only its own generated tone process; microphone self-test does not open hardware.

F01-F07 physical evidence remains NOT_RUN. Real-device evidence must independently observe native connection lifetime, targeted streamer generation, Windows process creation/execution identity, coherent frame marker and input effects. UI badges/facade booleans cannot prove them; process death is separate from Activity recreation. Missing evidence for a mandatory later checkpoint yields CODE_VERIFIED_DEVICE_PENDING, never acceptance.

No existing androidTest runner/dependencies or AndroidX test cache was found. Instrumentation setup is deferred with coordinator agreement until a concrete authorized fixture exists. The shipped command runner is not a completed MoonWaker E2E suite.
